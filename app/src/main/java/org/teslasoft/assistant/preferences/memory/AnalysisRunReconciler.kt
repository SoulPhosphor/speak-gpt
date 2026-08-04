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

package org.teslasoft.assistant.preferences.memory

/**
 * The recovery decision for the minimal temporary analysis-run storage
 * (canonical recovery plan §8.10, Phase 1 item 14).
 *
 * Temporary run state exists only long enough to finish or safely recover a
 * run. It is never provenance and never becomes part of a saved memory. The
 * rule this object encodes:
 *
 *  - a run whose candidates were consolidated and filed is DONE — its
 *    temporary rows are cleared;
 *  - a run that did not reach the filed state (process death, cancellation,
 *    or failure) is INTERRUPTED — its temporary candidates are discarded and
 *    nothing is left half-filed; the conversation bookmark is not advanced,
 *    so the frozen range is analyzed again cleanly on the next run.
 *
 * There is deliberately no third outcome: temporary state is either finished
 * work being cleaned up, or unfinished work being discarded. It never
 * survives as memory metadata.
 *
 * Pure Kotlin (no Android, no SQLCipher) so the interrupted-run case runs as
 * an ordinary JVM unit test; the store executes the returned decision.
 */
object AnalysisRunReconciler {

    /**
     * A temporary run's recoverable state.
     *
     * @param runId the run's id.
     * @param filed whether the run reached the point where its consolidated,
     *   validated candidates were filed into visible Pending and the bookmark
     *   was safely advanced. Only a filed run is finished work.
     */
    data class RunState(
        val runId: String,
        val filed: Boolean
    )

    /**
     * The run ids whose temporary candidates and state must be DISCARDED on
     * startup because the run never reached the filed state. These runs left
     * no visible memory behind and their frozen range will be reanalyzed.
     */
    fun interruptedRunIds(runs: List<RunState>): List<String> =
        runs.filterNot { it.filed }.map { it.runId }

    /**
     * The run ids that completed filing; their temporary rows are stale
     * bookkeeping and can be cleaned up, but they are NOT reanalyzed and no
     * candidate is discarded.
     */
    fun completedRunIds(runs: List<RunState>): List<String> =
        runs.filter { it.filed }.map { it.runId }
}
