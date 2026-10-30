/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.chatnavigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.MessageBookmarks
import org.teslasoft.assistant.preferences.chatnavigation.ChatListIndex.Entry
import org.teslasoft.assistant.preferences.chatnavigation.ChatListIndex.Part
import org.teslasoft.assistant.ui.drawer.ChatListDetails

/** The chat-list details database: complete before anything shows it, current after every change. */
class ChatListIndexTest {

    /** A chat as its own files describe it. */
    private data class Chat(
        var bookmarked: Boolean = false,
        var mostUsed: String? = "gpt-4o",
        var model: String = "gpt-4o-mini",
        var companion: String = "companion-1",
        var excluded: Boolean = false,
        var startedAt: Long? = 1_000L,
        var readable: Boolean = true
    )

    private class FakeTable : ChatListIndex.Table {
        val rows = LinkedHashMap<String, Entry>()
        var writes = 0
        override fun readAll() = HashMap(rows)
        override fun write(entries: Map<String, Entry>) { writes++; rows.putAll(entries) }
        override fun delete(chatIds: Collection<String>) { chatIds.forEach { rows.remove(it) } }
    }

    private class FakeSource : ChatListIndex.Source {
        val chats = LinkedHashMap<String, Chat>()
        val reads = ArrayList<String>()
        override fun chatIds() = chats.keys.toList()
        override fun read(chatId: String, parts: Set<Part>): ChatListIndex.Update? {
            val chat = chats[chatId]?.takeIf { it.readable } ?: return null
            reads.add(chatId)
            return ChatListIndex.Update(
                if (Part.CONTENT in parts) ChatListIndex.ContentValues(chat.bookmarked, chat.mostUsed, chat.startedAt) else null,
                if (Part.SETTINGS in parts) ChatListIndex.SettingsValues(chat.model, chat.companion, chat.excluded) else null
            )
        }
    }

    private val table = FakeTable()
    private val source = FakeSource()
    private fun core() = ChatListIndex.Core(table, source)
    private fun chats(count: Int) = (1..count).forEach { source.chats["chat-$it"] = Chat() }

    @Test fun `preparation gives every chat its details`() {
        chats(10)
        val index = core()
        assertTrue(index.prepareAll())
        (1..10).forEach { assertEquals("gpt-4o", index.entryFor("chat-$it")?.displayModel) }
        assertEquals(10, table.rows.size)
    }

    @Test fun `prepared chats are never read again`() {
        chats(10)
        core().prepareAll()
        source.reads.clear()
        val restarted = core()
        assertFalse(restarted.prepareAll())
        assertFalse(restarted.ensureReady(source.chats.keys))
        assertTrue(source.reads.isEmpty())
    }

    @Test fun `an interrupted preparation continues with only the chats it had not reached`() {
        chats(10)
        // The first run stops after four chats (the app was closed).
        ChatListIndex.Core(table, object : ChatListIndex.Source by source {
            override fun chatIds() = source.chats.keys.take(4)
        }).prepareAll()
        source.reads.clear()
        core().prepareAll()
        assertEquals((5..10).map { "chat-$it" }, source.reads)
        assertEquals(10, table.rows.size)
    }

    @Test fun `a list is never shown with chats that are not ready`() {
        chats(3)
        val index = core()
        assertTrue(index.ensureReady(source.chats.keys))
        source.chats.keys.forEach { assertTrue(index.entryFor(it) != null) }
        assertFalse(index.ensureReady(source.chats.keys))
    }

    @Test fun `a restore rebuilds restored chats from the restored data before it finishes`() {
        chats(3)
        val index = core()
        index.prepareAll()
        // The restore replaced chat-2 with an older copy that has a bookmark and another companion.
        source.chats["chat-2"] = Chat(bookmarked = true, companion = "companion-9")
        source.chats["chat-4"] = Chat(model = "claude", mostUsed = null)
        index.replace(listOf("chat-2", "chat-4"))
        assertTrue(index.entryFor("chat-2")!!.hasBookmarks)
        assertEquals("companion-9", index.entryFor("chat-2")!!.companionId)
        assertEquals("claude", index.entryFor("chat-4")!!.displayModel)
        assertEquals("companion-1", index.entryFor("chat-1")!!.companionId)
    }

    @Test fun `a restored chat that cannot be read keeps no outdated details`() {
        chats(2)
        val index = core()
        index.prepareAll()
        source.chats["chat-1"]!!.readable = false
        index.replace(listOf("chat-1"))
        assertNull(index.entryFor("chat-1"))
        assertNull(table.rows["chat-1"])
    }

    @Test fun `a restore interrupted while rebuilding leaves no outdated details`() {
        chats(3)
        core().prepareAll()
        // The restore replaced chat-2; the database write then fails mid-rebuild.
        source.chats["chat-2"] = Chat(bookmarked = true)
        var failWrites = true
        val failing = object : ChatListIndex.Table by table {
            override fun write(entries: Map<String, Entry>) {
                if (failWrites) throw IllegalStateException("disk busy") else table.write(entries)
            }
        }
        val interrupted = ChatListIndex.Core(failing, source)
        try { interrupted.replace(listOf("chat-2")) } catch (_: IllegalStateException) { }
        // The old details are gone rather than outdated, in memory and on disk.
        assertNull(interrupted.entryFor("chat-2"))
        assertNull(table.rows["chat-2"])
        // The next list load prepares it from the restored data.
        failWrites = false
        val restarted = core()
        assertTrue(restarted.ensureReady(source.chats.keys))
        assertTrue(restarted.entryFor("chat-2")!!.hasBookmarks)
    }

