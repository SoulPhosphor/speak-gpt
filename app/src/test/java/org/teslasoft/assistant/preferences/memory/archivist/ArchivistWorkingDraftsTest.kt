/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.archivist

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.archivist.ArchivistResponseParser.DraftMemory
import org.teslasoft.assistant.preferences.memory.archivist.ArchivistResponseParser.RawDraftEdit
import org.teslasoft.assistant.preferences.memory.scan.ScanCheckpoint

/** Stage 6B.1: scan-local working drafts, their validated edits and repair. */
class ArchivistWorkingDraftsTest {

    private val scene = ArchivistSceneContext("companion-a", null, null, null, null)

    private fun memory(
        content: String,
        scope: String = "real_life",
        targets: List<String> = emptyList(),
        related: List<String> = emptyList(),
        type: String? = "type-fact",
        tags: List<String> = emptyList()
    ) = DraftMemory(
        content = content, scope = scope, typeIdSuggestion = type, tags = tags, targetName = null,
        targetIds = targets, relatedExistingMemoryIds = related, scene = scene
    )

    private fun ref(id: String, start: Int = 0, end: Int = 10) = PassageRef(id, start, end)

    private fun section(n: Int) = listOf(ref("m$n"))

    private fun edit(vararg refs: String, content: String, type: String? = null, tags: List<String>? = null) =
        RawDraftEdit(refs.toList(), content, type, tags)

    /** Every draft shown, as a request built from the collection would. */
    private fun shown(drafts: WorkingDrafts) = drafts.drafts.associate { "D${it.id}" to it.id }

    private val noRepair: suspend (List<String>) -> Pair<List<RawDraftEdit>, List<String>>? = {
        throw AssertionError("no repair expected")
    }

    private fun changes(
        drafts: WorkingDrafts,
        edits: List<RawDraftEdit> = emptyList(),
        withdrawals: List<String> = emptyList(),
        newDrafts: List<DraftMemory> = emptyList(),
        sources: List<PassageRef> = emptyList(),
        repair: suspend (List<String>) -> Pair<List<RawDraftEdit>, List<String>>? = noRepair
    ): SectionDraftChanges = runBlocking {
        val (ops, unresolved) = WorkingDraftEdits.resolve(
            edits, withdrawals, shown(drafts), drafts.drafts.map { it.id }.toSet(),
            WorkingDraftEdits.samePlacement(drafts), repair
        )
        SectionDraftChanges(ops, unresolved.first, newDrafts, sources, unresolved.second)
    }

    private fun start(vararg contents: String): WorkingDrafts =
        WorkingDrafts().applying(SectionDraftChanges(newDrafts = contents.map { memory(it) }, sources = section(1)))

    @Test fun aLateReplyCorrectsAnEarlyProvisionalDraftInsteadOfAddingAnother() {
        // Section 1: still waiting to hear back.
        val early = start("Sent a job application to the library and is waiting to hear back.")
        // Section 9: the reply finally came, after a long delay.
        val late = early.applying(
            changes(
                early,
                edits = listOf(edit("D1", content = "Applied to the library and, after waiting six weeks, was offered the job.")),
                sources = section(9)
            )
        )
        val only = late.drafts.single()
        assertEquals(1, only.id)
        assertEquals("Applied to the library and, after waiting six weeks, was offered the job.", only.memory.content)
        assertEquals(listOf(ref("m1"), ref("m9")), only.sources)
        // Scope, Type and scene are kept.
        assertEquals("real_life", only.memory.scope)
        assertEquals("type-fact", only.memory.typeIdSuggestion)
        assertEquals(scene, only.memory.scene)
    }

    @Test fun editWithdrawAndCombineAreLosslessForEveryDraftNotNamed() {
        val drafts = start("Has a dog named Juniper", "Likes oolong", "Wrong guess about a cat", "Juniper is a collie", "Lives in Leeds")
        val after = drafts.applying(
            changes(
                drafts,
                edits = listOf(edit("D1", "d4", content = "Has a collie named Juniper")),
                withdrawals = listOf("D3"),
                newDrafts = listOf(memory("Started pottery classes")),
                sources = section(2)
            )
        )
        assertEquals(listOf(1, 2, 5, 6), after.drafts.map { it.id })
        assertEquals("Has a collie named Juniper", after.drafts[0].memory.content)
        // Drafts that were not named keep their exact text.
        assertEquals("Likes oolong", after.drafts[1].memory.content)
        assertEquals("Lives in Leeds", after.drafts[2].memory.content)
        assertEquals("Started pottery classes", after.drafts[3].memory.content)
        assertEquals(7, after.nextId)
    }

