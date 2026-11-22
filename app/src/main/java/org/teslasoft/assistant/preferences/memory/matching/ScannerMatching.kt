/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.matching

import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.RetrievalScope
import org.teslasoft.assistant.preferences.memory.archivist.ArchivistSceneContext
import org.teslasoft.assistant.preferences.memory.archivist.WorkingDraft
import org.teslasoft.assistant.preferences.memory.archivist.WorkingDrafts
import org.teslasoft.assistant.preferences.memory.librarian.Librarian
import org.teslasoft.assistant.preferences.memory.librarian.RetrievalDocument
import org.teslasoft.assistant.preferences.memory.librarian.VectorMath
import org.teslasoft.assistant.preferences.memory.scan.ScanSourceRow
import org.teslasoft.assistant.util.Hash

/**
 * One scan range's matching (Stage 6B.2), connecting [ScannerMatchEngine] to
 * the memory database, the comparison cache and the embedding model.
 *
 * Before a section is sent, [beforeSection] looks up what the section's text
 * finds among eligible Active, Pending and Archived memories and this scan's
 * eligible working drafts, plus memories the section's replies actually used.
 * After the section's reply is applied, [afterSection] looks up each new or
 * changed draft by its own wording and keeps the evidence on the draft, which
 * the checkpoint saves. Nothing here changes what the scanner AI is sent,
 * what is filed, or any memory.
 */
