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

package org.teslasoft.assistant.preferences.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.util.Hash

/**
 * Policy layer between the chat funnel and the transcript queue (Phase 2 of
 * memory-system-integration-plan.md). ChatActivity calls [recordTurn] once per
 * completed turn from the single generation funnel; everything here decides
 * whether and how that turn is captured:
 *
 *  - Store not provisioned -> no-op (capture never creates the encrypted DB).
 *  - Chat excluded by the user -> no capture at all, from this point forward.
 *  - Memory kill switch off for the chat -> captured but marked excluded
 *    (reversible: flip the exclusion back and the content is already there).
 *  - Companion memory_participation 'none' -> captured but marked excluded
 *    (the spec: such transcripts "arrive pre-excluded"). 'global_only' is a
 *    Archivist-side rule and captures normally.
 *
 * Always best-effort and always on the caller's worker thread: a capture
 * failure must never disturb the conversation.
 */
object TranscriptRecorder {

    fun recordTurn(
        context: Context,
        chatId: String,
        personaId: String,
        userMessage: String,
        assistantMessage: String,
        modelTag: String,
        quickSettingsJson: String?,
        memoryEnabled: Boolean,
        excludedByUser: Boolean,
        assistantComplete: Boolean = true
    ) {
        try {
            // Diagnostic breadcrumbs: capture is otherwise invisible, so each
            // early exit and the final DB outcome are logged to the Event log.
            if (chatId.isBlank() || userMessage.isBlank() || assistantMessage.isBlank()) {
                MemoryLog.log(context, "Transcript", "info",
                    "skip: blank field (chatId=${chatId.isNotBlank()} user=${userMessage.isNotBlank()} reply=${assistantMessage.isNotBlank()})")
                return
            }
            if (!MemoryStore.isProvisioned(context)) {
                MemoryLog.log(context, "Transcript", "info", "skip: memory store not provisioned")
                return
            }
            if (excludedByUser) {
                MemoryLog.log(context, "Transcript", "info", "skip: chat is set to \"Don't archive\"")
                return
            }

            val store = MemoryStore.getInstance(context)

            var companionId: String? = null
            var participation = "full"
            if (personaId.isNotBlank()) {
                // A chat with a persona always resolves to a companion: the
                // record is created on first contact if the bootstrap hasn't
                // covered it. personaId is already Hash.hash(label); guard
                // against a raw label sneaking in by hashing on lookup miss.
                val companion = MemoryCompanionSync.ensureCompanionForPersona(context, personaId)
                    ?: MemoryCompanionSync.ensureCompanionForPersona(context, Hash.hash(personaId))
                if (companion != null) {
                    companionId = companion.companionId
                    participation = companion.memoryParticipation
                }
            }

            val markExcluded = !memoryEnabled || participation == "none"
            val outcome = store.appendTranscriptTurn(
                chatId = chatId,
                companionId = companionId,
                userMessage = userMessage,
                assistantMessage = assistantMessage,
                modelTag = modelTag,
                quickSettingsJson = quickSettingsJson,
                markExcluded = markExcluded,
                assistantComplete = assistantComplete
            )
            MemoryLog.log(context, "Transcript", "info",
                "captured chat=$chatId companion=${companionId ?: "none"} memOn=$memoryEnabled complete=$assistantComplete -> $outcome")
        } catch (e: Exception) {
            MemoryLog.log(context, "Transcript", "error", "Turn capture failed: ${e.message}")
        }
    }

    /**
     * The result of a one-time backfill pass. [completed] is true ONLY when the
     * whole pass ran without error — every eligible chat was either imported or
     * correctly skipped (already had a transcript, or was empty). The caller
     * sets the run-once completion flag only when [completed] is true, so any
     * failure — store unprovisioned/unreadable, chat list or serialization
     * error, a DB insert that reported failure, or an exception that cut the
     * loop short — leaves the flag unset and the backfill retries on the next
     * start. Retries are idempotent: a chat that already has any transcript is
     * skipped, so an already-imported chat is never duplicated. [created] is how
     * many chats this pass added.
     */
    data class BackfillOutcome(val completed: Boolean, val created: Int)

    /**
     * One-time backfill of chats that existed before the memory system: each
     * chat's current history becomes a single 'imported' pending transcript so
     * pre-update conversations are eligible for review too. Skips chats set to
     * "Archive" off (excluded) and any chat that already has a transcript, so
     * it is safe to run more than once (idempotent — see [BackfillOutcome]).
     */
    fun backfillExistingChats(context: Context): BackfillOutcome {
        return try {
            // Not provisioned means there is no store to backfill into and no
            // determination was made — not a completed pass, so the flag stays
            // unset. (The startup caller already guards on provisioning.)
            if (!MemoryStore.isProvisioned(context)) return BackfillOutcome(false, 0)
            val store = MemoryStore.getInstance(context)
            val chatPrefs = ChatPreferences.getChatPreferences()
            var created = 0
            // A single insert reporting failure means the pass did not fully
            // complete: leave the flag unset so that chat (which still has no
            // transcript) re-qualifies and is retried next start.
            var allSucceeded = true
            for (chat in chatPrefs.getChatList(context)) {
                val name = chat["name"] ?: continue
                val chatId = Hash.hash(name)
                if (store.hasAnyTranscriptForChat(chatId)) continue

                val prefs = Preferences.getPreferences(context, chatId)
                val messages = chatPrefs.getChatById(context, chatId)
                if (messages.isEmpty()) continue

                val turns = JSONArray()
                for (m in messages) {
                    val content = m["message"]?.toString() ?: continue
                    if (content.isBlank()) continue
                    val role = if (m["isBot"] == true) "assistant" else "user"
                    val turn = JSONObject().put("role", role).put("content", content)
                    // An assistant reply that never finished streaming is marked
                    // so the Archivist won't mine a truncated fragment as fact.
                    // Absent "complete" means complete (legacy rows, user turns).
                    if (role == "assistant" &&
                        !org.teslasoft.assistant.preferences.MessageCompletionState.isComplete(
                            m[org.teslasoft.assistant.preferences.MessageCompletionState.KEY_STATE]?.toString()
                        )
                    ) {
                        turn.put("complete", false)
                    }
                    turns.put(turn)
                }
                if (turns.length() == 0) continue

                val personaId = prefs.getPersonaId()
                val companionId = if (personaId.isNotBlank())
                    store.findCompanionByAppCharacterId(personaId)?.companionId else null

                // "Archive this chat" off => capture but mark excluded, same as
                // the live path; the row still exists so it can be re-included.
                val markExcluded = prefs.isChatExcludedFromMemory() || !prefs.getChatMemoryEnabled()
                if (store.insertBackfillTranscript(chatId, companionId, turns.toString(), prefs.getModel(), markExcluded)) {
                    created++
                } else {
                    allSucceeded = false
                }
            }
            MemoryLog.log(context, "Backfill", "info",
                "Backfilled $created existing chat(s) into the review queue" +
                    (if (allSucceeded) "." else " (some inserts failed; will retry next start)."))
            BackfillOutcome(allSucceeded, created)
        } catch (e: Exception) {
            // Any exception — unreadable store, chat list failure, serialization
            // error — means the pass did not complete: report not-completed so
            // the completion flag stays unset and the pass retries next start.
            MemoryLog.log(context, "Backfill", "error", "Backfill failed: ${e.message}")
            BackfillOutcome(false, 0)
        }
    }
}
