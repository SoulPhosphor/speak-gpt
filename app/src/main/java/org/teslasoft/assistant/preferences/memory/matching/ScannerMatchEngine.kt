/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.matching

import org.teslasoft.assistant.preferences.memory.MemoryMatch
import org.teslasoft.assistant.preferences.memory.PossibleMatchFinder
import org.teslasoft.assistant.preferences.memory.librarian.KeywordText
import org.teslasoft.assistant.preferences.memory.librarian.VectorMath

/** One record the scanner may compare against, already filtered for scope,
 *  target and status eligibility. [document] is the text a vector represents
 *  and [fingerprint] identifies it, so a changed record never reuses an old
 *  vector. */
data class ComparisonRecord(
    val memoryId: String?,
    val draftId: Int?,
    val status: MatchStatus,
    val content: String,
    val document: String,
    val fingerprint: String,
    val updatedAt: String? = null
) {
    val key: String get() = memoryId?.let { "m:$it" } ?: "d:$draftId"

    /** Informative words of [content], worked out once per record. */
    val terms: Set<String> by lazy { KeywordText.terms(content).toSet() }
}

/**
 * Where vectors come from. Every read and generation goes through the
 * embedding queue at background priority; the engine only decides how many.
 */
interface ComparisonVectors {
    /** The embedding model and document version; part of every cache key. */
    val modelKey: String

    /** Vectors already available without reading the model: the Active
     *  search index and the comparison cache, matched to each record's
     *  current [ComparisonRecord.fingerprint]. Keyed by [ComparisonRecord.key]. */
    fun known(records: List<ComparisonRecord>): Map<String, FloatArray>

    /** Reads a missing vector with the model and keeps it for reuse; null
     *  when it could not be read (or the record is not cacheable). */
    fun generate(record: ComparisonRecord): FloatArray?

    /** Whether [generate] may read this record. Active memories are not:
     *  the search index and its repair job own their vectors. */
    fun canGenerate(record: ComparisonRecord): Boolean = true

    /** A search-side vector for scanner text (a query window). */
    fun query(text: String): FloatArray?
}

/**
 * The scanner's scoped matching engine (memory development plan M.2, Stage
 * 6B.2). Pure: records arrive already eligible, vectors come through
 * [ComparisonVectors], and the result is evidence only. Exact and keyword
 * evidence work without any model; a keyword match never gets a similarity
 * score, and records whose meaning was not compared are counted, so a
 * partial check never claims to be exhaustive.
 */
object ScannerMatchEngine {

    /**
     * Starting limits (Stage 6B.2). Each bounds one cost of a lookup:
     * embedding reads of query windows are made in batches with a
     * cancellation check between them; [maxMissingVectors] bounds model reads
     * of records without a vector; [maxCompared] bounds the records scored;
     * the match counts bound the result. Phone measurements belong to 6D.
     */
    data class Limits(
        val maxWindowsPerBatch: Int = 8,
        val maxMissingVectors: Int = 12,
        val maxCompared: Int = 3_000,
        val maxMatches: Int = 8,
        val maxKeywordMatches: Int = 4
    )

    /** A section window must reach the live section gate and share an
     *  informative word with the section (Step 2's section rule). */
    const val SECTION_SIMILARITY_MIN = 0.48f
    /** A proposal compared document to document uses the Possible Match bar. */
    val PROPOSAL_SIMILARITY_MIN = PossibleMatchFinder.SEMANTIC_COSINE_THRESHOLD
    /** Shortest normalized record wording that counts as appearing whole in
     *  a section. */
    const val MIN_EXACT_IN_SECTION_CHARS = 16
    /** Informative words a keyword match must share (fewer when the record
     *  itself has fewer). */
    const val MIN_SHARED_TERMS = 2

    /**
     * Before extraction: what the section's windows find among [records].
     * [clueIds] are memories the section's replies actually used; each is
     * reported only when it is among the eligible [records].
     */
    fun lookupSection(
        fingerprint: String,
        sectionText: String,
        windows: List<String>,
        records: List<ComparisonRecord>,
        clueIds: Collection<String>,
        vectors: ComparisonVectors?,
        limits: Limits = Limits(),
        checkActive: () -> Unit = {}
    ): MatchEvidence {
        val normalizedSection = MemoryMatch.normalizeContent(sectionText)
        val queryVectors: List<FloatArray>? = vectors?.let { v ->
            val out = ArrayList<FloatArray>()
            for (batch in windows.chunked(limits.maxWindowsPerBatch.coerceAtLeast(1))) {
                checkActive()
                for (w in batch) v.query(w)?.let(out::add)
            }
            out.takeIf { windows.isNotEmpty() && it.size == windows.size }
        }
        val compared = compare(
            queryTerms = KeywordText.terms(sectionText).toSet(),
            records = records,
            vectors = if (queryVectors != null) vectors else null,
            similarity = { v -> queryVectors?.maxOfOrNull { VectorMath.cosine(it, v) } ?: 0f },
            threshold = SECTION_SIMILARITY_MIN,
            requireSharedTerm = true,
            isExact = { r ->
                val n = MemoryMatch.normalizeContent(r.content)
                n.length >= MIN_EXACT_IN_SECTION_CHARS && normalizedSection.contains(n)
            },
            limits = limits,
            checkActive = checkActive
        )
        val clueSet = clueIds.toSet()
        val clues = records.filter { it.memoryId != null && it.memoryId in clueSet }
            .map { ScannerMatch(it.memoryId, null, it.status, MatchMethod.CHAT_CLUE) }
        return MatchEvidence(
            fingerprint = fingerprint,
            modelKey = if (queryVectors != null) vectors?.modelKey else null,
            matches = combine(compared.matches, clues, limits.maxMatches),
            unexamined = compared.unexamined
        )
    }

