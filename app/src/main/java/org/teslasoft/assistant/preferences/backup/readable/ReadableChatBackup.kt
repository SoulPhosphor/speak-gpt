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

package org.teslasoft.assistant.preferences.backup.readable

import android.content.Context
import com.google.gson.Gson
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.preferences.memory.MemoryLog
import org.teslasoft.assistant.util.Hash
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Builds the Human-Readable Chat Backup ZIP into private staging (owner
 * directive, July 22 2026). Save As is launched by the UI only AFTER this
 * build has succeeded and the staged ZIP has been verified — the same
 * build-before-Save-As architecture as the recovery package.
 *
 * Rules enforced here:
 *  - ONE unreadable chat fails the whole build, visibly — a "complete"
 *    readable backup must never silently omit a chat (owner rule). LOCKED,
 *    CORRUPT and FAILED read states are unreadable; EMPTY and MISSING are
 *    honest data (a chat with no messages exports as an empty conversation).
 *  - Chat identity follows the app's OWN mapping: id = Hash.hash of the
 *    stored name's string form — a list entry with a missing or blank name
 *    is serialized faithfully, never dropped and never a failure (the app
 *    itself displays such a chat; the backup mirrors the app).
 *  - Only chat titles and messages travel. Per-chat settings — and with them
 *    any credential-shaped value — are not part of a readable chat export.
 *  - The whole read runs inside CHAT_LIST_LOCK so the list and histories
 *    form one consistent set; runs on a background thread only.
 *  - Failure logging carries chat IDS (already hashes) and counts — never a
 *    title or message content.
 */
object ReadableChatBackup {

    enum class Format { TEXT, JSON, BOTH }

    sealed class BuildResult {
        /** Staged and verified; [fingerprints] covers ALL current chats (the
         *  baseline candidate), [chatCount] the chats inside the ZIP. */
        data class Ok(
            val chatCount: Int,
            val fingerprints: Map<String, String>
        ) : BuildResult()

        /** Incremental run with no new or changed chats: no ZIP is created
         *  (never an empty ZIP — owner directive). */
        object NothingNew : BuildResult()

        /** No chats exist at all (complete mode). */
        object NothingToBackUp : BuildResult()

        /** The chat list or one chat could not be read: typed, visible
         *  failure — no partial output exists. */
        object ChatsUnreadable : BuildResult()

        /** The staged ZIP could not be written or failed verification. */
        object Failed : BuildResult()
    }

    private data class ChatData(
        val title: String,
        val messages: List<Map<String, Any?>>,
        val messagesJson: String
    )

