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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** System Logs: ProcessExit routing, its own retention, Error Log preservation. */
class LoggerSystemLogTest {

    @Test fun systemLogIsARoutedChannel() {
        assertTrue(Logger.isPersistentType(Logger.SYSTEM_LOG_TYPE))
    }

    @Test fun processExitGoesToSystemLogsNeverTheVoiceLog() {
        val channels = Logger.processExitChannels(systemLogging = true, appBreaking = false)
        assertEquals(listOf(Logger.SYSTEM_LOG_TYPE), channels)
        assertFalse("event" in Logger.processExitChannels(true, true))
        assertFalse("event" in Logger.processExitChannels(false, true))
    }

    @Test fun systemLogsOffWritesNoLifecycleLine() {
        assertEquals(emptyList<String>(), Logger.processExitChannels(systemLogging = false, appBreaking = false))
    }

    @Test fun appBreakingExitStillReachesErrorLogWhetherOrNotSystemLogsIsOn() {
        assertTrue("crash" in Logger.processExitChannels(systemLogging = false, appBreaking = true))
        assertEquals(
            listOf(Logger.SYSTEM_LOG_TYPE, "crash"),
            Logger.processExitChannels(systemLogging = true, appBreaking = true)
        )
    }

    @Test fun systemLogDefaultRetentionKeepsNewestTen() {
        assertEquals(10, Preferences.SYSTEM_LOG_DEFAULT_MAX_ENTRIES)
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd h:mm a")
        val ts = LocalDateTime.now().format(fmt)
        val log = (1..12).joinToString("") { "[$ts] [ProcessExit] [INFO] exit$it\n" }
        val out = Logger.trimByEntries(
            log, Preferences.SYSTEM_LOG_DEFAULT_MAX_ENTRIES, Preferences.LOG_DEFAULT_MAX_DAYS.toLong()
        )
        assertFalse(out.contains("exit2\n"))
        assertTrue(out.contains("exit3\n"))
        assertTrue(out.contains("exit12\n"))
    }
}
