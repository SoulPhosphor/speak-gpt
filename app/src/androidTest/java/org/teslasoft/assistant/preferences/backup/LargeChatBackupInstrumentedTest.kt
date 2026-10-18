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

package org.teslasoft.assistant.preferences.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.ModelEndpointStateGenerationStore
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.preferences.backup.portable.ChatLogicalSerializer
import org.teslasoft.assistant.preferences.backup.portable.ChatsArtifactScanner
import org.teslasoft.assistant.preferences.backup.portable.PortablePackage
import org.teslasoft.assistant.preferences.backup.portable.PortableRecoveryLimits
import org.teslasoft.assistant.preferences.backup.portable.PortableRecoverySemanticValidator
import org.teslasoft.assistant.preferences.backup.portable.PortableRecoveryWriter
import org.teslasoft.assistant.preferences.chatstore.ChatStorageAuthority
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStore
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.profileimages.ProfileImageDb

/**
 * A Recovery Backup of a chat set larger than the old 64 MiB ceiling is
 * created and verified from today's chat storage (chat-storage-and-backup-plan.md
 * §5, stage 3), keeps never-saved and empty histories apart, and is refused
 * by the current whole-read restore engine as too large rather than crashing
 * it (until stage 4 replaces that engine).
 */
@RunWith(AndroidJUnit4::class)
class LargeChatBackupInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val created = ArrayList<File>()
    private val chatIds = ArrayList<String>()

    @Before
    fun prepare() {
        ChatStorageAuthority.clearForTest(context)
        SecurePrefs.get(context, "chat_list").edit().clear().commit()
        MemoryStore.getInstance(context).readableDatabase.rawQuery("SELECT 1", null).use { it.moveToFirst() }
        ProfileImageDb.getInstance(context).readableDatabase.rawQuery("SELECT 1", null).use { it.moveToFirst() }
        GeneratedImageCatalogStore.ensureAvailableForRegistration(context)
        ModelEndpointStateGenerationStore.get(context).read()
    }

    @After
    fun cleanup() {
        chatIds.forEach { SecurePrefs.get(context, "chat_$it").edit().clear().commit() }
        SecurePrefs.get(context, "chat_list").edit().clear().commit()
        created.forEach { it.deleteRecursively() }
    }

    @Test
    fun chatSetOverTheOldCeilingBacksUpAndVerifies() {
        val text = "A long companion conversation paragraph. ".repeat(2_500) // ~100 KB
        val rows = JSONArray()
        var expectedMessages = 0L
        repeat(FULL_CHATS) { index ->
            val id = UUID.randomUUID().toString().also(chatIds::add)
            rows.put(JSONObject().put("name", "Chat $index").put("id", id))
            val history = JSONArray()
            repeat(MESSAGES_PER_CHAT) { position ->
                history.put(
                    JSONObject()
                        .put("message", "$position $text")
                        .put("isBot", position % 2 == 1)
                        .put("message_id", UUID.randomUUID().toString())
                )
            }
            expectedMessages += MESSAGES_PER_CHAT
            assertTrue(SecurePrefs.get(context, "chat_$id").edit().putString("chat", history.toString()).commit())
        }
        val emptyId = UUID.randomUUID().toString().also(chatIds::add)
        rows.put(JSONObject().put("name", "Empty").put("id", emptyId))
        assertTrue(SecurePrefs.get(context, "chat_$emptyId").edit().putString("chat", "[]").commit())
        val neverSavedId = UUID.randomUUID().toString().also(chatIds::add)
        rows.put(JSONObject().put("name", "Never saved").put("id", neverSavedId))
        assertTrue(SecurePrefs.get(context, "chat_list").edit().putString("data", rows.toString()).commit())

        val output = tempFile("large.sgbak")
        val written = PortableRecoveryWriter.createPackage(context, output, null, null, "large-chat-test")
        assertTrue("writer result: $written", written is PortableRecoveryWriter.Result.Ok)
        written as PortableRecoveryWriter.Result.Ok
        assertEquals(FULL_CHATS + 2, written.chatCount)
        assertEquals(RecoveryBackupReceipt.STORAGE_LEGACY, written.chatSource?.storage)

        val decodeRoot = tempDirectory("large-decode")
        val decoded = PortablePackage.decodeWithSecret(output, ByteArray(0), decodeRoot)
            as PortablePackage.DecodeResult.Ok
        val validated = PortablePackage.validateAndExtract(
            decoded.innerZip, File(decodeRoot, "artifacts").apply { mkdirs() }
        ) as PortablePackage.ValidateResult.Ok
        val chats = validated.artifacts.single { it.type == PortablePackage.TYPE_CHATS_JSON }.stagedFile
        assertTrue(
            "chats artifact is ${chats.length()} bytes",
            chats.length() > PortableRecoveryLimits.WHOLE_READ_RESTORE_CHATS_JSON_BYTES
        )

        val summary = (chats.bufferedReader().use { ChatsArtifactScanner.scan(it) }
            as ChatsArtifactScanner.Result.Ok).summary
        assertEquals(ChatLogicalSerializer.FORMAT_V3, summary.format)
        assertEquals(FULL_CHATS + 2, summary.chatCount)
        assertEquals(expectedMessages, summary.messageCount)
        assertEquals(1, occurrences(chats, "\"history_saved\":false"))
        assertEquals(FULL_CHATS + 1, occurrences(chats, "\"history_saved\":true"))

        val full = PortableRecoverySemanticValidator.validate(
            context, validated.artifacts, validated.declaredCategories,
            validated.explicitlyEmptyCategories, validated.categoryRecordCounts
        )
        assertTrue("full check: $full", full is PortableRecoverySemanticValidator.Result.Valid)

        // The whole-read restore engine refuses it as too large instead of
        // running out of memory.
        assertTrue(
            PortableRecoverySemanticValidator.validatePackage(
                validated.artifacts, validated.declaredCategories, validated.explicitlyEmptyCategories
            ) is PortableRecoverySemanticValidator.Result.TooLarge
        )
    }

    /** Count [needle] in [file] a block at a time, never reading it whole. */
    private fun occurrences(file: File, needle: String): Int {
        var count = 0
        var carry = ""
        file.bufferedReader().use { reader ->
            val buffer = CharArray(64 * 1024)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                val window = carry + String(buffer, 0, read)
                var from = 0
                while (true) {
                    val at = window.indexOf(needle, from)
                    if (at < 0) break
                    count++
                    from = at + needle.length
                }
                // Shorter than the needle, so no match is ever counted twice.
                carry = window.takeLast(needle.length - 1)
            }
        }
        return count
    }

    private fun tempFile(name: String): File =
        File(context.cacheDir, "large-chat-${System.nanoTime()}-$name").also(created::add)

    private fun tempDirectory(name: String): File =
        File(context.cacheDir, "large-chat-${System.nanoTime()}-$name").apply { mkdirs() }.also(created::add)

    private companion object {
        const val FULL_CHATS = 36
        const val MESSAGES_PER_CHAT = 20
    }
}
