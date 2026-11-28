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

package org.teslasoft.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** The memory breakdown on the memory sample line and the heap field on the chat-destroy line. */
class MemoryFieldsTest {

    @Test fun androidsBreakdownIsShownInMegabytesInAFixedOrder() {
        val stats = mapOf(
            "summary.java-heap" to "140288",
            "summary.native-heap" to "1589248",
            "summary.graphics" to "40960",
            "summary.code" to "61440",
            "summary.stack" to "2048",
            "summary.private-other" to "20480",
            "summary.system" to "30720",
            "summary.total-pss" to "1561600",
            "summary.total-swap" to "312320"
        )
        assertEquals(
            "java=137MB native=1552MB graphics=40MB code=60MB other=20MB swap=305MB",
            MemoryFields.breakdown(stats)
        )
    }

    @Test fun aCategoryAndroidDidNotReportShowsAQuestionMark() {
        assertEquals(
            "java=? native=? graphics=? code=? other=? swap=?",
            MemoryFields.breakdown(emptyMap())
        )
        assertEquals(
            "java=1MB native=? graphics=? code=? other=? swap=?",
            MemoryFields.breakdown(mapOf("summary.java-heap" to "1024", "summary.native-heap" to "n/a"))
        )
    }

    @Test fun theHeapFieldShowsUseAndLimit() {
        assertEquals(
            "heap=139/256MB",
            MemoryFields.javaHeap(139L * 1024 * 1024, 256L * 1024 * 1024)
        )
    }
}
