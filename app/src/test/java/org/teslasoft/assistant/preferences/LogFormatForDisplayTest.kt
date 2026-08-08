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

package org.teslasoft.assistant.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Logs viewer's display formatting: ordering (newest or oldest first)
 * and the one-blank-line separation between whole entries. Storage is untouched;
 * this only shapes what the screen shows.
 */
class LogFormatForDisplayTest {

    // Two ordinary single-line entries as the standard log() path writes them:
    // header + space + body, separated by only a line break (no blank line).
    private val twoSingleLine =
        "[2026-08-07 4:15 PM] [Tag] [INFO] first\n" +
        "[2026-08-07 4:16 PM] [Tag] [INFO] second\n"

    @Test fun oldestFirstKeepsStoredOrder() {
        assertEquals(
            "[2026-08-07 4:15 PM] [Tag] [INFO] first\n\n" +
            "[2026-08-07 4:16 PM] [Tag] [INFO] second",
            Logger.formatForDisplay(twoSingleLine, newestFirst = false)
        )
    }

    @Test fun newestFirstReversesEntries() {
        assertEquals(
            "[2026-08-07 4:16 PM] [Tag] [INFO] second\n\n" +
            "[2026-08-07 4:15 PM] [Tag] [INFO] first",
            Logger.formatForDisplay(twoSingleLine, newestFirst = true)
        )
    }

    @Test fun neverLeavesMoreThanOneBlankLineBetweenEntries() {
        // The readability fix for the logs that ran their entries together:
        // the gap between any two entries is exactly one blank line, so three
        // consecutive newlines must never appear.
        val out = Logger.formatForDisplay(twoSingleLine, newestFirst = false)
        assertEquals(false, out.contains("\n\n\n"))
    }

    @Test fun normalizesExtraBlankLinesToOne() {
        // Provider Failure / Response Lifecycle store two blank lines between
        // entries; display collapses that to a single blank line so every log
        // spaces the same way.
        val doubleSpaced =
            "[2026-08-07 4:15 PM]\nProvider Error: a\n\n\n" +
            "[2026-08-07 4:16 PM]\nProvider Error: b\n\n\n"
        assertEquals(
            "[2026-08-07 4:15 PM]\nProvider Error: a\n\n" +
            "[2026-08-07 4:16 PM]\nProvider Error: b",
            Logger.formatForDisplay(doubleSpaced, newestFirst = false)
        )
    }

    @Test fun keepsMultiLineEntryIntactAndReordersAsAUnit() {
        // A multi-line entry (e.g. a stack trace) must move as one block, never
        // split by its interior newlines.
        val multi =
            "[2026-08-07 4:15 PM] [A] [INFO] one\n" +
            "[2026-08-07 4:16 PM] [B] [ERROR] two\n  at x\n  at y\n"
        assertEquals(
            "[2026-08-07 4:16 PM] [B] [ERROR] two\n  at x\n  at y\n\n" +
            "[2026-08-07 4:15 PM] [A] [INFO] one",
            Logger.formatForDisplay(multi, newestFirst = true)
        )
    }

    @Test fun emptyLogStaysEmpty() {
        assertEquals("", Logger.formatForDisplay("", newestFirst = true))
    }

    @Test fun contentWithoutHeadersIsLeftUnchanged() {
        val noHeaders = "just some text\nwith no entry header"
        assertEquals(noHeaders, Logger.formatForDisplay(noHeaders, newestFirst = true))
    }
}
