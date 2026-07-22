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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crash report's screen/activity breadcrumb formatting. The whole point is
 * robustness in a broken state, so the tests are about null / malformed / huge
 * inputs and noise collapsing, not happy-path prettiness.
 */
class CrashReportFormatTest {

    @Test
    fun nullYieldsTheNoneMarkerNotACrash() {
        assertEquals(CrashReportFormat.NONE_RECORDED, CrashReportFormat.formatActivityHistory(null))
    }

    @Test
    fun blankAndWhitespaceOnlyYieldTheNoneMarker() {
        assertEquals(CrashReportFormat.NONE_RECORDED, CrashReportFormat.formatActivityHistory(""))
        assertEquals(CrashReportFormat.NONE_RECORDED, CrashReportFormat.formatActivityHistory("   \n  \n\t"))
    }

    @Test
    fun normalHistoryIsTrimmedAndPreservedInOrder() {
        val raw = "2026-07-22 14:36:01: MainActivity created\n" +
            "2026-07-22 14:36:20: ChatActivity resumed\n" +
            "2026-07-22 14:36:25: SettingsActivity resumed"
        assertEquals(
            "2026-07-22 14:36:01: MainActivity created\n" +
                "2026-07-22 14:36:20: ChatActivity resumed\n" +
                "2026-07-22 14:36:25: SettingsActivity resumed",
            CrashReportFormat.formatActivityHistory(raw)
        )
    }

    @Test
    fun consecutiveDuplicatesCollapseButRevisitsSurvive() {
        // A repeated identical line (library churn) collapses; a genuine
        // revisit of a screen later in the trail is kept.
        val raw = "MainActivity resumed\nMainActivity resumed\nChatActivity resumed\nMainActivity resumed"
        assertEquals(
            "MainActivity resumed\nChatActivity resumed\nMainActivity resumed",
            CrashReportFormat.formatActivityHistory(raw)
        )
    }

    @Test
    fun blankInteriorLinesAreDropped() {
        val raw = "A created\n\n\nB resumed\n   \nC paused"
        assertEquals("A created\nB resumed\nC paused", CrashReportFormat.formatActivityHistory(raw))
    }

    @Test
    fun tooManyLinesKeepsOnlyTheMostRecent() {
        // 200 distinct lines, cap 5 -> the last 5 (nearest the crash) survive.
        val raw = (1..200).joinToString("\n") { "line $it" }
        val out = CrashReportFormat.formatActivityHistory(raw, maxLines = 5)
        assertEquals("line 196\nline 197\nline 198\nline 199\nline 200", out)
    }

    @Test
    fun oversizedInputIsCappedAndMarkedTruncated() {
        // One giant line well over the char cap must be bounded, not passed through.
        val raw = "x".repeat(50_000)
        val out = CrashReportFormat.formatActivityHistory(raw, maxChars = 1000)
        assertTrue(out.startsWith("…(truncated)"))
        // marker + newline (14) + exactly maxChars of content
        assertEquals("…(truncated)\n".length + 1000, out.length)
    }

    @Test
    fun withinCapsIsReturnedVerbatimWithNoTruncationMarker() {
        val raw = "A created\nB resumed"
        val out = CrashReportFormat.formatActivityHistory(raw, maxLines = 60, maxChars = 8000)
        assertTrue(!out.contains("truncated"))
        assertEquals("A created\nB resumed", out)
    }
}
