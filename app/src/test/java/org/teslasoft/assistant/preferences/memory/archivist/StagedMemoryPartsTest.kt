package org.teslasoft.assistant.preferences.memory.archivist

import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.CandidateResult
import org.teslasoft.assistant.preferences.memory.MemoryCandidateValidator
import org.teslasoft.assistant.preferences.memory.PendingMemoryRecordFactory
import org.teslasoft.assistant.preferences.memory.librarian.MemoryTokenBudget

class StagedMemoryPartsTest {
    private fun part(id: String, text: String, tags: List<String> = emptyList()) =
        PendingMemoryRecordFactory.build(
            (MemoryCandidateValidator.validateGeneral("global", text, tags = tags) as CandidateResult.Valid).candidate,
            id, "2026-11-17T00:00:00Z")

    @Test fun repeatedFinalPartsMergeTagsAndRelationshipHintsInsteadOfCreatingDuplicates() {
        val stage = StagedMemoryParts(MemoryTokenBudget.Reader("fixture", 100) { it.length })
        assertTrue(stage.add(part("a", "Shared detail.", listOf("first")), listOf("old-a")))
        assertFalse(stage.add(part("b", "Shared detail.", listOf("second")), listOf("old-b")))
        assertEquals(1, stage.records.size)
        assertEquals(listOf("first", "second"), MemoryTokenBudget.Input.from(stage.records.single()).tags)
        assertEquals(listOf("old-a", "old-b"), stage.relationshipHints["a"])
    }

    @Test fun sameContentWithDifferentTypeOrPlacementIsKept() {
        val stage = StagedMemoryParts(null)
        val original = part("a", "Shared detail.")
        assertTrue(stage.add(original, emptyList()))
        assertTrue(stage.add(original.copy(memoryId = "b", typeId = "different"), emptyList()))
        assertTrue(stage.add(original.copy(memoryId = "c", scope = "project", projectIds = listOf("p")), emptyList()))
        assertEquals(3, stage.records.size)
    }

    @Test fun metadataThatCannotFitTogetherIsKeptInSeparateFittingDocuments() {
        val reader = MemoryTokenBudget.Reader("fixture", 30) { it.length }
        val stage = StagedMemoryParts(reader)
        stage.add(part("a", "Detail", listOf("first-long-label")), emptyList())
        stage.add(part("b", "Detail", listOf("second-long-label")), emptyList())
        assertEquals(2, stage.records.size)
        assertEquals(listOf("first-long-label", "second-long-label"), stage.records.flatMap {
            MemoryTokenBudget.Input.from(it).tags
        })
        assertTrue(stage.records.all { reader.measure(MemoryTokenBudget.Input.from(it)).fits })
    }

    @Test fun overlappingProposalsAndAnUnsplitProposalUseTheSameFinalPartIdentity() {
        val reader = MemoryTokenBudget.Reader("fixture", 30) { it.length }
        val stage = StagedMemoryParts(reader)
        val text = "First shared detail. Second shared detail. Third shared detail."
        val pieces = org.teslasoft.assistant.preferences.memory.librarian.MemoryProposalSplitter.split(text, emptyList(), reader)
        for ((i, piece) in pieces.withIndex()) stage.add(part("a-$i", piece.content), emptyList())
        for ((i, piece) in pieces.withIndex()) assertFalse(stage.add(part("b-$i", piece.content), emptyList()))
        assertFalse(stage.add(part("short", pieces.first().content), emptyList()))
        assertEquals(pieces.size, stage.records.size)
    }
}
