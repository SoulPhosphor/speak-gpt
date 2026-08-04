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

import org.junit.Assert.assertEquals
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.AnalysisRunReconciler.RunState

/**
 * The interrupted temporary analysis-run recovery decision (canonical
 * recovery plan §8.10, Phase 1 item 17). An unfinished run's temporary
 * candidates are discarded and nothing is left half-filed; a filed run is
 * finished work that is merely cleaned up, never reanalyzed.
 */
class AnalysisRunReconcilerTest {

    @Test
    fun interruptedRunIsDiscarded() {
        val runs = listOf(RunState("run-a", filed = false))
        assertEquals(listOf("run-a"), AnalysisRunReconciler.interruptedRunIds(runs))
        assertEquals(emptyList<String>(), AnalysisRunReconciler.completedRunIds(runs))
    }

    @Test
    fun filedRunIsCompletedNotDiscarded() {
        val runs = listOf(RunState("run-b", filed = true))
        assertEquals(emptyList<String>(), AnalysisRunReconciler.interruptedRunIds(runs))
        assertEquals(listOf("run-b"), AnalysisRunReconciler.completedRunIds(runs))
    }

    @Test
    fun mixedRunsPartitionCleanly() {
        val runs = listOf(
            RunState("done-1", filed = true),
            RunState("dead-1", filed = false),
            RunState("dead-2", filed = false),
            RunState("done-2", filed = true)
        )
        assertEquals(listOf("dead-1", "dead-2"), AnalysisRunReconciler.interruptedRunIds(runs))
        assertEquals(listOf("done-1", "done-2"), AnalysisRunReconciler.completedRunIds(runs))
    }

    @Test
    fun emptyStateIsNoOp() {
        assertEquals(emptyList<String>(), AnalysisRunReconciler.interruptedRunIds(emptyList()))
        assertEquals(emptyList<String>(), AnalysisRunReconciler.completedRunIds(emptyList()))
    }
}
