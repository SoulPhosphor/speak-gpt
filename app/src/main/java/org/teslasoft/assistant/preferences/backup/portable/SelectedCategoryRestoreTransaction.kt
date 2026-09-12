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

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.util.AtomicFileWriter

/**
 * Durable all-or-nothing boundary for the selected restore categories.
 * Participants own their category-specific staging and rollback data. This
 * coordinator guarantees ordering and records which participants may have
 * begun mutation before calling them.
 */
object SelectedCategoryRestoreTransaction {
    interface Participant {
        /** Stable non-sensitive category key persisted in the journal. */
        val categoryKey: String

        /** Read-only validation. */
        fun validate(): Boolean

        /** Prepare incoming data and a complete rollback source. No live writes. */
        fun stage(): Boolean

        /** Apply the staged category. May be called only after every stage succeeds. */
        fun apply(): Boolean

        /** Restore the exact pre-transaction category state. */
        fun rollback(): Boolean

        /** Remove category-owned staging after success or complete rollback. */
        fun cleanup()
    }

    enum class Failure {
        EMPTY_SELECTION,
        DUPLICATE_CATEGORY,
        PENDING_RECOVERY,
        VALIDATION_FAILED,
        STAGING_FAILED,
        JOURNAL_FAILED,
        APPLY_FAILED,
        ROLLBACK_FAILED
    }

    sealed class Result {
        data object Success : Result()
        data class Failed(
            val reason: Failure,
            val categoryKey: String? = null,
            val dataState: DataState = DataState.UNCHANGED
        ) : Result()
    }

    enum class DataState {
        /** Validation/staging failed, or every started write rolled back. */
        UNCHANGED,
        /** Every selected category applied; only durable-journal cleanup failed. */
        RESTORED_CLEANUP_PENDING,
        /** At least one attempted write could not be rolled back. */
        RECOVERY_REQUIRED
    }

    enum class InterruptionPoint {
        AFTER_PREPARED,
        AFTER_FIRST_PARTICIPANT_STARTED,
        AFTER_FIRST_APPLY
    }
    private class SimulatedInterruption : Error("simulated process interruption")

    fun execute(
        journalRoot: File,
        participants: List<Participant>,
        interruptionPoint: InterruptionPoint? = null,
        beforeCleanup: () -> Boolean = { true }
    ): Result {
        if (participants.isEmpty()) return Result.Failed(Failure.EMPTY_SELECTION)
        val keys = participants.map { it.categoryKey }
        if (keys.any { !safeKey(it) } || keys.size != keys.toSet().size) {
            return Result.Failed(Failure.DUPLICATE_CATEGORY)
        }
        if (journalRoot.exists()) return Result.Failed(Failure.PENDING_RECOVERY)

        for (participant in participants) {
            if (!safeCall(participant::validate)) {
                cleanup(participants)
                return Result.Failed(Failure.VALIDATION_FAILED, participant.categoryKey)
            }
        }
        for (participant in participants) {
            if (!safeCall(participant::stage)) {
                cleanup(participants)
                return Result.Failed(Failure.STAGING_FAILED, participant.categoryKey)
            }
        }
        if (!journalRoot.mkdirs()) {
            cleanup(participants)
            return Result.Failed(Failure.JOURNAL_FAILED)
        }
        if (!writeState(journalRoot, Phase.PREPARED, emptyList())) {
            journalRoot.deleteRecursively()
            cleanup(participants)
            return Result.Failed(Failure.JOURNAL_FAILED)
        }
        if (interruptionPoint == InterruptionPoint.AFTER_PREPARED) {
            throw SimulatedInterruption()
        }

        val started = ArrayList<Participant>()
        for ((index, participant) in participants.withIndex()) {
            started.add(participant)
            // Persist BEFORE apply: a process death or false return may have
            // happened after a partial live write, so this participant must be
            // included in recovery even when apply never reports success.
            if (!writeState(journalRoot, Phase.APPLYING, started.map { it.categoryKey })) {
                return rollbackAfterFailure(journalRoot, started, Failure.JOURNAL_FAILED, participant.categoryKey)
            }
            if (index == 0 &&
                interruptionPoint == InterruptionPoint.AFTER_FIRST_PARTICIPANT_STARTED
            ) {
                throw SimulatedInterruption()
            }
            if (!safeCall(participant::apply)) {
                return rollbackAfterFailure(journalRoot, started, Failure.APPLY_FAILED, participant.categoryKey)
            }
            if (index == 0 && interruptionPoint == InterruptionPoint.AFTER_FIRST_APPLY) {
                throw SimulatedInterruption()
            }
        }

        if (!writeState(journalRoot, Phase.COMPLETE, started.map { it.categoryKey })) {
            return rollbackAfterFailure(journalRoot, started, Failure.JOURNAL_FAILED, null)
        }
        // COMPLETE remains durable until the service has written the terminal
        // result that the clean process will consume. If publication fails,
        // startup recovery may safely finish cleanup without replaying apply.
        if (!safeCall(beforeCleanup)) return Result.Failed(
            Failure.JOURNAL_FAILED,
            dataState = DataState.RESTORED_CLEANUP_PENDING
        )
        cleanup(participants)
        if (!deleteJournal(journalRoot)) return Result.Failed(
            Failure.JOURNAL_FAILED,
            dataState = DataState.RESTORED_CLEANUP_PENDING
        )
        return Result.Success
    }