    @Test fun combiningKeepsTheRelationshipHintsOfEveryCombinedDraft() {
        val drafts = WorkingDrafts().applying(
            SectionDraftChanges(newDrafts = listOf(memory("A", related = listOf("m-1")), memory("B", related = listOf("m-2"))))
        )
        val after = drafts.applying(changes(drafts, edits = listOf(edit("D2", "D1", content = "A and B"))))
        assertEquals(listOf("m-1", "m-2"), after.drafts.single().memory.relatedExistingMemoryIds.sorted())
        assertEquals(1, after.drafts.single().id)
    }

    @Test fun invalidOperationsDoNotDestroyValidWork() {
        val drafts = start("Visiting Lisbon in May", "Afraid of flying")
        val result = changes(
            drafts,
            edits = listOf(edit("D1", content = "Visiting Lisbon and Porto in May")),
            withdrawals = listOf("D99"),
            newDrafts = listOf(memory("Booked a train to Porto"))
        )
        // An invented reference alone is never repaired, never linked and
        // never becomes a draft.
        assertEquals(1, result.failedEdits)
        assertTrue(result.alternatives.isEmpty())
        val after = drafts.applying(result)
        assertEquals(
            listOf("Visiting Lisbon and Porto in May", "Afraid of flying", "Booked a train to Porto"),
            after.drafts.map { it.memory.content }
        )
    }

    @Test fun anEditNamingOnlyAnInventedReferenceChangesNothingAndAddsNothing() {
        val drafts = start("Likes oolong")
        val result = changes(drafts, edits = listOf(edit("D7", content = "Likes green tea")))
        assertEquals(drafts, drafts.applying(result))
        assertEquals(1, result.failedEdits)
    }

    @Test fun aDraftNotShownInTheRequestCannotBeEdited() {
        val drafts = start("Likes oolong", "Lives in Leeds")
        val (ops, unresolved) = runBlocking {
            WorkingDraftEdits.resolve(
                listOf(edit("D2", content = "Lives in York")), emptyList(), mapOf("D1" to 1), setOf(1, 2),
                WorkingDraftEdits.samePlacement(drafts), noRepair
            )
        }
        assertTrue(ops.isEmpty())
        assertEquals(1, unresolved.second)
    }

    @Test fun conflictingEditsAreRepairedWithOneNarrowRequest() {
        val drafts = start("Moving in spring", "Likes oolong")
        val asked = ArrayList<List<String>>()
        val result = changes(
            drafts,
            edits = listOf(edit("D1", content = "Moving in April"), edit("D1", content = "Moving in June")),
            repair = { problems ->
                asked.add(problems)
                listOf(edit("D1", content = "Moving in June, after first planning April")) to emptyList()
            }
        )
        assertEquals(listOf(listOf("D1 was named by more than one edit or withdrawal.")), asked)
        assertEquals(0, result.failedEdits)
        val after = drafts.applying(result)
        assertEquals("Moving in June, after first planning April", after.drafts[0].memory.content)
        assertEquals("Likes oolong", after.drafts[1].memory.content)
    }

    @Test fun aRepairCannotTouchADraftTheFailedEditsDidNotName() {
        val drafts = start("Moving in spring", "Likes oolong")
        val result = changes(
            drafts,
            edits = listOf(edit("D1", content = "April"), edit("D1", content = "June")),
            repair = { listOf(edit("D2", content = "Likes coffee")) to listOf("D1") }
        )
        val after = drafts.applying(result)
        // D2 was not part of the failure and stays exactly as it was; the
        // repair's withdrawal of D1 was allowed.
        assertEquals(listOf("Likes oolong"), after.drafts.map { it.memory.content })
    }

