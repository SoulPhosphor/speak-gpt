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

class LoggerRetentionTest {

    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private fun entry(ts: LocalDateTime, msg: String) = "[${ts.format(fmt)}] [Tag] [INFO] $msg\n"
    private fun headerCount(log: String) = Regex("""(?m)^\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}] """).findAll(log).count()

    @Test fun emptyStaysEmpty() {
        assertEquals("", Logger.trimByEntries("", 500, 30))
    }

    @Test fun countCapKeepsMostRecent() {
        val now = LocalDateTime.now()
        val log = (1..5).joinToString("") { entry(now, "msg$it") }
        val out = Logger.trimByEntries(log, 3, 30)
        assertEquals(3, headerCount(out))
        assertFalse(out.contains("msg1"))
        assertFalse(out.contains("msg2"))
        assertTrue(out.contains("msg3"))
        assertTrue(out.contains("msg5"))
    }

    @Test fun ageCapDropsOldEntries() {
        val now = LocalDateTime.now()
        val log = entry(now.minusDays(40), "ancient") + entry(now, "fresh")
        val out = Logger.trimByEntries(log, 500, 30)
        assertFalse(out.contains("ancient"))
        assertTrue(out.contains("fresh"))
    }

    @Test fun multiLineEntryIsKeptWhole() {
        // A multi-line entry (e.g. a stack trace) must survive intact, never split.
        val now = LocalDateTime.now()
        val multi = "[${now.format(fmt)}] [GenError] [ERROR] [U0] boom\n" +
            "\tat com.example.Foo.bar(Foo.kt:1)\n" +
            "\tat com.example.Baz.qux(Baz.kt:2)\n"
        val log = entry(now.minusSeconds(5), "earlier") + multi
        val whole = Logger.trimByEntries(log, 500, 30)
        assertTrue(whole.contains("Foo.kt:1"))
        assertTrue(whole.contains("Baz.kt:2"))
    }

    @Test fun cappingToOneKeepsTheWholeLastEntry() {
        val now = LocalDateTime.now()
        val multi = "[${now.format(fmt)}] [GenError] [ERROR] [U0] boom\n" +
            "\tat com.example.Foo.bar(Foo.kt:1)\n" +
            "\tat com.example.Baz.qux(Baz.kt:2)\n"
        val log = entry(now.minusSeconds(5), "earlier") + multi
        val capped = Logger.trimByEntries(log, 1, 30)
        assertEquals(1, headerCount(capped))
        assertFalse(capped.contains("earlier"))
        assertTrue(capped.contains("[U0] boom"))
        assertTrue(capped.contains("Foo.kt:1"))
        assertTrue(capped.contains("Baz.kt:2"))
    }
}
