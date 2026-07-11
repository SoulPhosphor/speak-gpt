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

package org.teslasoft.assistant.preferences

/**
 * The atomic core of a chat rename. A rename moves data across THREE
 * preferences files (the chat history file, the per-chat settings file, and
 * the chat-list pointer), and the old implementation cleared the source
 * before the destination was known to be written — a process kill in that
 * window silently destroyed the whole conversation, and the settings were
 * re-derived by hand-maintained copy blocks that drifted out of sync with
 * the real per-chat key set.
 *
 * Discipline here (the same copy → verify → only-then-clear order
 * SecurePrefs.migrateIfNeeded already uses):
 *
 *  1. Write the NEW chat history file and read it back to verify.
 *  2. Copy the WHOLE settings file (every key, wholesale — nothing is
 *     enumerated by hand, so a newly added per-chat setting can never be
 *     forgotten; nothing is re-derived from endpoint profiles) and verify
 *     the copy key-for-key.
 *  3. Only then flip the chat-list pointer — the moment the rename becomes
 *     visible. Every write is a synchronous commit, so this ordering holds
 *     on disk, not just in memory.
 *  4. Only after the pointer is durable, clear the old history file. A
 *     failure here is a WARNING (a harmless orphan file), never data loss.
 *
 * If any write or verification before step 3 fails, the new-side files are
 * best-effort removed and the caller keeps using the old id — the old chat
 * is untouched by construction, because nothing old is modified until
 * step 4. Interruption at any boundary leaves either "still the old chat,
 * fully intact" or "fully the new chat".
 *
 * Pure logic against [FileAccess] so the whole ladder — including failure
 * injection and kill-at-every-boundary — is unit-tested in app/src/test
 * (ChatRenameTransactionTest). The SharedPreferences-backed implementation
 * lives in ChatPreferences.
 */
object ChatRenameTransaction {

    const val CHAT_LIST_FILE = "chat_list"
    const val CHAT_LIST_KEY = "data"
    const val CHAT_CONTENT_KEY = "chat"

    fun chatFile(chatId: String) = "chat_$chatId"
    fun settingsFile(chatId: String) = "settings.$chatId"

    const val STAGE_WRITE_NEW_CHAT = "writing the new chat history file"
    const val STAGE_VERIFY_NEW_CHAT = "verifying the new chat history file"
    const val STAGE_COPY_SETTINGS = "copying the chat settings"
    const val STAGE_VERIFY_SETTINGS = "verifying the copied chat settings"
    const val STAGE_WRITE_LIST = "updating the chat list"

    /**
     * Minimal storage surface the transaction needs. Every mutation must be
     * a SYNCHRONOUS commit (return false on failure) — apply()-style async
     * writes would break the ordering guarantee the whole design rests on.
     */
    interface FileAccess {
        fun readAll(fileName: String): Map<String, Any?>
        fun readString(fileName: String, key: String): String?

        /** Replace the file's contents with exactly [entries] (clear + put
         *  in one committed edit). Clearing first matters: a previously
         *  deleted chat with the same name hash may have left stale settings
         *  under the target file, and stale keys absent from the source must
         *  not survive into the renamed chat. */
        fun replaceAll(fileName: String, entries: Map<String, Any?>): Boolean

        /** Write one key without touching the file's other keys. */
        fun writeString(fileName: String, key: String, value: String): Boolean

        fun clear(fileName: String): Boolean
    }

    data class Outcome(
        val success: Boolean,
        val failedStage: String? = null,
        val warnings: List<String> = emptyList()
    )

    fun rename(
        files: FileAccess,
        oldChatId: String,
        newChatId: String,
        newChatListJson: String
    ): Outcome {
        if (oldChatId == newChatId || oldChatId.isBlank() || newChatId.isBlank()) {
            return Outcome(false, "invalid chat ids (old=$oldChatId, new=$newChatId)")
        }

        // A missing history key reads as an empty conversation, never null —
        // the rename of a never-written chat must still produce a valid file.
        val history = files.readString(chatFile(oldChatId), CHAT_CONTENT_KEY) ?: "[]"

        if (!files.replaceAll(chatFile(newChatId), mapOf(CHAT_CONTENT_KEY to history))) {
            return abort(files, newChatId, STAGE_WRITE_NEW_CHAT)
        }
        if (files.readString(chatFile(newChatId), CHAT_CONTENT_KEY) != history) {
            return abort(files, newChatId, STAGE_VERIFY_NEW_CHAT)
        }

        // Null values can't be re-put into SharedPreferences; dropping them
        // is lossless (a null-valued key reads the same as an absent one).
        val settings = files.readAll(settingsFile(oldChatId)).filterValues { it != null }
        if (!files.replaceAll(settingsFile(newChatId), settings)) {
            return abort(files, newChatId, STAGE_COPY_SETTINGS)
        }
        val copied = files.readAll(settingsFile(newChatId))
        if (copied.keys != settings.keys || settings.any { (k, v) -> copied[k] != v }) {
            return abort(files, newChatId, STAGE_VERIFY_SETTINGS)
        }

        // The pointer flip: from here on the rename is visible and must not
        // be rolled back — everything the new id needs is verified on disk.
        if (!files.writeString(CHAT_LIST_FILE, CHAT_LIST_KEY, newChatListJson)) {
            return abort(files, newChatId, STAGE_WRITE_LIST)
        }

        val warnings = ArrayList<String>()
        if (!clearQuietly(files, chatFile(oldChatId))) {
            warnings.add(
                "the old chat history file (${chatFile(oldChatId)}) could not be cleared; " +
                    "the rename itself succeeded and no data is at risk"
            )
        }
        // The old settings file is deliberately left in place: live Preferences
        // instances may still be pointed at it for the rest of the turn (the
        // caller re-points them after this returns), and an orphaned settings
        // file is harmless.
        return Outcome(true, null, warnings)
    }

    /** Best-effort removal of the half-written new-side files, then failure.
     *  The old chat has not been touched on any abort path. */
    private fun abort(files: FileAccess, newChatId: String, stage: String): Outcome {
        clearQuietly(files, chatFile(newChatId))
        clearQuietly(files, settingsFile(newChatId))
        return Outcome(false, stage)
    }

    private fun clearQuietly(files: FileAccess, fileName: String): Boolean = try {
        files.clear(fileName)
    } catch (_: Exception) {
        false
    }
}