class ScannerMatching(
    private val store: MemoryStore,
    private val librarian: Librarian,
    private val rangeId: String,
    /** The scene's comparison eligibility (the reconciliation scope). */
    private val scopeFor: (ArchivistSceneContext) -> RetrievalScope,
    private val limits: ScannerMatchEngine.Limits = ScannerMatchEngine.Limits()
) {

    /** Evidence for one section, or null when the section has no rows. */
    fun beforeSection(rows: List<ScanSourceRow>, working: WorkingDrafts, checkActive: () -> Unit): MatchEvidence? {
        val first = rows.firstOrNull() ?: return null
        val scope = scopeFor(ArchivistSceneContext.from(first))
        val text = rows.joinToString("\n") { it.text }
        val embedder = librarian.scannerEmbedder()
        val windows = embedder?.let { ScannerQueryWindows.split(text, it.queryTokenLimit, it::countQueryTokens) }
            .orEmpty()
        return ScannerMatchEngine.lookupSection(
            fingerprint = Hash.hash(text),
            sectionText = text,
            windows = windows,
            records = filedRecords(scope) + draftRecords(working, scope),
            clueIds = rows.flatMap { it.usedMemoryIds }.distinct(),
            vectors = embedder?.let(::Vectors),
            limits = limits,
            checkActive = checkActive
        )
    }

    /**
     * [after] with evidence on its new and changed drafts: the section's
     * evidence, and each draft's own lookup. At most
     * [MAX_PROPOSAL_LOOKUPS_PER_SECTION] lookups run per section; the rest
     * stay marked as deferred (out-of-date or missing evidence) and run after
     * later sections.
     */
    fun afterSection(
        before: WorkingDrafts,
        after: WorkingDrafts,
        section: MatchEvidence?,
        checkActive: () -> Unit
    ): WorkingDrafts {
        val embedder = librarian.scannerEmbedder()
        val vectors = embedder?.let(::Vectors)
        val beforeById = before.drafts.associateBy { it.id }
        val filedByScope = HashMap<RetrievalScope, List<ComparisonRecord>>()
        var lookups = 0
        val drafts = after.drafts.map { draft ->
            var evidence = draft.evidence ?: DraftMatchEvidence()
            if (section != null && beforeById[draft.id]?.memory != draft.memory) {
                evidence = evidence.copy(sections = mergeSection(evidence.sections, section.matches, draft.id))
            }
            val self = draftRecord(draft)
            val key = lookupKey(draft, self)
            if (evidence.needsLookup(key, vectors?.modelKey) &&
                lookups < MAX_PROPOSAL_LOOKUPS_PER_SECTION
            ) {
                lookups++
                val scope = draft.memory.scene?.let(scopeFor) ?: RetrievalScope.NONE
                val filed = filedByScope.getOrPut(scope) { filedRecords(scope) }
                evidence = evidence.copy(
                    proposal = ScannerMatchEngine.lookupProposal(
                        self = self,
                        records = filed + draftRecords(after, scope),
                        aiRelatedIds = draft.memory.relatedExistingMemoryIds,
                        alternativeOfIds = draft.alternativeOf,
                        vectors = vectors,
                        limits = limits,
                        checkActive = checkActive
                    ).copy(fingerprint = key)
                )
            }
            draft.copy(evidence = evidence.takeIf { it.proposal != null || it.sections.isNotEmpty() })
        }
        return after.copy(drafts = drafts)
    }

    /**
     * [working] with every deferred draft lookup run (Stage 6B.3: a draft
     * whose own lookup is missing or out of date has not been compared and
     * is never treated as "no match"). Bounded passes of [afterSection].
     */
    fun completeLookups(working: WorkingDrafts, checkActive: () -> Unit): WorkingDrafts {
        var current = working
        repeat(MAX_COMPLETION_PASSES) {
            val modelKey = librarian.scannerEmbedder()?.modelKey
            if (current.drafts.none { (it.evidence ?: DraftMatchEvidence()).needsLookup(lookupKey(it), modelKey) }) {
                return current
            }
            current = afterSection(current, current, null, checkActive)
        }
        return current
    }

    private fun filedRecords(scope: RetrievalScope): List<ComparisonRecord> =
        store.scannerComparableMemories(scope).mapNotNull { (m, status) ->
            val matchStatus = MatchStatus.ofMemoryStatus(status) ?: return@mapNotNull null
            val document = RetrievalDocument.semanticDocument(
                m.content, m.embeddingText, RetrievalDocument.parseTags(m.tagsJson)
            )
            ComparisonRecord(
                memoryId = m.memoryId, draftId = null, status = matchStatus, content = m.content,
                document = document, fingerprint = Hash.hash(document),
                updatedAt = m.updatedAt ?: m.createdAt
            )
        }

    private fun draftRecords(working: WorkingDrafts, scope: RetrievalScope): List<ComparisonRecord> =
        working.drafts.filter { ScannerDraftEligibility.eligible(it.memory, scope) }.map(::draftRecord)

    private inner class Vectors(private val embedder: Librarian.ScannerEmbedder) : ComparisonVectors {
        override val modelKey: String = embedder.modelKey

        /** Vectors already read in this pass, by record key and fingerprint,
         *  so several lookups in one pass read each vector once. */
        private val seen = HashMap<String, FloatArray>()

        override fun known(records: List<ComparisonRecord>): Map<String, FloatArray> {
            val out = HashMap<String, FloatArray>()
            val unread = ArrayList<ComparisonRecord>()
            for (r in records) {
                val v = seen[r.key + "|" + r.fingerprint]
                if (v != null) out[r.key] = v else unread.add(r)
            }
            if (unread.isEmpty()) return out
            val loaded = load(unread)
            for (r in unread) loaded[r.key]?.let { v ->
                seen[r.key + "|" + r.fingerprint] = v
                out[r.key] = v
            }
            return out
        }

        private fun load(records: List<ComparisonRecord>): Map<String, FloatArray> {
            val out = HashMap<String, FloatArray>()
            val active = records.filter { it.status == MatchStatus.ACTIVE && it.memoryId != null }
            if (active.isNotEmpty()) {
                store.embeddingsForMemories(active.map { it.memoryId!! }, modelKey).forEach { (id, blob) ->
                    out["m:$id"] = VectorMath.fromBlob(blob)
                }
            }
            val filed = records.filter {
                (it.status == MatchStatus.PENDING || it.status == MatchStatus.ARCHIVED) && it.memoryId != null
            }
            if (filed.isNotEmpty()) {
                ScannerComparisonCache.filedVectors(
                    store.writableDatabase, modelKey, filed.associate { it.memoryId!! to it.fingerprint }
                ).forEach { (id, blob) -> out["m:$id"] = VectorMath.fromBlob(blob) }
            }
            val drafts = records.filter { it.status == MatchStatus.WORKING_DRAFT && it.draftId != null }
            if (drafts.isNotEmpty()) {
                ScannerComparisonCache.draftVectors(
                    store.writableDatabase, rangeId, modelKey, drafts.associate { it.draftId!! to it.fingerprint }
                ).forEach { (id, blob) -> out["d:$id"] = VectorMath.fromBlob(blob) }
            }
            return out
        }

        override fun canGenerate(record: ComparisonRecord): Boolean = record.status != MatchStatus.ACTIVE

        override fun generate(record: ComparisonRecord): FloatArray? {
            if (!canGenerate(record)) return null
            val vector = embedder.embedDocument(record.document) ?: return null
            seen[record.key + "|" + record.fingerprint] = vector
            val blob = VectorMath.toBlob(vector)
            val db = store.writableDatabase
            if (record.memoryId != null) {
                ScannerComparisonCache.putFiled(db, record.memoryId, modelKey, record.fingerprint, blob)
            } else if (record.draftId != null) {
                ScannerComparisonCache.putDraft(db, rangeId, record.draftId, modelKey, record.fingerprint, blob)
            }
            return vector
        }

        override fun query(text: String): FloatArray? = embedder.embedQuery(text)
    }

    companion object {
        /** Draft lookups after one section; the rest are deferred. */
        const val MAX_PROPOSAL_LOOKUPS_PER_SECTION = 20

        /** Passes of deferred lookups before filing (20 drafts each). */
        const val MAX_COMPLETION_PASSES = 10

        /** A working draft as a comparison record: its own wording and tags,
         *  as a filed memory's document would be. */
        fun draftRecord(draft: WorkingDraft): ComparisonRecord {
            val document = RetrievalDocument.semanticDocument(draft.memory.content, null, draft.memory.tags)
            return ComparisonRecord(
                memoryId = null, draftId = draft.id, status = MatchStatus.WORKING_DRAFT,
                content = draft.memory.content, document = document, fingerprint = Hash.hash(document)
            )
        }

        /** What a draft's own lookup depends on: its comparison document and
         *  the links it carries (AI-named related memories and alternative
         *  drafts), so a new link alone also brings a fresh lookup. */
        fun lookupKey(draft: WorkingDraft, self: ComparisonRecord = draftRecord(draft)): String =
            Hash.hash(
                self.fingerprint + "\u0000" +
                    draft.memory.relatedExistingMemoryIds.sorted().joinToString(",") + "\u0000" +
                    draft.alternativeOf.sorted().joinToString(",")
            )

        /** The newest section's evidence first, one entry per record, never
         *  the draft itself, bounded. */
        fun mergeSection(earlier: List<ScannerMatch>, latest: List<ScannerMatch>, selfId: Int): List<ScannerMatch> {
            val seen = HashSet<String>()
            return (latest + earlier)
                .filter { it.draftId != selfId && seen.add(it.key) }
                .take(DraftMatchEvidence.MAX_SECTION_MATCHES)
        }
    }
}
