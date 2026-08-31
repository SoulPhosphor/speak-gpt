package org.teslasoft.assistant.preferences.chatsearch

import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchTextPolicyTest {
    private val locale = Locale.US

    @Test fun searchTruthTable() {
        val default = SearchOptions()
        assertNotNull(SearchTextPolicy.match("search", "search", default, locale))
        assertNotNull(SearchTextPolicy.match("search", "Search", default, locale))
        assertNotNull(SearchTextPolicy.match("search", "searching", default, locale))
        assertNull(SearchTextPolicy.match("search", "research", default, locale))

        val whole = SearchOptions(wholeWords = true)
        assertNotNull(SearchTextPolicy.match("search", "Search", whole, locale))
        assertNull(SearchTextPolicy.match("search", "searching", whole, locale))

        val case = SearchOptions(matchCase = true)
        assertNotNull(SearchTextPolicy.match("search", "searching", case, locale))
        assertNull(SearchTextPolicy.match("search", "Search", case, locale))

        val both = SearchOptions(wholeWords = true, matchCase = true)
        assertNotNull(SearchTextPolicy.match("search", "search", both, locale))
        assertNull(SearchTextPolicy.match("search", "Search", both, locale))
        assertNull(SearchTextPolicy.match("search", "searching", both, locale))
    }

    @Test fun multiTokenAndUnicodeBoundariesAndHighlights() {
        val match = SearchTextPolicy.match("café 東京", "東京で Caféteria", SearchOptions(), locale)
        assertNotNull(match)
        assertTrue(match!!.ranges.isNotEmpty())
        assertNull(SearchTextPolicy.match("cafe", "café", SearchOptions(), locale))
        assertNotNull(SearchTextPolicy.match("bar", "foo-bar", SearchOptions(wholeWords = true), locale))
    }

    @Test fun punctuationOnlyQueryEmitsNoMatch() {
        assertNull(SearchTextPolicy.match("*** -- ", "anything", SearchOptions(), locale))
        assertTrue(SearchTextPolicy.queryTokens("***", SearchOptions(), locale).isEmpty())
    }
}
