package org.teslasoft.assistant.preferences.chatsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSnippetPolicyTest {
    @Test fun collapsesWhitespaceAndKeepsHighlightsOnMatchedText() {
        val text = "Before\n\nmatching\twords after"
        val result = SearchSnippetPolicy.create(text, listOf(8..15), contextChars = 100)

        assertEquals("Before matching words after", result.text)
        assertTrue(result.ranges.isNotEmpty())
        assertEquals("matching", result.text.substring(result.ranges.single()))
    }

    @Test fun addsEllipsesOnlyWhenContextWasTrimmed() {
        val text = "0123456789 target 9876543210"
        val result = SearchSnippetPolicy.create(text, listOf(11..16), contextChars = 3)

        assertTrue(result.text.startsWith("…"))
        assertTrue(result.text.endsWith("…"))
        assertEquals("target", result.text.substring(result.ranges.single()))
    }
}
