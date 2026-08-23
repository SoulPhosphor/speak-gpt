/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

/**
 * Permanent regeneration lock for the oldest conversation prefix that has
 * successfully reached usable condensed output. Summary and compaction keep
 * separate monotonic boundaries so the UI can explain which operation fixed
 * an assistant response into history. Compaction wins where both overlap.
 */
object CondensedRegenerationLock {
    enum class Kind { SUMMARY, COMPACTION }

    fun kindAt(
        messagePosition: Int,
        summaryBoundary: Int,
        compactionBoundary: Int
    ): Kind? = when {
        messagePosition < 0 -> null
        messagePosition < compactionBoundary.coerceAtLeast(0) -> Kind.COMPACTION
        messagePosition < summaryBoundary.coerceAtLeast(0) -> Kind.SUMMARY
        else -> null
    }
}
