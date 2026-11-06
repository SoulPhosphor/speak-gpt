package org.teslasoft.assistant.preferences.memory.enforcer

import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.MemoryRecord
import org.teslasoft.assistant.preferences.memory.MemoryTypeMigration

class ManualMemoryPriorityTest {
    private fun memory(id: String) = MemoryRecord(
        memoryId = id, scope = "companion", content = "Full memory $id\nSecond line stays intact.",
        embeddingText = null, tagsJson = "[]", importance = 0,
        worldIds = emptyList(), roleplayCharacterIds = emptyList(), campaignIds = emptyList(), projectIds = emptyList(),
        protectionJson = null, modeHintsJson = "[]", createdAt = "2026-11-06T00:00:00Z", updatedAt = null,
        status = "active", supersedes = null, companionIds = listOf("other-companion"), entityRefs = emptyList(),
        changeLog = emptyList()
    )
    @Test fun manualAttachmentsNeedNeitherEmbeddingTextNorMatchingCompanionScope() {
        val selected = memory("chosen").copy(title = "Browser label stays out of prompt")
        val prompt = ManualMemoryPriority.render(listOf(selected.memoryId)) { selected }
        assertTrue(prompt.prompt.contains(selected.content))
        assertFalse(prompt.prompt.contains(selected.title))
        assertEquals(listOf("chosen"), prompt.memoryIds)
    }
    @Test fun manualAttachmentsPreserveInstructionTypeAndProtection() {
        val selected = memory("rule").copy(
            typeId = MemoryTypeMigration.INSTRUCTION_TYPE_ID,
            protectionJson = """{"handling":["Use care"],"never_assume":["No assumptions"]}"""
        )
        val resolved = ManualMemoryPriority.resolve(listOf(selected.memoryId)) { selected }.single()
        val assembled = ManualMemoryPriority.assemble(resolved, 1f, 1f)
        assertTrue(assembled.isInstruction)
        assertEquals(listOf("Use care"), assembled.handling)
        assertEquals(listOf("No assumptions"), assembled.neverAssume)
    }
    @Test fun fiveManualMemoriesLeaveThreeAutomaticSlotsWithNoDuplicateIds() {
        val manualIds = (1..5).map { "m$it" }
        val manual = ManualMemoryPriority.resolve(manualIds, ::memory)
        val ranked = (1..12).map { memory("m$it") }.filterNot { it.memoryId in manualIds }
        val auto = RetrievalBackfill.select(ranked, ManualMemoryPriority.remainingSlots(8, manual.size)) { true }
        val combined = manual.map { it.memoryId } + auto.kept.map { it.memoryId }
        assertEquals((1..8).map { "m$it" }, combined)
        assertEquals(combined.size, combined.distinct().size)
    }
    @Test fun explicitSelectionRemainsWholeEvenWhenNormalLimitIsLower() {
        assertEquals(0, ManualMemoryPriority.remainingSlots(2, 5))
        assertEquals(5, ManualMemoryPriority.resolve((1..5).map { "m$it" }, ::memory).size)
    }
    @Test fun duplicateSelectedIdsOnlyLoadOneAttachment() {
        var reads = 0
        val resolved = ManualMemoryPriority.resolve(listOf("same", "same")) { reads++; memory(it) }
        assertEquals(1, reads)
        assertEquals(1, resolved.size)
    }
    @Test(expected = IllegalStateException::class)
    fun deletedMemoryStopsPreparationRatherThanSendingWithoutIt() {
        ManualMemoryPriority.resolve(listOf("gone")) { null }
    }
    @Test(expected = IllegalStateException::class)
    fun unapprovedDraftIsNotSilentlyInjected() {
        ManualMemoryPriority.resolve(listOf("draft")) { memory(it).copy(status = "draft") }
    }
    @Test(expected = IllegalArgumentException::class)
    fun malformedCallerCannotBypassFiveMemoryLimit() {
        ManualMemoryPriority.resolve((1..6).map { "m$it" }, ::memory)
    }
}