    @Test fun aFailedRepairKeepsTheOriginalAndTheNewWordingAsLinkedAlternatives() {
        val drafts = start("Moving in spring", "Likes oolong")
        val result = changes(
            drafts,
            edits = listOf(edit("D1", content = "Moving in April"), edit("D1", content = "Moving in June")),
            withdrawals = listOf("D2", "D2"),
            repair = { null }
        )
        // The repeated withdrawal of D2 is one valid operation; only the two
        // conflicting edits failed.
        assertEquals(2, result.failedEdits)
        val after = drafts.applying(result)
        assertEquals(
            listOf("Moving in spring", "Moving in April", "Moving in June"),
            after.drafts.map { it.memory.content }
        )
        assertEquals(listOf(1), after.drafts[1].alternativeOf)
        assertEquals(listOf(1), after.drafts[2].alternativeOf)
        // The alternatives keep the original's placement.
        assertEquals(after.drafts[0].memory.scope, after.drafts[1].memory.scope)
    }

    @Test fun aRepairRequestThatFailsToSendStillKeepsValidWork() {
        val drafts = start("Moving in spring", "Likes oolong")
        val result = changes(
            drafts,
            edits = listOf(
                edit("D2", content = "Likes oolong and jasmine tea"),
                edit("D1", content = ""),
            ),
            repair = { throw IllegalStateException("provider unavailable") }
        )
        val after = drafts.applying(result)
        assertEquals(listOf("Moving in spring", "Likes oolong and jasmine tea"), after.drafts.map { it.memory.content })
        assertEquals(1, result.failedEdits)
    }

    @Test fun draftsWithDifferentPlacementsAreNeverCombined() {
        val drafts = WorkingDrafts().applying(
            SectionDraftChanges(
                newDrafts = listOf(memory("Garden plan", scope = "project", targets = listOf("p-1")), memory("Garden plan v2", scope = "real_life"))
            )
        )
        val asked = ArrayList<List<String>>()
        val result = changes(drafts, edits = listOf(edit("D1", "D2", content = "Garden plan")), repair = { asked.add(it); null })
        assertEquals(listOf(listOf("D1 and D2 have different scopes or targets and cannot be combined.")), asked)
        assertEquals(listOf("Garden plan", "Garden plan v2"), drafts.applying(result).drafts.take(2).map { it.memory.content })
    }

    @Test fun anEditKeepsTheDraftsTypeAndTagsUnlessItGivesNewOnes() {
        val drafts = WorkingDrafts().applying(SectionDraftChanges(newDrafts = listOf(memory("Likes oolong", tags = listOf("tea")))))
        val kept = drafts.applying(changes(drafts, edits = listOf(edit("D1", content = "Likes oolong a lot"))))
        assertEquals("type-fact", kept.drafts.single().memory.typeIdSuggestion)
        assertEquals(listOf("tea"), kept.drafts.single().memory.tags)
        val changed = drafts.applying(
            changes(drafts, edits = listOf(edit("D1", content = "x", type = "type-habit", tags = listOf("drinks"))))
        )
        assertEquals("type-habit", changed.drafts.single().memory.typeIdSuggestion)
        assertEquals(listOf("drinks"), changed.drafts.single().memory.tags)
    }

    @Test fun theCombiningRuleIsSuppliedByTheCaller() {
        val ids = setOf(1, 2)
        val refs = mapOf("D1" to 1, "D2" to 2)
        val combine = listOf(RawDraftEdit(listOf("D1", "D2"), "both", null, null))
        val open = WorkingDraftEdits.validate(combine, emptyList(), refs, ids)
        assertEquals(1, open.valid.size)
        val closed = WorkingDraftEdits.validate(combine, emptyList(), refs, ids, { _, label -> "$label differ." })
        assertTrue(closed.valid.isEmpty())
        assertEquals(listOf("D1 and D2 differ."), closed.problems)
    }

