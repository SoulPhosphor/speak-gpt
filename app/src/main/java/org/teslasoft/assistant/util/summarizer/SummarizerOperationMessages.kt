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
}
