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

/**
 * Proves the corruption-preservation notice reaches a real persistent log
 * channel. `Logger.log` silently drops any `type` outside its known set, so
 * the old "error" argument wrote nowhere — this pins the fix (the constant
 * `preserveCorruptData` now uses IS a routed channel, specifically the Error
 * Log) so the regression can't return unnoticed.
 */
class LoggerTypeTest {

    @Test fun corruptDataNoticeTypeIsARoutedChannel() {
        assertTrue(
            "preserveCorruptData must log to a channel Logger.log actually routes",
            Logger.isPersistentType(ChatPreferences.CORRUPT_DATA_LOG_TYPE)
        )
    }

    @Test fun corruptDataNoticeGoesToTheErrorLog() {
        // The Error Log is the "crash" channel (see Logger.log's when-branches).
        assertEquals("crash", ChatPreferences.CORRUPT_DATA_LOG_TYPE)
    }

    @Test fun theOldInvalidTypeWasNotRouted() {
        // Guards the intent: "error" is exactly the value that silently dropped.
        assertFalse(Logger.isPersistentType("error"))
    }

    @Test fun knownChannelsAreRoutedAndUnknownAreNot() {
        // "performance" stays routed (the legacy shared log's contents are left
        // untouched); "whisper_perf" and "memory_usage" are the July 23 2026
        // split's two new dedicated channels.
        for (t in listOf("crash", "event", "memory", "performance", "whisper_perf", "memory_usage")) {
            assertTrue("$t must be routed", Logger.isPersistentType(t))
        }
        for (t in listOf("", "error", "Crash", "warning", "info", "debug")) {
            assertFalse("$t must not be routed", Logger.isPersistentType(t))
        }
    }
}
