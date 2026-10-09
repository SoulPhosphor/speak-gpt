/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.stt.api

import java.util.Locale

/**
 * Custom vocabulary for API speech-to-text. Entries are kept oldest first, so
 * the newest words sit at the end of the hint sent with a recording, where a
 * model that reads only the tail of its hint still sees them.
 */
object SttVocabulary {
    /** App-side cap (owner ruling, Oct 9 2026), not a service limit. */
    const val MAX_ENTRIES = 75

    sealed interface AddResult {
        data class Added(val entries: List<String>) : AddResult
        data object Blank : AddResult
        data object Duplicate : AddResult
        data object Full : AddResult
    }

    fun add(entries: List<String>, raw: String): AddResult {
        val entry = raw.trim()
        return when {
            entry.isEmpty() -> AddResult.Blank
            entries.size >= MAX_ENTRIES -> AddResult.Full
            entries.any { it.lowercase(Locale.ROOT) == entry.lowercase(Locale.ROOT) } -> AddResult.Duplicate
            else -> AddResult.Added(entries + entry)
        }
    }

    fun isFull(entries: List<String>): Boolean = entries.size >= MAX_ENTRIES

    /** The hint sent to the service, or null when there are no entries. */
    fun prompt(entries: List<String>): String? = entries.joinToString(", ").ifBlank { null }
}

/** Removal confirmations can be silenced until the app process restarts. */
object SttVocabularySession {
    @Volatile var skipRemoveConfirmation = false
}
