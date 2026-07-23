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
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The frequency PERSISTENCE encoding. [RecoveryBackupState] stores the chosen
 * frequency as [BackupFrequency.key] and reads it back with [fromKey]; that
 * round-trip is exactly what makes the user's choice survive a restart, so it
 * is pinned here (the SharedPreferences layer itself is thin Context glue and,
 * per this project's setup, unit-tested through its pure encoding).
 *
 * The keys are the STABLE persisted identifiers — changing one would silently
 * reset an existing user's choice, so these literals are intentionally hard-
 * coded here to catch any accidental rename.
 */
class BackupFrequencyTest {

    @Test
    fun keyRoundTripsForEveryFrequency() {
        for (f in BackupFrequency.values()) {
            assertEquals("key must round-trip ($f)", f, BackupFrequency.fromKey(f.key))
        }
    }

    @Test
    fun stableKeysNeverChange() {
        assertEquals("daily", BackupFrequency.DAILY.key)
        assertEquals("weekly", BackupFrequency.WEEKLY.key)
        assertEquals("biweekly", BackupFrequency.BIWEEKLY.key)
        assertEquals("monthly", BackupFrequency.MONTHLY.key)
    }

    @Test
    fun displayOrderIsTheFourInOwnerOrder() {
        assertEquals(
            listOf(
                BackupFrequency.DAILY,
                BackupFrequency.WEEKLY,
                BackupFrequency.BIWEEKLY,
                BackupFrequency.MONTHLY
            ),
            BackupFrequency.displayOrder
        )
    }

    @Test
    fun unknownOrNullKeyIsNull() {
        // An unrecognized stored value must not masquerade as a real choice —
        // RecoveryBackupState.getAutoFrequency falls back to the DAILY default.
        assertNull(BackupFrequency.fromKey(null))
        assertNull(BackupFrequency.fromKey(""))
        assertNull(BackupFrequency.fromKey("fortnightly"))
    }
}