    /**
     * Build the ZIP into [staged]. On every result other than Ok, [staged]
     * is deleted before returning.
     */
    fun build(context: Context, staged: File, allChats: Boolean, format: Format): BuildResult {
        try {
            val gson = Gson()
            val chatPreferences = ChatPreferences.getChatPreferences()

            val collected = ArrayList<ChatData>()
            val fingerprints = LinkedHashMap<String, String>()

            synchronized(ChatPreferences.CHAT_LIST_LOCK) {
                val listResult = chatPreferences.getChatListResult(context, includeFirstMessage = false)
                if (!ChatStorageHealth.isAuthoritative(listResult.state)) {
                    logUnreadable(context, "the chat list could not be read (state ${listResult.state})")
                    return fail(staged, BuildResult.ChatsUnreadable)
                }
                for (chat in listResult.chats) {
                    // The app's own identity mapping (see ChatPreferences'
                    // first_message loop): the stored name's string form is
                    // hashed even when the entry is malformed, so every chat
                    // the app can display, this backup can carry.
                    val name = chat["name"].toString()
                    val chatId = Hash.hash(name)
                    val history = chatPreferences.getChatByIdResult(context, chatId)
                    if (!ChatStorageHealth.isAuthoritative(history.state)) {
                        logUnreadable(context, "chat $chatId could not be read (state ${history.state})")
                        return fail(staged, BuildResult.ChatsUnreadable)
                    }
                    val messagesJson = gson.toJson(history.messages)
                    fingerprints[chatId] = ReadableChatFormats.fingerprint(name, messagesJson)
                    collected.add(ChatData(name, history.messages, messagesJson))
                }
            }

            val selectedIndices: List<Int> = if (allChats) {
                collected.indices.toList()
            } else {
                val baseline = ReadableBackupState.getBaseline(context)
                val changedIds = ReadableChatFormats
                    .selectChanged(fingerprints, baseline).toHashSet()
                // fingerprints and collected share iteration order; map ids
                // back to indices through the same order.
                val ids = fingerprints.keys.toList()
                collected.indices.filter { ids[it] in changedIds }
            }

            if (selectedIndices.isEmpty()) {
                return fail(staged, if (allChats) BuildResult.NothingToBackUp else BuildResult.NothingNew)
            }

            val baseNames = ReadableChatFormats.assignBaseNames(
                selectedIndices.map { ReadableChatFormats.sanitizeTitle(collected[it].title) }
            )

            val expectedEntries = LinkedHashSet<String>()
            ZipOutputStream(staged.outputStream().buffered()).use { zip ->
                for ((slot, index) in selectedIndices.withIndex()) {
                    val chat = collected[index]
                    val base = baseNames[slot]
                    if (format == Format.TEXT || format == Format.BOTH) {
                        val entryName = "$base.txt"
                        zip.putNextEntry(ZipEntry(entryName))
                        zip.write(ReadableChatFormats.formatText(chat.title, chat.messages).toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                        expectedEntries.add(entryName)
                    }
                    if (format == Format.JSON || format == Format.BOTH) {
                        val entryName = "$base.json"
                        zip.putNextEntry(ZipEntry(entryName))
                        zip.write(ReadableChatFormats.formatJson(chat.title, chat.messagesJson).toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                        expectedEntries.add(entryName)
                    }
                }
            }

            if (!verifyStagedZip(staged, expectedEntries)) {
                MemoryLog.log(context, "ReadableChatBackup", "error",
                    "Staged readable chat backup failed verification; nothing was saved.")
                return fail(staged, BuildResult.Failed)
            }

            return BuildResult.Ok(chatCount = selectedIndices.size, fingerprints = fingerprints)
        } catch (e: Exception) {
            MemoryLog.log(context, "ReadableChatBackup", "error",
                "Readable chat backup build failed (${e.javaClass.simpleName}).")
            return fail(staged, BuildResult.Failed)
        }
    }

    /** Reopen the staged ZIP and read EVERY entry to the end — ZipInputStream
     *  verifies each entry's CRC as it closes, so a torn or corrupt ZIP fails
     *  here instead of being offered to Save As. The entry set must match
     *  exactly what was written. */
    private fun verifyStagedZip(staged: File, expectedEntries: Set<String>): Boolean {
        return try {
            val seen = HashSet<String>()
            ZipInputStream(FileInputStream(staged).buffered()).use { zip ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!seen.add(entry.name)) return false
                    while (zip.read(buf) >= 0) { /* drain; CRC checked at entry end */ }
                    zip.closeEntry()
                }
            }
            seen == expectedEntries
        } catch (_: Exception) {
            false
        }
    }

    private fun fail(staged: File, result: BuildResult): BuildResult {
        runCatching { if (staged.exists()) staged.delete() }
        return result
    }

    /** Ids and states only — never a chat title or message (owner rule). */
    private fun logUnreadable(context: Context, detail: String) {
        val msg = "Readable chat backup failed: $detail. No backup was created; nothing was modified."
        MemoryLog.log(context, "ReadableChatBackup", "error", msg)
        Logger.log(context, ChatPreferences.CORRUPT_DATA_LOG_TYPE, "ReadableChatBackup", "error", msg)
    }
}
