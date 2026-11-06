package org.teslasoft.assistant.ui.util

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.materialswitch.MaterialSwitch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.R
import org.teslasoft.assistant.ui.activities.PersonasListActivity

@RunWith(AndroidJUnit4::class)
class PromptLibrarySearchInstrumentedTest {
    @Test fun searchRestoresAndClosingClearsTheFilter() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<PersonasListActivity>(Intent(context, PersonasListActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.search_controls).visibility)
                assertFalse(activity.findViewById<MaterialSwitch>(R.id.check_whole_words).isChecked)
                activity.findViewById<View>(R.id.btn_search_toggle).performClick()
                activity.findViewById<EditText>(R.id.field_search).setText("winter")
                activity.findViewById<MaterialSwitch>(R.id.check_whole_words).isChecked = true
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.search_controls).visibility)
                assertEquals("winter", activity.findViewById<EditText>(R.id.field_search).text.toString())
                assertTrue(activity.findViewById<MaterialSwitch>(R.id.check_whole_words).isChecked)
                activity.findViewById<View>(R.id.btn_search_toggle).performClick()
                assertEquals("", activity.findViewById<EditText>(R.id.field_search).text.toString())
                assertEquals(View.GONE, activity.findViewById<View>(R.id.search_controls).visibility)
                activity.findViewById<View>(R.id.btn_search_toggle).performClick()
                activity.findViewById<EditText>(R.id.field_search).setText("again")
                activity.onBackPressedDispatcher.onBackPressed()
                assertEquals("", activity.findViewById<EditText>(R.id.field_search).text.toString())
                assertEquals(View.GONE, activity.findViewById<View>(R.id.search_controls).visibility)
            }
        }
    }

    @Test fun allFiveLayoutsInflateSharedSearchControls() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<PersonasListActivity>(Intent(context, PersonasListActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                for (layout in listOf(R.layout.activity_persona_list, R.layout.activity_activation_prompt_list,
                    R.layout.activity_system_prompts_list, R.layout.activity_prompt_memory_list, R.layout.activity_search)) {
                    val root = LayoutInflater.from(activity).inflate(layout, null)
                    assertNotNull(root.findViewById<EditText>(R.id.field_search))
                    assertFalse(root.findViewById<MaterialSwitch>(R.id.check_whole_words).isChecked)
                    if (layout != R.layout.activity_search) assertNotNull(root.findViewById<View>(R.id.btn_search_toggle))
                }
            }
        }
    }
}
