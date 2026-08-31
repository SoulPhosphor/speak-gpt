package org.teslasoft.assistant.preferences.chatsearch

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchQueryCompilerTest {
    @Test fun compilesOnlyPolicyTokensAndTreatsOperatorsAsDataOrSeparators() {
        val compiled = SearchQueryCompiler.compile("alpha OR beta*", SearchOptions(), Locale.US)!!
        assertEquals("\"alpha\"* AND \"or\"* AND \"beta\"*", compiled)
        assertFalse(compiled.contains(" beta*"))
        assertNull(SearchQueryCompiler.compile("***", SearchOptions(), Locale.US))
    }

    @Test fun wholeWordsOmitsPrefixOperator() {
        assertEquals("\"search\"", SearchQueryCompiler.compile(
            "search", SearchOptions(wholeWords = true), Locale.US
        ))
    }
}

