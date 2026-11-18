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

package org.teslasoft.assistant.preferences.memory.archivist

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatScanPassMergeTest {

    private fun outcome(result: String, memories: Int = 0) =
        Archivist.RunOutcome(null, 1, 1, memories, 0, emptyList(), outcome = result)

    @Test fun laterFailureAfterSavedMemoriesIsPartial() {
        val merged = Archivist.mergePasses(outcome("completed", 2), outcome("full_failed"))
        assertEquals("partial_failed", merged.outcome)
        assertEquals(2, merged.memoriesFound)
    }

    @Test fun laterPassWithNothingNewKeepsTheScanComplete() {
        assertEquals("completed", Archivist.mergePasses(outcome("completed", 1), outcome("no_new")).outcome)
    }

    @Test fun nothingSavedEarlierKeepsTheLaterOutcome() {
        assertEquals("full_failed", Archivist.mergePasses(outcome("no_new"), outcome("full_failed")).outcome)
        assertEquals("completed", Archivist.mergePasses(outcome("no_new"), outcome("completed", 3)).outcome)
    }

    @Test fun aStopStaysAStop() {
        assertEquals("cancelled", Archivist.mergePasses(outcome("completed", 1), outcome("cancelled")).outcome)
    }
}