    @Test fun `bookmarks turn the indicator on and off`() {
        chats(1)
        val index = core()
        index.prepareAll()
        val messages = listOf(mapOf("message" to "hi", "isBot" to false, MessageBookmarks.KEY to true))
        source.chats["chat-1"]!!.bookmarked = ChatListDetails.fromMessages(messages).hasBookmarks
        assertTrue(index.refresh("chat-1", setOf(Part.CONTENT)))
        assertTrue(index.entryFor("chat-1")!!.hasBookmarks)
        source.chats["chat-1"]!!.bookmarked = false
        assertTrue(index.refresh("chat-1", setOf(Part.CONTENT)))
        assertFalse(index.entryFor("chat-1")!!.hasBookmarks)
    }

    @Test fun `the model label is the most-used model, else the selected model`() {
        chats(1)
        val index = core()
        index.prepareAll()
        assertEquals("gpt-4o", index.entryFor("chat-1")!!.displayModel)
        source.chats["chat-1"]!!.mostUsed = null
        index.refresh("chat-1", setOf(Part.CONTENT))
        assertEquals("gpt-4o-mini", index.entryFor("chat-1")!!.displayModel)
        source.chats["chat-1"]!!.model = "claude"
        index.refresh("chat-1", setOf(Part.SETTINGS))
        assertEquals("claude", index.entryFor("chat-1")!!.displayModel)
    }

    @Test fun `memory exclusion follows the setting both ways`() {
        chats(1)
        val index = core()
        index.prepareAll()
        source.chats["chat-1"]!!.excluded = true
        index.refresh("chat-1", setOf(Part.SETTINGS))
        assertTrue(index.entryFor("chat-1")!!.memoryExcluded)
        source.chats["chat-1"]!!.excluded = false
        index.refresh("chat-1", setOf(Part.SETTINGS))
        assertFalse(index.entryFor("chat-1")!!.memoryExcluded)
    }

    @Test fun `a settings change leaves the conversation details alone`() {
        chats(1)
        val index = core()
        index.prepareAll()
        source.chats["chat-1"]!!.bookmarked = true
        source.chats["chat-1"]!!.companion = "companion-2"
        index.refresh("chat-1", setOf(Part.SETTINGS))
        assertEquals("companion-2", index.entryFor("chat-1")!!.companionId)
        assertFalse(index.entryFor("chat-1")!!.hasBookmarks)
    }

    @Test fun `an unchanged chat causes no write`() {
        chats(1)
        val index = core()
        index.prepareAll()
        val writes = table.writes
        assertFalse(index.refresh("chat-1", setOf(Part.CONTENT, Part.SETTINGS)))
        assertEquals(writes, table.writes)
    }

    @Test fun `deleted chats lose their details and a reused ID starts fresh`() {
        chats(2)
        val index = core()
        index.prepareAll()
        index.forget("chat-1")
        assertNull(index.entryFor("chat-1"))
        source.chats.remove("chat-2")
        index.prepareAll()
        assertNull(table.rows["chat-2"])
        source.chats["chat-1"] = Chat(companion = "companion-new")
        index.replace(listOf("chat-1"))
        assertEquals("companion-new", index.entryFor("chat-1")!!.companionId)
    }

    @Test fun `an unreadable chat is not reread on every list load but is retried on change`() {
        chats(1)
        source.chats["chat-1"]!!.readable = false
        val index = core()
        index.ensureReady(listOf("chat-1"))
        source.chats["chat-1"]!!.readable = true
        assertFalse(index.ensureReady(listOf("chat-1")))
        assertTrue(index.refresh("chat-1", setOf(Part.SETTINGS)))
        assertTrue(index.entryFor("chat-1") != null)
    }

    @Test fun `thousands of chats are prepared once and looked up from memory`() {
        chats(5_000)
        val index = core()
        val prepareStart = System.nanoTime()
        index.prepareAll()
        val prepareMs = (System.nanoTime() - prepareStart) / 1_000_000
        val lookupStart = System.nanoTime()
        repeat(10) { source.chats.keys.forEach { chatId -> index.entryFor(chatId) } }
        val perLookupNs = (System.nanoTime() - lookupStart) / 50_000
        println("chat-list details: prepared 5000 chats (excluding file reads) in ${prepareMs} ms; one row lookup takes ${perLookupNs} ns")
        assertEquals(5_000, table.rows.size)
        // Batched: 5,000 chats in a hundred writes, not five thousand.
        assertEquals(100, table.writes)
        // Far below one frame (16 ms) even for a whole screen of rows.
        assertTrue(perLookupNs < 50_000)
    }
}
