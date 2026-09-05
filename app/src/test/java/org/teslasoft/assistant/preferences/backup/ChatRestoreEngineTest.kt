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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.preferences.chatsearch.ChatSearchIndexManager
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Phase 9.2 end-to-end engine proof: [ChatRestoreManager.restoreFromArchive]
 * validates a real archive, stages it, quarantines the current files, swaps the
 * complete set in, and — the hardening this covers — verifies the final live set
 * against the manifest before it clears the journal. A superseded chat file left
 * in place is deleted; a mixed old/new visible set never survives.
 *
 * The engine works on raw shared_prefs files (not SecurePrefs), so the archive
 * is built with the exact entry names the reader whitelists and the exact
 * manifest shape [RecoveryBackupManager] produces (manifest_version, complete,
 * chats, file_hashes). Calling the engine from a test is allowed: the
 * no-reachable-caller guard scans src/main only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28, 36])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ChatRestoreEngineTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetProcessStatics() {
        // restoreFromArchive now runs the settle-or-refuse gate, which reads
        // SecurePrefs-backed journals. Clear the process-static cache so a
        // pending-operation pointer seeded by another test cannot leak in and
        // make the restore refuse. Rebind the Search manager singleton too.
        SecurePrefs.clearCacheForTest()
        ChatSearchIndexManager.resetForTest()
    }

    private fun sharedPrefsDir(): File =
        File(context.dataDir, "shared_prefs").apply { mkdirs() }

    @Test
    fun aValidArchiveReplacesTheCompleteChatSetAndVerifies() {
        // Live storage: an old chat list, one old chat, and a STALE chat file
        // the restore must remove (its chat is not in the archive).
        val oldListBytes = "OLD-LIST".toByteArray()
        seedLive("enc.chat_list.xml", "OLD-LIST")
        seedLive("enc.chat_old.xml", "OLD-CHAT")
        seedLive("enc.settings.old.xml", "OLD-SETTINGS")
        val stale = seedLive("enc.chat_stale.xml", "STALE")

        // Archive: a new list + one new chat with both files.
        val newList = "NEW-LIST".toByteArray()
        val newChat = "NEW-CHAT".toByteArray()
        val newSettings = "NEW-SETTINGS".toByteArray()
        val archive = buildArchive(
            chatIds = listOf("n1"),
            files = linkedMapOf(
                "enc.chat_list.xml" to newList,
                "enc.chat_n1.xml" to newChat,
                "enc.settings.n1.xml" to newSettings
            )
        )

        val result = ChatRestoreManager.restoreFromArchive(context, archive)

        assertTrue("a valid archive must restore: ${result.detail}", result.ok)
        assertArrayEquals(newList, File(sharedPrefsDir(), "enc.chat_list.xml").readBytes())
        assertArrayEquals(newChat, File(sharedPrefsDir(), "enc.chat_n1.xml").readBytes())
        assertArrayEquals(newSettings, File(sharedPrefsDir(), "enc.settings.n1.xml").readBytes())
        // The old chat files and the stale file are gone — no mixed set.
        assertFalse(File(sharedPrefsDir(), "enc.chat_old.xml").exists())
        assertFalse(File(sharedPrefsDir(), "enc.settings.old.xml").exists())
        assertFalse("a superseded chat file must be deleted", stale.exists())
        // The pre-restore quarantine kept a copy of the old chat list (compared
        // against the pre-restore bytes, since the live file now holds the new).
        assertTrue(quarantineHasCopyOf("enc.chat_list.xml", oldListBytes))
        assertJournalCleared()
    }

    @Test
    fun aHashMismatchInTheArchiveFailsBeforeAnyMutation() {
        val liveList = seedLive("enc.chat_list.xml", "OLD-LIST")
        val archive = buildArchive(
            chatIds = listOf("n1"),
            files = linkedMapOf("enc.chat_list.xml" to "NEW-LIST".toByteArray()),
            corruptHashFor = "enc.chat_list.xml"
        )

        val result = ChatRestoreManager.restoreFromArchive(context, archive)

        assertFalse(result.ok)
        // The live file is untouched — validation failed before staging.
        assertArrayEquals("OLD-LIST".toByteArray(), liveList.readBytes())
        assertJournalCleared()
    }

    // ---- helpers ------------------------------------------------------------

    private fun seedLive(name: String, text: String): File =
        File(sharedPrefsDir(), name).apply { writeBytes(text.toByteArray()) }

    private fun buildArchive(
        chatIds: List<String>,
        files: Map<String, ByteArray>,
        corruptHashFor: String? = null
    ): File {
        val archive = File(context.cacheDir, "archive_${System.nanoTime()}.zip")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            val fileHashes = JSONObject()
            for ((name, bytes) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
                val hash = if (name == corruptHashFor) "0".repeat(64) else hex(sha256(bytes))
                fileHashes.put(name, hash)
            }
            val chatsArr = JSONArray()
            for (id in chatIds) chatsArr.put(JSONObject().put("chat_id", id).put("available", true))
            val meta = JSONObject()
                .put("manifest_version", ChatSnapshotManifest.MANIFEST_VERSION)
                .put("complete", true)
                .put("chats", chatsArr)
                .put("file_hashes", fileHashes)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(meta.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return archive
    }

    private fun quarantineHasCopyOf(originalName: String, bytes: ByteArray): Boolean {
        val dir = File(context.filesDir, "storage_recovery")
        val copies = dir.listFiles() ?: return false
        return copies.any {
            it.name.startsWith("$originalName.pre_restore_") &&
                MessageDigest.isEqual(it.readBytes(), bytes)
        }
    }

    private fun assertJournalCleared() {
        val p = context.getSharedPreferences("storage_health", Context.MODE_PRIVATE)
        assertNull(p.getString("chatrestore.phase", null))
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
