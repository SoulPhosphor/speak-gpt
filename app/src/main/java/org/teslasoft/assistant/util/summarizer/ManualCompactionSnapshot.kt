/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.util.summarizer

/** Pure snapshot guard for one user-requested Compact operation. */
object ManualCompactionSnapshot {
    fun prefixStillCurrent(
        frozen: List<SummarizerController.Entry>,
        current: List<SummarizerController.Entry>
    ): Boolean =
        current.size >= frozen.size && current.take(frozen.size) == frozen
}
