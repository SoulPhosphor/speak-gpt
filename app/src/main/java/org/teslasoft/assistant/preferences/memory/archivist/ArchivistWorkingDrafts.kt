/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.archivist

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.memory.MemoryMatch
import org.teslasoft.assistant.util.edits.ReferencedEdits
import java.util.Locale

/**
 * A scan's working drafts (memory development plan M.2, Stage 6B.1): the
 * temporary memory proposals of one chat range, each with a stable scan-local
 * ID, that later sections may correct, combine or withdraw before anything is
 * filed. Kotlin owns the collection; the model only names drafts by the
 * request-local references it was shown ("D4"), and every operation is
 * validated before anything changes.
 *
 * Only working drafts are edited here. Filed Active, Pending and Archived
 * memories are never changed by a scan.
 */

/** Where a draft's support came from: a message UUID and the character
 *  offsets read from it. Never copied text. */
data class PassageRef(val messageId: String, val start: Int, val end: Int)

data class WorkingDraft(
    val id: Int,
    val memory: ArchivistResponseParser.DraftMemory,
    val sources: List<PassageRef> = emptyList(),
    /** Drafts this one is an unreconciled alternative wording of: an edit
     *  that could not be applied even after repair. Stage 6B.3 reconciles. */
    val alternativeOf: List<Int> = emptyList()
)

/** A validated operation, in stable draft IDs. */
sealed class DraftOperation {
    abstract val ids: List<Int>

    data class Edit(
        override val ids: List<Int>,
        val content: String,
        val typeId: String?,
        val tags: List<String>?
    ) : DraftOperation()

    data class Withdraw(val id: Int) : DraftOperation() {
        override val ids: List<Int> get() = listOf(id)
    }
}

/** New wording kept beside the drafts it names, replacing nothing. */
data class DraftAlternative(
    val alternativeOf: List<Int>,
    val content: String,
    val typeId: String?,
    val tags: List<String>?
)

/** Everything one request changes in the working collection. */
data class SectionDraftChanges(
    val operations: List<DraftOperation> = emptyList(),
    val alternatives: List<DraftAlternative> = emptyList(),
    val newDrafts: List<ArchivistResponseParser.DraftMemory> = emptyList(),
    /** The passages the request read as its new section. */
    val sources: List<PassageRef> = emptyList(),
    /** Edits and withdrawals that were finally not applied. */
    val failedEdits: Int = 0
)

