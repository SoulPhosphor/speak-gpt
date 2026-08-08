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

/** Matches the app's existing long date presentation used by Memory Analysis. */
object MemoryDateFormatter {
    fun format(iso: String, locale: Locale = Locale.getDefault(), zoneId: ZoneId = ZoneId.systemDefault()): String =
        try {
            DateTimeFormatter.ofPattern("MMMM d, yyyy", locale)
                .withZone(zoneId)
                .format(Instant.parse(iso))
        } catch (_: Exception) {
            iso
        }
}