    @Test fun anExactRepeatMergesIntoTheExistingDraft() {
        val drafts = start("Likes oolong")
        val after = drafts.applying(SectionDraftChanges(newDrafts = listOf(memory("Likes oolong", tags = listOf("tea"))), sources = section(4)))
        assertEquals(1, after.drafts.size)
        assertEquals(listOf("tea"), after.drafts.single().memory.tags)
        assertEquals(listOf(ref("m1"), ref("m4")), after.drafts.single().sources)
        assertEquals(2, after.nextId)
    }

    @Test fun theSavedCollectionRestoresExactlyIncludingIdsSourcesAndLinks() {
        val drafts = start("Moving in spring", "Likes oolong")
        val withAlternative = drafts.applying(
            changes(drafts, edits = listOf(edit("D1", content = "April"), edit("D1", content = "June")), withdrawals = listOf("D2"), repair = { null })
        )
        val restored = WorkingDrafts.restore(withAlternative.drafts.map(WorkingDrafts::payload), withAlternative.nextId)
        assertEquals(withAlternative, restored)
        // A paused scan resumed from the saved collection continues exactly
        // as an uninterrupted one.
        val next = SectionDraftChanges(
            operations = listOf(DraftOperation.Edit(listOf(3), "April, then June", null, null)),
            newDrafts = listOf(memory("Bought boxes")), sources = section(5)
        )
        assertEquals(withAlternative.applying(next), restored.applying(next))
    }

    @Test fun draftsSavedBeforeDraftIdsExistedAreNumberedWithoutReusingIds() {
        val legacy = ArchivistCandidateBoundary.payload(memory("Older draft"))
        val current = WorkingDrafts.payload(WorkingDraft(4, memory("Newer draft")))
        val restored = WorkingDrafts.restore(listOf(legacy, current), savedNextId = 1)
        assertEquals(listOf(5, 4), restored.drafts.map { it.id })
        assertEquals(6, restored.nextId)
    }

    @Test fun sourcesAreBoundedKeepingTheFirstAndLatest() {
        val many = (1..100).map { ref("m$it") }
        val bounded = WorkingDrafts.boundedSources(many)
        assertEquals(WorkingDrafts.MAX_SOURCES_PER_DRAFT, bounded.size)
        assertEquals(ref("m1"), bounded.first())
        assertEquals(ref("m100"), bounded.last())
    }

    @Test fun theCheckpointCarriesTheNextDraftId() {
        val saved = ScanCheckpoint(nextDraftId = 12).encode()
        assertEquals(12, ScanCheckpoint.decode(saved).nextDraftId)
        assertEquals(1, ScanCheckpoint.decode("""{"sections":2}""").nextDraftId)
        val chat = listOf(
            org.teslasoft.assistant.preferences.memory.scan.ScanMessage(
                id = "m1", number = 1, role = "user", text = "hi", eligible = true, scope = null,
                timeMillis = null, model = null, fingerprint = "f"
            )
        )
        assertEquals(12, ScanCheckpoint(nextDraftId = 12).advancedTo(chat, 0, 2).nextDraftId)
    }

    @Test fun relatedDraftsAreChosenWhenNotAllFit() {
        val items = listOf("greenhouse heater broke", "likes tea", "moved house", "car repairs")
        val chosen = WorkingDraftEdits.select(items, { it }, "the heater in the greenhouse", 2, 10_000) { 1 }
        assertEquals(listOf("greenhouse heater broke", "car repairs"), chosen)
        assertFalse(chosen.contains("likes tea"))
    }

    @Test fun anOversizedCombinationIsCheckedWholeAndAnUnknownReferenceRejectsIt() {
        val drafts = WorkingDrafts().applying(
            SectionDraftChanges(newDrafts = (1..11).map { memory("Fact $it") })
        )
        val all = (1..11).map { "D$it" }.toTypedArray()
        val combined = drafts.applying(changes(drafts, edits = listOf(edit(*all, content = "Facts 1 to 11"))))
        assertEquals(listOf("Facts 1 to 11"), combined.drafts.map { it.memory.content })
        val withUnknown = changes(drafts, edits = listOf(edit(*all, "D99", content = "Facts")), repair = { null })
        assertEquals(1, withUnknown.failedEdits)
        assertEquals(11, drafts.applying(withUnknown).drafts.count { it.alternativeOf.isEmpty() })
    }
}
