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

/**
 * The 3-strikes category split (Build Phase 3 item 8). The invariant the
 * owner cares about: destination/verify trouble NEVER routes to repair, and
 * source trouble NEVER opens the storage dialog.
 */
class BackupFailurePolicyTest {

    @Test
    fun underThreeStrikesStaysQuiet() {
        for (category in BackupFailureCategory.values()) {
            assertEquals(BackupFailurePolicy.Response.NONE, BackupFailurePolicy.respond(0, category))
            assertEquals(BackupFailurePolicy.Response.NONE, BackupFailurePolicy.respond(2, category))
        }
    }

    @Test
    fun destinationAndVerifyFailuresGetTheStorageDialog() {
        assertEquals(
            BackupFailurePolicy.Response.STORAGE_DIALOG,
            BackupFailurePolicy.respond(3, BackupFailureCategory.DESTINATION_PERMISSION)
        )
        assertEquals(
            BackupFailurePolicy.Response.STORAGE_DIALOG,
            BackupFailurePolicy.respond(4, BackupFailureCategory.DESTINATION_WRITE)
        )
        assertEquals(
            BackupFailurePolicy.Response.STORAGE_DIALOG,
            BackupFailurePolicy.respond(10, BackupFailureCategory.VERIFY)
        )
    }

    @Test
    fun sourceFailuresRouteToTheStoreCheckNeverTheStorageDialog() {
        assertEquals(
            BackupFailurePolicy.Response.SOURCE_CHECK,
            BackupFailurePolicy.respond(3, BackupFailureCategory.SOURCE)
        )
    }

    @Test
    fun aStreakWithNoRecordedCategoryStaysQuiet() {
        assertEquals(BackupFailurePolicy.Response.NONE, BackupFailurePolicy.respond(5, null))
    }
}
