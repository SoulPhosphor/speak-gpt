package org.teslasoft.assistant.ui.chat.parallel

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.conversation.responsegroups.*
import org.teslasoft.assistant.usage.TokenUsageAccounting

class ParallelResponseSelectionTest {
    private fun history(): List<HashMap<String, Any>> = Gson().fromJson(
        javaClass.classLoader!!.getResource("response-groups/history.json")!!.readText(),
        object : TypeToken<List<HashMap<String, Any>>>() {}.type)
    private fun group(row: Map<String, Any>) = (ResponseGroupCodec.read(row) as ResponseGroupRead.Valid).group

    @Test fun browsedTabAndItsVersionSurviveReopenWithoutChangingTheConversationOrUsage() {
        val row = history()[2]
        val original = group(row)
        val chosen = original.slots[3]
        val browsed = ResponseGroupEdits.install(row, ResponseGroupEdits.browse(original, chosen.slotId, chosen.versions.last().versionId))
        val reopened: HashMap<String, Any> = Gson().fromJson(Gson().toJson(browsed), object : TypeToken<HashMap<String, Any>>() {}.type)
        assertEquals(row["message"], reopened["message"])
        assertEquals("answer-1-3-1", ParallelResponseSelection.display(reopened)["message"])
        assertEquals(ResponseGroupEdits.usage(original), ResponseGroupEdits.usage(group(reopened)))
        val pending = ParallelResponseSelection.pending(listOf(history()[1], reopened))!!
        assertFalse(pending.hasDescendants)
        assertEquals(chosen.slotId, ParallelResponseSelection.adopt(pending.group).primarySlotId)
    }

    @Test fun sendAdoptsOnlyTheDisplayedSlotAndVersionAndPreservesEverySiblingAndAttempt() {
        val rows = history().take(3)
        val row = rows.last()
        val original = group(row)
        val slot = original.slots[2]
        val selected = ResponseGroupEdits.browse(original, slot.slotId, slot.versions.last().versionId)
        val adopted = ParallelResponseSelection.adopt(selected)
        val change = ResponseGroupHistory.replace("chat", rows, ResponseGroupTarget("chat", original.assistantMessageId, original.groupId),
            row[ResponseGroupCodec.KEY] as String, adopted)
        assertEquals("answer-1-2-1", change.after["message"])
        assertEquals(original.slots.filter { it.slotId != slot.slotId }, adopted.slots.filter { it.slotId != slot.slotId })
        assertEquals(original.input, adopted.input)
        assertEquals(ResponseGroupEdits.usage(original), ResponseGroupEdits.usage(adopted))
        assertNull(ParallelResponseSelection.pending(change.messages))
    }

    @Test fun earlierBrowsingRequiresBranchPermissionBeforeRemovingAnyDescendant() {
        val rows = history()
        val row = rows[2]
        val original = group(row)
        val slot = original.slots[1]
        val browsed = ResponseGroupEdits.install(row, ResponseGroupEdits.browse(original, slot.slotId, slot.canonicalVersionId))
        val history = rows.mapIndexed { index, saved -> if (index == 2) browsed else saved }
        val pending = ParallelResponseSelection.pending(history)!!
        assertTrue(pending.hasDescendants)
        val target = ResponseGroupTarget("chat", original.assistantMessageId, original.groupId)
        try {
            ResponseGroupHistory.replace("chat", history, target, browsed[ResponseGroupCodec.KEY] as String, ParallelResponseSelection.adopt(pending.group))
            fail("Branch change needs explicit permission")
        } catch (_: IllegalArgumentException) { }
        assertEquals(rows.size, history.size)
        val changed = ResponseGroupHistory.replace("chat", history, target, browsed[ResponseGroupCodec.KEY] as String,
            ParallelResponseSelection.adopt(pending.group), discardDescendants = true)
        assertEquals(rows.takeLast(2), changed.removed)
        assertEquals(original.input, group(changed.after).input)
    }

    @Test fun tokenBackfillCannotWriteAcrossTabsOrIntoTheCanonicalCompatibilityMirror() {
        val row = history()[2]
        val group = group(row)
        val slot = group.slots[3]
        val browsed = ResponseGroupEdits.install(row, ResponseGroupEdits.browse(group, slot.slotId, slot.versions.last().versionId))
        val display = ParallelResponseSelection.display(browsed)
        val index = ParallelResponseSelection.displayIndex(browsed)!!
        assertEquals(false, ParallelResponseSelection.storeEstimate(browsed, display, index - 1, "answer-1-3-1", 17))
        assertEquals(true, ParallelResponseSelection.storeEstimate(browsed, display, index, "answer-1-3-1", 17))
        assertEquals(row["message"], browsed["message"])
        assertEquals("17", ParallelResponseSelection.display(browsed)["visibleTextTokenEstimate"])
        assertEquals(group.slots.take(3), group(browsed).slots.take(3))
        assertEquals(ResponseGroupEdits.usage(group), TokenUsageAccounting.durableRecordsOf(browsed))
    }

