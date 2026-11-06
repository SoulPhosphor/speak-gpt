package org.teslasoft.assistant.ui.util

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.preferences.chatsearch.SearchOptions

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PromptLibrarySearchTest {
    @Test fun titlesAndAllContentFieldsUseAppSearchRules() {
        val options = SearchOptions()
        assertTrue(PromptLibrarySearch.matches("", options, "Any title"))
        assertTrue(PromptLibrarySearch.matches("comp", options, "Companion", "A prompt"))
        assertTrue(PromptLibrarySearch.matches("winter", options, "Companion", "Summer", "A winter prompt variant"))
        assertTrue(PromptLibrarySearch.matches("comp winter", options, "Companion", "A winter prompt"))
        assertTrue(PromptLibrarySearch.matches("prompt", options, "Title", "Prompts in the body"))
        assertFalse(PromptLibrarySearch.matches("prompt", SearchOptions(wholeWords = true), "Title", "Prompts in the body"))
        assertTrue(PromptLibrarySearch.matches("prompt", SearchOptions(wholeWords = true), "Title", "A PROMPT in the body"))
        assertFalse(PromptLibrarySearch.matches("missing", options, "Title", "Body"))
    }

}