    /**
     * After extraction: what one proposal ([self], a working draft) finds by
     * its own wording among [records] (never itself). [aiRelatedIds] and
     * [alternativeOfIds] become unresolved links when still eligible.
     */
    fun lookupProposal(
        self: ComparisonRecord,
        records: List<ComparisonRecord>,
        aiRelatedIds: Collection<String>,
        alternativeOfIds: Collection<Int>,
        vectors: ComparisonVectors?,
        limits: Limits = Limits(),
        checkActive: () -> Unit = {}
    ): MatchEvidence {
        val others = records.filter { it.key != self.key }
        val selfVector = vectors?.let { v -> v.known(listOf(self))[self.key] ?: v.generate(self) }
        val normalizedSelf = MemoryMatch.normalizeContent(self.content)
        val compared = compare(
            queryTerms = KeywordText.terms(self.content).toSet(),
            records = others,
            vectors = if (selfVector != null) vectors else null,
            similarity = { v -> if (selfVector == null) 0f else VectorMath.cosine(selfVector, v) },
            threshold = PROPOSAL_SIMILARITY_MIN,
            requireSharedTerm = false,
            isExact = { r -> MemoryMatch.normalizeContent(r.content) == normalizedSelf },
            limits = limits,
            checkActive = checkActive
        )
        val related = aiRelatedIds.toSet()
        val alternatives = alternativeOfIds.toSet()
        val links = others.mapNotNull { r ->
            when {
                r.memoryId != null && r.memoryId in related ->
                    ScannerMatch(r.memoryId, null, r.status, MatchMethod.AI_RELATED)
                r.draftId != null && r.draftId in alternatives ->
                    ScannerMatch(null, r.draftId, r.status, MatchMethod.ALTERNATIVE)
                else -> null
            }
        }
        return MatchEvidence(
            fingerprint = self.fingerprint,
            modelKey = if (selfVector != null) vectors?.modelKey else null,
            matches = compared.matches.take(limits.maxMatches),
            links = links,
            unexamined = compared.unexamined
        )
    }

    private class Compared(val matches: List<ScannerMatch>, val unexamined: Int)

    private fun compare(
        queryTerms: Set<String>,
        records: List<ComparisonRecord>,
        vectors: ComparisonVectors?,
        similarity: (FloatArray) -> Float,
        threshold: Float,
        requireSharedTerm: Boolean,
        isExact: (ComparisonRecord) -> Boolean,
        limits: Limits,
        checkActive: () -> Unit
    ): Compared {
        val shared = records.associate { r ->
            r.key to (r.terms.count { it in queryTerms } to r.terms.size)
        }
        val exact = records.filter(isExact).map { ScannerMatch(it.memoryId, it.draftId, it.status, MatchMethod.EXACT) }
        val exactKeys = exact.mapTo(HashSet()) { it.key }

        // Records most likely to matter are compared first: shared words,
        // then the most recently changed.
        val ordered = records.sortedWith(
            compareByDescending<ComparisonRecord> { shared.getValue(it.key).first }
                .thenByDescending { it.updatedAt ?: "" }
        )
        val withoutMeaning = ArrayList<ComparisonRecord>()
        val embedding = ArrayList<ScannerMatch>()
        if (vectors == null) {
            withoutMeaning.addAll(ordered)
        } else {
            val considered = ordered.take(limits.maxCompared.coerceAtLeast(0))
            withoutMeaning.addAll(ordered.drop(considered.size))
            val known = HashMap(vectors.known(considered))
            var generations = 0
            for (r in considered) {
                if (r.key in known) continue
                if (!vectors.canGenerate(r) || generations >= limits.maxMissingVectors) {
                    withoutMeaning.add(r)
                    continue
                }
                checkActive()
                generations++
                val v = vectors.generate(r)
                if (v == null) withoutMeaning.add(r) else known[r.key] = v
            }
            for (r in considered) {
                val v = known[r.key] ?: continue
                val score = similarity(v)
                if (score < threshold || !score.isFinite()) continue
                if (requireSharedTerm && shared.getValue(r.key).first == 0) continue
                embedding.add(ScannerMatch(r.memoryId, r.draftId, r.status, MatchMethod.EMBEDDING, score))
            }
            embedding.sortByDescending { it.score }
        }
        val keyword = withoutMeaning.mapNotNull { r ->
            val (count, size) = shared.getValue(r.key)
            val needed = minOf(MIN_SHARED_TERMS, size)
            if (needed == 0 || count < needed) null else r to count
        }.sortedByDescending { it.second }
            .take(limits.maxKeywordMatches)
            .map { (r, _) -> ScannerMatch(r.memoryId, r.draftId, r.status, MatchMethod.KEYWORD) }

        val out = ArrayList<ScannerMatch>(exact)
        out.addAll(embedding.filter { it.key !in exactKeys })
        val seen = out.mapTo(HashSet()) { it.key }
        out.addAll(keyword.filter { seen.add(it.key) })
        return Compared(out, withoutMeaning.size)
    }

    /** Exact and meaning evidence first, then clues, then keyword evidence,
     *  one entry per record, bounded; clues are never crowded out by
     *  keyword evidence. */
    private fun combine(found: List<ScannerMatch>, clues: List<ScannerMatch>, max: Int): List<ScannerMatch> {
        val strong = found.filter { it.method != MatchMethod.KEYWORD }
        val keyword = found.filter { it.method == MatchMethod.KEYWORD }
        val out = ArrayList<ScannerMatch>()
        val seen = HashSet<String>()
        for (m in strong + clues + keyword) {
            if (out.size >= max) break
            if (seen.add(m.key)) out.add(m)
        }
        return out
    }
}
