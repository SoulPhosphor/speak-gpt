/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

/** Parser for the manual compaction slash command. The command is recognized
 * only at the beginning of a trimmed draft and only as the complete `/compact`
 * token, so ordinary text such as `/compactor` remains an ordinary message. */
object CompactCommand {
    sealed interface Parse {
        data object NotCompact : Parse
        data object CompactOnly : Parse
        data class CompactAndSend(val message: String) : Parse
    }

    fun parse(raw: String): Parse {
        val trimmed = raw.trimStart()
        if (!trimmed.startsWith(COMMAND, ignoreCase = true)) return Parse.NotCompact
        if (trimmed.length > COMMAND.length && !trimmed[COMMAND.length].isWhitespace()) {
            return Parse.NotCompact
        }
        val remainder = trimmed.substring(COMMAND.length).trimStart()
        return if (remainder.isBlank()) Parse.CompactOnly else Parse.CompactAndSend(remainder)
    }

    private const val COMMAND = "/compact"
}
