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

    @Test fun ordinarySingleResponseRowsKeepTheirExistingProjection() {
        val ordinary = history().first()
        assertNull(ParallelResponseSelection.pending(listOf(ordinary)))
        assertNull(ParallelResponseSelection.displayIndex(ordinary))
        assertEquals(ordinary, ParallelResponseSelection.display(ordinary))
    }
}