data class WorkingDrafts(
    val drafts: List<WorkingDraft> = emptyList(),
    val nextId: Int = 1
) {
    fun ref(id: Int): String = "D$id"

    /**
     * The collection after [changes]: validated edits and withdrawals first,
     * then alternatives, then new proposals. An exact repeat of an existing
     * draft merges into it (as [ArchivistCandidateBoundary.collectFlat] does)
     * instead of becoming a second draft. Deterministic, so a section split by
     * a size retry applies exactly as an unsplit one would.
     */
    fun applying(changes: SectionDraftChanges): WorkingDrafts {
        val list = ArrayList(drafts)
        var next = nextId
        for (op in changes.operations) {
            when (op) {
                is DraftOperation.Withdraw -> list.removeAll { it.id == op.id }
                is DraftOperation.Edit -> {
                    val named = list.filter { it.id in op.ids }
                    // Validated against this collection; a missing draft means
                    // the operation is stale and changes nothing.
                    if (named.size != op.ids.distinct().size) continue
                    val base = named.first()
                    val edited = base.copy(
                        memory = edit(base.memory, named.map { it.memory }, op.content, op.typeId, op.tags),
                        sources = boundedSources(named.flatMap { it.sources } + changes.sources)
                    )
                    list.removeAll { it.id in op.ids && it.id != base.id }
                    list[list.indexOfFirst { it.id == base.id }] = edited
                }
            }
        }
        for (alternative in changes.alternatives) {
            val base = list.firstOrNull { it.id in alternative.alternativeOf } ?: continue
            val memory = base.memory.copy(
                content = alternative.content,
                typeIdSuggestion = alternative.typeId ?: base.memory.typeIdSuggestion,
                tags = alternative.tags ?: base.memory.tags
            )
            next = add(list, memory, changes.sources, next, alternative.alternativeOf.filter { id -> list.any { it.id == id } })
        }
        for (memory in changes.newDrafts) {
            next = add(list, memory, changes.sources, next, emptyList())
        }
        return WorkingDrafts(list, next)
    }

    private fun add(
        list: ArrayList<WorkingDraft>,
        memory: ArchivistResponseParser.DraftMemory,
        sources: List<PassageRef>,
        next: Int,
        alternativeOf: List<Int>
    ): Int {
        val key = ArchivistCandidateBoundary.identityKey(memory)
        val at = list.indexOfFirst { ArchivistCandidateBoundary.identityKey(it.memory) == key }
        if (at >= 0) {
            val prior = list[at]
            val merged = ArchivistCandidateBoundary.collectFlat(listOf(prior.memory, memory)).memories.single()
            list[at] = prior.copy(
                memory = merged,
                sources = boundedSources(prior.sources + sources),
                alternativeOf = (prior.alternativeOf + alternativeOf).distinct().filter { it != prior.id }
            )
            return next
        }
        list.add(WorkingDraft(next, memory, boundedSources(sources), alternativeOf))
        return next + 1
    }

    companion object {
        /** Passage references kept per draft: its first and its latest. */
        const val MAX_SOURCES_PER_DRAFT = 32
        private const val FIRST_SOURCES_KEPT = 8

        fun boundedSources(sources: List<PassageRef>): List<PassageRef> {
            val distinct = sources.distinct()
            if (distinct.size <= MAX_SOURCES_PER_DRAFT) return distinct
            return distinct.take(FIRST_SOURCES_KEPT) +
                distinct.takeLast(MAX_SOURCES_PER_DRAFT - FIRST_SOURCES_KEPT)
        }

        /** An edit keeps the draft's scope, targets and scene; it replaces the
         *  prose and, when given, the Type and tags. Combined drafts share
         *  their relationship hints. */
        internal fun edit(
            base: ArchivistResponseParser.DraftMemory,
            named: List<ArchivistResponseParser.DraftMemory>,
            content: String,
            typeId: String?,
            tags: List<String>?
        ): ArchivistResponseParser.DraftMemory = base.copy(
            content = content,
            typeIdSuggestion = typeId ?: base.typeIdSuggestion,
            tags = tags ?: base.tags,
            relatedExistingMemoryIds = named.flatMap { it.relatedExistingMemoryIds }.distinct().take(10),
            unresolvedTargetReference = named.any { it.unresolvedTargetReference }
        )

        /** Where a draft is filed: scope plus resolved targets. Drafts with
         *  different placements are never combined. */
        internal fun placement(memory: ArchivistResponseParser.DraftMemory): String =
            MemoryMatch.placementKey(
                memory.scope,
                memory.targetIds.ifEmpty { listOfNotNull(memory.scene?.targetIdFor(memory.scope)) }
            )

        /** The encrypted temporary payload of a working draft. */
        fun payload(draft: WorkingDraft): String =
            JSONObject(ArchivistCandidateBoundary.payload(draft.memory)).apply {
                put("draft_id", draft.id)
                put("sources", JSONArray().apply {
                    draft.sources.forEach { put(JSONArray().put(it.messageId).put(it.start).put(it.end)) }
                })
                if (draft.alternativeOf.isNotEmpty()) put("alternative_of", JSONArray(draft.alternativeOf))
            }.toString()

        /**
         * The working collection rebuilt from its saved payloads, in saved
         * order. Drafts saved before draft IDs existed are numbered after the
         * highest known ID. [savedNextId] is the checkpoint's next ID.
         */
        fun restore(payloads: List<String>, savedNextId: Int): WorkingDrafts {
            val parsed = payloads.mapNotNull { json ->
                val memory = ArchivistCandidateBoundary.memoryFromPayload(json) ?: return@mapNotNull null
                val o = try { JSONObject(json) } catch (_: Exception) { return@mapNotNull null }
                val sources = ArrayList<PassageRef>()
                o.optJSONArray("sources")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val ref = arr.optJSONArray(i) ?: continue
                        val id = ref.optString(0).takeIf { it.isNotBlank() } ?: continue
                        sources.add(PassageRef(id, ref.optInt(1), ref.optInt(2)))
                    }
                }
                val alternativeOf = ArrayList<Int>()
                o.optJSONArray("alternative_of")?.let { arr ->
                    for (i in 0 until arr.length()) arr.optInt(i, 0).takeIf { it > 0 }?.let(alternativeOf::add)
                }
                Triple(o.optInt("draft_id", 0).takeIf { it > 0 }, memory, sources to alternativeOf)
            }
            var next = maxOf(savedNextId, (parsed.mapNotNull { it.first }.maxOrNull() ?: 0) + 1)
            val used = HashSet<Int>()
            val drafts = parsed.map { (id, memory, extra) ->
                val stable = id?.takeIf { used.add(it) } ?: next++.also { used.add(it) }
                WorkingDraft(stable, memory, extra.first, extra.second)
            }
            return WorkingDrafts(drafts, next)
        }
    }
}

