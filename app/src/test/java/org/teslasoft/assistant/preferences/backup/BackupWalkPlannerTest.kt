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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The §15.6 backup-walk selection (Build Phase 3 item 4): candidate shapes
 * and newest-first ordering — the ordering is load-bearing, because the walk
 * verifies candidates in this order and restores the FIRST that passes.
 */
class BackupWalkPlannerTest {

    @Test
    fun recognizesBothExporterShapes() {
        assertTrue(BackupWalkPlanner.isCandidate("memory-export-2026-07-20T09-15-30.123Z.json"))
        assertTrue(BackupWalkPlanner.isCandidate("memory-backup-2026-07-20T09-15-30.123Z.json"))
    }

    @Test
    fun rejectsForeignFiles() {
        assertFalse(BackupWalkPlanner.isCandidate("lorebook-2026.json"))
        assertFalse(BackupWalkPlanner.isCandidate("memory-export-2026.txt"))
        assertFalse(BackupWalkPlanner.isCandidate("chats-00001753000000000.zip"))
        assertFalse(BackupWalkPlanner.isCandidate("notmemory-export-2026-07-20.json"))
    }

    @Test
    fun ordersNewestFirstAcrossBothShapes() {
        val ordered = BackupWalkPlanner.orderNewestFirst(
            listOf(
                "memory-export-2026-07-18T10-00-00Z.json",
                "memory-backup-2026-07-21T08-30-00Z.json",
                "memory-export-2026-07-20T10-00-00Z.json",
                "not-a-backup.json"
            )
        )
        assertEquals(
            listOf(
                "memory-backup-2026-07-21T08-30-00Z.json",
                "memory-export-2026-07-20T10-00-00Z.json",
                "memory-export-2026-07-18T10-00-00Z.json"
            ),
            ordered
        )
    }

    @Test
    fun newestIsAlwaysTriedFirst() {
        val ordered = BackupWalkPlanner.orderNewestFirst(
            listOf(
                "memory-export-2026-01-01T00-00-00Z.json",
                "memory-export-2026-12-31T23-59-59Z.json"
            )
        )
        assertEquals("memory-export-2026-12-31T23-59-59Z.json", ordered.first())
    }

    @Test
    fun recoversTheInstantFromTheFlattenedIsoStamp() {
        // MemoryExporter writes nowIso().replace(":", "-").
        val iso = "2026-07-20T09:15:30.123Z"
        val name = "memory-export-${iso.replace(":", "-")}.json"
        assertEquals(Instant.parse(iso).toEpochMilli(), BackupWalkPlanner.backupInstantMillis(name))
    }

    @Test
    fun unparseableStampYieldsNullNotAGuess() {
        assertNull(BackupWalkPlanner.backupInstantMillis("memory-export-garbage.json"))
        assertNull(BackupWalkPlanner.backupInstantMillis("unrelated.json"))
    }

    @Test
    fun dateOnlyStampStillOrdersEvenIfInstantParseFails() {
        // Ordering is lexical on the stamp, so even a shape the instant
        // parser rejects keeps a stable, correct position in the walk.
        val ordered = BackupWalkPlanner.orderNewestFirst(
            listOf("memory-export-2026-07-19.json", "memory-export-2026-07-21.json")
        )
        assertEquals("memory-export-2026-07-21.json", ordered.first())
        assertNotNull(ordered)
    }
}
