/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.preferences.MessageVisibility
import org.teslasoft.assistant.preferences.memory.scan.ScanMessageScope
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
    val scope: ScanMessageScope?
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
                scope = ScanMessageScope.read(stored)
            )
        }
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
}
