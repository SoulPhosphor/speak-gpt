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
        worldId: String? = null,
        campaignId: String? = null,
        roleplayCharacterId: String? = null,
        userPersonaId: String? = null
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

            // Scene attribution (Phase 6 prep; campaign amendment item 5 +
            // rules §3): stamp the turn's roleplay scene onto the transcript
            // row so the Archivist can hold the fiction firewall and file
            // campaign state under the right continuity. Resolution mirrors
            // the enforcer (spec §2/§8b): a selected campaign's OWN links
            // outrank the chat's world/character picks — there is no per-chat
            // override. Ids that no longer resolve degrade to null rather
            // than fail the insert (the transcript columns carry FKs, and a
            // capture failure must never disturb a turn).
            // A resolution failure degrades to a null scene, never a lost
            // capture (the row is still worth keeping without attribution).
            var campaign: CampaignRecord? = null
            var sceneWorldId: String? = null
            var sceneCharacterId: String? = null
            var scenePersonaId: String? = null
            try {
                campaign = campaignId?.takeIf { it.isNotBlank() }?.let { store.getCampaign(it) }
                sceneWorldId = (campaign?.worldId ?: worldId?.takeIf { it.isNotBlank() })
                    ?.takeIf { store.getWorld(it) != null }
                sceneCharacterId = (campaign?.roleplayCharacterId
                    ?: roleplayCharacterId?.takeIf { it.isNotBlank() })
                    ?.takeIf { store.getRoleplayCharacter(it) != null }
                scenePersonaId = userPersonaId?.takeIf { it.isNotBlank() }
                    ?.takeIf { store.getUserPersona(it) != null }
            } catch (e: Exception) {
                MemoryLog.log(context, "Transcript", "error", "Scene resolution failed (capturing without scene): ${e.message}")
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
                worldId = sceneWorldId,
                campaignId = campaign?.campaignId,
                roleplayCharacterId = sceneCharacterId,
                userPersonaId = scenePersonaId
            )
            val sceneNote = if (campaign == null && sceneWorldId == null && sceneCharacterId == null && scenePersonaId == null)
                "none"
            else
                "w=${sceneWorldId ?: "-"} c=${campaign?.campaignId ?: "-"} rp=${sceneCharacterId ?: "-"} up=${scenePersonaId ?: "-"}"
            MemoryLog.log(context, "Transcript", "info",
                "captured chat=$chatId companion=${companionId ?: "none"} memOn=$memoryEnabled scene=$sceneNote -> $outcome")
        } catch (e: Exception) {
            MemoryLog.log(context, "Transcript", "error", "Turn capture failed: ${e.message}")
        }
    }

    /**
     * One-time backfill of chats that existed before the memory system: each
     * chat's current history becomes a single 'imported' pending transcript so
     * pre-update conversations are eligible for review too. Skips chats set to
     * "Archive" off (excluded) and any chat that already has a transcript, so
     * it is safe to run more than once. Returns how many chats were added.
     */
    fun backfillExistingChats(context: Context): Int {
        return try {
            if (!MemoryStore.isProvisioned(context)) return 0
            val store = MemoryStore.getInstance(context)
            val chatPrefs = ChatPreferences.getChatPreferences()
            var created = 0
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
                    turns.put(JSONObject().put("role", role).put("content", content))
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
                }
            }
            MemoryLog.log(context, "Backfill", "info", "Backfilled $created existing chat(s) into the review queue.")
            created
        } catch (e: Exception) {
            MemoryLog.log(context, "Backfill", "error", "Backfill failed: ${e.message}")
            0
        }
    }
}
