/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.matching

import org.json.JSONArray
import org.json.JSONObject

/*
 * Scanner match evidence (memory development plan M.2, Stage 6B.2). The
 * memory scanner looks for records related to what it reads: filed Active,
 * Pending and Archived memories and this scan's own working drafts. The
 * evidence says which record, its status, how it was found and the
 * similarity score when one was actually computed. It decides nothing:
 * Stage 6B.3 reconciles and files, and shows a section's Pending and
 * Archived matches to the scanner AI ([org.teslasoft.assistant.preferences.memory.archivist.ScannerPromptMatches]).
 */

/** The status of a matched record when it was matched. */
enum class MatchStatus(val code: String) {
    ACTIVE("active"),
    PENDING("pending"),
    ARCHIVED("archived"),
    WORKING_DRAFT("working_draft");

    companion object {
        fun fromCode(code: String?): MatchStatus? = entries.firstOrNull { it.code == code }

        /** A filed memory's stored status; Superseded and anything else are
         *  not scanner comparison records. */
        fun ofMemoryStatus(status: String): MatchStatus? = when (status) {
            "active" -> ACTIVE
            "draft" -> PENDING
            "archived" -> ARCHIVED
            else -> null
        }
    }
}

/** How a record was found. Only [EMBEDDING] carries a similarity score. */
enum class MatchMethod(val code: String) {
    /** The same wording (normalized), or for a section, the record's whole
     *  wording appearing in it. */
    EXACT("exact"),
    /** Meaning similarity from the on-device embedding model. */
    EMBEDDING("embedding"),
    /** A memory a reply in this section actually used (its Active Memories
     *  record), still eligible. A clue, not a similarity. */
    CHAT_CLUE("chat_clue"),
    /** A relationship the scanner AI named for a draft (validated ID). */
    AI_RELATED("ai_related"),
    /** The draft is an unreconciled alternative wording of this draft. */
    ALTERNATIVE("alternative"),
    /** Shared informative words, used where meaning was not compared. */
    KEYWORD("keyword");

    companion object {
        fun fromCode(code: String?): MatchMethod? = entries.firstOrNull { it.code == code }
    }
}

/** One matched record: a filed memory ([memoryId]) or a working draft
 *  ([draftId]), never both. */
data class ScannerMatch(
    val memoryId: String?,
    val draftId: Int?,
    val status: MatchStatus,
    val method: MatchMethod,
    /** Cosine similarity; present only for [MatchMethod.EMBEDDING]. */
    val score: Float? = null
) {
    val key: String get() = memoryId?.let { "m:$it" } ?: "d:$draftId"
}

/**
 * The result of one lookup.
 *
 * @param fingerprint what was looked up (a section's text, or a draft's
 *  comparison document together with its links), so a later change is
 *  recognised as needing a new lookup.
 * @param modelKey the embedding model and document version used, or null when
 *  no embedding model was usable (keyword and exact evidence only).
 * @param matches the strongest evidence, bounded.
 * @param links unresolved relationships (AI-named records and alternative
 *  drafts) that are still eligible; never decided here.
 * @param unexamined eligible records whose meaning was not compared (no
 *  model, no vector yet, or beyond this lookup's bounds). A lookup with
 *  unexamined records is never an exhaustive meaning check.
 */
data class MatchEvidence(
    val fingerprint: String,
    val modelKey: String?,
    val matches: List<ScannerMatch>,
    val links: List<ScannerMatch> = emptyList(),
    val unexamined: Int = 0
) {
    val semanticComplete: Boolean get() = modelKey != null && unexamined == 0
}

/** A working draft's evidence: its own lookup after extraction, and what
 *  the lookups before the sections that wrote or changed it found. */
data class DraftMatchEvidence(
    val proposal: MatchEvidence? = null,
    val sections: List<ScannerMatch> = emptyList()
) {
    /** Whether the draft's own lookup is missing or out of date: deferred,
     *  not completed. */
    fun needsLookup(fingerprint: String, modelKey: String?): Boolean =
        proposal == null || proposal.fingerprint != fingerprint || proposal.modelKey != modelKey

    companion object {
        const val MAX_SECTION_MATCHES = 12
    }
}

/** The checkpoint form of match evidence (inside a working draft's encrypted
 *  payload). Only IDs, codes, scores and fingerprints; never text. */
object MatchEvidenceCodec {

    fun encode(evidence: DraftMatchEvidence): JSONObject = JSONObject().apply {
        evidence.proposal?.let { put("proposal", encodeLookup(it)) }
        if (evidence.sections.isNotEmpty()) put("sections", encodeMatches(evidence.sections))
    }

    fun decode(json: JSONObject?): DraftMatchEvidence? {
        json ?: return null
        val proposal = json.optJSONObject("proposal")?.let(::decodeLookup)
        val sections = decodeMatches(json.optJSONArray("sections"))
        if (proposal == null && sections.isEmpty()) return null
        return DraftMatchEvidence(proposal, sections)
    }

    private fun encodeLookup(e: MatchEvidence): JSONObject = JSONObject().apply {
        put("fingerprint", e.fingerprint)
        e.modelKey?.let { put("model", it) }
        put("matches", encodeMatches(e.matches))
        if (e.links.isNotEmpty()) put("links", encodeMatches(e.links))
        put("unexamined", e.unexamined)
    }

    private fun decodeLookup(o: JSONObject): MatchEvidence? {
        val fingerprint = o.optString("fingerprint").takeIf { it.isNotBlank() } ?: return null
        return MatchEvidence(
            fingerprint = fingerprint,
            modelKey = o.optString("model").takeIf { o.has("model") && it.isNotBlank() },
            matches = decodeMatches(o.optJSONArray("matches")),
            links = decodeMatches(o.optJSONArray("links")),
            unexamined = o.optInt("unexamined", 0).coerceAtLeast(0)
        )
    }

    private fun encodeMatches(list: List<ScannerMatch>): JSONArray = JSONArray().apply {
        for (m in list) {
            put(JSONObject().apply {
                m.memoryId?.let { put("memory_id", it) }
                m.draftId?.let { put("draft_id", it) }
                put("status", m.status.code)
                put("method", m.method.code)
                m.score?.let { put("score", it.toDouble()) }
            })
        }
    }

    private fun decodeMatches(arr: JSONArray?): List<ScannerMatch> {
        arr ?: return emptyList()
        val out = ArrayList<ScannerMatch>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val memoryId = o.optString("memory_id").takeIf { o.has("memory_id") && it.isNotBlank() }
            val draftId = o.optInt("draft_id", 0).takeIf { o.has("draft_id") && it > 0 }
            if ((memoryId == null) == (draftId == null)) continue
            val status = MatchStatus.fromCode(o.optString("status")) ?: continue
            val method = MatchMethod.fromCode(o.optString("method")) ?: continue
            val score = if (method == MatchMethod.EMBEDDING && o.has("score")) {
                o.optDouble("score").takeIf { !it.isNaN() }?.toFloat()
            } else null
            out.add(ScannerMatch(memoryId, draftId, status, method, score))
        }
        return out
    }
}