    /**
     * Resolve a journal left by process death. The caller supplies freshly
     * constructed category handlers keyed by the stable keys in the journal.
     */
    fun recover(journalRoot: File, participants: Map<String, Participant>): Boolean {
        if (!journalRoot.exists()) return true
        val state = readState(journalRoot) ?: return false
        if (state.phase == Phase.COMPLETE) {
            state.started.mapNotNull(participants::get).let(::cleanup)
            return deleteJournal(journalRoot)
        }
        val started = state.started.map { participants[it] ?: return false }
        var ok = true
        for (participant in started.asReversed()) {
            if (!safeCall(participant::rollback)) ok = false
        }
        if (!ok) return false
        cleanup(started)
        return deleteJournal(journalRoot)
    }

    private fun rollbackAfterFailure(
        journalRoot: File,
        started: List<Participant>,
        originalFailure: Failure,
        categoryKey: String?
    ): Result {
        var rolledBack = true
        for (participant in started.asReversed()) {
            if (!safeCall(participant::rollback)) rolledBack = false
        }
        if (!rolledBack) return Result.Failed(
            Failure.ROLLBACK_FAILED,
            categoryKey,
            DataState.RECOVERY_REQUIRED
        )
        // Keep exact rollback snapshots until the durable journal is gone. If
        // deletion fails, a later recovery pass can safely repeat rollback.
        if (!deleteJournal(journalRoot)) return Result.Failed(Failure.JOURNAL_FAILED, categoryKey)
        cleanup(started)
        return Result.Failed(originalFailure, categoryKey)
    }

    private enum class Phase { PREPARED, APPLYING, COMPLETE }
    private data class State(val phase: Phase, val started: List<String>)

    private fun writeState(root: File, phase: Phase, started: List<String>): Boolean =
        AtomicFileWriter.writeAndVerify(
            File(root, STATE_FILE),
            JSONObject()
                .put("version", 1)
                .put("phase", phase.name)
                .put("started", JSONArray().apply { started.forEach(::put) })
                .toString()
        )

    private fun readState(root: File): State? {
        return try {
            val file = File(root, STATE_FILE)
            if (!file.isFile || file.length() > MAX_STATE_BYTES) return null
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (json.optInt("version", -1) != 1) return null
            val startedJson = json.getJSONArray("started")
            val started = ArrayList<String>(startedJson.length())
            repeat(startedJson.length()) {
                val key = startedJson.getString(it)
                if (!safeKey(key) || !started.add(key)) return null
            }
            State(Phase.valueOf(json.getString("phase")), started)
        } catch (_: Exception) {
            null
        }
    }

    private fun safeCall(operation: () -> Boolean): Boolean = try {
        operation()
    } catch (_: Exception) {
        false
    }

    private fun cleanup(participants: List<Participant>) {
        participants.forEach { runCatching(it::cleanup) }
    }

    private fun deleteJournal(root: File): Boolean =
        root.deleteRecursively() || !root.exists()

    private fun safeKey(value: String): Boolean =
        value.matches(Regex("[a-z][a-z0-9_-]{0,63}"))

    private const val STATE_FILE = "state.json"
    private const val MAX_STATE_BYTES = 64L * 1024L
}
