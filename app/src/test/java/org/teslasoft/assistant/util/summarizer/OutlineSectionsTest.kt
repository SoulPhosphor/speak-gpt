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

package org.teslasoft.assistant.util.summarizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineSectionsTest {

    private val outline = """
- Opening note

## Garden
- Tomatoes

## Taxes
- Form due in April
    """.trimIndent()

    @Test
    fun splitsAtHeadersWithTextBeforeTheFirstHeaderAsItsOwnSection() {
        assertEquals(
            listOf("- Opening note", "## Garden\n- Tomatoes", "## Taxes\n- Form due in April"),
            OutlineSections.split(outline)
        )
    }

    @Test
    fun headersInsideFencedCodeDoNotStartASection() {
        val text = "## Script\n```\n# a comment\n```\n## Next\n- item"
        assertEquals(
            listOf("## Script\n```\n# a comment\n```", "## Next\n- item"),
            OutlineSections.split(text)
        )
    }

    @Test
    fun aFenceClosesOnlyWithAMatchingFence() {
        // A shorter or different fence inside a block does not end it, and a
        // fence with text after it is not a closing fence.
        val example = "## Example\n````md\n```kotlin\n# inner\n```\n~~~\n# still inside\n````\n## Next"
        assertEquals(
            listOf("## Example\n````md\n```kotlin\n# inner\n```\n~~~\n# still inside\n````", "## Next"),
            OutlineSections.split(example)
        )
        val notClosing = "## A\n```\n```text\n# inside\n```\n## B"
        assertEquals(listOf("## A\n```\n```text\n# inside\n```", "## B"), OutlineSections.split(notClosing))
    }

    @Test
    fun rendersEachSectionAfterItsReference() {
        assertEquals(
            "S1\n- Opening note\n\nS2\n## Garden\n- Tomatoes\n\nS3\n## Taxes\n- Form due in April",
            OutlineSections.render(outline)
        )
        assertEquals("", OutlineSections.render(""))
    }

    @Test
    fun rewritesNamedSectionsAndAddsNewSectionsAtTheEnd() {
        val reply = """
            {"section_edits": [{"section": "s2", "content": "## Garden\n- Tomatoes\n- Basil"}],
             "new_sections": "## Travel\n- Train booked"}
        """.trimIndent()
        assertEquals(
            "- Opening note\n\n## Garden\n- Tomatoes\n- Basil\n\n## Taxes\n- Form due in April\n\n## Travel\n- Train booked",
            OutlineSections.apply(outline, reply)
        )
    }

    @Test
    fun theFirstReplyBuildsTheOutlineFromNothing() {
        val reply = """{"section_edits": [], "new_sections": "## Garden\n- Tomatoes"}"""
        assertEquals("## Garden\n- Tomatoes", OutlineSections.apply("", reply))
    }

    @Test
    fun aReplyWithNoChangesKeepsTheOutline() {
        assertEquals(outline, OutlineSections.apply(outline, """{"section_edits": [], "new_sections": ""}"""))
        assertEquals(outline, OutlineSections.apply(outline, """{"section_edits": null, "new_sections": null}"""))
    }

    @Test
    fun textAroundTheJsonIsIgnored() {
        val reply = "Here you go:\n```json\n{\"new_sections\": \"## Travel\"}\n```"
        assertEquals("$outline\n\n## Travel", OutlineSections.apply(outline, reply))
    }

    @Test
    fun aReplyThatIsNotJsonCannotBeApplied() {
        assertNull(OutlineSections.apply(outline, "## Travel\n- Train booked"))
        assertNull(OutlineSections.apply(outline, "{not json}"))
    }

    @Test
    fun aReplyWithNeitherFieldCannotBeApplied() {
        assertNull(OutlineSections.apply(outline, """{"outline": "## Travel"}"""))
    }

    @Test
    fun malformedFieldsCannotBeApplied() {
        assertNull(OutlineSections.apply(outline, """{"section_edits": "S2"}"""))
        assertNull(OutlineSections.apply(outline, """{"section_edits": ["S2"]}"""))
        assertNull(OutlineSections.apply(outline, """{"section_edits": [{"content": "## Garden"}]}"""))
        assertNull(OutlineSections.apply(outline, """{"section_edits": [{"section": "S2"}]}"""))
        assertNull(OutlineSections.apply(outline, """{"new_sections": ["## Travel"]}"""))
    }

    @Test
    fun anEditNamingASectionThatWasNotShownFailsTheWholeReply() {
        val reply = """
            {"section_edits": [{"section": "S2", "content": "## Garden\n- Basil"},
                               {"section": "S9", "content": "## Lost"}],
             "new_sections": "## Travel"}
        """.trimIndent()
        assertNull(OutlineSections.apply(outline, reply))
        assertNull(OutlineSections.apply(outline, """{"section_edits": [{"section": "D2", "content": "x"}]}"""))
    }

    @Test
    fun anEditWithNoContentCannotEmptyASection() {
        assertNull(OutlineSections.apply(outline, """{"section_edits": [{"section": "S2", "content": "  "}]}"""))
    }

    @Test
    fun aSectionNamedTwiceWithDifferentTextFailsTheWholeReply() {
        val reply = """
            {"section_edits": [{"section": "S2", "content": "## Garden\n- A"},
                               {"section": "S2", "content": "## Garden\n- B"}]}
        """.trimIndent()
        assertNull(OutlineSections.apply(outline, reply))
    }

    @Test
    fun anEditMaySplitASectionInTwo() {
        val reply = """{"section_edits": [{"section": "S3", "content": "## Taxes\n- Filed\n\n## Refund\n- Expected in May"}]}"""
        val revised = OutlineSections.apply(outline, reply)!!
        assertEquals(4, OutlineSections.split(revised).size)
        assertTrue(revised.endsWith("## Refund\n- Expected in May"))
    }

    @Test
    fun theRequestShowsTheOutlineBySectionAndTheEditRule() {
        val body = SummarizerPrompts.outlineRequestBody("Prompt.", outline, listOf("User" to "Hello"))
        assertTrue(body.contains(SummarizerPrompts.OUTLINE_CONTEXT_RULE))
        assertTrue(body.contains("Outline so far:\nS1\n- Opening note\n\nS2\n## Garden"))
        assertTrue(
            SummarizerPrompts.outlineRequestBody("Prompt.", "", listOf("User" to "Hello"))
                .contains("Outline so far:\nNone yet.")
        )
    }
}
