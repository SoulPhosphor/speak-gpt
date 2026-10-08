/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The human-facing date/time of a summary section, in the device's time zone
 * and 12-hour time: "October 1, 2026 · 1:00 PM–3:15 PM", or a single time
 * when the section has one prompt. Never sent to any model.
 */
object SummarySectionTime {

    fun format(
        startMillis: Long,
        endMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault()
    ): String {
        val dateFormat = DateTimeFormatter.ofPattern("MMMM d, yyyy", locale)
        val timeFormat = DateTimeFormatter.ofPattern("h:mm a", locale)
        val start = Instant.ofEpochMilli(startMillis).atZone(zone)
        val end = Instant.ofEpochMilli(maxOf(startMillis, endMillis)).atZone(zone)
        val startDate = start.format(dateFormat)
        val startTime = start.format(timeFormat)
        val endTime = end.format(timeFormat)
        return when {
            start.toLocalDate() != end.toLocalDate() ->
                "$startDate · $startTime – ${end.format(dateFormat)} · $endTime"
            startTime == endTime -> "$startDate · $startTime"
            else -> "$startDate · $startTime–$endTime"
        }
    }
}
