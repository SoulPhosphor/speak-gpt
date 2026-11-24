/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.preferences.MessageVisibility
import org.teslasoft.assistant.preferences.memory.scan.ScanMessageScope
import org.teslasoft.assistant.ui.chat.ChatSpeakerNames
import org.teslasoft.assistant.util.summarizer.SummarizerController

/** Who wrote a message, as recorded on its passages and in its search text. */
enum class Speaker(val stored: String) {
    USER("user"),
    AI("ai");

    companion object {
        fun of(isBot: Boolean): Speaker = if (isBot) AI else USER
        fun fromStored(value: String): Speaker = entries.first { it.stored == value }
    }
}

/**
 * One stored chat message as the conversation index sees it (memory
 * development plan G.4, G.5a rule 1). The caller passes every stored message
 * of the chat, in stored order, so positions and exchanges are exact.
 *
 * [modelText] is the Summarizer's model-facing text for the message
 * ([SummarizerController.Entry.text]): the words the AI would be sent, or ""
 * for a message that is not sent. [scope] is the scope recorded on the
 * message when its turn finished, or null when it is unknown (G.7).
 */
data class IndexSourceMessage(
    val messageId: String,
    val isBot: Boolean,
    val modelText: String,
    val hiddenFromAi: Boolean,
    val completionState: String?,
    val timeMillis: Long?,
    val scope: ScanMessageScope?,
    /**
     * The speaker name saved on the message when it was written (the user
     * name it was sent under, or the companion name of the reply): its
     * historical name, not the identity's current display name. Null when
     * none was saved. Supplementary only; permanent IDs in [scope] are the
     * identity.
     */
    val recordedSpeakerName: String? = null
) {
    companion object {
        /**
         * Pairs one stored message with its Summarizer snapshot entry.
         *
         * The reply version the AI is sent is the one whose fields the stored
         * message's top-level keys mirror: `message`, `state` and
         * `memoryScope` always hold the canonical version
         * (`canonicalVariant` for regenerated replies, the primary slot's
         * canonical version for parallel response groups). Browsing another
         * version changes only display state, so it changes nothing here.
         *
         * Returns null when the entry does not belong to [stored].
         */
        fun from(stored: Map<String, Any>, entry: SummarizerController.Entry): IndexSourceMessage? {
            val id = MessageIdentity.idOf(stored)
            if (id != entry.id) return null
            return IndexSourceMessage(
                messageId = id,
                isBot = entry.isBot,
                modelText = entry.text,
                hiddenFromAi = MessageVisibility.isHidden(stored),
                completionState = stored[MessageCompletionState.KEY_STATE]?.toString(),
                timeMillis = entry.timeMillis,
                scope = ScanMessageScope.read(stored),
                recordedSpeakerName = stored[
                    if (entry.isBot) ChatSpeakerNames.COMPANION_NAME_KEY else ChatSpeakerNames.USER_NAME_KEY
                ]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            )
        }
    }
}

/**
 * The title given to the embedding model for a message's passages (G.5b
 * cutter rule 7). Each message gets its own, so identity-aware titles can be
 * tested later (Steps 14–15). API message roles are never affected.
 */
fun interface SpeakerTitlePolicy {
    fun titleFor(message: IndexSourceMessage): String

    companion object {
        const val HUMAN = "Human"
        const val COMPANION = "Companion"

        /** The owner's default (2026-11-23): Human for the user, Companion for the AI. */
        val DEFAULT = SpeakerTitlePolicy { if (it.isBot) COMPANION else HUMAN }
    }
}

/** G.5a rules 1 and 7: which messages may have passages. */
object IndexEligibility {

    /**
     * A message is eligible only when it has a permanent ID, is visible to
     * the AI, is finished (an AI reply that is streaming, failed, stopped or
     * interrupted is not), and has model-facing text to index. The text is
     * always the version the AI is sent ([IndexSourceMessage.from]).
     */
    fun isEligible(message: IndexSourceMessage): Boolean =
        message.messageId.isNotBlank() &&
            !message.hiddenFromAi &&
            (!message.isBot || MessageCompletionState.isComplete(message.completionState)) &&
            message.modelText.isNotBlank()

    /**
     * Messages that would be indexed if they had a permanent ID (G.5a rule 7):
     * the chat has not been opened since IDs were introduced (G28).
     */
    fun unidentified(messages: List<IndexSourceMessage>): Int =
        messages.count { it.messageId.isBlank() && isEligible(it.copy(messageId = ID_PROBE)) }

    /**
     * The messages the updater indexes: each eligible message, and a repeated
     * permanent ID only once. Every one of them gets at least one passage.
     */
    fun indexedMessages(messages: List<IndexSourceMessage>): Int {
        val seen = HashSet<String>()
        return messages.count { isEligible(it) && seen.add(it.messageId) }
    }

    /** Stands in for a missing permanent ID when asking whether a message could otherwise be indexed. */
    private const val ID_PROBE = "unidentified"
}
