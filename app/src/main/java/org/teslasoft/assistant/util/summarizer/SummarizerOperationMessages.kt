/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

import org.teslasoft.assistant.R

/** Short, cause-specific status-chip wording. Technical evidence remains in
 * the Summarizer/Error/Provider Failure logs. */
object SummarizerOperationMessages {
    fun failureMessageRes(
        kind: SummarizerController.OperationKind,
        category: SummarizerErrorCategory
    ): Int {
        val compacting = kind == SummarizerController.OperationKind.COMPACTING
        return when (category) {
            SummarizerErrorCategory.CONNECT_TIMEOUT,
            SummarizerErrorCategory.RESPONSE_TIMEOUT ->
                if (compacting) R.string.compaction_status_timeout else R.string.summarizer_status_timeout
            SummarizerErrorCategory.SERVICE_UNREACHABLE ->
                if (compacting) R.string.compaction_status_unreachable else R.string.summarizer_status_unreachable
            SummarizerErrorCategory.REQUEST_TOO_LARGE ->
                if (compacting) R.string.compaction_status_too_large else R.string.summarizer_status_too_large
            SummarizerErrorCategory.SAVE_FAILED ->
                if (compacting) R.string.compaction_status_save_failed else R.string.summarizer_status_save_failed
            else -> if (compacting) R.string.compaction_status_failed else R.string.summarizer_status_failed
        }
    }

    /* Read-only lock for the summary / compacted text while an operation runs
     * (owner ruling, Oct 3 2026): the text can't be edited until the run has
     * saved its result, and then the newest version is shown for editing. */

    fun isLocked(state: SummarizerController.OperationState): Boolean =
        state is SummarizerController.OperationState.Running

    fun inProgressRes(kind: SummarizerController.OperationKind): Int =
        if (kind == SummarizerController.OperationKind.COMPACTING) R.string.compaction_summary_in_progress
        else R.string.compaction_summary_summarizing

    fun readOnlyRes(kind: SummarizerController.OperationKind): Int =
        if (kind == SummarizerController.OperationKind.COMPACTING) R.string.compaction_summary_read_only
        else R.string.compaction_summary_read_only_summarizing

    fun successRes(kind: SummarizerController.OperationKind): Int =
        if (kind == SummarizerController.OperationKind.COMPACTING) R.string.compaction_summary_success
        else R.string.compaction_summary_summarized
}
