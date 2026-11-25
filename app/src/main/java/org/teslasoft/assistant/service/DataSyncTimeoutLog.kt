/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.service

/**
 * What the app's data-sync services log when Android's background execution
 * time limit is reached (Android 15 and later call `Service.onTimeout`).
 * Wording approved by the owner on 2026-11-25: a service timeout is logged as
 * a timeout, never as a cancellation or an operation failure, and says what
 * becomes of the work. Only identifiers are written, never chat names or
 * content.
 */
object DataSyncTimeoutLog {

    /** One Summarizer-service operation: the chat's ID and what runs for it. */
    data class SummarizerOperation(val chatId: String, val kind: String)

    /** One image generation: the chat's ID and the image's ID. */
    data class ImageJob(val chatId: String, val imageId: String)

    enum class RestoreOperation(val text: String) {
        CHAT_RESTORE("chat restore from a backup archive"),
        UNIFIED_RESTORE("unified backup restore"),
        UNKNOWN("restore (operation not recorded)")
    }

    fun summarizer(operations: List<SummarizerOperation>): String = buildString {
        append("Summarizer Service: Android's background execution time limit was reached. ")
        append("The keep-alive service has stopped. ")
        append("Operations running: ")
        append(if (operations.isEmpty()) "none recorded" else operations.joinToString { "${it.kind} for chat ${it.chatId}" })
        append(". ")
        append(if (operations.size == 1) "The operation was" else "These operations were")
        append(" not explicitly cancelled, but further execution depends on Android allowing the app to run.")
    }

    fun imageGeneration(jobs: List<ImageJob>): String = buildString {
        append("Image Generation Service: Android's background execution time limit was reached. ")
        append("The keep-alive service has stopped. ")
        append("Image generations running: ")
        append(if (jobs.isEmpty()) "none recorded" else jobs.joinToString { "image ${it.imageId} for chat ${it.chatId}" })
        append(". ")
        append("Image generation was not explicitly cancelled, but further execution depends on Android allowing the app to run.")
    }

    fun restore(operation: RestoreOperation): String =
        "Restore Service: Android's background execution time limit was reached. The service has stopped. " +
            "Operation: ${operation.text}. Restore progress may be interrupted. " +
            "An incomplete restore will be handled by the existing recovery mechanism at the next app launch."

    /** Summarizer operation kinds as they read in the log. */
    fun summarizerKind(name: String): String = when (name) {
        "COMPACTING" -> "compaction"
        "OUTLINING" -> "chat outline"
        "SUMMARIZING" -> "summarization"
        else -> name.lowercase()
    }
}
