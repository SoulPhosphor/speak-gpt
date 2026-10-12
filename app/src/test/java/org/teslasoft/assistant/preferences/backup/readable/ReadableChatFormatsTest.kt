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

import com.google.gson.Gson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

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

    private val fallbackNames = ReadableChatFormats.SpeakerNames(
        currentCompanion = "Moon",
        userFallback = "User",
        companionFallback = "Assistant"
    )

    @Test
    fun textFormatUsesTheNamesShownWhenEachMessageWasSent() {
        val text = ReadableChatFormats.formatText(
            "My Chat",
            listOf(
                mapOf("message" to "hello", "isBot" to false, "userName" to "Robin"),
                mapOf("message" to "hi there", "isBot" to true, "companionName" to "Sol"),
                mapOf("message" to "renamed", "isBot" to false, "userName" to "Wren"),
                mapOf("message" to "older reply", "isBot" to true),
                mapOf("message" to "unnamed", "isBot" to false)
            ),
            fallbackNames,
            includeThinking = true
        )
        assertTrue(text.startsWith("My Chat\n"))
        assertTrue(text.contains("\nRobin\nhello\n"))
        assertTrue(text.contains("\nSol\nhi there\n"))
        assertTrue(text.contains("\nWren\nrenamed\n"))
        assertTrue(text.contains("\nMoon\nolder reply\n"))
        assertTrue(text.contains("\nUser\nunnamed\n"))
        assertFalse(text.contains("You:"))
        assertFalse(text.contains("Assistant:"))
    }

    @Test
    fun textFormatAlwaysCarriesDateTimeAndTheCompanionModel() {
        val text = ReadableChatFormats.formatText(
            "Chat",
            listOf(
                mapOf("message" to "q", "isBot" to false, "messageTime" to "0"),
                mapOf("message" to "a", "isBot" to true, "messageTime" to "0", "responseModel" to "provider/model",
                    "responseTokens" to "42")
            ),
            fallbackNames,
            includeThinking = true,
            Locale.US,
            TimeZone.getTimeZone("UTC")
        )
        val lines = text.lines()
        assertEquals("User", lines[2])
        assertTrue(lines[3].startsWith("Jan 1, 1970 12:00"))
        assertFalse(lines[3].contains("provider/model"))
        assertEquals("Moon", lines[6])
        assertTrue(lines[7].startsWith("Jan 1, 1970 12:00"))
        assertTrue(lines[7].endsWith(" · provider/model"))
        assertFalse(text.contains("42"))
        assertFalse(text.contains("Tokens"))
    }

    @Test
    fun jsonRecordsDropEveryTokenFieldAtAnyDepth() {
        val variant = mapOf("message" to "v1", "responseTokens" to "9", "visibleTextTokenEstimate" to "3")
        val records = ReadableChatFormats.jsonRecords(
            listOf(
                mapOf(
                    "message" to "a", "isBot" to true, "messageTime" to "0",
                    "responseModel" to "provider/model",
                    "responseTokens" to "42", "visibleTextTokenEstimate" to "40",
                    "tokenUsageRecords" to "[]", "reasoningTokens" to "7",
                    "variants" to listOf(variant)
                )
            ),
            fallbackNames,
            includeThinking = true,
            Locale.US,
            TimeZone.getTimeZone("UTC")
        )
        val json = Gson().toJson(records)
        for (key in listOf("responseTokens", "visibleTextTokenEstimate", "tokenUsageRecords", "reasoningTokens")) {
            assertFalse("$key must not be exported", json.contains(key))
        }
        val record = records.single()
        assertEquals("Moon", record["name"])
        assertEquals("Jan 1, 1970", record["date"])
        assertTrue(record.containsKey("time"))
        assertEquals("provider/model", record["model"])
        assertTrue(json.contains("\"v1\""))
    }

    @Test
    fun userRecordsCarryNoModel() {
        val record = ReadableChatFormats.jsonRecords(
            listOf(mapOf("message" to "q", "isBot" to false, "responseModel" to "x")),
            fallbackNames,
            includeThinking = true
        ).single()
        assertFalse(record.containsKey("model"))
        assertEquals("User", record["name"])
    }

    @Test
    fun chatUsernameReplacesEveryUserNameButNoCompanionName() {
        val names = fallbackNames.copy(chatUsername = "Mary")
        val text = ReadableChatFormats.formatText(
            "Chat",
            listOf(
                mapOf("message" to "a", "isBot" to false, "userName" to "Cindy"),
                mapOf("message" to "b", "isBot" to false),
                mapOf("message" to "c", "isBot" to true, "companionName" to "Sol")
            ),
            names,
            includeThinking = true
        )
        assertTrue(text.contains("\nMary\na\n"))
        assertTrue(text.contains("\nMary\nb\n"))
        assertTrue(text.contains("\nSol\nc\n"))
        assertFalse(text.contains("Cindy"))
    }

    @Test
    fun storedVersionTextIsSanitizedToo() {
        val variants = """[{"message":"v1","responseTokens":"9","reasoningText":"old thought"}]"""
        val message = mapOf("message" to "a", "isBot" to true, "variants" to variants)
        val on = Gson().toJson(ReadableChatFormats.jsonRecords(listOf(message), fallbackNames, includeThinking = true))
        assertFalse(on.contains("responseTokens"))
        assertTrue(on.contains("old thought"))
        val off = Gson().toJson(ReadableChatFormats.jsonRecords(listOf(message), fallbackNames, includeThinking = false))
        assertFalse(off.contains("old thought"))
        assertTrue(off.contains("v1"))
    }

    @Test
    fun aChatUsernameReplacesTheStoredNameInJson() {
        val record = ReadableChatFormats.jsonRecords(
            listOf(mapOf("message" to "q", "isBot" to false, "userName" to "Cindy")),
            fallbackNames.copy(chatUsername = "Mary"),
            includeThinking = true
        ).single()
        assertEquals("Mary", record["name"])
        assertFalse(record.containsKey("userName"))
    }

    @Test
    fun everyExportRenderInputChangesTheFingerprint() {
        fun render(
            names: ReadableChatFormats.SpeakerNames = fallbackNames,
            thinking: Boolean = true,
            folders: Boolean = false,
            locale: Locale = Locale.US,
            zone: String = "UTC"
        ) = ReadableChatFormats.fingerprint(
            "Chat", "[]", null,
            ReadableChatFormats.renderFingerprint(names, thinking, folders, locale, TimeZone.getTimeZone(zone))
        )
        val base = render()
        assertEquals(base, render())
        for (changed in listOf(
            render(names = fallbackNames.copy(chatUsername = "Mary")),
            render(names = fallbackNames.copy(currentCompanion = "Sol")),
            render(names = fallbackNames.copy(userFallback = "Utilisateur")),
            render(thinking = false),
            render(folders = true),
            render(locale = Locale.FRANCE),
            render(zone = "Asia/Tokyo")
        )) assertNotEquals(base, changed)
    }

    // ---- thinking ----

    private val thinkingMessages = listOf(
        mapOf("message" to "q", "isBot" to false),
        mapOf("message" to "answer", "isBot" to true, "reasoningText" to "pondering",
            "reasoningIsSummary" to "true", "variants" to listOf(mapOf("reasoningText" to "older"))),
        mapOf("message" to "plain", "isBot" to true)
    )

    @Test
    fun textLabelsThinkingAndResponseOnlyWhenThinkingIsIncludedAndPresent() {
        val on = ReadableChatFormats.formatText("Chat", thinkingMessages, fallbackNames, includeThinking = true)
        assertTrue(on.contains("\nThinking:\npondering\n\nResponse:\nanswer\n"))
        assertFalse(on.contains("Summary"))
        assertTrue(on.contains("\nMoon\nplain\n"))
        assertEquals(1, Regex("Response:").findAll(on).count())

        val off = ReadableChatFormats.formatText("Chat", thinkingMessages, fallbackNames, includeThinking = false)
        assertFalse(off.contains("Thinking:"))
        assertFalse(off.contains("Response:"))
        assertFalse(off.contains("pondering"))
        assertTrue(off.contains("\nMoon\nanswer\n"))
    }

    @Test
    fun jsonCarriesThinkingOnlyWhenIncluded() {
        val on = ReadableChatFormats.jsonRecords(thinkingMessages, fallbackNames, includeThinking = true)
        assertEquals("pondering", on[1]["thinking"])
        assertFalse(on[0].containsKey("thinking"))
        assertFalse(on[2].containsKey("thinking"))

        val off = Gson().toJson(ReadableChatFormats.jsonRecords(thinkingMessages, fallbackNames, includeThinking = false))
        for (key in listOf("\"thinking\"", "reasoningText", "reasoningIsSummary", "pondering", "older")) {
            assertFalse("$key must not be exported", off.contains(key))
        }
    }

    // ---- folder locations ----

    @Test
    fun entryPathsStayFlatWhenFolderLocationsAreOff() {
        val paths = ReadableChatFormats.assignEntryPaths(
            listOf("Plan", "Plan"), listOf("Work", "Home"), includeFolders = false
        )
        assertEquals(listOf("Plan", "Plan-2"), paths)
    }

    @Test
    fun entryPathsUseChatFoldersWhenIncluded() {
        val paths = ReadableChatFormats.assignEntryPaths(
            listOf("Plan", "Plan", "Plan", "Loose", "Notes"),
            listOf("Work", "Home", "Work", null, "work"),
            includeFolders = true
        )
        assertEquals(listOf("Work/Plan", "Home/Plan", "Work/Plan-2", "Loose", "Work/Notes"), paths)
    }

    @Test
    fun folderDirectoriesAreSanitized() {
        val paths = ReadableChatFormats.assignEntryPaths(
            listOf("Plan"), listOf("A/B: C"), includeFolders = true
        )
        assertEquals(listOf("AB C/Plan"), paths)
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
