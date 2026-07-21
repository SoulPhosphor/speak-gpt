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

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders one compact status line per backup type for the Backup Status
 * section. PURE: the owner-approved English templates are injected (they live
 * in strings.xml at the UI layer), so the exact wording and date format are
 * asserted in unit tests without Android resources.
 *
 * Owner-approved patterns (July 21 2026), where the row label is Memory /
 * Lorebooks / Chats / User Image Database:
 *   "Memory: Never backed up"
 *   "Memory: Creating backup…"
 *   "Memory: Backed up July 20, 2026 at 2:30 PM"
 *   "Memory: Backup failed. Last good backup: July 19, 2026 at 2:30 PM"
 *
 * NOTE (open wording): the owner supplied the failed pattern WITH a prior good
 * backup. When a type has never had a successful backup and then fails, there
 * is no date to show; [Templates.failedNoLastGood] carries that case and is a
 * plain truncation of the approved line ("Backup failed"). It is flagged for
 * the owner to confirm before shipping — the UI layer supplies the final
 * string.
 */
object BackupStatusFormatter {

    /** Owner date/time format: "July 20, 2026 at 2:30 PM" — the word "at"
     *  between the year and the time, not a comma (owner correction, July 21
     *  2026). Rendered in the device's local zone, US month/AM-PM tokens. */
    private val DATE_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a", Locale.US)

    fun formatDateTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DATE_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /**
     * The injected owner-approved templates. [backedUp] and [failedWithLastGood]
     * each contain a single `%1$s` date placeholder.
     */
    data class Templates(
        val neverBackedUp: String,
        val creating: String,
        val backedUp: String,
        val failedWithLastGood: String,
        val failedNoLastGood: String
    )

    /**
     * @param typeLabel the row name (Memory / Lorebooks / Chats / User Image
     *        Database).
     * @param inProgress a backup for this type is running right now.
     * @param lastSuccessMillis last successful backup epoch millis, or <= 0 when
     *        the type has never been backed up.
     * @param lastFailed the most recent attempt for this type failed.
     */
    fun statusLine(
        typeLabel: String,
        inProgress: Boolean,
        lastSuccessMillis: Long,
        lastFailed: Boolean,
        templates: Templates,
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val body = when {
            inProgress -> templates.creating
            lastFailed && lastSuccessMillis > 0L ->
                templates.failedWithLastGood.format(formatDateTime(lastSuccessMillis, zone))
            lastFailed -> templates.failedNoLastGood
            lastSuccessMillis > 0L ->
                templates.backedUp.format(formatDateTime(lastSuccessMillis, zone))
            else -> templates.neverBackedUp
        }
        return "$typeLabel: $body"
    }
}
