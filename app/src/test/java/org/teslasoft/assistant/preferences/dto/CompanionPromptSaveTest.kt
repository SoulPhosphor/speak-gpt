package org.teslasoft.assistant.preferences.dto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionPromptSaveTest {
    @Test
    fun savingOnlyTheFourthPopulatedPromptMakesItTheDefaultWithoutChangingItsIdentityOrText() {
        val drafts = listOf(
            CompanionPromptVariant(id = "one", name = "Prompt 1", text = "", isDefault = true),
            CompanionPromptVariant(id = "two", name = "Prompt 2", text = " ", isDefault = false),
            CompanionPromptVariant(id = "three", name = "Prompt 3", text = "\n\t", isDefault = false),
            CompanionPromptVariant(id = "four", name = "Prompt 4", text = "  Keep this text.\n", isDefault = false)
        )

        val saved = CompanionPromptVariant.forProfileSave(drafts)

        assertEquals(listOf("four"), saved.map { it.id })
        assertEquals("Prompt 4", saved.single().name)
        assertEquals("  Keep this text.\n", saved.single().text)
        assertTrue(saved.single().isDefault)
        assertEquals(saved.single().text, CompanionPromptVariant.defaultPrompt(saved))
        assertTrue(drafts.first().isDefault)
        assertFalse(drafts.last().isDefault)
    }

    @Test
    fun savingKeepsThePopulatedDefaultAndTheOrderOfTheOtherPopulatedPrompts() {
        val drafts = listOf(
            CompanionPromptVariant(id = "one", name = "One", text = "First", isDefault = false),
            CompanionPromptVariant(id = "two", name = "Two", text = "", isDefault = false),
            CompanionPromptVariant(id = "three", name = "Three", text = "Default", isDefault = true)
        )

        val saved = CompanionPromptVariant.forProfileSave(drafts)

        assertEquals(listOf("one", "three"), saved.map { it.id })
        assertEquals(listOf("three"), saved.filter { it.isDefault }.map { it.id })
        assertEquals("Default", CompanionPromptVariant.defaultPrompt(saved))
    }

    @Test
    fun removingAnEmptyDefaultMakesTheFirstPopulatedPromptDefault() {
        val drafts = listOf(
            CompanionPromptVariant(name = "Empty", text = "", isDefault = true),
            CompanionPromptVariant(name = "First", text = "A", isDefault = false),
            CompanionPromptVariant(name = "Next", text = "B", isDefault = false)
        )

        val saved = CompanionPromptVariant.forProfileSave(drafts)

        assertEquals(listOf("First", "Next"), saved.map { it.name })
        assertEquals(listOf("First"), saved.filter { it.isDefault }.map { it.name })
        assertEquals("A", CompanionPromptVariant.defaultPrompt(saved))
    }

    @Test
    fun emptyDraftsDoNotProduceASavedPrompt() {
        assertTrue(CompanionPromptVariant.forProfileSave(emptyList()).isEmpty())
        assertTrue(CompanionPromptVariant.forProfileSave(listOf(
            CompanionPromptVariant(name = "Empty", text = " \n\t", isDefault = true)
        )).isEmpty())
    }
}
