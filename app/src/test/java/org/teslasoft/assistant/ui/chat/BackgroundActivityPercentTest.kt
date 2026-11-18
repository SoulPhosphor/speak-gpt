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

package org.teslasoft.assistant.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.archivist.Archivist

class BackgroundActivityPercentTest {

    private fun progress(index: Int, count: Int) = Archivist.Progress(1, 1, index, count, index, count)

    @Test fun unknownTotalReadsZero() {
        assertEquals(0, BackgroundActivityPercent.memoryScan(null))
        assertEquals(0, BackgroundActivityPercent.memoryScan(progress(1, 0)))
    }

    @Test fun countsOnlyFinishedConversations() {
        // The second of four is in progress: one finished.
        assertEquals(25, BackgroundActivityPercent.memoryScan(progress(2, 4)))
        assertEquals(75, BackgroundActivityPercent.memoryScan(progress(4, 4)))
    }
}
