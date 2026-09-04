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

import android.app.Application
import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Phase 9.2 startup recovery: an interrupted swap is finished from staging only
 * when the staged bytes still hash to the value the journal recorded. This is
 * what stops a truncated or altered staging from being copied over the live
 * chat files; the pre-restore quarantine stays the recovery source instead.
 *
 * The journal is seeded through its on-disk contract (the `storage_health`
 * preferences file and the `chatrestore.*` keys `ChatRestoreManager` reads at
 * startup), because process death cannot be induced from a unit test. What is
 * exercised is the real [ChatRestoreManager.resumeIfPending] path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28, 36])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ChatRestoreJournalRecoveryTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val chatListEntry = "enc.chat_list.xml"

    @Test
    fun aSwapResumesWhenStagedBytesMatchTheJournaledHash() {
        val newBytes = "NEW-CHAT-LIST".toByteArray()
        val live = seedLiveChatList("OLD-CHAT-LIST".toByteArray())
        val staging = seedStaging(newBytes)
        seedSwappingJournal(staging, mapOf(chatListEntry to hex(sha256(newBytes))))

        ChatRestoreManager.resumeIfPending(context)

        assertArrayEquals("the verified staged bytes must be swapped in", newBytes, live.readBytes())
        assertJournalCleared()
    }

    @Test
    fun aTamperedStagingIsNeverSwappedIn() {
        val oldBytes = "OLD-CHAT-LIST".toByteArray()
        val live = seedLiveChatList(oldBytes)
        // Staging holds different bytes than the journal's recorded hash.
        val staging = seedStaging("TAMPERED".toByteArray())
        seedSwappingJournal(staging, mapOf(chatListEntry to hex(sha256("NEW-CHAT-LIST".toByteArray()))))

        ChatRestoreManager.resumeIfPending(context)

        assertArrayEquals("unproven staging must never replace the live file", oldBytes, live.readBytes())
        assertJournalCleared()
    }

    @Test
    fun aMissingStagedFileIsNeverSwappedIn() {
        val oldBytes = "OLD-CHAT-LIST".toByteArray()
        val live = seedLiveChatList(oldBytes)
        val staging = File(context.filesDir, "chat_restore_staging/missing").apply { mkdirs() }
        // The journal names a file the staging does not contain.
        seedSwappingJournal(staging, mapOf(chatListEntry to hex(sha256("NEW-CHAT-LIST".toByteArray()))))

        ChatRestoreManager.resumeIfPending(context)

        assertArrayEquals(oldBytes, live.readBytes())
        assertJournalCleared()
    }

    // ---- helpers ------------------------------------------------------------

    private fun sharedPrefsDir(): File =
        File(context.dataDir, "shared_prefs").apply { mkdirs() }

    private fun seedLiveChatList(bytes: ByteArray): File =
        File(sharedPrefsDir(), chatListEntry).apply { writeBytes(bytes) }

    private fun seedStaging(chatListBytes: ByteArray): File {
        val staging = File(context.filesDir, "chat_restore_staging/${System.nanoTime()}")
        staging.mkdirs()
        File(staging, chatListEntry).writeBytes(chatListBytes)
        return staging
    }

    private fun seedSwappingJournal(staging: File, fileHashes: Map<String, String>) {
        val files = JSONObject()
        for ((name, hash) in fileHashes) files.put(name, hash)
        context.getSharedPreferences("storage_health", Context.MODE_PRIVATE).edit()
            .putString("chatrestore.phase", "swapping")
            .putString("chatrestore.staging_dir", staging.absolutePath)
            .putString("chatrestore.files", files.toString())
            .commit()
    }

    private fun assertJournalCleared() {
        val p = context.getSharedPreferences("storage_health", Context.MODE_PRIVATE)
        assertNull("the restore journal must be cleared after recovery", p.getString("chatrestore.phase", null))
        assertFalse(p.contains("chatrestore.files"))
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
