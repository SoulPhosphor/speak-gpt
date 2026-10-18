/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.chatstore

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatStoreInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val key = "chat-store-test-key".toByteArray()
    private val opened = ArrayList<Pair<String, ChatStore>>()

    @After
    fun cleanup() {
        opened.forEach { (name, store) ->
            runCatching { store.close() }
            listOf(name, "$name-wal", "$name-shm", "$name-journal").forEach {
                runCatching { context.getDatabasePath(it).delete() }
            }
        }
    }

    private fun open(name: String = "chat-store-test-${System.nanoTime()}.db"): ChatStore =
        ChatStore.open(context, key, ChatStore.CloseOnCorruption, name).also { opened.add(name to it) }

    private fun chat(id: String, order: Long = 0) = ChatStore.StoredChat(
        chatId = id,
        listOrder = order,
        listRowJson = """{"name":"Chat $id","id":"$id","pinned":"false"}""",
        prefsJson = "{}",
        readable = true,
        revision = 0
    )

    private fun row(text: String, isBot: Boolean = false, id: String? = null) =
        ChatStore.MessageRow(id, isBot, """{"message":${quote(text)},"isBot":$isBot}""")

    private fun quote(text: String): String = org.json.JSONObject.quote(text)

    @Test
    fun newStore_hasSchemaAndUniqueStoreId() {
        val a = open()
        val b = open()
        assertEquals("1", a.getMeta(ChatStore.META_SCHEMA))
        assertNotNull(a.storeId())
        assertFalse(a.storeId() == b.storeId())
    }

    @Test
    fun chats_roundTripInListOrder_andUpdateKeepsMessages() {
        val store = open()
        store.putChat(chat("b", order = 2))
        store.putChat(chat("a", order = 1))
        store.appendMessage("a", row("hello"))
        assertEquals(listOf("a", "b"), store.listChats().map { it.chatId })

        // Updating a chat's list row must not cascade away its messages.
        store.putChat(chat("a", order = 3).copy(listRowJson = """{"name":"Renamed"}"""))
        assertEquals("""{"name":"Renamed"}""", store.getChat("a")?.listRowJson)
        assertEquals(1, store.messageCount("a"))
        assertEquals(listOf("b", "a"), store.listChats().map { it.chatId })
    }

    @Test
    fun appendAndRead_preservesOrderTextAndFields() {
        val store = open()
        store.putChat(chat("c"))
        val payload = """{"message":"hi","isBot":false,"messageTime":1.7E12,"unknownField":{"x":[1,2]}}"""
        store.appendMessage("c", ChatStore.MessageRow("id-1", false, payload))
        store.appendMessage("c", row("reply", isBot = true))

        val page = store.readPage("c", 0, 10)
        assertEquals(2, page.size)
        assertEquals(payload, page[0].payloadJson)
        assertEquals("id-1", page[0].messageId)
        assertTrue(page[1].isBot)
        assertEquals(page[0].seq, store.findByMessageId("c", "id-1")?.seq)
    }

    @Test
    fun unicodePayload_roundTripsExactly() {
        val store = open()
        store.putChat(chat("u"))
        val text = "é 中文 😀👩‍👩‍👧 ‍ עברית"
        store.appendMessage("u", row(text))
        assertEquals(row(text).payloadJson, store.messageAt("u", 0)?.payloadJson)
    }

    @Test
    fun largePayload_isSplitAndReassembledWithoutBreakingSurrogates() {
        val store = open()
        store.putChat(chat("big"))
        // Over three parts, with emoji straddling every part boundary.
        val text = "😀".repeat(ChatStore.PART_CHARS * 3 / 2 + 7)
        val payload = row(text, isBot = true).payloadJson
        val seq = store.appendMessage("big", ChatStore.MessageRow(null, true, payload))
        assertEquals(payload, store.messageAt("big", 0)?.payloadJson)

        // Replacing a split payload with a short one drops the old parts.
        assertTrue(store.replaceMessage("big", seq, row("short", isBot = true)))
        assertEquals(row("short", isBot = true).payloadJson, store.messageAt("big", 0)?.payloadJson)
        val parts = store.readableDatabase.rawQuery("SELECT COUNT(*) FROM message_parts", null)
            .use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, parts)
    }

    @Test
    fun messageRefs_readPayloadsOnlyWhenAskedAndCopyPartByPart() {
        val store = open()
        store.putChat(chat("refs"))
        val big = row("😀".repeat(ChatStore.PART_CHARS * 2 + 3)).payloadJson
        store.appendMessage("refs", row("small"))
        store.appendMessage("refs", ChatStore.MessageRow("big-id", true, big))
        val seen = ArrayList<String>()
        store.forEachMessageRef("refs", pageSize = 1) { ref ->
            val copied = StringBuilder().also { ref.copyPayloadTo(it) }.toString()
            assertEquals(ref.payloadJson(), copied)
            seen.add(copied)
            true
        }
        assertEquals(listOf(row("small").payloadJson, big), seen)
    }

    @Test
    fun readBefore_pagesBackwardFromNewest() {
        val store = open()
        store.putChat(chat("p"))
        store.replaceAllMessages("p", (0 until 50).asSequence().map { row("m$it") })

        val newest = store.readBefore("p", null, 10)
        assertEquals((40 until 50).map { row("m$it").payloadJson }, newest.map { it.payloadJson })
        val earlier = store.readBefore("p", newest.first().seq, 10)
        assertEquals((30 until 40).map { row("m$it").payloadJson }, earlier.map { it.payloadJson })
        val first = store.readBefore("p", store.messageAt("p", 3)!!.seq, 10)
        assertEquals((0 until 3).map { row("m$it").payloadJson }, first.map { it.payloadJson })
    }

    @Test
    fun deleteAndTruncate_keepRemainingOrderAndPositions() {
        val store = open()
        store.putChat(chat("d"))
        store.replaceAllMessages("d", (0 until 6).asSequence().map { row("m$it") })

        val second = store.messageAt("d", 1)!!
        assertTrue(store.deleteMessage("d", second.seq))
        assertNull(store.positionOf("d", second.seq))
        assertEquals(row("m2").payloadJson, store.messageAt("d", 1)?.payloadJson)

        val keep = store.messageAt("d", 2)!!
        assertEquals(2, store.deleteAfter("d", keep.seq))
        assertEquals(
            listOf("m0", "m2", "m3").map { row(it).payloadJson },
            store.readPage("d", 0, 10).map { it.payloadJson }
        )
        // Appends continue after the current last message.
        store.appendMessage("d", row("m-new"))
        assertEquals(row("m-new").payloadJson, store.messageAt("d", 3)?.payloadJson)
    }

    @Test
    fun replaceAllMessages_isAllOrNothing() {
        val store = open()
        store.putChat(chat("t"))
        store.replaceAllMessages("t", sequenceOf(row("original")))

        val failing = sequence {
            yield(row("new 1"))
            yield(row("new 2"))
            throw IllegalStateException("source failed mid-way")
        }
        try {
            store.replaceAllMessages("t", failing)
            fail("expected the source failure to propagate")
        } catch (_: IllegalStateException) {
        }
        assertEquals(listOf(row("original").payloadJson), store.readPage("t", 0, 10).map { it.payloadJson })
    }

    @Test
    fun deleteChat_removesItsMessagesAndParts() {
        val store = open()
        store.putChat(chat("x"))
        store.putChat(chat("y"))
        store.appendMessage("x", row("z".repeat(ChatStore.PART_CHARS + 1)))
        store.appendMessage("y", row("kept"))
        assertTrue(store.deleteChat("x"))
        assertEquals(0, store.messageCount("x"))
        assertEquals(1, store.messageCount("y"))
        val parts = store.readableDatabase.rawQuery("SELECT COUNT(*) FROM message_parts", null)
            .use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, parts)
    }

    @Test
    fun revision_increasesOnEveryChange() {
        val store = open()
        store.putChat(chat("r"))
        val r0 = store.revision("r")!!
        val seq = store.appendMessage("r", row("a"))
        val r1 = store.revision("r")!!
        store.replaceMessage("r", seq, row("b"))
        val r2 = store.revision("r")!!
        store.deleteMessage("r", seq)
        val r3 = store.revision("r")!!
        // A list-row update with a stale revision still advances it.
        store.putChat(chat("r").copy(listRowJson = """{"name":"Renamed"}""", revision = 0))
        val r4 = store.revision("r")!!
        assertTrue(r0 < r1 && r1 < r2 && r2 < r3 && r3 < r4)
    }

    @Test
    fun revision_isNeverRepeatedWhenAChatIsRecreated() {
        val store = open()
        store.putChat(chat("again"))
        store.appendMessage("again", row("old history"))
        val old = store.revision("again")!!
        store.deleteChat("again")
        store.putChat(chat("again"))
        store.appendMessage("again", row("new history"))
        assertTrue(store.revision("again")!! > old)
    }

    @Test
    fun listSaved_onlyOnceAChatExistsOrItIsMarked() {
        val a = open()
        assertFalse(a.listSaved())
        a.putChat(chat("l"))
        a.deleteChat("l")
        assertTrue(a.listSaved())
        val b = open()
        b.markListSaved()
        assertTrue(b.listSaved())
    }

    @Test
    fun listState_roundTrips() {
        val store = open()
        store.putListState("folders", """[{"id":"f1","name":"Stories"}]""")
        store.putListState("folders", """[{"id":"f1","name":"Renamed"}]""")
        assertEquals("""[{"id":"f1","name":"Renamed"}]""", store.getListState("folders"))
        assertEquals(listOf("folders"), store.listStateKeys())
    }

    @Test
    fun data_survivesReopen() {
        val name = "chat-store-reopen-${System.nanoTime()}.db"
        val first = open(name)
        first.putChat(chat("k"))
        first.appendMessage("k", row("persisted"))
        val storeId = first.storeId()
        first.close()

        val second = ChatStore.open(context, key, ChatStore.CloseOnCorruption, name)
        opened.add(name to second)
        assertEquals(storeId, second.storeId())
        assertEquals(row("persisted").payloadJson, second.messageAt("k", 0)?.payloadJson)
    }

    @Test
    fun includeText_roundTripsSplitsAndReplaces() {
        val store = open()
        store.putChat(chat("doc"))
        val seq = store.appendMessage("doc", row("see attached"))
        // A book-length document with emoji straddling the part boundaries.
        val book = "Chapter 😀 ".repeat(ChatStore.PART_CHARS / 5)
        val before = store.revision("doc")!!
        assertTrue(store.putIncludeText("doc", seq, "inc-1", book))
        assertTrue(store.putIncludeText("doc", seq, "inc-2", ""))
        assertTrue(store.revision("doc")!! > before)
        assertEquals(book, store.readIncludeText("doc", seq, "inc-1"))
        assertEquals("", store.readIncludeText("doc", seq, "inc-2"))
        assertNull(store.readIncludeText("doc", seq, "inc-3"))
        assertEquals(listOf("inc-1", "inc-2"), store.includeTextIds("doc", seq))

        assertTrue(store.putIncludeText("doc", seq, "inc-1", "short"))
        assertEquals("short", store.readIncludeText("doc", seq, "inc-1"))
        assertTrue(store.deleteIncludeText("doc", seq, "inc-2"))
        assertEquals(listOf("inc-1"), store.includeTextIds("doc", seq))
        assertFalse(store.putIncludeText("doc", seq + 99, "inc-1", "no such message"))
    }

    @Test
    fun includeText_goesAwayWithItsMessageAndChat() {
        val store = open()
        store.putChat(chat("m"))
        val first = store.appendMessage("m", row("one"))
        val second = store.appendMessage("m", row("two"))
        store.putIncludeText("m", first, "a", "x".repeat(ChatStore.PART_CHARS + 1))
        store.putIncludeText("m", second, "b", "kept")

        store.deleteMessage("m", first)
        assertEquals(1, includePartCount(store))
        store.deleteChat("m")
        assertEquals(0, includePartCount(store))
        assertTrue(store.integrityOk())
    }

    @Test
    fun integrityOk_onNewAndFilledStore() {
        val store = open()
        assertTrue(store.integrityOk())
        store.putChat(chat("i"))
        store.replaceAllMessages("i", (0 until 10).asSequence().map { row("m$it") })
        assertTrue(store.integrityOk())
    }

    @Test
    fun integrityOk_rejectsIncompleteOrInconsistentSplitValues() {
        val gap = open()
        gap.putChat(chat("g"))
        gap.appendMessage("g", row("y".repeat(ChatStore.PART_CHARS * 3)))
        gap.writableDatabase.execSQL("DELETE FROM message_parts WHERE part_index = 1")
        assertFalse(gap.integrityOk())

        val none = open()
        none.putChat(chat("n"))
        none.appendMessage("n", row("y".repeat(ChatStore.PART_CHARS * 2)))
        none.writableDatabase.execSQL("DELETE FROM message_parts")
        assertFalse(none.integrityOk())

        val both = open()
        both.putChat(chat("b"))
        both.appendMessage("b", row("y".repeat(ChatStore.PART_CHARS * 2)))
        both.writableDatabase.execSQL("UPDATE messages SET payload_json = '{}'")
        assertFalse(both.integrityOk())

        val doc = open()
        doc.putChat(chat("d"))
        val docSeq = doc.appendMessage("d", row("doc"))
        doc.putIncludeText("d", docSeq, "inc", "z".repeat(ChatStore.PART_CHARS * 3))
        assertTrue(doc.integrityOk())
        doc.writableDatabase.execSQL("DELETE FROM include_text_parts WHERE part_index = 0")
        assertFalse(doc.integrityOk())
    }

    @Test
    fun integrityOk_requiresTheStoreMetadata() {
        for (broken in listOf(
            "DELETE FROM meta WHERE key = 'store_id'",
            "DELETE FROM meta WHERE key = 'revision_counter'",
            "UPDATE meta SET value = '0' WHERE key = 'revision_counter'",
            "UPDATE meta SET value = '99' WHERE key = 'schema'"
        )) {
            val store = open()
            store.putChat(chat("meta"))
            store.appendMessage("meta", row("x"))
            assertTrue(store.integrityOk())
            store.writableDatabase.execSQL(broken)
            assertFalse(broken, store.integrityOk())
        }
    }

    private fun includePartCount(store: ChatStore): Int =
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM include_text_parts", null)
            .use { it.moveToFirst(); it.getInt(0) }

    /**
     * A very long conversation: write it from a lazy sequence and read it
     * back page by page, checking that memory in use stays far below the
     * size of the conversation itself.
     */
    @Test
    fun veryLongConversation_writesAndReadsWithBoundedMemory() {
        val store = open()
        store.putChat(chat("long"))
        val messageCount = 20_000
        val body = "A companion conversation paragraph. ".repeat(60) // ~2,200 chars
        val written = store.replaceAllMessages(
            "long",
            (0 until messageCount).asSequence().map { row("$it $body", isBot = it % 2 == 1) }
        )
        assertEquals(messageCount, written)
        assertEquals(messageCount, store.messageCount("long"))

        val runtime = Runtime.getRuntime()
        System.gc()
        val baseline = runtime.totalMemory() - runtime.freeMemory()
        var peak = baseline
        var visited = 0
        store.forEachMessage("long") { message ->
            if (!message.payloadJson.startsWith("""{"message":"$visited """)) {
                fail("message $visited out of order")
            }
            visited++
            if (visited % 2000 == 0) {
                // Collect first so only what the read is still holding counts.
                System.gc()
                peak = maxOf(peak, runtime.totalMemory() - runtime.freeMemory())
            }
            true
        }
        assertEquals(messageCount, visited)
        // The conversation is ~44 million characters; reading it must not
        // hold anything close to that at once.
        val conversationBytes = messageCount.toLong() * body.length * 2
        assertTrue(
            "peak growth ${peak - baseline} bytes vs conversation $conversationBytes bytes",
            peak - baseline < conversationBytes / 8
        )
    }
}
