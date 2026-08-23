/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

import org.teslasoft.assistant.preferences.includes.PersistentIncludeContext
import org.teslasoft.assistant.ui.adapters.chat.ChatAdapter

/** Durable-prefix guard used after the chat screen has detached. */
object ManualCompactionStorageGuard {
    data class Row(
        val isBot: Boolean,
        val message: String,
        val includes: String,
        val generatedImage: String
    )

    fun rows(messages: List<HashMap<String, Any>>): List<Row> = messages
        .asSequence()
        .filterNot {
            it[ChatAdapter.KEY_IMAGE_CONFIRMATION] == true ||
                it[ChatAdapter.KEY_IMAGE_PROGRESS] == true
        }
        .map {
            Row(
                isBot = it["isBot"] == true,
                message = it["message"]?.toString().orEmpty(),
                includes = it[PersistentIncludeContext.INCLUDES_KEY]?.toString().orEmpty(),
                generatedImage = it["generatedImage"]?.toString().orEmpty()
            )
        }
        .toList()

    fun prefixStillCurrent(frozenPrefix: List<Row>, current: List<Row>): Boolean =
        current.size >= frozenPrefix.size && current.take(frozenPrefix.size) == frozenPrefix
}
