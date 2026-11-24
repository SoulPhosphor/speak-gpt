/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.ui.chat

import android.content.Context
import org.teslasoft.assistant.R
import org.teslasoft.assistant.imagegen.GeneratedImageMetadata
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.preferences.MessageVisibility
import org.teslasoft.assistant.preferences.includes.CanonicalConversationMessage
import org.teslasoft.assistant.preferences.includes.ChatInclude
import org.teslasoft.assistant.preferences.includes.SummarizerSafeIncludeProjectionBuilder
import org.teslasoft.assistant.ui.adapters.chat.ChatAdapter
import org.teslasoft.assistant.ui.chat.parallel.ParallelResponseSelection
import org.teslasoft.assistant.util.summarizer.SummarizerController

/**
 * The text of a stored message as the AI is sent it, which is not always
 * what the user sees. The chat screen builds its requests and its
 * Summarizer snapshot from these functions, and the conversation index reads
 * stored chats through the same functions, so both always see the same words.
 */
object ModelFacingMessageText {

    /** Canonical conversation text before either Include projection is added. */
    fun conversationText(context: Context, message: Map<String, Any>): String {
        val content = message["message"].toString()
        if (message["isBot"] == true) {
            // A completed generated image is stored to the model as a file
            // marker, which means nothing to the model. Replace it with a plain
            // sentence saying an image exists here and who made it, using the
            // token-saving summary when one exists (owner request, Aug 16 2026).
            val groupImage = ParallelResponseSelection.canonicalImage(message)
            val meta = GeneratedImageMetadata.fromJson(groupImage ?: message[GeneratedImageMetadata.KEY]?.toString())
            if (groupImage != null && meta?.status == GeneratedImageMetadata.STATUS_COMPLETE)
                return listOf(content, imageInjectionSentence(context, meta)).filter { it.isNotBlank() }.joinToString("\n\n")
            if (meta != null && meta.status == GeneratedImageMetadata.STATUS_COMPLETE) {
                return imageInjectionSentence(context, meta)
            }
            if (meta == null && content.trimStart().startsWith("~file:")) {
                return context.getString(R.string.image_gen_injection_legacy)
            }
            if (content.isNotBlank() && MessageCompletionState.isIncomplete(
                    message[MessageCompletionState.KEY_STATE]?.toString())) {
                return content + "\n\n" + context.getString(R.string.message_incomplete_model_note)
            }
            return content
        }
        return content
    }

    fun imageInjectionSentence(context: Context, meta: GeneratedImageMetadata): String {
        val text = meta.effectiveSummary() ?: meta.prompt
        if (text.isBlank()) return context.getString(R.string.image_gen_injection_legacy)
        val res = if (meta.initiatedByUser()) {
            R.string.image_gen_injection_user
        } else {
            R.string.image_gen_injection_model
        }
        return context.getString(res, text)
    }

    /** A message hidden from the AI keeps its slot (so indexes stay aligned
     *  with the fold-in bookmark) but contributes no words or attachments. */
    fun canonicalMessage(context: Context, message: Map<String, Any>): CanonicalConversationMessage {
        val isBot = message["isBot"] == true
        if (MessageVisibility.isHidden(message)) {
            return CanonicalConversationMessage(isBot = isBot, text = "")
        }
        return CanonicalConversationMessage(
            isBot = isBot,
            text = conversationText(context, message),
            includes = if (isBot) emptyList() else ChatInclude.listFromJson(message[INCLUDES_KEY]?.toString())
        )
    }

    /** The stored messages, leaving out image confirmation and progress cards. */
    fun storedMessages(messages: List<Map<String, Any>>): List<Map<String, Any>> =
        messages.filterNot {
            it[ChatAdapter.KEY_IMAGE_CONFIRMATION] == true || it[ChatAdapter.KEY_IMAGE_PROGRESS] == true
        }

    /**
     * One Summarizer snapshot entry per stored message (see [storedMessages]),
     * in order; blank entries advance the fold-in bookmark without being sent.
     * Each entry is paired with the stored message it was made from.
     */
    fun summarizerEntries(
        context: Context,
        messages: List<Map<String, Any>>
    ): List<Pair<Map<String, Any>, SummarizerController.Entry>> {
        val stored = storedMessages(messages)
        val canonical = stored.map { canonicalMessage(context, it) }
        return SummarizerSafeIncludeProjectionBuilder.summarizerConversation(canonical).mapIndexed { index, projected ->
            val message = stored[index]
            message to SummarizerController.Entry(
                isBot = projected.isBot,
                text = projected.text,
                id = MessageIdentity.idOf(message),
                timeMillis = message[ChatAdapter.KEY_MESSAGE_TIME]?.toString()?.toLongOrNull()
            )
        }
    }

    private const val INCLUDES_KEY = "includes"
}