/**
 * Validation and targeted repair of the edit operations in one response,
 * against app-issued IDs. Nothing here knows what the IDs stand for: the
 * caller says which IDs exist and supplies its own rule for which items may
 * be combined ([combineProblem]). An operation applies only when every
 * reference it names was shown in that request, its content is usable, the
 * caller's rule allows the combination, and no other operation names the same
 * item. Anything else is isolated: the valid operations still apply, and the
 * failed ones get one narrow repair request that may touch only the items
 * they named.
 */
object WorkingDraftEdits {

    data class Failed(
        val edit: ArchivistResponseParser.RawDraftEdit?,
        /** The shown drafts the operation named (fabricated references are
         *  never resolved to a draft). */
        val knownIds: List<Int>
    )

    data class Validation(
        val valid: List<DraftOperation>,
        val failed: List<Failed>,
        /** One plain line per problem, for the repair request. */
        val problems: List<String>
    )

    /** One plain-text problem line per failure kind, for the model. */
    internal fun describeRef(ref: String): String =
        if (Regex("D\\d{1,7}").matches(ref)) ref else "An unrecognized reference"

    /** The memory scanner's combining rule: drafts are combined only when
     *  they share one placement (scope plus targets). */
    fun samePlacement(drafts: WorkingDrafts): (ids: List<Int>, label: String) -> String? {
        val byId = drafts.drafts.associateBy { it.id }
        return { ids, label ->
            if (ids.mapNotNull { byId[it] }.map { WorkingDrafts.placement(it.memory) }.distinct().size > 1)
                "$label have different scopes or targets and cannot be combined." else null
        }
    }

    /**
     * @param exposed the request's references ("D4") to stable IDs.
     * @param present the IDs that currently exist.
     * @param combineProblem the caller's rule: a problem line when the named
     *  items may not be combined, else null.
     * @param allowed when given, only these items may be named (a repair).
     */
    fun validate(
        edits: List<ArchivistResponseParser.RawDraftEdit>,
        withdrawals: List<String>,
        exposed: Map<String, Int>,
        present: Set<Int>,
        combineProblem: (ids: List<Int>, label: String) -> String? = { _, _ -> null },
        allowed: Set<Int>? = null
    ): Validation {
        val result = ReferencedEdits.validate(
            edits, withdrawals, exposed, present,
            combineProblem = { ids, refs -> combineProblem(ids, label(refs)) },
            allowed = allowed
        )
        return Validation(
            valid = result.valid.map { op ->
                when (op) {
                    is ReferencedEdits.Accepted.Edit ->
                        DraftOperation.Edit(op.ids, op.edit.content, op.edit.typeId, op.edit.tags)
                    is ReferencedEdits.Accepted.Withdraw -> DraftOperation.Withdraw(op.id)
                }
            },
            failed = result.failed.map { Failed(it.edit, it.knownIds) },
            problems = result.problems.mapTo(LinkedHashSet(), ::describe).toList()
        )
    }

    private fun label(refs: List<String>): String = refs.joinToString(" and ") { describeRef(it) }

