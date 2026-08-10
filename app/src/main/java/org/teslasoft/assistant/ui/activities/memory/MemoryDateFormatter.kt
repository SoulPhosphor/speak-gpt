/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.activities.memory

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One presentation for every user-facing Memory timestamp. */
object MemoryDateFormatter {
    fun format(iso: String, locale: Locale = Locale.getDefault(), zoneId: ZoneId = ZoneId.systemDefault()): String =
        try {
            DateTimeFormatter.ofPattern("MMMM d, yyyy h:mm a", locale)
                .withZone(zoneId)
                .format(Instant.parse(iso))
        } catch (_: Exception) {
            iso
        }
}

/** Pure supersession presentation policy shared by the browser row and its
 * history dialog. The recorded relationship timestamp wins over a generic
 * change-log timestamp whenever the lifecycle event is supersession. */
object MemorySupersessionPresentation {
    fun badge(
        status: String,
        statusLabel: String,
        recordedSupersessionAt: String?,
        format: (String) -> String
    ): String = if (status == "superseded" && recordedSupersessionAt != null) {
        "$statusLabel · ${format(recordedSupersessionAt)}"
    } else {
        statusLabel
    }

    fun timestamp(
        action: String,
        eventAt: String,
        recordedSupersessionAt: String?
    ): String = if (action == "superseded" && recordedSupersessionAt != null) {
        recordedSupersessionAt
    } else {
        eventAt
    }
}
