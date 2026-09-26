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

        /** The non-content reason for this participant's most recent false
         * result: a reason code, optionally followed by table/column names,
         * counts or value types. Never a value, name, or record ID. */
        fun failureDetail(): String? = null
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
            val dataState: DataState = DataState.UNCHANGED,
            /** Diagnostic detail for the Error Log only; never shown in a dialog. */
            val detail: String? = null
        ) : Result()
    }

    enum class RecoveryStep {
        READ_JOURNAL,
        MISSING_PARTICIPANT,
        ROLLBACK,
        DELETE_JOURNAL
    }

    sealed class RecoveryResult {
        data object Success : RecoveryResult()
        data class Failed(
            val step: RecoveryStep,
            val categoryKey: String? = null,
            val detail: String? = null
        ) : RecoveryResult()
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
    enum class Boundary {
        AFTER_PREPARED,
        AFTER_PARTICIPANT_STARTED,
        AFTER_PARTICIPANT_COMPLETED,
        AFTER_TRANSACTION_COMPLETED
    }
    fun interface FaultInjector {
        fun hit(boundary: Boundary, categoryKey: String?)
    }
    private class SimulatedInterruption : Error("simulated process interruption")

    fun execute(
        journalRoot: File,
        participants: List<Participant>,
        interruptionPoint: InterruptionPoint? = null,
        beforeCleanup: () -> Boolean = { true },
        faultInjector: FaultInjector = FaultInjector { _, _ -> }
    ): Result {
        if (participants.isEmpty()) return Result.Failed(Failure.EMPTY_SELECTION)
        val keys = participants.map { it.categoryKey }
        if (keys.any { !safeKey(it) } || keys.size != keys.toSet().size) {
            return Result.Failed(Failure.DUPLICATE_CATEGORY)
        }
        if (journalRoot.exists()) return Result.Failed(Failure.PENDING_RECOVERY)

        for (participant in participants) {
            attempt(participant, participant::validate)?.let { detail ->
                cleanup(participants)
                return Result.Failed(
                    Failure.VALIDATION_FAILED, participant.categoryKey, detail = detail
                )
            }
        }
        for (participant in participants) {
            attempt(participant, participant::stage)?.let { detail ->
                cleanup(participants)
                return Result.Failed(
                    Failure.STAGING_FAILED, participant.categoryKey, detail = detail
                )
            }
        }
        if (!journalRoot.mkdirs()) {
            cleanup(participants)
            return Result.Failed(Failure.JOURNAL_FAILED, detail = "journal_directory_unavailable")
        }
        if (!writeState(journalRoot, Phase.PREPARED, emptyList(), emptyList())) {
            journalRoot.deleteRecursively()
            cleanup(participants)
            return Result.Failed(Failure.JOURNAL_FAILED, detail = "journal_write_failed: PREPARED")
        }
        if (interruptionPoint == InterruptionPoint.AFTER_PREPARED) {
            throw SimulatedInterruption()
        }
        faultInjector.hit(Boundary.AFTER_PREPARED, null)

        val started = ArrayList<Participant>()
        val completed = ArrayList<Participant>()
        for ((index, participant) in participants.withIndex()) {
            started.add(participant)
            // Persist BEFORE apply: a process death or false return may have
            // happened after a partial live write, so this participant must be
            // included in recovery even when apply never reports success.
            if (!writeState(
                    journalRoot,
                    Phase.APPLYING,
                    started.map { it.categoryKey },
                    completed.map { it.categoryKey }
                )
            ) {
                return rollbackAfterFailure(
                    journalRoot, started, Failure.JOURNAL_FAILED, participant.categoryKey,
                    "journal_write_failed: APPLYING before apply"
                )
            }
            if (index == 0 &&
                interruptionPoint == InterruptionPoint.AFTER_FIRST_PARTICIPANT_STARTED
            ) {
                throw SimulatedInterruption()
            }
            faultInjector.hit(Boundary.AFTER_PARTICIPANT_STARTED, participant.categoryKey)
            attempt(participant, participant::apply)?.let { detail ->
                return rollbackAfterFailure(
                    journalRoot, started, Failure.APPLY_FAILED, participant.categoryKey, detail
                )
            }
            completed.add(participant)
            if (!writeState(
                    journalRoot,
                    Phase.APPLYING,
                    started.map { it.categoryKey },
                    completed.map { it.categoryKey }
                )
            ) {
                return rollbackAfterFailure(
                    journalRoot, started, Failure.JOURNAL_FAILED, participant.categoryKey,
                    "journal_write_failed: APPLYING after apply"
                )
            }
            if (index == 0 && interruptionPoint == InterruptionPoint.AFTER_FIRST_APPLY) {
                throw SimulatedInterruption()
            }
            faultInjector.hit(Boundary.AFTER_PARTICIPANT_COMPLETED, participant.categoryKey)
        }

        if (!writeState(
                journalRoot,
                Phase.COMPLETE,
                started.map { it.categoryKey },
                completed.map { it.categoryKey }
            )
        ) {
            return rollbackAfterFailure(
                journalRoot, started, Failure.JOURNAL_FAILED, null, "journal_write_failed: COMPLETE"
            )
        }
        faultInjector.hit(Boundary.AFTER_TRANSACTION_COMPLETED, null)
        // COMPLETE remains durable until the service has written the terminal
        // result that the clean process will consume. If publication fails,
        // startup recovery may safely finish cleanup without replaying apply.
        if (!safeCall(beforeCleanup)) return Result.Failed(
            Failure.JOURNAL_FAILED,
            dataState = DataState.RESTORED_CLEANUP_PENDING,
            detail = "result_record_not_saved"
        )
        cleanup(participants)
        if (!deleteJournal(journalRoot)) return Result.Failed(
            Failure.JOURNAL_FAILED,
            dataState = DataState.RESTORED_CLEANUP_PENDING,
            detail = "journal_delete_failed"
        )
        return Result.Success
    }

    /**
     * Resolve a journal left by process death. The caller supplies freshly
     * constructed category handlers keyed by the stable keys in the journal.
     */
    fun recover(journalRoot: File, participants: Map<String, Participant>): Boolean =
        recoverDetailed(journalRoot, participants) == RecoveryResult.Success

    /**
     * Same recovery behavior as [recover], but preserves the exact non-content
     * reason when recovery cannot finish so the UI and Error Log do not collapse
     * every failure into an unexplained retry loop.
     */
    fun recoverDetailed(
        journalRoot: File,
        participants: Map<String, Participant>
    ): RecoveryResult {
        if (!journalRoot.exists()) return RecoveryResult.Success
        val state = readState(journalRoot) ?: return RecoveryResult.Failed(
            RecoveryStep.READ_JOURNAL,
            detail = "journal_state_unreadable"
        )
        if (state.phase == Phase.COMPLETE) {
            state.started.mapNotNull(participants::get).let(::cleanup)
            return if (deleteJournal(journalRoot)) RecoveryResult.Success
            else RecoveryResult.Failed(
                RecoveryStep.DELETE_JOURNAL,
                detail = "journal_delete_failed_after_complete"
            )
        }

        val started = ArrayList<Participant>(state.started.size)
        for (key in state.started) {
            val participant = participants[key] ?: return RecoveryResult.Failed(
                RecoveryStep.MISSING_PARTICIPANT,
                categoryKey = key,
                detail = "recovery_participant_unavailable"
            )
            started.add(participant)
        }

        val failures = ArrayList<Pair<String, String>>()
        for (participant in started.asReversed()) {
            attempt(participant, participant::rollback)?.let { detail ->
                failures.add(participant.categoryKey to detail)
            }
        }
        if (failures.isNotEmpty()) {
            val first = failures.first()
            return RecoveryResult.Failed(
                RecoveryStep.ROLLBACK,
                categoryKey = first.first,
                detail = failures.joinToString("; ") { (key, detail) -> "$key: $detail" }
            )
        }

        cleanup(started)
        return if (deleteJournal(journalRoot)) RecoveryResult.Success
        else RecoveryResult.Failed(
            RecoveryStep.DELETE_JOURNAL,
            detail = "journal_delete_failed_after_rollback"
        )
    }

    private fun rollbackAfterFailure(
        journalRoot: File,
        started: List<Participant>,
        originalFailure: Failure,
        categoryKey: String?,
        detail: String?
    ): Result {
        val rollbackFailures = ArrayList<String>()
        for (participant in started.asReversed()) {
            attempt(participant, participant::rollback)?.let {
                rollbackFailures.add("rollback of ${participant.categoryKey} failed: $it")
            }
        }
        val original = "${originalFailure.name}: ${detail ?: "no_reason_given"}"
        if (rollbackFailures.isNotEmpty()) return Result.Failed(
            Failure.ROLLBACK_FAILED,
            categoryKey,
            DataState.RECOVERY_REQUIRED,
            (listOf("after $original") + rollbackFailures).joinToString("; ")
        )
        // Keep exact rollback snapshots until the durable journal is gone. If
        // deletion fails, a later recovery pass can safely repeat rollback.
        if (!deleteJournal(journalRoot)) return Result.Failed(
            Failure.JOURNAL_FAILED, categoryKey, detail = "journal_delete_failed after $original"
        )
        cleanup(started)
        return Result.Failed(originalFailure, categoryKey, detail = detail)
    }

    private enum class Phase { PREPARED, APPLYING, COMPLETE }
    private data class State(
        val phase: Phase,
        val started: List<String>,
        val completed: List<String>
    )

    private fun writeState(
        root: File,
        phase: Phase,
        started: List<String>,
        completed: List<String>
    ): Boolean =
        AtomicFileWriter.writeAndVerify(
            File(root, STATE_FILE),
            JSONObject()
                .put("version", 2)
                .put("phase", phase.name)
                .put("started", JSONArray().apply { started.forEach(::put) })
                .put("completed", JSONArray().apply { completed.forEach(::put) })
                .toString()
        )

    private fun readState(root: File): State? {
        return try {
            val file = File(root, STATE_FILE)
            if (!file.isFile || file.length() > MAX_STATE_BYTES) return null
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val version = json.optInt("version", -1)
            if (version !in 1..2) return null
            val started = readKeys(json.getJSONArray("started")) ?: return null
            val completed = if (version == 1) emptyList() else {
                readKeys(json.getJSONArray("completed")) ?: return null
            }
            if (!started.containsAll(completed)) return null
            val phase = Phase.valueOf(json.getString("phase"))
            if (version >= 2 && phase == Phase.COMPLETE && completed != started) return null
            State(phase, started, completed)
        } catch (_: Exception) {
            null
        }
    }

    private fun readKeys(values: JSONArray): List<String>? {
        val keys = ArrayList<String>(values.length())
        repeat(values.length()) {
            val key = values.getString(it)
            if (!safeKey(key) || !keys.add(key)) return null
        }
        return keys
    }

    /** Null when [operation] succeeds; otherwise the participant's own reason,
     * or the type of the unexpected error it threw. */
    private fun attempt(participant: Participant, operation: () -> Boolean): String? = try {
        if (operation()) null else participant.failureDetail() ?: "no_reason_given"
    } catch (e: Exception) {
        PortableRestoreDiagnostics.unexpected(e)
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
