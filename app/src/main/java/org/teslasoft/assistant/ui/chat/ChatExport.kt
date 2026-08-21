/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.chat

import com.google.gson.Gson
import java.text.DateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone

enum class ChatExportFormat(
    val extension: String,
    val mimeType: String,
    val label: String
) {
    JSON("json", "application/json", "json"),
    TEXT("txt", "text/plain", "txt"),
    MARKDOWN("md", "text/markdown", "MD"),
    PDF("pdf", "application/pdf", "PDF")
}

data class ChatExportOptions(
    val format: ChatExportFormat = ChatExportFormat.JSON,
    val includeDate: Boolean = false,
    val includeTime: Boolean = false,
    val includeModel: Boolean = false,
    val includeUserTokenCount: Boolean = false,
    val includeCompanionTokenCount: Boolean = false
)

data class ChatExportMessage(
    val isCompanion: Boolean,
    val name: String,
    val content: String,
    val timestampMillis: Long? = null,
    val model: String? = null,
    val tokenCount: Int? = null
)

/**
 * Produces the same speaker/metadata order for text, Markdown, and PDF, and
 * a compatible record-per-message shape for JSON.
 */
object ChatExportFormatter {

    private const val MIDDLE_DOT = " · "
    private const val TOKEN_SUFFIX = " Tokens"

    fun formatText(
        messages: List<ChatExportMessage>,
        options: ChatExportOptions,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String =
        messages.joinToString("\n\n") { message ->
            formatMessageText(message, options, locale, timeZone)
        }

    /**
     * The JSON export remains an array of message records, matching the
     * dormant chat importer’s historical top-level shape while adding only
     * the fields selected in the export dialog.
     */
    fun formatJson(
        messages: List<ChatExportMessage>,
        options: ChatExportOptions,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val records = messages.map { message ->
            val record = LinkedHashMap<String, Any?>()
            record["message"] = message.content
            record["isBot"] = message.isCompanion
            record["name"] = message.name

            metadataParts(message, options, locale, timeZone).forEach { (key, value) ->
                record[key] = value
            }
            record
        }
        return Gson().toJson(records)
    }

    private fun formatMessageText(
        message: ChatExportMessage,
        options: ChatExportOptions,
        locale: Locale,
        timeZone: TimeZone
    ): String {
        val parts = metadataParts(message, options, locale, timeZone)
        val dateAndTime = listOfNotNull(parts["date"], parts["time"])
            .joinToString(" ")
            .takeIf { it.isNotEmpty() }
        val metadata = listOfNotNull(
            dateAndTime,
            parts["model"],
            parts["tokens"]
        ).joinToString(MIDDLE_DOT)
        return buildString {
            append(message.name)
            if (metadata.isNotEmpty()) {
                append('\n')
                append(metadata)
            }
            append('\n')
            append(message.content)
        }
    }

    private fun metadataParts(
        message: ChatExportMessage,
        options: ChatExportOptions,
        locale: Locale,
        timeZone: TimeZone
    ): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        val timestamp = message.timestampMillis?.let { Date(it) }
        if (options.includeDate && timestamp != null) {
            result["date"] = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).apply {
                this.timeZone = timeZone
            }
                .format(timestamp)
        }
        if (options.includeTime && timestamp != null) {
            result["time"] = DateFormat.getTimeInstance(DateFormat.SHORT, locale).apply {
                this.timeZone = timeZone
            }
                .format(timestamp)
        }
        if (options.includeModel && message.isCompanion) {
            message.model?.trim()?.takeIf { it.isNotEmpty() }?.let { result["model"] = it }
        }
        val includeTokens = if (message.isCompanion) {
            options.includeCompanionTokenCount
        } else {
            options.includeUserTokenCount
        }
        if (includeTokens && message.tokenCount != null) {
            result["tokens"] = message.tokenCount.toString() + TOKEN_SUFFIX
        }
        return result
    }

}
