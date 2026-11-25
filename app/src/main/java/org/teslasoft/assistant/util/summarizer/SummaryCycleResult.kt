/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

/**
 * What one automatic summary run saved (Owner, 2026-11-25). A section made
 * from messages that changed while its request was out is [discarded]: the
 * approved stale-result rejection, never an error. [stoppedEarly]: the run
 * stopped because the chat closed or was changing before its next section.
 */
data class SummaryCycleResult(
    val saved: Int = 0,
    val discarded: Int = 0,
    val stoppedEarly: Boolean = false
) {
    enum class End {
        /** Every section the run made was saved: "Summary Successful!". */
        SUCCEEDED,
        /** Some saved, some discarded because the conversation changed: "Summary Partially Updated". */
        PARTIAL,
        /** Nothing more was saved because the conversation changed or the chat closed: no message, no error. */
        SUPERSEDED,
        /** An actual failure, recorded in Summarizer Errors, whatever was saved before it. */
        FAILED
    }

    companion object {
        fun endOf(result: SummaryCycleResult, failed: Boolean): End = when {
            failed -> End.FAILED
            result.saved > 0 && result.discarded > 0 -> End.PARTIAL
            result.saved > 0 && result.discarded == 0 && !result.stoppedEarly -> End.SUCCEEDED
            else -> End.SUPERSEDED
        }
    }
}
