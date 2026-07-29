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

package org.teslasoft.assistant.imagegen

/**
 * Spoken approval for a pending image confirmation
 * (image-generation-rebuild-plan.md §5, owner ruling 2026-07-29): the next
 * recognized utterance answers the request. An utterance matching
 * "create it" approves, one matching "cancel" denies, and anything else
 * denies the image AND is handled as a normal message, so an
 * over-enthusiastic model cannot derail the conversation.
 */
object ImageConfirmationSpeech {

    enum class Answer {
        APPROVE,
        DENY,

        /** Deny the image; the words continue as a normal message. */
        DENY_AND_CONTINUE
    }

    fun interpret(utterance: String): Answer {
        val normalized = utterance.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return when (normalized) {
            "create it" -> Answer.APPROVE
            "cancel" -> Answer.DENY
            else -> Answer.DENY_AND_CONTINUE
        }
    }
}
