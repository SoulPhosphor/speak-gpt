/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.backup.portable.PortableRestoreProcessGate
import org.teslasoft.assistant.preferences.chatstore.ChatStorageAuthority
import org.teslasoft.assistant.preferences.damaged.DamagedChatOperation
import java.util.UUID

/**
 * Permanent message numbers assigned by the real save path (Stage 6A,
 * owner decision 10): messages saved without numbers receive them, the
 * numbers survive reloading and later saves, and no number is reused.
 */
@RunWith(AndroidJUnit4::class)
class MessageNumbersInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = ChatPreferences.getChatPreferences()
    private val ids = ArrayList<String>()

    @Before fun setup() {
        PortableRestoreProcessGate.clear(context)
        ChatStorageAuthority.clearForTest(context)
    }

    @After fun cleanup() {
        ids.forEach { id ->
            SecurePrefs.get(context, "chat_$id").edit().clear().commit()
            SecurePrefs.get(context, "settings.$id").edit().clear().commit()
        }
    }

    private fun newChat() = UUID.randomUUID().toString().also(ids::add)

    private fun message(text: String, bot: Boolean = false) = hashMapOf<String, Any>(
        "message" to text, "isBot" to bot, MessageIdentity.KEY to UUID.randomUUID().toString()
    )

    private fun read(chatId: String) =
        prefs.getChatByIdResult(context, chatId, DamagedChatOperation.OPENING_A_CHAT).messages

    private fun numbers(messages: List<Map<String, Any>>) = messages.map { MessageNumbers.numberOf(it) }

    @Test fun unnumberedMessagesAreNumberedOnSaveAndKeepTheirNumbers() {
        val chatId = newChat()
        val saved = arrayListOf(message("one"), message("two", bot = true), message("three"))
        assertEquals(ChatStorageHealth.WriteOutcome.OK, prefs.saveChatHistory(context, chatId, saved, synchronous = true))
        val loaded = read(chatId)
        assertEquals(listOf(1L, 2L, 3L), numbers(loaded))

        // Saving the reloaded chat again changes nothing.
        assertEquals(ChatStorageHealth.WriteOutcome.OK, prefs.saveChatHistory(context, chatId, loaded, synchronous = true))
        assertEquals(listOf(1L, 2L, 3L), numbers(read(chatId)))

        // Deleting the last message never frees its number.
        val shorter = ArrayList(read(chatId).dropLast(1))
        assertEquals(ChatStorageHealth.WriteOutcome.OK, prefs.saveChatHistory(context, chatId, shorter, synchronous = true))
        val grown = ArrayList(read(chatId)).apply { add(message("four")) }
        assertEquals(ChatStorageHealth.WriteOutcome.OK, prefs.saveChatHistory(context, chatId, grown, synchronous = true))
        assertEquals(listOf(1L, 2L, 4L), numbers(read(chatId)))
    }

    @Test fun anImportedNumberedChatContinuesFromItsHighestNumber() {
        val chatId = newChat()
        val imported = (1L..10L).map { n -> message("m$n").apply { put(MessageNumbers.KEY, n) } }
        assertTrue(prefs.importChatHistoryJson(context, chatId, Gson().toJson(imported)))
        val afterDelete = ArrayList(read(chatId).dropLast(1))
        assertEquals(ChatStorageHealth.WriteOutcome.OK, prefs.saveChatHistory(context, chatId, afterDelete, synchronous = true))
        val next = ArrayList(read(chatId)).apply { add(message("new")) }
        assertEquals(ChatStorageHealth.WriteOutcome.OK, prefs.saveChatHistory(context, chatId, next, synchronous = true))
        assertEquals(11L, MessageNumbers.numberOf(read(chatId).last()))
    }
}
