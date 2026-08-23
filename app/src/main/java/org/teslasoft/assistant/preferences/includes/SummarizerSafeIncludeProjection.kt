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

package org.teslasoft.assistant.preferences.includes

/**
 * Phase 6.2's projection contract version. A persisted rolling summary made
 * before this version may contain attachment payload text and is therefore
 * incompatible with the separate persistent-Include layer.
 */
object SummarizerProjectionContract {
    const val VERSION = 3
}

/** One canonical stored conversation message before request projection. */
data class CanonicalConversationMessage(
    val isBot: Boolean,
    val text: String,
    val includes: List<ChatInclude> = emptyList()
)

/** One model-facing conversation-layer message. */
data class ProjectedConversationMessage(
    val isBot: Boolean,
    val text: String,
    /** Non-empty only on the Summarizer-off inline path, for FULL images. */
    val inlineIncludes: List<ChatInclude> = emptyList()
)

/** One user-authority Include payload in its stable activation slot. */
data class PersistentIncludeUnit(val include: ChatInclude)

/**
 * An immutable, provider-neutral projection of one canonical chat snapshot.
 * No unit is persisted separately: ownership remains exclusively on the
 * original user message and the persistent units are derived for each send.
 */
data class SummarizerSafeIncludeProjection(
    val persistentIncludes: List<PersistentIncludeUnit>,
    val conversation: List<ProjectedConversationMessage>
)

/**
 * Builds the Summarizer-safe dual projection from one immutable canonical
 * snapshot. Summarizer-off deliberately retains the pre-6.2 inline behavior.
 */
object SummarizerSafeIncludeProjectionBuilder {

    fun build(
        messages: List<CanonicalConversationMessage>,
        summarizerActive: Boolean,
        foldedCount: Int
    ): SummarizerSafeIncludeProjection {
        if (!summarizerActive) {
            return SummarizerSafeIncludeProjection(
                persistentIncludes = emptyList(),
                conversation = messages.mapNotNull(::inlineMessage).toList()
            )
        }

        // The first canonical occurrence owns the logical slot. Rebuilding
        // after a form change replaces that slot's payload without moving it.
        val ownerById = owners(messages)

        val persistent = ownerById.values.map { PersistentIncludeUnit(it.second) }
        val start = foldedCount.coerceIn(0, messages.size)
        val conversation = ArrayList<ProjectedConversationMessage>()
        val allConversation = referenceConversation(messages, ownerById)
        for (messageIndex in start until allConversation.size) {
            val message = allConversation[messageIndex]
            if (message.text.isBlank()) continue
            conversation.add(message)
        }

        return SummarizerSafeIncludeProjection(persistent.toList(), conversation.toList())
    }

    /**
     * Full conversation-only view used as Summarizer fold-in input. Blank
     * entries are retained so persisted bookmark indexes remain aligned with
     * canonical stored messages.
     */
    fun summarizerConversation(
        messages: List<CanonicalConversationMessage>
    ): List<ProjectedConversationMessage> = messages.map { message ->
        // Attachments remain exclusively in the independently projected,
        // user-controlled Include layer. Neither their payload nor even an
        // attachment reference is material for summary/compaction.
        ProjectedConversationMessage(
            isBot = message.isBot,
            // Generated-image rows are attachment references too. Retain the
            // blank slot for bookmark alignment without exposing the local file.
            text = message.text.takeUnless { it.startsWith("~file:") }.orEmpty()
        )
    }

    private fun owners(
        messages: List<CanonicalConversationMessage>
    ): LinkedHashMap<String, Pair<Int, ChatInclude>> {
        val ownerById = LinkedHashMap<String, Pair<Int, ChatInclude>>()
        messages.forEachIndexed { messageIndex, message ->
            if (message.isBot) return@forEachIndexed
            for (include in message.includes) {
                ownerById.putIfAbsent(include.id, Pair(messageIndex, include))
            }
        }
        return ownerById
    }

    private fun referenceConversation(
        messages: List<CanonicalConversationMessage>,
        ownerById: Map<String, Pair<Int, ChatInclude>>
    ): List<ProjectedConversationMessage> = messages.mapIndexed { messageIndex, message ->
        if (message.isBot) {
            ProjectedConversationMessage(isBot = true, text = message.text)
        } else {
            val ownedHere = message.includes.filter { include ->
                ownerById[include.id]?.first == messageIndex
            }
            ProjectedConversationMessage(
                isBot = false,
                text = StableAttachmentReference.renderUserMessage(message.text, ownedHere)
            )
        }
    }

    private fun inlineMessage(
        message: CanonicalConversationMessage
    ): ProjectedConversationMessage? {
        if (message.isBot) {
            return message.text.takeIf { it.isNotBlank() }
                ?.let { ProjectedConversationMessage(isBot = true, text = it) }
        }
        val text = IncludeRenderer.renderUserMessage(message.text, message.includes)
        val hasImage = message.includes.any { it.hasLiveImageBytes() }
        if (text.isBlank() && !hasImage) return null
        return ProjectedConversationMessage(
            isBot = false,
            text = text,
            inlineIncludes = message.includes.toList()
        )
    }
}

/**
 * Minimal stable reference serialization for the conversation/Summarizer
 * layer. The stable Include id is the identity; the name is display context
 * only and is encoded so arbitrary characters cannot alter the wrapper.
 */
object StableAttachmentReference {

    fun renderUserMessage(text: String, includes: List<ChatInclude>): String {
        if (includes.isEmpty()) return text
        return buildString {
            append(text)
            for (include in includes) {
                if (isNotEmpty()) append("\n\n")
                append(serialize(include))
            }
        }
    }

    fun serialize(include: ChatInclude): String = buildString {
        append("<attachment-reference>{\"id\":\"")
        append(jsonString(include.id))
        append("\",\"kind\":\"")
        append(jsonString(include.kind.key))
        append("\",\"name\":\"")
        append(jsonString(include.fileName))
        append("\"}</attachment-reference>")
    }

    /** Stable identity plus this unit's one current payload representation. */
    fun renderPersistentPayload(include: ChatInclude): String {
        val payload = IncludeRenderer.renderUserMessage("", listOf(include))
        return if (payload.isBlank()) {
            serialize(include)
        } else {
            serialize(include) + "\n\n" + payload
        }
    }

    private fun jsonString(value: String): String = buildString(value.length) {
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<' -> append("\\u003c")
                '>' -> append("\\u003e")
                '&' -> append("\\u0026")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }
}
