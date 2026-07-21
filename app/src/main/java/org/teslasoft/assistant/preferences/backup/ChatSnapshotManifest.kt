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

import com.google.gson.Gson
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.util.Hash
import android.content.Context
import java.security.MessageDigest

/**
 * Builds a transactionally-consistent MANIFEST of the chat set for the chats
 * recovery artifact (Build Phase 2 item 6). This is the lock-coordinated read
 * the artifact is built from; the encrypted-file archive + SAF destination
 * write are the next pass.
 *
 * VERIFIED LOCK ORDER (July 21 2026 — from OutageReconciler.kt:168 and the
 * `@Synchronized` methods of SecurePrefs): `CHAT_LIST_LOCK` is ALWAYS acquired
 * BEFORE the SecurePrefs monitor and NEVER the reverse.
 * `SecurePrefs.reconcileOutageAtStartup` holds `CHAT_LIST_LOCK` (passed into
 * `OutageReconciler.reconcile`) while its `Files` callbacks take
 * `synchronized(this@SecurePrefs)`, and no `@Synchronized` SecurePrefs method
 * reaches back for `CHAT_LIST_LOCK`. The only documented ordering in
 * ChatPreferences is `CHAT_LIST_LOCK -> RenameJournal monitor`; SecurePrefs was
 * NOT previously written into the chain but the reconcile path establishes it
 * safely as the innermost lock. Therefore holding `CHAT_LIST_LOCK` across these
 * result-typed reads (which reach through SecurePrefs) cannot invert with any
 * existing path, and the whole read runs inside the lock so no chat mutation
 * can interleave — the list, histories and per-chat settings form ONE
 * consistent set.
 *
 * Non-authoritative chats (LOCKED / CORRUPT / FAILED — Round 4) are recorded as
 * identifier-only, unavailable entries and mark the manifest incomplete; a
 * masked empty history is never fabricated (the same rule MemoryExporter
 * follows). A non-authoritative chat LIST pauses ONLY this artifact and yields
 * an incomplete manifest with no entries — it never looks like "no chats".
 *
 * The manifest carries a version and, per available chat, a SHA-256 of the
 * chat's serialized logical content, so the eventual archive can be verified by
 * hash rather than trusting a readable ZIP alone (item 6).
 */
object ChatSnapshotManifest {

    const val MANIFEST_VERSION = 1

    data class ChatEntry(
        val chatId: String,
        val name: String,
        val available: Boolean,
        val contentHash: String?
    )

    data class Manifest(
        val version: Int,
        val listAuthoritative: Boolean,
        val complete: Boolean,
        val chats: List<ChatEntry>
    )

    fun build(context: Context): Manifest {
        val gson = Gson()
        val chatPreferences = ChatPreferences.getChatPreferences()

        // The entire read runs inside CHAT_LIST_LOCK so no chat mutation can
        // interleave; the verified lock order lets us reach through SecurePrefs
        // (the result-typed reads below) without any inversion.
        synchronized(ChatPreferences.CHAT_LIST_LOCK) {
            val listResult = chatPreferences.getChatListResult(context, includeFirstMessage = false)
            if (!ChatStorageHealth.isAuthoritative(listResult.state)) {
                return Manifest(MANIFEST_VERSION, listAuthoritative = false, complete = false, chats = emptyList())
            }

            var complete = true
            val entries = ArrayList<ChatEntry>()
            for (chat in listResult.chats) {
                val name = chat["name"] ?: continue
                val chatId = Hash.hash(name)
                val history = chatPreferences.getChatByIdResult(context, chatId)
                if (ChatStorageHealth.isAuthoritative(history.state)) {
                    val payload = gson.toJson(history.messages)
                    entries.add(ChatEntry(chatId, name, available = true, contentHash = sha256(payload)))
                } else {
                    // Round 4: never fabricate an empty history for an
                    // unreadable chat; record it identifier-only and mark the
                    // whole manifest incomplete.
                    complete = false
                    entries.add(ChatEntry(chatId, name, available = false, contentHash = null))
                }
            }
            return Manifest(MANIFEST_VERSION, listAuthoritative = true, complete = complete, chats = entries)
        }
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
