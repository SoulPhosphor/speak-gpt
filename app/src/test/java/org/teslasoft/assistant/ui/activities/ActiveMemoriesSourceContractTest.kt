/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.ui.activities

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the response-version and final-selection wiring around the Android UI. */
class ActiveMemoriesSourceContractTest {

    @Test
    fun adapterMakesAttributionVersionSpecificAndUsesDisplayedVersion() {
        val source = source("ui/adapters/chat/ChatAdapter.kt")
        assertTrue(source.contains("KEY_ACTIVE_MEMORY_ATTRIBUTION,"))
        assertTrue(source.contains("updateActiveMemories(display)"))
        assertFalse(source.contains("updateActiveMemories(chatMessage)"))
    }

    @Test
    fun frozenRequestAttributesTheExactListRenderedIntoItsPrompt() {
        val source = source("ui/activities/ChatActivity.kt")
        assertTrue(source.contains("for (match in injectedLoreMatches)"))
        assertTrue(source.contains("injectedLoreMatches.map { it.entry.id }"))
        assertFalse(source.contains("dedupedLoreMatches.map { it.entry.id }"))
        assertFalse(source.contains("allLoreMatches.map { it.entry.id }"))
    }

    @Test
    fun voiceAndResendAttributeTheExactLorebookListTheySend() {
        // Lorebook entries are their own request layer on every way of
        // sending; the enforcer only compares memories with them.
        val chat = source("ui/activities/ChatActivity.kt")
        val enforcer = source("preferences/memory/enforcer/Enforcer.kt")
        assertTrue(chat.contains("for (match in loreBudget.kept)"))
        assertTrue(chat.contains("val lorebookIds = loreBudget.injectedEntryIds"))
        assertFalse(enforcer.contains("lorebookEntryIds"))
    }

    @Test
    fun everyWayOfSendingChecksMemoriesAgainstTheLorebookEntriesItSends() {
        // Typed sending, and voice and resending, hand the enforcer exactly
        // the lorebook entries they send, so a memory repeating one is dropped
        // on all of them (owner decision, Stage 2).
        val chat = source("ui/activities/ChatActivity.kt")
        assertTrue(chat.contains("sentLore = injectedLoreMatches"))
        assertTrue(chat.contains("sentLore = loreBudget.kept"))
        assertEquals(2, Regex("LorebookLimits\\.select\\(").findAll(chat).count())
    }

    @Test
    fun contextAssistanceSkipsExperimentalModeAndTypedDraftsUntilSent() {
        val chat = source("ui/activities/ChatActivity.kt")
        assertTrue(chat.contains("useContextAssistance = !experimentalModeOn()"))
        assertTrue(chat.contains("useContextAssistance = !legacyExperimental"))
        // A typed draft records its subject only when it is committed.
        assertTrue(chat.contains("deferContextCommit = { commitContext = it }"))
        val commit = chat.substringAfter("private fun commitPreparedTurn(").substringBefore("\n    }")
        assertTrue(commit.contains("prepared.commitContext()"))
    }

    @Test
    fun viewerDistinguishesDeletionFromReadErrorsAndPreservesBlankLabelContent() {
        val viewer = source("ui/activities/ActiveMemoriesActivity.kt")
        assertTrue(viewer.contains("data object Deleted"))
        assertTrue(viewer.contains("data object ReadError"))
        assertTrue(viewer.contains("logReadError(\"Memory\""))
        assertTrue(viewer.contains("logReadError(\"Lorebook Entry\""))
        assertTrue(
            viewer.contains(
                "entry.label.ifBlank { getString(R.string.active_memories_unnamed_lorebook_entry) }"
            )
        )
        assertFalse(viewer.contains("active_memories_not_available"))
    }

    private fun source(relative: String): String {
        val path = "src/main/java/org/teslasoft/assistant/$relative"
        val candidates = listOf(
            File(path),
            File("app/$path"),
            File(System.getProperty("user.dir"), path),
            File(System.getProperty("user.dir"), "app/$path")
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate $relative")
    }
}
