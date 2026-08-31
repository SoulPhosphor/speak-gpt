package org.teslasoft.assistant.preferences.chatsearch

import android.app.Application
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SearchRankingPolicyTest {
    @Test fun exactTitleAndTitlePrefixOutrankMessageMatches() {
        val options = SearchOptions()
        fun cls(kind: SearchDocumentKind, text: String): Int {
            val match = SearchTextPolicy.match("search", text, options, Locale.US)!!
            return SearchRankingPolicy.rankClass(kind, text, "search", options, match, Locale.US)
        }
        assertEquals(SearchRankingPolicy.EXACT_FULL_TITLE, cls(SearchDocumentKind.TITLE, "search"))
        assertTrue(cls(SearchDocumentKind.TITLE, "searching notes") < cls(SearchDocumentKind.MESSAGE, "search"))
        assertTrue(cls(SearchDocumentKind.MESSAGE, "search") < cls(SearchDocumentKind.MESSAGE, "searching"))
    }
}
