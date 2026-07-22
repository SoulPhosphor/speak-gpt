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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ownership index rules (owner directive, July 22 2026): records round
 * trip faithfully; a corrupt index reads as empty (nothing eligible for
 * future pruning — the safe direction); retention candidates are VERIFIED
 * entries in the CURRENT folder only, keep-5 newest; and the record cap
 * drops oldest RECORDS, never suggesting file deletion.
 */
class AutomaticBackupIndexTest {

    private fun entry(
        name: String,
        createdAt: Long,
        verified: Boolean = true,
        treeUri: String = "content://tree/primary",
        uri: String = "content://doc/$name"
    ) = AutomaticBackupIndex.Entry(
        uri = uri, treeUri = treeUri, fileName = name,
        sha256 = "ab".repeat(32), createdAtMillis = createdAt,
        sizeBytes = 1234L, verified = verified
    )

    @Test
    fun jsonRoundTripsAllFields() {
        val entries = listOf(entry("a.zip", 100L), entry("b.zip", 200L, verified = false))
        val back = AutomaticBackupIndex.fromJson(AutomaticBackupIndex.toJson(entries))
        assertEquals(entries, back)
    }

    @Test
    fun corruptOrMissingIndexReadsAsEmpty_theSafeDirection() {
        assertTrue(AutomaticBackupIndex.fromJson(null).isEmpty())
        assertTrue(AutomaticBackupIndex.fromJson("").isEmpty())
        assertTrue(AutomaticBackupIndex.fromJson("{not an array}").isEmpty())
    }

    @Test
    fun retentionKeepsTheFiveNewestVerified() {
        val entries = (1..8).map { entry("f$it.zip", it * 1000L) }
        val candidates = AutomaticBackupIndex.retentionCandidates(entries, "content://tree/primary")
        assertEquals(listOf("f1.zip", "f2.zip", "f3.zip"), candidates.map { it.fileName })
    }

    @Test
    fun retentionNeverConsidersUnverifiedEntries() {
        val entries = (1..8).map { entry("f$it.zip", it * 1000L, verified = it % 2 == 0) }
        // Verified: f2,f4,f6,f8 (4 entries) <= keep 5 -> nothing identified.
        assertTrue(AutomaticBackupIndex.retentionCandidates(entries, "content://tree/primary").isEmpty())
    }

    @Test
    fun retentionOnlyConsidersTheCurrentFolder() {
        val old = (1..6).map { entry("old$it.zip", it * 1000L, treeUri = "content://tree/oldfolder") }
        val cur = (1..6).map { entry("cur$it.zip", 100_000L + it * 1000L) }
        val candidates = AutomaticBackupIndex.retentionCandidates(old + cur, "content://tree/primary")
        // Only the current folder's overflow is identified; the old folder's
        // files are untouched (they become unmanaged if never revisited).
        assertEquals(listOf("cur1.zip"), candidates.map { it.fileName })
    }

    @Test
    fun retentionUnderKeepIdentifiesNothing() {
        val entries = (1..5).map { entry("f$it.zip", it * 1000L) }
        assertTrue(AutomaticBackupIndex.retentionCandidates(entries, "content://tree/primary").isEmpty())
    }

    @Test
    fun recordCapDropsOldestRecordsOnly() {
        val many = (1..AutomaticBackupIndex.MAX_ENTRIES).map { entry("f$it.zip", it * 1000L) }
        val appended = AutomaticBackupIndex.appendCapped(many, entry("new.zip", 999_999_999L))
        assertEquals(AutomaticBackupIndex.MAX_ENTRIES, appended.size)
        assertEquals("new.zip", appended.last().fileName)
        // The oldest RECORD fell out; f1's file merely becomes unmanaged.
        assertTrue(appended.none { it.fileName == "f1.zip" })
    }

    @Test
    fun appendUnderCapKeepsEverything() {
        val entries = (1..3).map { entry("f$it.zip", it * 1000L) }
        val appended = AutomaticBackupIndex.appendCapped(entries, entry("new.zip", 9000L))
        assertEquals(4, appended.size)
    }
}
