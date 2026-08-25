/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 */

package org.teslasoft.assistant.ui.chat

/** Provider-independent rules for the one-shot conversation title request. */
object ConversationTitlePolicy {
    const val MAX_TITLE_CHARS = 80
    const val MAX_TITLE_WORDS = 6
    const val TITLE_OUTPUT_TOKENS = 512

    const val SYSTEM_PROMPT =
        "Create a concise 2-6 word title for the supplied conversation excerpt. " +
            "Return only the title text. Describe its topic. Do not answer the conversation. " +
            "Do not add quotation marks, markdown, or a Title/Name/Chat/Topic label."

    private val labelPrefix = Regex(
        "(?i)^[\\s*_`#]*(?:conversation\\s+title|chat\\s+title|title|name|chat|topic)" +
            "[\\s*_`]*(?::|[-–—])\\s*"
    )
    private val embeddedLabel = Regex(
        "(?i)(?:conversation\\s+title|chat\\s+title|title|name|topic)" +
            "[\\s*_`]*(?::|[-–—])\\s*(.+)$"
    )
    private val preamble = Regex(
        "(?i)^(?:sure|certainly|of course|here(?:'s| is)|a concise title|the title would be)\\b"
    )
    private val fallbackLead = Regex(
        "(?i)^(?:(?:please\\s+)?(?:can|could|would)\\s+you\\s+|" +
            "(?:please\\s+)?help\\s+me\\s+(?:with\\s+|to\\s+)?|" +
            "i\\s+(?:need|want|would like)\\s+(?:you\\s+)?to\\s+)"
    )

    fun conversationExcerpt(firstUserMessage: String, firstAssistantReply: String): String =
        buildString {
            append("User: ").append(cleanExcerpt(firstUserMessage).take(1600))
            val assistant = cleanExcerpt(firstAssistantReply)
            if (assistant.isNotBlank()) {
                append("\nAssistant: ").append(assistant.take(1600))
            }
        }

    fun sanitize(raw: String?): String? {
        val lines = raw.orEmpty().lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) return null

        val labeled = lines.firstNotNullOfOrNull { line ->
            embeddedLabel.find(line)?.groupValues?.getOrNull(1)
        }
        val candidate = labeled
            ?: lines.firstOrNull { !preamble.containsMatchIn(it) }
            ?: lines.first()

        var cleaned = candidate
            .replace(Regex("^#{1,6}\\s*"), "")
            .replace(Regex("^(?:[-*•]|\\d+[.)])\\s+"), "")
            .trim()
        repeat(3) {
            cleaned = stripOuterMarkup(cleaned)
            cleaned = cleaned.replace(labelPrefix, "").trim()
        }
        cleaned = cleaned
            .replace(Regex("^[*_`]+\\s*"), "")
            .replace(Regex("\\s*[*_`]+$"), "")
            .trim(' ', '\t', '\n', '\r', ':', '-', '–', '—')
            .trimEnd('.', ',', ';', ':')
            .trim()
        if (cleaned.isBlank()) return null

        return cleaned.split(Regex("\\s+"))
            .take(MAX_TITLE_WORDS)
            .joinToString(" ")
            .take(MAX_TITLE_CHARS)
            .trim()
            .takeIf { it.isNotBlank() }
    }

    fun fallbackFromUserMessage(message: String): String {
        var cleaned = cleanExcerpt(message)
            .replace(fallbackLead, "")
            .trim()
        cleaned = cleaned.substringBefore('\n')
            .substringBefore('?')
            .substringBefore('!')
            .replace(Regex("(?i)\\s+please[.,;:]*$"), "")
            .trim()
        return sanitize(cleaned) ?: "New conversation"
    }

    private fun cleanExcerpt(text: String): String = text
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun stripOuterMarkup(value: String): String {
        var result = value.trim()
        val pairs = listOf("**" to "**", "__" to "__", "`" to "`", "\"" to "\"", "'" to "'")
        for ((start, end) in pairs) {
            if (result.length > start.length + end.length &&
                result.startsWith(start) && result.endsWith(end)
            ) {
                result = result.substring(start.length, result.length - end.length).trim()
            }
        }
        return result
    }
}
