/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.ui.chat

import android.content.Context
import com.aallam.ktoken.Encoding
import com.aallam.ktoken.Tokenizer
import org.teslasoft.assistant.preferences.MessageBookmarks
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.ui.adapters.chat.ChatAdapter

/**
 * Builds a chat export file from a list of conversation messages. Export Chat
 * passes the whole conversation; Selection Mode's export passes only the
 * selected messages. The chat screen asks where to save the result.
 */
internal object ChatExportBuilder {

    /** The exportable messages, in order, with each speaker's displayed name. */
    fun snapshot(
        context: Context,
        messages: List<HashMap<String, Any>>,
        currentCompanion: String?,
        chatUsername: String?
    ): List<ChatExportMessage> = messages
        .filterNot {
            it[ChatAdapter.KEY_IMAGE_CONFIRMATION] == true ||
                it[ChatAdapter.KEY_IMAGE_PROGRESS] == true
        }
        .map { message ->
            val isCompanion = message["isBot"] == true ||
                message["isBot"]?.toString() == "true"
            ChatExportMessage(
                isCompanion = isCompanion,
                name = if (isCompanion) {
                    ChatSpeakerNames.companionName(context, message, currentCompanion)
                } else {
                    ChatSpeakerNames.displayedUserName(context, message, chatUsername)
                },
                bookmarked = MessageBookmarks.isBookmarked(message),
                messageId = MessageIdentity.idOf(message),
                content = message["message"]?.toString().orEmpty(),
                timestampMillis = message[ChatAdapter.KEY_MESSAGE_TIME]
                    ?.toString()
                    ?.toLongOrNull(),
                model = if (isCompanion) {
                    message[ChatAdapter.KEY_MESSAGE_MODEL]?.toString()
                } else {
                    null
                },
                thinking = if (isCompanion) {
                    message[ChatAdapter.KEY_MESSAGE_REASONING]?.toString()
                } else {
                    null
                }
            )
        }

    /** The file's bytes in the chosen format. Runs off the main thread. */
    suspend fun bytes(
        context: Context,
        snapshot: List<ChatExportMessage>,
        options: ChatExportOptions,
        folderName: String?
    ): ByteArray {
        val tokenized = if (
            options.includeUserTokenCount || options.includeCompanionTokenCount
        ) {
            val tokenizer = Tokenizer.of(Encoding.CL100K_BASE)
            snapshot.map { message ->
                val includeTokens = if (message.isCompanion) {
                    options.includeCompanionTokenCount
                } else {
                    options.includeUserTokenCount
                }
                if (!includeTokens) {
                    message
                } else {
                    message.copy(
                        tokenCount = runCatching {
                            tokenizer.encode(message.content).size
                        }.getOrNull()
                    )
                }
            }
        } else {
            snapshot
        }

        return when (options.format) {
            ChatExportFormat.JSON ->
                ChatExportFormatter.formatJson(tokenized, options, folderName = folderName)
                    .toByteArray(Charsets.UTF_8)
            ChatExportFormat.TEXT,
            ChatExportFormat.MARKDOWN ->
                ChatExportFormatter.formatText(tokenized, options)
                    .toByteArray(Charsets.UTF_8)
            ChatExportFormat.PDF ->
                ChatExportPdfWriter.toBytes(context, tokenized, options)
        }
    }
}
