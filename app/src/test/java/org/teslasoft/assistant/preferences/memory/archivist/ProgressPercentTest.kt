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

class ProgressPercentTest {

    @Test fun oneChatCountsFinishedSections() {
        // Third of eight sections in progress: two finished.
        assertEquals(25, Archivist.Progress(1, 1, 1, 1, 1, 1, sectionsDone = 2, sectionsTotal = 8).percent())
        assertEquals(0, Archivist.Progress(1, 1, 1, 1, 1, 1, sectionsDone = 0, sectionsTotal = 8).percent())
    }

    @Test fun oneChatNotYetSplitReadsZero() {
        assertEquals(0, Archivist.Progress(1, 1, 1, 1, 1, 1).percent())
    }

    @Test fun aBatchCountsFinishedConversationsOnly() {
        // Second of four conversations, halfway through its sections: still 25%.
        assertEquals(25, Archivist.Progress(1, 1, 2, 4, 2, 4, sectionsDone = 3, sectionsTotal = 6).percent())
    }

    @Test fun unknownTotalReadsZero() {
        assertEquals(0, Archivist.Progress(1, 1, 1, 1, 0, 0, sectionsDone = 1, sectionsTotal = 2).percent())
    }
}
