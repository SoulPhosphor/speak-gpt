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

package org.teslasoft.assistant.preferences.backup

import java.util.Locale

/**
 * Human-readable BINARY (1024-based) file sizes for backup status display
 * (owner directive, July 23 2026: "File Size:" on the automatic-backup status
 * and "Chat Backup File Size:" on the Human-Readable Chat Backup status). Pure
 * and unit-tested — no Android types.
 *
 * Bytes render as a plain integer ("512 B"); KB/MB/GB render with one decimal
 * place ("4.8 MB") — sensible rounding, not a raw byte count, capped at GB
 * (no TB tier — a backup this app produces is never remotely that large).
 * `Locale.US` is forced so the decimal point never becomes a comma on a
 * device set to a comma-decimal locale.
 */
object ByteSizeFormatter {

    private const val UNIT = 1024L
    private val UNIT_NAMES = arrayOf("KB", "MB", "GB")

    /**
     * @param bytes a negative value is treated as 0 (defensive — a real file
     *        size is never negative; this must never throw or crash a status
     *        line over a bad input).
     */
    fun format(bytes: Long): String {
        val n = if (bytes < 0L) 0L else bytes
        if (n < UNIT) return "$n B"

        var value = n.toDouble() / UNIT
        var unitIndex = 0 // 0=KB, 1=MB, 2=GB
        while (value >= UNIT && unitIndex < UNIT_NAMES.lastIndex) {
            value /= UNIT
            unitIndex++
        }

        // Rounding to one decimal can carry a near-boundary value up to the
        // next unit's threshold (e.g. 1023.96 KB -> "1024.0 KB", which reads
        // wrong): bump the unit once more so the displayed number is always
        // < 1024 at whatever unit it's shown in. A single bump always
        // suffices — dividing a near-1024 value by 1024 lands near 1.0, nowhere
        // close to the next threshold, so this never needs to cascade.
        if (roundsUpToUnitThreshold(value) && unitIndex < UNIT_NAMES.lastIndex) {
            value /= UNIT
            unitIndex++
        }

        return String.format(Locale.US, "%.1f %s", value, UNIT_NAMES[unitIndex])
    }

    /** True when formatting [value] to one decimal place would display 1024.0
     *  ("%.1f" rounds half-up at the tenths place, so anything >= 1023.95 does). */
    private fun roundsUpToUnitThreshold(value: Double): Boolean = value >= UNIT - 0.05
}
