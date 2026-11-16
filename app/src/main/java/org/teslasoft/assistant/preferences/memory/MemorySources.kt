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

/**
 * Where a memory came from (memory provenance, development plan M.3): the
 * conversation the Memory Scanner read, or the user's own entry. Stored in
 * `memory_sources`, removed with its memory. The message, speaker, Glamour
 * and time fields are kept available for later use and are not filled now:
 * the scanner is not asked which message a memory came from.
 *
 * Sources are metadata for the Memory Browser only: never sent to an AI model
 * and never part of a memory's embedding text. No message text is stored.
 */
data class MemorySource(
    val sourceId: String,
    val memoryId: String,
    /** [KIND_CHAT_MESSAGE], [KIND_CONVERSATION], [KIND_USER_ENTRY], [KIND_IMPORT] or [KIND_UNKNOWN]. */
    val kind: String,
    val chatId: String? = null,
    /** The permanent message ID, when known. */
    val messageId: String? = null,
    /** [SPEAKER_USER], [SPEAKER_AI], [SPEAKER_OTHER], [SPEAKER_UNKNOWN], or null when not recorded. */
    val speaker: String? = null,
    /** The name the chat showed for the message when the source was recorded. */
    val speakerName: String? = null,
    /** [PROFILE_COMPANION] or [PROFILE_ROLEPLAY_CHARACTER] when the speaker name came from one. */
    val speakerProfileKind: String? = null,
    val speakerProfileId: String? = null,
    /** The Glamour in use for a user message, and its last known profile name. */
    val glamourId: String? = null,
    val glamourName: String? = null,
    /** When the message was sent (or the user's own entry was created), ISO-8601. */
    val statedAt: String? = null,
    val attachedAt: String,
    /** [ATTACHED_MEMORY_ASSISTANT], [ATTACHED_CARRIED], [ATTACHED_USER_ENTRY] or [ATTACHED_IMPORT]. */
    val attachedBy: String
) {
    companion object {
        const val KIND_CHAT_MESSAGE = "chat_message"
        const val KIND_CONVERSATION = "conversation"
        const val KIND_USER_ENTRY = "user_entry"
        const val KIND_IMPORT = "import"
        const val KIND_UNKNOWN = "unknown"

        const val SPEAKER_USER = "user"
        const val SPEAKER_AI = "ai"
        const val SPEAKER_OTHER = "other"
        const val SPEAKER_UNKNOWN = "unknown"

        const val PROFILE_COMPANION = "companion"
        const val PROFILE_ROLEPLAY_CHARACTER = "roleplay_character"

        const val ATTACHED_MEMORY_ASSISTANT = "memory_assistant"
        const val ATTACHED_CARRIED = "carried"
        const val ATTACHED_USER_ENTRY = "user_entry"
        const val ATTACHED_IMPORT = "import"

        const val ID_PREFIX = "src-"
    }
}

/** Pure rules for recording and showing memory sources (M.3). */
object MemorySourceRules {

    /** The Memory Scanner's source: the whole conversation it read. */
    fun conversationOnly(sourceId: String, memoryId: String, chatId: String, now: String) =
        MemorySource(
            sourceId = sourceId,
            memoryId = memoryId,
            kind = MemorySource.KIND_CONVERSATION,
            chatId = chatId,
            attachedAt = now,
            attachedBy = MemorySource.ATTACHED_MEMORY_ASSISTANT
        )

    /** A memory the user typed in: no conversation, dated at creation. */
    fun userEntry(sourceId: String, memoryId: String, createdAt: String) = MemorySource(
        sourceId = sourceId,
        memoryId = memoryId,
        kind = MemorySource.KIND_USER_ENTRY,
        statedAt = createdAt,
        attachedAt = createdAt,
        attachedBy = MemorySource.ATTACHED_USER_ENTRY
    )

    /** Display order: oldest attachment first. */
    fun displayOrder(sources: List<MemorySource>): List<MemorySource> =
        sources.sortedWith(compareBy<MemorySource>({ it.attachedAt }, { it.sourceId }))
}
