/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.ui.chat

import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.ResponseLifecycle

/**
 * How a regular reply that was cancelled mid-request ends: the state written
 * on its row, its Error Log entry (if any) and its Response Lifecycle record.
 * One source for both the reply's own cancellation handler and a message
 * deletion that settles the reply at once.
 */
internal object CancelledReplyTerminal {
    data class Plan(
        val state: String,
        val detail: String,
        /** Shown with the row's state; null when there is no known reason. */
        val reason: String?,
        val logAsError: Boolean,
        val errorTag: String,
        val errorSummary: String,
        val outcome: ResponseLifecycle.Outcome,
        val termination: ResponseLifecycle.Termination,
        val lifecycleError: String?
    )

    fun plan(
        userStop: Boolean,
        destroying: Boolean,
        replyStarted: Boolean,
        requestDispatched: Boolean,
        screenClosedReason: String
    ): Plan {
        val (state, detail) = MessageCompletionState.classifyCancellation(userStop, destroying, replyStarted)
        return when (detail) {
            MessageCompletionState.DETAIL_USER_STOP -> Plan(
                state, detail, null, logAsError = false, errorTag = "", errorSummary = "",
                outcome = ResponseLifecycle.Outcome.STOPPED,
                termination = ResponseLifecycle.Termination.USER_STOP, lifecycleError = null
            )
            MessageCompletionState.DETAIL_START_FAILED -> {
                val reason = if (destroying) screenClosedReason else null
                val summary = "reply could not start" + (reason?.let { ": $it" } ?: "")
                Plan(
                    state, detail, reason, logAsError = !destroying, errorTag = "GenStartFailed", errorSummary = summary,
                    outcome = if (destroying) ResponseLifecycle.Outcome.CANCELLED else ResponseLifecycle.Outcome.INCOMPLETE,
                    termination = if (destroying) ResponseLifecycle.Termination.APP_CANCEL
                        else ResponseLifecycle.Termination.STREAM_CLOSED,
                    lifecycleError = summary
                )
            }
            MessageCompletionState.DETAIL_SCREEN_CLOSED -> Plan(
                state, detail, screenClosedReason, logAsError = false, errorTag = "GenInterrupted",
                errorSummary = "app interrupted the reply: $screenClosedReason",
                outcome = ResponseLifecycle.Outcome.CANCELLED,
                termination = ResponseLifecycle.Termination.APP_CANCEL,
                lifecycleError = "app interrupted: $screenClosedReason"
            )
            // Cancelled with no user stop and no teardown. Before the provider
            // request was sent this is a pre-dispatch cancellation; once sent it
            // is the "ended early; cause unknown" diagnostic. The row's handling
            // is the same either way.
            else -> Plan(
                state, detail, null, logAsError = true, errorTag = "GenUnknownEnd",
                errorSummary = "reply ended early; cause unknown",
                outcome = if (requestDispatched) ResponseLifecycle.Outcome.INCOMPLETE
                    else ResponseLifecycle.Outcome.CANCELLED,
                termination = if (requestDispatched) ResponseLifecycle.Termination.STREAM_CLOSED
                    else ResponseLifecycle.Termination.REQUEST_NOT_SENT,
                lifecycleError = if (requestDispatched) "ended early; cause unknown" else null
            )
        }
    }
}
