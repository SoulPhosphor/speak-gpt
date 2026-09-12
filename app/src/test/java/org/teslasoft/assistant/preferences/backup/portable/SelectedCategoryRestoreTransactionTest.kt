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

package org.teslasoft.assistant.preferences.backup.portable

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedCategoryRestoreTransactionTest {

    @Test
    fun validationFailureChangesNothingAndStagesNothing() {
        val events = ArrayList<String>()
        val one = participant("chats", events)
        val two = participant("memories", events, valid = false)

        val result = SelectedCategoryRestoreTransaction.execute(tempRoot(), listOf(one, two))

        assertEquals(
            SelectedCategoryRestoreTransaction.Result.Failed(
                SelectedCategoryRestoreTransaction.Failure.VALIDATION_FAILED,
                "memories"
            ),
            result
        )
        assertFalse(events.any { it.startsWith("stage:") || it.startsWith("apply:") })
    }

    @Test
    fun everyCategoryStagesBeforeTheFirstApply() {
        val events = ArrayList<String>()
        val root = tempRoot()
        val result = SelectedCategoryRestoreTransaction.execute(
            root,
            listOf(participant("chats", events), participant("memories", events))
        )

        assertEquals(SelectedCategoryRestoreTransaction.Result.Success, result)
        assertTrue(events.indexOf("stage:memories") < events.indexOf("apply:chats"))
        assertFalse(root.exists())
    }

    @Test
    fun stagingFailureChangesNothingAndDoesNotCreateAJournal() {
        val events = ArrayList<String>()
        val root = tempRoot()
        val result = SelectedCategoryRestoreTransaction.execute(
            root,
            listOf(
                participant("chats", events),
                participant("memories", events, stages = false)
            )
        )

        assertEquals(
            SelectedCategoryRestoreTransaction.Result.Failed(
                SelectedCategoryRestoreTransaction.Failure.STAGING_FAILED,
                "memories"
            ),
            result
        )
        assertFalse(events.any { it.startsWith("apply:") || it.startsWith("rollback:") })
        assertFalse(root.exists())
    }

    @Test
    fun anApplyFailureRollsBackEveryStartedCategoryInReverseOrder() {
        val events = ArrayList<String>()
        val root = tempRoot()
        val result = SelectedCategoryRestoreTransaction.execute(
            root,
            listOf(
                participant("chats", events),
                participant("memories", events, applies = false)
            )
        )

        assertEquals(
            SelectedCategoryRestoreTransaction.Result.Failed(
                SelectedCategoryRestoreTransaction.Failure.APPLY_FAILED,
                "memories"
            ),
            result
        )
        assertTrue(events.indexOf("rollback:memories") < events.indexOf("rollback:chats"))
        assertFalse(root.exists())
    }

    @Test
    fun rollbackFailureKeepsTheJournalForRecovery() {
        val events = ArrayList<String>()
        val root = tempRoot()
        val result = SelectedCategoryRestoreTransaction.execute(
            root,
            listOf(
                participant("chats", events, rollsBack = false),
                participant("memories", events, applies = false)
            )
        )

        assertEquals(
            SelectedCategoryRestoreTransaction.Failure.ROLLBACK_FAILED,
            (result as SelectedCategoryRestoreTransaction.Result.Failed).reason
        )
        assertEquals(
            SelectedCategoryRestoreTransaction.DataState.RECOVERY_REQUIRED,
            result.dataState
        )
        assertTrue(root.resolve("state.json").isFile)
    }

    @Test
    fun startupRecoveryRollsBackAProcessInterruption() {
        val events = ArrayList<String>()
        val root = tempRoot()
        val chats = participant("chats", events)
        val memories = participant("memories", events)
        try {
            SelectedCategoryRestoreTransaction.execute(
                root,
                listOf(chats, memories),
                SelectedCategoryRestoreTransaction.InterruptionPoint.AFTER_FIRST_APPLY
            )
            throw AssertionError("expected simulated interruption")
        } catch (_: Error) {
            // Process death leaves the durable journal and category staging.
        }

        assertTrue(root.resolve("state.json").isFile)
        assertTrue(
            SelectedCategoryRestoreTransaction.recover(
                root,
                mapOf("chats" to chats, "memories" to memories)
            )
        )
        assertTrue(events.contains("rollback:chats"))
        assertFalse(events.contains("rollback:memories"))
        assertFalse(root.exists())
    }

    @Test
    fun terminalOutcomeIsWrittenBeforeCleanupAndJournalRelease() {
        val events = ArrayList<String>()
        val root = tempRoot()
        val result = SelectedCategoryRestoreTransaction.execute(
            root,
            listOf(participant("chats", events)),
            beforeCleanup = {
                assertTrue(root.resolve("state.json").isFile)
                events.add("terminal")
                true
            }
        )

        assertEquals(SelectedCategoryRestoreTransaction.Result.Success, result)
        assertTrue(events.indexOf("apply:chats") < events.indexOf("terminal"))
        assertTrue(events.indexOf("terminal") < events.indexOf("cleanup:chats"))
        assertFalse(root.exists())
    }

    @Test
    fun terminalOutcomeFailureRetainsCompleteJournalForStartupCleanup() {
        val events = ArrayList<String>()
        val root = tempRoot()
        val chats = participant("chats", events)
        val result = SelectedCategoryRestoreTransaction.execute(
            root,
            listOf(chats),
            beforeCleanup = { false }
        ) as SelectedCategoryRestoreTransaction.Result.Failed

        assertEquals(SelectedCategoryRestoreTransaction.Failure.JOURNAL_FAILED, result.reason)
        assertEquals(
            SelectedCategoryRestoreTransaction.DataState.RESTORED_CLEANUP_PENDING,
            result.dataState
        )
        assertFalse(events.contains("cleanup:chats"))
        assertTrue(SelectedCategoryRestoreTransaction.recover(root, mapOf("chats" to chats)))
        assertTrue(events.contains("cleanup:chats"))
        assertFalse(events.contains("rollback:chats"))
    }

    private fun participant(
        key: String,
        events: MutableList<String>,
        valid: Boolean = true,
        stages: Boolean = true,
        applies: Boolean = true,
        rollsBack: Boolean = true
    ) = object : SelectedCategoryRestoreTransaction.Participant {
        override val categoryKey = key
        override fun validate(): Boolean { events.add("validate:$key"); return valid }
        override fun stage(): Boolean { events.add("stage:$key"); return stages }
        override fun apply(): Boolean { events.add("apply:$key"); return applies }
        override fun rollback(): Boolean { events.add("rollback:$key"); return rollsBack }
        override fun cleanup() { events.add("cleanup:$key") }
    }

    private fun tempRoot() = Files.createTempDirectory("restore-transaction-test")
        .resolve("journal")
        .toFile()
}