    /** The problem line the repair request shows the model. */
    private fun describe(problem: ReferencedEdits.Problem): String = when (problem) {
        ReferencedEdits.Problem.NoReference -> "A draft edit named no working draft."
        is ReferencedEdits.Problem.Unknown -> "${describeRef(problem.ref)} is not a supplied working draft."
        is ReferencedEdits.Problem.NoContent -> "The edit to ${label(problem.refs)} has no content."
        is ReferencedEdits.Problem.CannotCombine -> problem.text
        is ReferencedEdits.Problem.Conflict -> "D${problem.id} was named by more than one edit or withdrawal."
    }

    /**
     * Validates [parsed]'s operations, repairs failures once where a shown
     * draft was named, and keeps independently valid wording of any edit that
     * still cannot be applied as an alternative beside the original draft.
     * An invalid withdrawal, or an edit naming only invented references,
     * changes nothing and adds nothing, and wording whose named items the
     * caller's rule does not allow to be combined is never kept.
     */
    suspend fun resolve(
        edits: List<ArchivistResponseParser.RawDraftEdit>,
        withdrawals: List<String>,
        exposed: Map<String, Int>,
        present: Set<Int>,
        combineProblem: (ids: List<Int>, label: String) -> String?,
        /** One repair request: the operations it returned, or null when it
         *  could not be read. */
        repair: suspend (problems: List<String>) -> Pair<List<ArchivistResponseParser.RawDraftEdit>, List<String>>?
    ): Pair<List<DraftOperation>, Pair<List<DraftAlternative>, Int>> {
        val first = validate(edits, withdrawals, exposed, present, combineProblem)
        if (first.failed.isEmpty()) return first.valid to (emptyList<DraftAlternative>() to 0)

        val touched = first.valid.flatMap { it.ids }.toSet()
        val repairable = first.failed.flatMap { it.knownIds }.toSet() - touched
        val repaired = if (repairable.isEmpty()) emptyList() else {
            val answer = try {
                repair(first.problems)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            answer?.let { (repairEdits, repairWithdrawals) ->
                validate(repairEdits, repairWithdrawals, exposed, present, combineProblem, allowed = repairable).valid
            }.orEmpty()
        }
        val resolvedIds = repaired.flatMap { it.ids }.toSet()
        val remaining = first.failed.filter { f -> f.knownIds.none { it in resolvedIds } }
        val alternatives = remaining.mapNotNull { f ->
            val edit = f.edit ?: return@mapNotNull null
            if (f.knownIds.isEmpty() || edit.content.isBlank()) return@mapNotNull null
            // Kept wording must itself be allowed: a combination the
            // caller's rule rejects is never kept as a draft.
            if (f.knownIds.size > 1 && combineProblem(f.knownIds, "") != null) return@mapNotNull null
            DraftAlternative(f.knownIds, edit.content, edit.typeId, edit.tags)
        }
        return (first.valid + repaired) to (alternatives to remaining.size)
    }

    /**
     * The working drafts to show the model, within the request's budget:
     * all of them when they fit, otherwise those sharing the most words with
     * the section (newer first on a tie). Returned in collection order.
     */
    fun <T> select(
        items: List<T>,
        textOf: (T) -> String,
        sectionText: String,
        maxCount: Int,
        maxChars: Int,
        cost: (T) -> Int
    ): List<T> {
        if (items.size <= maxCount && items.sumOf(cost) <= maxChars) return items
        val words = words(sectionText)
        val ranked = items.withIndex().sortedWith(
            compareByDescending<IndexedValue<T>> { (_, item) -> words(textOf(item)).count { it in words } }
                .thenByDescending { it.index }
        )
        val chosen = HashSet<Int>()
        var used = 0
        for ((index, item) in ranked) {
            if (chosen.size >= maxCount) break
            val c = cost(item)
            if (used + c > maxChars) continue
            chosen.add(index)
            used += c
        }
        return items.filterIndexed { index, _ -> index in chosen }
    }

    private fun words(text: String): Set<String> =
        Regex("[\\p{L}\\p{N}]{4,}").findAll(text.lowercase(Locale.ROOT)).map { it.value }.toSet()
}
