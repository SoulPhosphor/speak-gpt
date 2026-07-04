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
import org.teslasoft.assistant.preferences.Logger
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
        excludedByUser: Boolean
    ) {
        try {
            if (chatId.isBlank() || userMessage.isBlank() || assistantMessage.isBlank()) return
            if (!MemoryStore.isProvisioned(context)) return
            if (excludedByUser) return

            val store = MemoryStore.getInstance(context)

            var companionId: String? = null
            var participation = "full"
            if (personaId.isNotBlank()) {
                // personaId is already Hash.hash(label); guard against a raw
                // label sneaking in by hashing only when lookup misses.
                val companion = store.findCompanionByAppCharacterId(personaId)
                    ?: store.findCompanionByAppCharacterId(Hash.hash(personaId))
                if (companion != null) {
                    companionId = companion.companionId
                    participation = companion.memoryParticipation
                }
            }

            val markExcluded = !memoryEnabled || participation == "none"
            store.appendTranscriptTurn(
                chatId = chatId,
                companionId = companionId,
                userMessage = userMessage,
                assistantMessage = assistantMessage,
                modelTag = modelTag,
                quickSettingsJson = quickSettingsJson,
                markExcluded = markExcluded
            )
        } catch (e: Exception) {
            Logger.log(context, "event", "Transcript", "ERROR", "Turn capture failed: ${e.message}")
        }
    }
}
