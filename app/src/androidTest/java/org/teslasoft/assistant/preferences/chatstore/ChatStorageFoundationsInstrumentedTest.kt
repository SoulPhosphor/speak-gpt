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
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.ChatStorageHealth.ReadState
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.preferences.damaged.DamagedChatOperation

/**
 * The shared chat-storage interfaces (chat-storage-and-backup-plan.md §4.2):
 * both [ChatSource] implementations give the same answers for the same chats,
 * damaged data is never presented as empty, and the authority flag and staged
 * databases behave as the later steps rely on.
 */
@RunWith(AndroidJUnit4::class)
class ChatStorageFoundationsInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val key = "chat-foundations-test-key".toByteArray()
    private val chatIds = listOf("fa1", "fa2", "fa3")
    private val stores = ArrayList<ChatStore>()
    private val stages = ArrayList<StagedChatDatabase>()

    private val history1 = """[
        {"message":"Hello 😀 <b>","isBot":false,"messageTime":1.7E12,"message_id":"m-1"},
        {"message":"Hi","isBot":true,"variants":"[\"a\",\"b\"]","unknown":{"nested":[1,2.50,null]}},
        null
    ]"""

    @Before
    fun prepare() {
        resetLegacyFiles()
        ChatStorageAuthority.clearForTest(context)
    }

    @After
    fun cleanup() {
        stores.forEach { runCatching { it.close() } }
        stages.forEach { runCatching { it.discard() } }
        resetLegacyFiles()
        ChatStorageAuthority.clearForTest(context)
    }

    private fun resetLegacyFiles() {
        SecurePrefs.get(context, LegacyChatSource.LIST_FILE).edit().clear().commit()
        chatIds.forEach { SecurePrefs.get(context, LegacyChatSource.historyFile(it)).edit().clear().commit() }
    }

    private fun writeLegacy() {
        assertTrue(SecurePrefs.get(context, LegacyChatSource.LIST_FILE).edit()
            .putString(LegacyChatSource.LIST_KEY, """[
                {"name":"First","id":"fa1","pinned":"true"},
                {"name":"Second","id":"fa2"},
                {"name":"Third","id":"fa3"}
            ]""")
            .putString("folders", """[{"id":"f1","name":"Stories"}]""")
            .putLong("list_revision", 7L)
            .commit())
        assertTrue(SecurePrefs.get(context, "chat_fa1").edit()
            .putString(LegacyChatSource.HISTORY_KEY, history1)
            .putLong("content_modified_at", 1_700_000_000_123L)
            .putBoolean("flag", true)
            .putStringSet("tags", setOf("x", "y"))
            .commit())
        assertTrue(SecurePrefs.get(context, "chat_fa2").edit()
            .putString(LegacyChatSource.HISTORY_KEY, "[]")
            .commit())
        // fa3 has never been saved: its history key is missing.
    }

    private fun openStore(): ChatStore =
        ChatStore.open(context, key, ChatStore.CloseOnCorruption, "chat-foundations-${System.nanoTime()}.db")
            .also { stores.add(it) }

    private fun messages(source: ChatSource, chatId: String): Pair<ReadState, List<ChatSource.Message>> {
        val out = ArrayList<ChatSource.Message>()
        val state = source.readHistory(chatId, DamagedChatOperation.RECOVERY_BACKUP) { out.add(it); true }
        return state to out
    }

    /** Copy everything [legacy] holds into [store], the way migration will. */
    private fun copyInto(store: ChatStore, legacy: ChatSource) {
        val list = legacy.readList()
        assertEquals(ReadState.OK, list.state)
        list.chats.forEachIndexed { index, entry ->
            val values = legacy.readChatValues(entry.chatId)
            assertEquals(ReadState.OK, values.state)
            val (state, read) = messages(legacy, entry.chatId)
            assertTrue(state == ReadState.OK || state == ReadState.EMPTY || state == ReadState.MISSING)
            store.putChat(ChatStore.StoredChat(
                entry.chatId, index.toLong(), entry.listRowJson,
                TypedValueCodec.encodeMap(values.values)!!, readable = true, revision = 0,
                historySaved = state != ReadState.MISSING
            ))
            if (state != ReadState.MISSING) {
                store.replaceAllMessages(entry.chatId, read.asSequence().map {
                    ChatStore.MessageRow(it.messageId, it.isBot, it.payloadJson)
                })
            }
        }
        val listValues = legacy.readListValues()
        assertEquals(ReadState.OK, listValues.state)
        listValues.values.forEach { (k, v) -> store.putListState(k, TypedValueCodec.encodeValue(v)!!) }
    }

    @Test
    fun legacySource_readsListHistoryAndValuesAsStored() {
        writeLegacy()
        val legacy = LegacyChatSource(context)

        val list = legacy.readList()
        assertEquals(ReadState.OK, list.state)
        assertEquals(chatIds, list.chats.map { it.chatId })
        assertEquals("""{"name":"First","id":"fa1","pinned":"true"}""", list.chats[0].listRowJson)

        val (state, read) = messages(legacy, "fa1")
        assertEquals(ReadState.OK, state)
        val expected = JsonParser.parseString(history1).asJsonArray.map { it.toString() }
        assertEquals(expected, read.map { it.payloadJson })
        assertTrue(read[0].payloadJson.contains("1.7E12"))
        assertEquals("m-1", read[0].messageId)
        assertFalse(read[0].isBot)
        assertTrue(read[1].isBot)
        assertNull(read[1].messageId)

        assertEquals(ReadState.EMPTY, messages(legacy, "fa2").first)
        assertEquals(ReadState.MISSING, messages(legacy, "fa3").first)

        val values = legacy.readChatValues("fa1")
        assertEquals(ReadState.OK, values.state)
        assertFalse(values.values.containsKey(LegacyChatSource.HISTORY_KEY))
        assertEquals(1_700_000_000_123L, values.values["content_modified_at"])
        assertEquals(true, values.values["flag"])
        assertEquals(setOf("x", "y"), values.values["tags"])

        val listValues = legacy.readListValues()
        assertEquals(ReadState.OK, listValues.state)
        assertFalse(listValues.values.containsKey(LegacyChatSource.LIST_KEY))
        assertEquals(7L, listValues.values["list_revision"])
    }

    @Test
    fun readHistory_stopsWhenAsked() {
        writeLegacy()
        var visits = 0
        LegacyChatSource(context).readHistory("fa1", DamagedChatOperation.RECOVERY_BACKUP) { visits++; false }
        assertEquals(1, visits)
    }

    @Test
    fun databaseSource_givesTheSameAnswersAsLegacy() {
        writeLegacy()
        val legacy = LegacyChatSource(context)
        val store = openStore()
        copyInto(store, legacy)
        val database = DatabaseChatSource(store, legacy)

        assertEquals(
            legacy.readList().chats.map { it.chatId to it.listRowJson },
            database.readList().chats.map { it.chatId to it.listRowJson }
        )
        for (id in chatIds) {
            val a = messages(legacy, id)
            val b = messages(database, id)
            assertEquals(a.first, b.first)
            assertEquals(a.second.map { it.payloadJson }, b.second.map { it.payloadJson })
            assertEquals(a.second.map { it.messageId }, b.second.map { it.messageId })
            assertEquals(a.second.map { it.isBot }, b.second.map { it.isBot })
            assertEquals(legacy.readChatValues(id).values, database.readChatValues(id).values)
        }
        assertEquals(legacy.readListValues().values, database.readListValues().values)
        assertEquals(ReadState.MISSING, messages(database, "not-a-chat").first)
    }

    @Test
    fun databaseSource_keepsMissingDistinctFromEmpty() {
        val store = openStore()
        val database = DatabaseChatSource(store, LegacyChatSource(context))
        store.putChat(ChatStore.StoredChat("fa3", 0, "{}", "{}", readable = true, revision = 0, historySaved = false))
        assertEquals(ReadState.MISSING, messages(database, "fa3").first)
        val seq = store.appendMessage("fa3", ChatStore.MessageRow(null, false, """{"message":"first"}"""))
        assertEquals(ReadState.OK, messages(database, "fa3").first)
        store.deleteMessage("fa3", seq)
        assertEquals(ReadState.EMPTY, messages(database, "fa3").first)
    }

    @Test
    fun databaseSource_readsUnmovedChatsFromTheirOriginalFile() {
        writeLegacy()
        val legacy = LegacyChatSource(context)
        val store = openStore()
        store.putChat(ChatStore.StoredChat("fa1", 0, "{}", "{}", readable = false, revision = 0))
        val database = DatabaseChatSource(store, legacy)

        assertEquals(
            messages(legacy, "fa1").second.map { it.payloadJson },
            messages(database, "fa1").second.map { it.payloadJson }
        )
        assertEquals(legacy.readChatValues("fa1").values, database.readChatValues("fa1").values)
        assertEquals(legacy.changeToken("fa1"), database.changeToken("fa1"))
    }

    @Test
    fun changeTokens_followContentAndNeverCrossSources() {
        writeLegacy()
        val legacy = LegacyChatSource(context)
        val before = legacy.changeToken("fa1")
        assertNotNull(before)
        assertEquals(before, legacy.changeToken("fa1"))
        SecurePrefs.get(context, "chat_fa1").edit()
            .putString(LegacyChatSource.HISTORY_KEY, """[{"message":"changed"}]""").commit()
        assertNotEquals(before, legacy.changeToken("fa1"))

        val store = openStore()
        copyInto(store, legacy)
        val database = DatabaseChatSource(store, legacy)
        val dbBefore = database.changeToken("fa1")
        assertNotNull(dbBefore)
        assertNotEquals(legacy.changeToken("fa1"), dbBefore)
        store.appendMessage("fa1", ChatStore.MessageRow(null, false, """{"message":"more"}"""))
        assertNotEquals(dbBefore, database.changeToken("fa1"))
        assertNull(database.changeToken("not-a-chat"))
    }

    @Test
    fun damagedLegacyHistory_isReportedThroughTheAppsOwnHandlingNeverAsEmpty() {
        writeLegacy()
        SecurePrefs.get(context, "chat_fa1").edit()
            .putString(LegacyChatSource.HISTORY_KEY, """[{"message":"cut off""").commit()
        var delivered = 0
        val state = LegacyChatSource(context)
            .readHistory("fa1", DamagedChatOperation.RECOVERY_BACKUP) { delivered++; true }
        assertEquals(ReadState.CORRUPT, state)
        assertEquals(0, delivered)
    }

    @Test
    fun historyOnlyTheAppsReaderAccepts_failsWithoutChangingIt() {
        writeLegacy()
        // Gson reads a map written as [key, value] pairs; the streaming check
        // refuses it. The result must be FAILED with the value left alone.
        val stored = """[[["message","pairs"]]]"""
        SecurePrefs.get(context, "chat_fa1").edit()
            .putString(LegacyChatSource.HISTORY_KEY, stored).commit()
        var delivered = 0
        val state = LegacyChatSource(context)
            .readHistory("fa1", DamagedChatOperation.RECOVERY_BACKUP) { delivered++; true }
        assertEquals(ReadState.FAILED, state)
        assertEquals(0, delivered)
        assertEquals(stored, SecurePrefs.get(context, "chat_fa1").getString(LegacyChatSource.HISTORY_KEY, null))
    }

    @Test
    fun authority_defaultsToLegacyAndNeverGuessesAnUnknownValue() {
        assertEquals(ChatStorageAuthority.Authority.LEGACY, ChatStorageAuthority.current(context))

        assertTrue(ChatStorageAuthority.setForTest(context, ChatStorageAuthority.Authority.DATABASE))
        ChatStorageAuthority.forgetCachedForTest()
        assertEquals(ChatStorageAuthority.Authority.DATABASE, ChatStorageAuthority.current(context))

        context.getSharedPreferences("chat_storage_authority", Context.MODE_PRIVATE)
            .edit().putString("authority", "something-newer").commit()
        ChatStorageAuthority.forgetCachedForTest()
        assertEquals(ChatStorageAuthority.Authority.UNREADABLE, ChatStorageAuthority.current(context))

        assertFalse(ChatStorageAuthority.setForTest(context, ChatStorageAuthority.Authority.UNREADABLE))
    }

    @Test
    fun stagedDatabase_validatesAndDiscardsCompletely() {
        val stage = StagedChatDatabase.create(context, "test", key).also { stages.add(it) }
        stage.store.putChat(ChatStore.StoredChat("s", 0, "{}", "{}", readable = true, revision = 0))
        stage.store.appendMessage("s", ChatStore.MessageRow(null, false, """{"message":"staged"}"""))
        assertTrue(stage.validate())
        assertTrue(stage.databaseName in StagedChatDatabase.leftovers(context))
        assertTrue(stage.discard())
        assertFalse(stage.file.exists())
        assertFalse(stage.databaseName in StagedChatDatabase.leftovers(context))
    }

    @Test
    fun stagedDatabase_leftoverFromAnInterruptionCanBeFoundAndDiscarded() {
        val stage = StagedChatDatabase.create(context, "test", key)
        stage.store.putChat(ChatStore.StoredChat("s", 0, "{}", "{}", readable = true, revision = 0))
        stage.close()
        assertTrue(stage.databaseName in StagedChatDatabase.leftovers(context))
        assertTrue(StagedChatDatabase.discardLeftover(context, stage.databaseName))
        assertFalse(stage.file.exists())
    }
}
