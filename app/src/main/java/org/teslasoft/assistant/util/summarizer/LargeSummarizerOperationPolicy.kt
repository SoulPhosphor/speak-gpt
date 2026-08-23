/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

/** Conservative provider-independent estimate used only for the 100k warning.
 * It deliberately counts the existing summary because every fold request sends
 * it again. Exact billing remains provider usage, not this estimate. */
object LargeSummarizerOperationPolicy {
    const val WARNING_INPUT_TOKENS = 100_000

    fun estimateInputTokens(existingSummary: String, entries: List<SummarizerController.Entry>): Int {
        val characters = existingSummary.length.toLong() + entries.sumOf { it.text.length.toLong() }
        return ((characters + 3L) / 4L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun needsConfirmation(estimatedInputTokens: Int): Boolean =
        estimatedInputTokens >= WARNING_INPUT_TOKENS
}
