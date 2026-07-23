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

package org.teslasoft.assistant.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the per-log retention clamps (owner spec, July 23 2026): the three
 * configurable diagnostic logs (Memory Diagnostics, Whisper Performance,
 * Memory Usage) each cap Maximum Logs Saved at 1,000 and Maximum Days Saved at
 * 30, with a floor of 1 (a 0 would erase the log on the next write). The UI
 * shows the over-ceiling dialog and then stores the clamped value; this proves
 * the storage layer itself also refuses to hold an out-of-range value, so a
 * bad number can never reach the trimmer regardless of entry point.
 */
class LogRetentionCoerceTest {

    @Test fun ceilingsMatchTheOwnerSpec() {
        assertEquals(1000, Preferences.LOG_MAX_ENTRIES_LIMIT)
        assertEquals(30, Preferences.LOG_MAX_DAYS_LIMIT)
    }

    @Test fun maxEntriesClampsAboveCeiling() {
        assertEquals(1000, Preferences.coerceLogMaxEntries(1001))
        assertEquals(1000, Preferences.coerceLogMaxEntries(9999))
    }

    @Test fun maxEntriesFloorsAtOne() {
        assertEquals(1, Preferences.coerceLogMaxEntries(0))
        assertEquals(1, Preferences.coerceLogMaxEntries(-5))
    }

    @Test fun maxEntriesPassesInRange() {
        assertEquals(1, Preferences.coerceLogMaxEntries(1))
        assertEquals(500, Preferences.coerceLogMaxEntries(500))
        assertEquals(1000, Preferences.coerceLogMaxEntries(1000))
    }

    @Test fun maxDaysClampsAboveCeiling() {
        assertEquals(30, Preferences.coerceLogMaxDays(31))
        assertEquals(30, Preferences.coerceLogMaxDays(99))
    }

    @Test fun maxDaysFloorsAtOne() {
        assertEquals(1, Preferences.coerceLogMaxDays(0))
        assertEquals(1, Preferences.coerceLogMaxDays(-1))
    }

    @Test fun maxDaysPassesInRange() {
        assertEquals(1, Preferences.coerceLogMaxDays(1))
        assertEquals(7, Preferences.coerceLogMaxDays(7))
        assertEquals(30, Preferences.coerceLogMaxDays(30))
    }
}
