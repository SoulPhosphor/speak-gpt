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

package org.teslasoft.assistant.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what text-to-speech is handed when "Read Formatting Language" is off:
 * code blocks become the spoken note, plain-text blocks are read, and the
 * Markdown formatting symbols the owner does not want pronounced (headings,
 * dividers, table pipes, emphasis) are gone while the words survive.
 */
class SpeechTextFormatterTest {

    @Test
    fun codeBlockBecomesTheSpokenNote() {
        val input = "Here you go:\n```python\nprint(\"hi\")\n```\nDone."
        val out = SpeechTextFormatter.forSpeech(input)
        assertTrue(out.contains(SpeechTextFormatter.CODE_NOTE))
        assertFalse(out.contains("print"))
    }

    @Test
    fun jsonLabeledBlockCountsAsCode() {
        val input = "```json\n{\"a\":1}\n```"
        assertEquals(SpeechTextFormatter.CODE_NOTE, SpeechTextFormatter.forSpeech(input))
    }

    @Test
    fun plainTextBlockIsReadAsContents() {
        val input = "```text\nRead me aloud\n```"
        assertEquals("Read me aloud", SpeechTextFormatter.forSpeech(input))
    }

    @Test
    fun unlabeledBlockIsReadAsContents() {
        val input = "```\nJust words here\n```"
        assertEquals("Just words here", SpeechTextFormatter.forSpeech(input))
    }

    @Test
    fun headingLosesItsHashes() {
        assertEquals("Title", SpeechTextFormatter.forSpeech("### Title"))
    }

    @Test
    fun horizontalRuleIsDropped() {
        val out = SpeechTextFormatter.forSpeech("Before\n---\nAfter")
        assertFalse(out.contains("-"))
        assertTrue(out.contains("Before"))
        assertTrue(out.contains("After"))
    }

    @Test
    fun tableIsReadWithoutItsPipesAndSeparatorRow() {
        val input = "| Name | Age |\n| --- | --- |\n| Sam | 30 |"
        val out = SpeechTextFormatter.forSpeech(input)
        assertFalse(out.contains("|"))
        assertTrue(out.contains("Name, Age"))
        assertTrue(out.contains("Sam, 30"))
    }

    @Test
    fun emphasisMarkersAreRemovedButWordsStay() {
        assertEquals("really important", SpeechTextFormatter.forSpeech("**really** *important*"))
    }

    @Test
    fun linkKeepsItsTextNotItsUrl() {
        assertEquals("the docs", SpeechTextFormatter.forSpeech("[the docs](https://example.com)"))
    }

    @Test
    fun ordinaryTextIsLeftAlone() {
        assertEquals("Just a normal sentence.", SpeechTextFormatter.forSpeech("Just a normal sentence."))
    }
}
