package org.teslasoft.assistant.ui.util

import android.app.Application
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.fragment.app.FragmentActivity
import com.google.android.material.materialswitch.MaterialSwitch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.chatsearch.SearchOptions

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PromptLibrarySearchTest {
    class Harness : FragmentActivity() {
        override fun onCreate(state: Bundle?) {
            setTheme(R.style.Theme_App)
            super.onCreate(state)
            setContentView(R.layout.activity_persona_list)
        }
    }

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

    @Test fun toggleFiltersImmediatelyAndClosingRestoresFullList() {
        val host = Robolectric.buildActivity(Harness::class.java).setup()
        val activity = host.get()
        var changes = 0
        val search = PromptLibrarySearch(activity, null) { changes++ }
        val controls = activity.findViewById<View>(R.id.search_controls)
        val field = activity.findViewById<EditText>(R.id.field_search)
        val whole = activity.findViewById<MaterialSwitch>(R.id.check_whole_words)
        val toggle = activity.findViewById<View>(R.id.btn_search_toggle)
        assertEquals(View.GONE, controls.visibility)
        assertFalse(whole.isChecked)
        toggle.performClick()
        assertEquals(View.VISIBLE, controls.visibility)
        field.setText("prompt")
        assertTrue(search.matches("Prompts"))
        whole.isChecked = true
        assertFalse(search.matches("Prompts"))
        assertEquals(2, changes)
        toggle.performClick()
        assertEquals(View.GONE, controls.visibility)
        assertEquals("", search.query)
        assertTrue(search.matches("Anything"))
        host.pause().stop().destroy()
    }

    @Test fun savedSearchRestoresVisibleWithItsOptionsAndBackClearsIt() {
        val first = Robolectric.buildActivity(Harness::class.java).setup()
        val search = PromptLibrarySearch(first.get(), null) {}
        first.get().findViewById<View>(R.id.btn_search_toggle).performClick()
        first.get().findViewById<EditText>(R.id.field_search).setText("winter")
        first.get().findViewById<MaterialSwitch>(R.id.check_whole_words).isChecked = true
        val saved = Bundle()
        search.saveState(saved)
        first.pause().stop().destroy()

        val second = Robolectric.buildActivity(Harness::class.java).setup()
        val restored = PromptLibrarySearch(second.get(), saved) {}
        assertEquals(View.VISIBLE, second.get().findViewById<View>(R.id.search_controls).visibility)
        assertEquals("winter", restored.query)
        assertTrue(restored.options.wholeWords)
        second.get().onBackPressedDispatcher.onBackPressed()
        assertEquals("", restored.query)
        assertEquals(View.GONE, second.get().findViewById<View>(R.id.search_controls).visibility)
        second.pause().stop().destroy()
    }

    @Test fun allLibraryLayoutsAndMainSearchInflateTheSameControls() {
        val host = Robolectric.buildActivity(Harness::class.java).setup()
        val activity = host.get()
        for (layout in listOf(R.layout.activity_persona_list, R.layout.activity_activation_prompt_list,
            R.layout.activity_system_prompts_list, R.layout.activity_prompt_memory_list, R.layout.activity_search)) {
            activity.setContentView(layout)
            assertNotNull(activity.findViewById<EditText>(R.id.field_search))
            assertFalse(activity.findViewById<MaterialSwitch>(R.id.check_whole_words).isChecked)
            if (layout != R.layout.activity_search) assertNotNull(activity.findViewById<View>(R.id.btn_search_toggle))
        }
        host.pause().stop().destroy()
    }
}
