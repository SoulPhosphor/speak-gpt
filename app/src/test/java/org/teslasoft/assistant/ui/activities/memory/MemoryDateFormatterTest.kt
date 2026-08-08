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

package org.teslasoft.assistant.ui.activities.memory

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryDateFormatterTest {

    @Test
    fun supersessionTimestampUsesExistingLongDatePresentation() {
        assertEquals(
            "August 8, 2026",
            MemoryDateFormatter.format(
                "2026-08-08T22:15:00Z", Locale.US, ZoneId.of("UTC")
            )
        )
    }

    @Test
    fun unreadableLegacyTimestampRemainsVisible() {
        assertEquals("legacy-date", MemoryDateFormatter.format("legacy-date", Locale.US))
    }
}
