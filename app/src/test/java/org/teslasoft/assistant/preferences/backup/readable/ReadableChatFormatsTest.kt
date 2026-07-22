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

package org.teslasoft.assistant.preferences.backup.readable

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure Human-Readable Chat Backup rules (owner directive, July 22 2026):
 * visible titles as filenames (sanitized only as far as safety requires,
 * never hashes), duplicate numbering -2/-3, and the incremental
 * fingerprint diff whose baseline only ever advances after a verified write
 * (the advance itself is the caller's contract; the selection math is here).
 */
class ReadableChatFormatsTest {

    // ---- sanitizeTitle ----

    @Test
    fun sanitizeKeepsVisibleWordsAndRemovesOnlyUnsafeCharacters() {
        assertEquals("What is 2+2", ReadableChatFormats.sanitizeTitle("What is 2+2?"))
        assertEquals("ab", ReadableChatFormats.sanitizeTitle("a\\/:*?\"<>|b"))
        assertEquals("Plan AB", ReadableChatFormats.sanitizeTitle("Plan  A\tB"))
        // Trailing dots/spaces are invalid on Windows extraction targets.
        assertEquals("Notes", ReadableChatFormats.sanitizeTitle("Notes. . "))
        // Unicode titles survive untouched.
        assertEquals("Café — plan", ReadableChatFormats.sanitizeTitle("Café — plan"))
    }

    @Test
    fun sanitizeFallsBackForEmptyTitles_neverAHash() {
        assertEquals("Chat", ReadableChatFormats.sanitizeTitle(""))
        assertEquals("Chat", ReadableChatFormats.sanitizeTitle("   "))
        assertEquals("Chat", ReadableChatFormats.sanitizeTitle("???"))
        assertEquals("Chat", ReadableChatFormats.sanitizeTitle(null))
    }

    @Test
    fun sanitizeCapsLength() {
        val long = "x".repeat(500)
        assertEquals(ReadableChatFormats.MAX_BASE_NAME, ReadableChatFormats.sanitizeTitle(long).length)
    }

    // ---- assignBaseNames ----

    @Test
    fun duplicateTitlesAreNumberedInOrder() {
        val names = ReadableChatFormats.assignBaseNames(listOf("Chat Title", "Chat Title", "Chat Title"))
        assertEquals(listOf("Chat Title", "Chat Title-2", "Chat Title-3"), names)
    }

    @Test
    fun numberingIsCaseInsensitive() {
        val names = ReadableChatFormats.assignBaseNames(listOf("chat", "Chat"))
        assertEquals(listOf("chat", "Chat-2"), names)
    }

    @Test
    fun numberingAvoidsCollidingWithARealTitleEndingInDashTwo() {
        val names = ReadableChatFormats.assignBaseNames(listOf("Plan", "Plan-2", "Plan"))
        assertEquals("Plan", names[0])
        assertEquals("Plan-2", names[1])
        // The third must not reuse "Plan-2".
        assertEquals("Plan-3", names[2])
        assertEquals(names.size, names.map { it.lowercase() }.toSet().size)
    }

    // ---- formats ----

    @Test
    fun textFormatLabelsSpeakers() {
        val text = ReadableChatFormats.formatText(
            "My Chat",
            listOf(
                mapOf("message" to "hello", "isBot" to false),
                mapOf("message" to "hi there", "isBot" to true)
            )
        )
        assertTrue(text.startsWith("My Chat\n"))
        assertTrue(text.contains("You:\nhello"))
        assertTrue(text.contains("Assistant:\nhi there"))
    }

    @Test
    fun jsonFormatCarriesTitleAndRawMessages() {
        val json = ReadableChatFormats.formatJson("My Chat", """[{"message":"hello","isBot":false}]""")
        val obj = JSONObject(json)
        assertEquals("My Chat", obj.getString("name"))
        assertEquals(1, obj.getJSONArray("messages").length())
        assertEquals("hello", obj.getJSONArray("messages").getJSONObject(0).getString("message"))
    }

    // ---- fingerprints + incremental selection ----

    @Test
    fun fingerprintChangesWithContentAndTitle() {
        val a = ReadableChatFormats.fingerprint("Chat", "[]")
        assertEquals(a, ReadableChatFormats.fingerprint("Chat", "[]"))
        assertNotEquals(a, ReadableChatFormats.fingerprint("Chat", """[{"message":"x"}]"""))
        assertNotEquals(a, ReadableChatFormats.fingerprint("Chat 2", "[]"))
    }

    @Test
    fun selectChangedFindsNewAndChangedOnly() {
        val baseline = mapOf("a" to "fp-a", "b" to "fp-b")
        val current = linkedMapOf(
            "a" to "fp-a",        // unchanged
            "b" to "fp-b2",       // changed
            "c" to "fp-c"         // new
        )
        assertEquals(listOf("b", "c"), ReadableChatFormats.selectChanged(current, baseline))
    }

    @Test
    fun emptyBaselineSelectsEverything() {
        val current = linkedMapOf("a" to "1", "b" to "2")
        assertEquals(listOf("a", "b"), ReadableChatFormats.selectChanged(current, emptyMap()))
    }

    @Test
    fun noChangesSelectsNothing() {
        val fp = mapOf("a" to "1", "b" to "2")
        assertTrue(ReadableChatFormats.selectChanged(fp, fp).isEmpty())
    }

    // ---- baseline persistence round-trip ----

    @Test
    fun baselineRoundTrips() {
        val baseline = mapOf("id1" to "fp1", "id2" to "fp2")
        val json = ReadableChatFormats.baselineToJson(baseline)
        assertEquals(baseline, ReadableChatFormats.baselineFromJson(json))
    }

    @Test
    fun corruptBaselineReadsAsEmpty_theSafeDirection() {
        assertTrue(ReadableChatFormats.baselineFromJson(null).isEmpty())
        assertTrue(ReadableChatFormats.baselineFromJson("").isEmpty())
        assertTrue(ReadableChatFormats.baselineFromJson("not json").isEmpty())
    }
}