    @Test fun imageRemindersAreVersionLocalWhileOriginalReceiptsAndFileReferencesRemainIntact() {
        val row = history()[2]
        val original = group(row)
        val slot = original.slots[3]
        val version = slot.versions.last()
        val imageId = java.util.UUID.randomUUID().toString()
        val image = org.teslasoft.assistant.imagegen.GeneratedImageMetadata(imageId, "a".repeat(64), "image/png", 32, 32,
            "fixture-endpoint", "fixture-model", "original image prompt", "fixture description", 100L, "complete", null).toJson()
        val imaged = original.copy(slots = original.slots.map { selected ->
            if (selected.slotId != slot.slotId) selected else selected.copy(versions = selected.versions.map { saved ->
                if (saved.versionId != version.versionId) saved else saved.copy(imageSummaryEdited = "edited reminder", attempts = saved.attempts.mapIndexed { index, attempt ->
                    if (index != saved.attempts.lastIndex) attempt else attempt.copy(imageJobId = imageId, imageResult = image)
                })
            })
        })
        val displayRow = ResponseGroupEdits.install(row, ResponseGroupEdits.browse(imaged, slot.slotId, version.versionId))
        val decoded = group(displayRow)
        assertEquals("edited reminder", org.teslasoft.assistant.imagegen.GeneratedImageMetadata.fromJson(
            ParallelResponseSelection.display(displayRow)[org.teslasoft.assistant.imagegen.GeneratedImageMetadata.KEY] as String)?.effectiveSummary())
        assertNull(ParallelResponseSelection.canonicalImage(displayRow))
        assertEquals(image, decoded.slots[3].versions.last().attempts.last().imageResult)
        assertEquals(setOf("a".repeat(64)), org.teslasoft.assistant.imagegen.GeneratedImageFiles.referencedHashes(listOf(displayRow)))
        assertEquals(ResponseGroupEdits.usage(original), ResponseGroupEdits.usage(decoded))
    }

    @Test fun editingTheDialogTargetKeepsBrowseSelectionDescendantsAndEveryReceipt() {
        val rows = history()
        val row = rows[2]
        val original = group(row)
        val slot = original.slots[3]
        val selected = ResponseGroupEdits.browse(original, slot.slotId, slot.versions.last().versionId)
        val target = ParallelReplyEditing.target(selected)
        val browsedElsewhere = ResponseGroupEdits.browse(selected, original.slots[1].slotId, original.slots[1].canonicalVersionId)
        val edited = ParallelReplyEditing.apply(browsedElsewhere, target, "edited alternative")
        assertFalse(ParallelReplyEditing.canonical(edited, target))
        assertEquals(browsedElsewhere.foregroundSlotId, edited.foregroundSlotId)
        assertEquals("edited alternative", edited.slots[3].versions.last().fields["message"])
        assertEquals(browsedElsewhere.slots.take(3), edited.slots.take(3))
        assertEquals(original.slots[3].versions.last().attempts, edited.slots[3].versions.last().attempts)
        val saved = ResponseGroupEdits.install(row, edited)
        assertTrue(ResponseGroupCodec.read(saved) is ResponseGroupRead.Valid)
        assertEquals(row["message"], saved["message"])
        val after = rows.mapIndexed { index, value -> if (index == 2) saved else value }
        ResponseGroupHistory.validateSavedTransition(rows, after)
        assertEquals(rows.takeLast(2), after.takeLast(2))
        assertEquals(ResponseGroupEdits.usage(original), ResponseGroupEdits.usage(group(saved)))
    }

    @Test fun editingCanonicalTextUpdatesItsMirrorWithoutReplacingHistoryOrRequestEvidence() {
        val rows = history()
        val row = rows[2]
        val original = group(row)
        val primary = original.slots.single { it.slotId == original.primarySlotId }
        val selected = ResponseGroupEdits.browse(original, primary.slotId, primary.canonicalVersionId)
        val target = ParallelReplyEditing.target(selected)
        assertTrue(ParallelReplyEditing.canonical(selected, target))
        val edited = ParallelReplyEditing.apply(selected, target, "edited canonical")
        val saved = ResponseGroupEdits.install(row, edited)
        val after = rows.mapIndexed { index, value -> if (index == 2) saved else value }
        ResponseGroupHistory.validateSavedTransition(rows, after)
        assertEquals("edited canonical", saved["message"])
        assertEquals(rows.takeLast(2), after.takeLast(2))
        assertEquals(original.input, edited.input)
        assertEquals(original.slots.flatMap { it.versions }.flatMap { it.attempts },
            edited.slots.flatMap { it.versions }.flatMap { it.attempts })
    }

    @Test fun ordinarySingleResponseRowsKeepTheirExistingProjection() {
        val ordinary = history().first()
        assertNull(ParallelResponseSelection.pending(listOf(ordinary)))
        assertNull(ParallelResponseSelection.displayIndex(ordinary))
        assertEquals(ordinary, ParallelResponseSelection.display(ordinary))
    }
}
