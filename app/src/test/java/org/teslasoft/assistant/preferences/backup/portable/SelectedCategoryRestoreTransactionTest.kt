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
import org.json.JSONArray
import org.json.JSONObject
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
                "memories",
                detail = "no_reason_given"
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
                "memories",
                detail = "no_reason_given"
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
                "memories",
                detail = "no_reason_given"
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
    fun detailedRecoveryReportsTheParticipantAndReasonWithoutDroppingTheJournal() {
        val root = tempRoot().apply { mkdirs() }
        root.resolve("state.json").writeText(
            JSONObject()
                .put("version", 2)
                .put("phase", "APPLYING")
                .put("started", JSONArray().put("chats"))
                .put("completed", JSONArray())
                .toString()
        )
        val chats = object : SelectedCategoryRestoreTransaction.Participant {
            override val categoryKey = "chats"
            override fun validate() = true
            override fun stage() = true
            override fun apply() = true
            override fun rollback() = false
            override fun cleanup() = Unit
            override fun failureDetail() = "rollback_snapshot_missing"
        }

        val result = SelectedCategoryRestoreTransaction.recoverDetailed(
            root, mapOf("chats" to chats)
        ) as SelectedCategoryRestoreTransaction.RecoveryResult.Failed

        assertEquals(SelectedCategoryRestoreTransaction.RecoveryStep.ROLLBACK, result.step)
        assertEquals("chats", result.categoryKey)
        assertEquals("chats: rollback_snapshot_missing", result.detail)
        assertTrue(root.resolve("state.json").isFile)
    }

    @Test
    fun everyParticipantStartAndCompleteBoundaryRecoversExactLogicalContent() {
        val categoryKeys = listOf(
            "chats",
            "generated_images",
            "identity_bundle",
            "companion_memory_store",
            "profile_images",
            "lorebooks",
            "model_endpoint_settings"
        )
        val faultCases = buildList {
            add(SelectedCategoryRestoreTransaction.Boundary.AFTER_PREPARED to null)
            categoryKeys.forEach { key ->
                add(SelectedCategoryRestoreTransaction.Boundary.AFTER_PARTICIPANT_STARTED to key)
                add(SelectedCategoryRestoreTransaction.Boundary.AFTER_PARTICIPANT_COMPLETED to key)
            }
        }

        faultCases.forEach { (boundary, faultCategory) ->
            val root = tempRoot()
            val logicalContent = categoryKeys.associateWithTo(LinkedHashMap()) { "original-$it" }
            val participants = categoryKeys.map { key ->
                statefulParticipant(key, logicalContent, "desired-$key")
            }
            var interrupted = false
            try {
                SelectedCategoryRestoreTransaction.execute(
                    root,
                    participants,
                    faultInjector = SelectedCategoryRestoreTransaction.FaultInjector {
                            actualBoundary, actualCategory ->
                        if (actualBoundary == boundary && actualCategory == faultCategory) {
                            throw SimulatedProcessDeath()
                        }
                    }
                )
            } catch (_: SimulatedProcessDeath) {
                interrupted = true
            }

            assertTrue("$boundary/$faultCategory did not interrupt", interrupted)
            assertTrue(
                "$boundary/$faultCategory did not recover",
                SelectedCategoryRestoreTransaction.recover(
                    root, participants.associateBy { it.categoryKey }
                )
            )
            assertEquals(
                "$boundary/$faultCategory exposed partial logical content",
                categoryKeys.associateWith { "original-$it" },
                logicalContent
            )
            assertFalse("$boundary/$faultCategory left a journal", root.exists())
        }
    }

    @Test
    fun interruptionAfterTransactionCompleteKeepsDesiredContentAndOnlyCleansUp() {
        val root = tempRoot()
        val logicalContent = linkedMapOf("chats" to "original-chats")
        val chats = statefulParticipant("chats", logicalContent, "desired-chats")
        var interrupted = false
        try {
            SelectedCategoryRestoreTransaction.execute(
                root,
                listOf(chats),
                faultInjector = SelectedCategoryRestoreTransaction.FaultInjector { boundary, _ ->
                    if (boundary ==
                        SelectedCategoryRestoreTransaction.Boundary.AFTER_TRANSACTION_COMPLETED
                    ) throw SimulatedProcessDeath()
                }
            )
        } catch (_: SimulatedProcessDeath) {
            interrupted = true
        }

        assertTrue(interrupted)
        assertTrue(SelectedCategoryRestoreTransaction.recover(root, mapOf("chats" to chats)))
        assertEquals(mapOf("chats" to "desired-chats"), logicalContent)
        assertFalse(root.exists())
    }

    @Test
    fun versionOneJournalRemainsRecoverableAfterTheMarkerUpgrade() {
        val root = tempRoot().apply { mkdirs() }
        root.resolve("state.json").writeText(
            JSONObject()
                .put("version", 1)
                .put("phase", "APPLYING")
                .put("started", JSONArray().put("chats"))
                .toString()
        )
        val logicalContent = linkedMapOf("chats" to "partially-applied")
        val chats = statefulParticipant("chats", logicalContent, "desired-chats", "original-chats")

        assertTrue(SelectedCategoryRestoreTransaction.recover(root, mapOf("chats" to chats)))
        assertEquals(mapOf("chats" to "original-chats"), logicalContent)
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

    @Test
    fun stagingFailureKeepsTheParticipantsOwnReason() {
        val events = ArrayList<String>()
        val failing = object : SelectedCategoryRestoreTransaction.Participant {
            override val categoryKey = "identity_bundle"
            override fun validate() = true
            override fun stage() = false
            override fun apply() = true
            override fun rollback() = true
            override fun cleanup() = Unit
            override fun failureDetail() = "current_snapshot_mismatch_after_reread: format version"
        }

        val result = SelectedCategoryRestoreTransaction.execute(
            tempRoot(), listOf(participant("chats", events), failing)
        ) as SelectedCategoryRestoreTransaction.Result.Failed

        assertEquals(SelectedCategoryRestoreTransaction.Failure.STAGING_FAILED, result.reason)
        assertEquals("identity_bundle", result.categoryKey)
        assertEquals("current_snapshot_mismatch_after_reread: format version", result.detail)
    }

    @Test
    fun unexpectedErrorIsRecordedByTypeWithoutItsMessage() {
        val throwing = object : SelectedCategoryRestoreTransaction.Participant {
            override val categoryKey = "memories"
            override fun validate() = true
            override fun stage(): Boolean = throw IllegalStateException("private text")
            override fun apply() = true
            override fun rollback() = true
            override fun cleanup() = Unit
        }

        val result = SelectedCategoryRestoreTransaction.execute(
            tempRoot(), listOf(throwing)
        ) as SelectedCategoryRestoreTransaction.Result.Failed

        assertEquals(SelectedCategoryRestoreTransaction.Failure.STAGING_FAILED, result.reason)
        assertEquals("unexpected_error: IllegalStateException", result.detail)
    }

    @Test
    fun rollbackFailureReportsTheApplyReasonAndEachFailedRollback() {
        val events = ArrayList<String>()
        val result = SelectedCategoryRestoreTransaction.execute(
            tempRoot(),
            listOf(
                participant("chats", events, rollsBack = false),
                participant("memories", events, applies = false)
            )
        ) as SelectedCategoryRestoreTransaction.Result.Failed

        assertEquals(SelectedCategoryRestoreTransaction.Failure.ROLLBACK_FAILED, result.reason)
        assertEquals(SelectedCategoryRestoreTransaction.DataState.RECOVERY_REQUIRED, result.dataState)
        assertEquals(
            "after APPLY_FAILED: no_reason_given; rollback of chats failed: no_reason_given",
            result.detail
        )
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

    private fun statefulParticipant(
        key: String,
        logicalContent: MutableMap<String, String>,
        desired: String,
        stagedOriginal: String = logicalContent.getValue(key)
    ): SelectedCategoryRestoreTransaction.Participant {
        return object : SelectedCategoryRestoreTransaction.Participant {
            override val categoryKey = key
            override fun validate() = true
            override fun stage() = true
            override fun apply(): Boolean {
                logicalContent[key] = desired
                return true
            }
            override fun rollback(): Boolean {
                logicalContent[key] = stagedOriginal
                return true
            }
            override fun cleanup() = Unit
        }
    }

    private class SimulatedProcessDeath : Error()

    private fun tempRoot() = Files.createTempDirectory("restore-transaction-test")
        .resolve("journal")
        .toFile()
}
