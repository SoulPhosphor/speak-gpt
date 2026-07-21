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

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Pins the owner-approved status wording and date/time format (July 21 2026).
 * The templates here mirror the English that will live in strings.xml, so any
 * drift from the approved patterns fails the build.
 */
class BackupStatusFormatterTest {

    private val templates = BackupStatusFormatter.Templates(
        neverBackedUp = "Never backed up",
        creating = "Creating backup…",
        backedUp = "Backed up %1\$s",
        failedWithLastGood = "Backup failed. Last good backup: %1\$s",
        failedNoLastGood = "Backup failed. No successful backup yet.",
        nothingToBackUp = "Nothing to Back Up"
    )

    private val utc = ZoneOffset.UTC

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, utc).toInstant().toEpochMilli()

    @Test
    fun neverBackedUp() {
        assertEquals(
            "Memory: Never backed up",
            BackupStatusFormatter.statusLine("Memory", inProgress = false, nothingToBackUp = false, lastSuccessMillis = 0L, lastFailed = false, templates = templates, zone = utc)
        )
    }

    @Test
    fun creatingInProgress() {
        assertEquals(
            "Memory: Creating backup…",
            BackupStatusFormatter.statusLine("Memory", inProgress = true, nothingToBackUp = false, lastSuccessMillis = 0L, lastFailed = false, templates = templates, zone = utc)
        )
    }

    @Test
    fun backedUpShowsAtNotComma() {
        assertEquals(
            "Memory: Backed up July 20, 2026 at 2:30 PM",
            BackupStatusFormatter.statusLine("Memory", inProgress = false, nothingToBackUp = false, lastSuccessMillis = millis(2026, 7, 20, 14, 30), lastFailed = false, templates = templates, zone = utc)
        )
    }

    @Test
    fun failedShowsLastGoodBackup() {
        assertEquals(
            "Memory: Backup failed. Last good backup: July 19, 2026 at 2:30 PM",
            BackupStatusFormatter.statusLine("Memory", inProgress = false, nothingToBackUp = false, lastSuccessMillis = millis(2026, 7, 19, 14, 30), lastFailed = true, templates = templates, zone = utc)
        )
    }

    @Test
    fun failedWithNoPriorGoodBackup() {
        // Owner-approved wording (July 21 2026) for a failure with no prior good
        // backup — no date to show.
        assertEquals(
            "Memory: Backup failed. No successful backup yet.",
            BackupStatusFormatter.statusLine("Memory", inProgress = false, nothingToBackUp = false, lastSuccessMillis = 0L, lastFailed = true, templates = templates, zone = utc)
        )
    }

    @Test
    fun inProgressWinsOverAPriorFailure() {
        assertEquals(
            "Chats: Creating backup…",
            BackupStatusFormatter.statusLine("Chats", inProgress = true, nothingToBackUp = false, lastSuccessMillis = millis(2026, 7, 19, 14, 30), lastFailed = true, templates = templates, zone = utc)
        )
    }

    @Test
    fun nothingToBackUpIsNeutralAndWinsOverStalePriorState() {
        // Owner ruling (July 21 2026): a store that never existed is a neutral
        // state, not a failure, and reflects the current run over any stale
        // prior success or failure.
        assertEquals(
            "Memory: Nothing to Back Up",
            BackupStatusFormatter.statusLine("Memory", inProgress = false, nothingToBackUp = true, lastSuccessMillis = millis(2026, 7, 19, 14, 30), lastFailed = true, templates = templates, zone = utc)
        )
    }

    @Test
    fun dateFormatMatchesOwnerPattern() {
        assertEquals("July 20, 2026 at 2:30 PM", BackupStatusFormatter.formatDateTime(millis(2026, 7, 20, 14, 30), utc))
    }

    @Test
    fun eachTypeLabelPrefixesItsLine() {
        assertEquals(
            "User Image Database: Never backed up",
            BackupStatusFormatter.statusLine("User Image Database", inProgress = false, nothingToBackUp = false, lastSuccessMillis = 0L, lastFailed = false, templates = templates, zone = utc)
        )
    }
}
