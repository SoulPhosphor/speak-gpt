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
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** The memory lines added to a "killed by the system to free memory" entry. */
class LoggerMemoryKillTest {

    private val zone = ZoneId.of("America/Detroit")
    private fun ms(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private val snapshot = "uptime=28m pss=1525MB heap=137/256/256MB native=1552/1596MB | whisper=small.en handsFreeService=true"

    @Test fun androidsFiguresAndTheKilledSessionsLastSampleAreBothShown() {
        val sample = Logger.MemorySample(ms("2026-11-27T20:20:10"), pid = 4242, snapshot = snapshot)
        assertEquals(
            "Memory at exit: pss=1530MB rss=1610MB\n" +
                "Last sample (2026-11-27 8:20 PM): $snapshot",
            Logger.lowMemoryExitLines(1530L * 1024, 1610L * 1024, sample, 4242, ms("2026-11-27T20:21:30"), zone)
        )
    }

    @Test fun aSampleFromAnotherProcessIsNeverShown() {
        val sample = Logger.MemorySample(ms("2026-11-27T20:20:10"), pid = 1111, snapshot = snapshot)
        assertEquals(
            "Memory at exit: pss=1530MB rss=1610MB",
            Logger.lowMemoryExitLines(1530L * 1024, 1610L * 1024, sample, 4242, ms("2026-11-27T20:21:30"), zone)
        )
    }

    @Test fun aSampleTakenAfterTheKillIsNeverShown() {
        val sample = Logger.MemorySample(ms("2026-11-27T20:22:00"), pid = 4242, snapshot = snapshot)
        assertEquals(
            "Memory at exit: pss=1530MB rss=1610MB",
            Logger.lowMemoryExitLines(1530L * 1024, 1610L * 1024, sample, 4242, ms("2026-11-27T20:21:30"), zone)
        )
    }

    @Test fun figuresAndroidDidNotReportAreLeftOut() {
        val sample = Logger.MemorySample(ms("2026-11-27T20:20:10"), pid = 4242, snapshot = snapshot)
        assertEquals(
            "Last sample (2026-11-27 8:20 PM): $snapshot",
            Logger.lowMemoryExitLines(0, 0, sample, 4242, ms("2026-11-27T20:21:30"), zone)
        )
        assertEquals(
            "Memory at exit: rss=1610MB",
            Logger.lowMemoryExitLines(0, 1610L * 1024, null, 4242, ms("2026-11-27T20:21:30"), zone)
        )
        assertEquals("", Logger.lowMemoryExitLines(0, 0, null, 4242, ms("2026-11-27T20:21:30"), zone))
    }

    @Test fun theSavedSampleRoundTripsIncludingItsSeparators() {
        val raw = Logger.encodeMemorySample(1795832410000L, 4242, snapshot)
        assertEquals(Logger.MemorySample(1795832410000L, 4242, snapshot), Logger.decodeMemorySample(raw))
    }

    @Test fun anEmptyOrDamagedSavedSampleIsIgnored() {
        assertNull(Logger.decodeMemorySample(""))
        assertNull(Logger.decodeMemorySample("abc|4242|$snapshot"))
        assertNull(Logger.decodeMemorySample("1795832410000|x|$snapshot"))
        assertNull(Logger.decodeMemorySample("1795832410000|4242|"))
    }
}
