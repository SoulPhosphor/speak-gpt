package org.teslasoft.assistant.preferences.chatsearch

import java.util.Locale
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
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
