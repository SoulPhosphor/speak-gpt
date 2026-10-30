/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.chatnavigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.MessageBookmarks
import org.teslasoft.assistant.preferences.PerChatSettingKeys
import org.teslasoft.assistant.ui.drawer.ChatListDetails

/** The chat-list row details that let the drawer draw rows without opening chat files. */
class ChatListIndexTest {

    private fun row(vararg pairs: Pair<String, String>): HashMap<String, String> =
        hashMapOf("id" to "chat-1", "name" to "A chat", *pairs)

    private val content = ChatListIndex.ContentValues(hasBookmarks = false, mostUsedModel = "gpt-4o", startedAt = 1_000L)
    private val settings = ChatListIndex.SettingsValues(model = "gpt-4o-mini", companionId = "companion-1", memoryExcluded = false)
    private val full = ChatListIndex.Update(content, settings)

    @Test fun `an unfilled row has no stored details`() {
        assertNull(ChatListIndex.entryOf(row()))
        // A row filled by an older version is refilled, not trusted.
        assertNull(ChatListIndex.entryOf(row(ChatListIndex.VERSION_KEY to "0")))
    }

    @Test fun `filling a row stores every detail and marks it filled`() {
        val target = row()
        assertTrue(ChatListIndex.apply(target, full))
        val entry = assertNotNullEntry(target)
        assertFalse(entry.hasBookmarks)
        assertEquals("gpt-4o", entry.mostUsedModel)
        assertEquals("gpt-4o-mini", entry.model)
        assertEquals("companion-1", entry.companionId)
        assertFalse(entry.memoryExcluded)
        assertEquals("1000", target["created_at"])
    }

    @Test fun `one part alone never marks an unfilled row filled`() {
        val target = row()
        ChatListIndex.apply(target, ChatListIndex.Update(settings = settings))
        assertNull(ChatListIndex.entryOf(target))
        ChatListIndex.apply(target, ChatListIndex.Update(content = content))
        assertNull(ChatListIndex.entryOf(target))
    }

    @Test fun `an unchanged value causes no write`() {
        val target = row()
        ChatListIndex.apply(target, full)
        assertFalse(ChatListIndex.apply(target, full))
        assertFalse(ChatListIndex.apply(target, ChatListIndex.Update(settings = settings)))
        assertFalse(ChatListIndex.apply(target, ChatListIndex.Update(content = content)))
    }

    @Test fun `a settings change updates only the settings it covers`() {
        val target = row()
        ChatListIndex.apply(target, full)
        assertTrue(ChatListIndex.apply(target, ChatListIndex.Update(settings = settings.copy(memoryExcluded = true))))
        val entry = assertNotNullEntry(target)
        assertTrue(entry.memoryExcluded)
        assertEquals("gpt-4o", entry.mostUsedModel)
        assertEquals("companion-1", entry.companionId)
    }

    @Test fun `memory exclusion can be turned back off`() {
        val target = row()
        ChatListIndex.apply(target, ChatListIndex.Update(content, settings.copy(memoryExcluded = true)))
        ChatListIndex.apply(target, ChatListIndex.Update(settings = settings.copy(memoryExcluded = false)))
        assertFalse(assertNotNullEntry(target).memoryExcluded)
    }

    @Test fun `a new bookmark turns the indicator on and removing it turns it off`() {
        val target = row()
        ChatListIndex.apply(target, full)
        val bookmarked = listOf(mapOf("message" to "hi", "isBot" to false, MessageBookmarks.KEY to true))
        val details = ChatListDetails.fromMessages(bookmarked)
        assertTrue(details.hasBookmarks)
        assertTrue(ChatListIndex.apply(target, ChatListIndex.Update(
            content = content.copy(hasBookmarks = details.hasBookmarks)
        )))
        assertTrue(assertNotNullEntry(target).hasBookmarks)
        ChatListIndex.apply(target, ChatListIndex.Update(content = content.copy(hasBookmarks = false)))
        assertFalse(assertNotNullEntry(target).hasBookmarks)
    }

    @Test fun `the model label is the most-used model, else the selected model`() {
        val target = row()
        ChatListIndex.apply(target, full)
        assertEquals("gpt-4o", assertNotNullEntry(target).displayModel)
        ChatListIndex.apply(target, ChatListIndex.Update(content = content.copy(mostUsedModel = null)))
        assertNull(target[ChatListIndex.MOST_USED_MODEL_KEY])
        assertEquals("gpt-4o-mini", assertNotNullEntry(target).displayModel)
        ChatListIndex.apply(target, ChatListIndex.Update(settings = settings.copy(model = "claude")))
        assertEquals("claude", assertNotNullEntry(target).displayModel)
    }

    @Test fun `a known start date is never replaced`() {
        val target = row("created_at" to "500")
        ChatListIndex.apply(target, full)
        assertEquals("500", target["created_at"])
    }

    @Test fun `an unfilled row is read in full whatever was requested`() {
        val requested = setOf(ChatListIndex.Part.SETTINGS)
        assertEquals(ChatListIndex.Part.entries.toSet(), ChatListIndex.partsToRead(row(), requested))
        val filled = row().also { ChatListIndex.apply(it, full) }
        assertEquals(requested, ChatListIndex.partsToRead(filled, requested))
    }

    /** An interrupted fill-in resumes with only the rows it had not reached. */
    @Test fun `after an interruption only unfilled rows are processed again`() {
        val rows = (1..10).map { hashMapOf("id" to "chat-$it", "name" to "Chat $it") }
        rows.take(4).forEach { ChatListIndex.apply(it, full) }
        val stillToFill = rows.filter { ChatListIndex.entryOf(it) == null }.map { ChatPreferences.storedChatId(it) }
        assertEquals((5..10).map { "chat-$it" }, stillToFill)
    }

    @Test fun `display copies never overlap real chat settings or list fields`() {
        ChatListIndex.KEYS.forEach { key ->
            assertFalse("$key is a real per-chat setting", key in PerChatSettingKeys.ALL)
            assertFalse(key in setOf("id", "name", "pinned", "timestamp", "created_at", "folder_id"))
        }
        // The real settings the copies are made from stay real settings.
        assertTrue("memory_excluded" in PerChatSettingKeys.ALL)
        assertTrue("persona_id" in PerChatSettingKeys.ALL)
    }

    private fun assertNotNullEntry(target: Map<String, String>): ChatListIndex.Entry {
        val entry = ChatListIndex.entryOf(target)
        assertNotNull(entry)
        return entry!!
    }
}
