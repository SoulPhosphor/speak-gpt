package org.teslasoft.assistant.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionSelectionPolicyTest {
    @Test
    fun validCurrentSelectionAlwaysWins() {
        assertEquals("selected", CompanionSelectionPolicy.resolve(
            currentId = "selected",
            lastSuccessfulId = "recent",
            availableIds = listOf("first", "selected", "recent")
        ))
    }

    @Test
    fun missingSelectionRecoversRecentThenFirst() {
        assertEquals("recent", CompanionSelectionPolicy.resolve(
            currentId = "deleted",
            lastSuccessfulId = "recent",
            availableIds = listOf("first", "recent")
        ))
        assertEquals("first", CompanionSelectionPolicy.resolve(
            currentId = "deleted",
            lastSuccessfulId = "also-deleted",
            availableIds = listOf("first", "second")
        ))
    }

    @Test
    fun noCompanionsHasNoInventedSelection() {
        assertNull(CompanionSelectionPolicy.resolve("old", "recent", emptyList()))
    }
}
