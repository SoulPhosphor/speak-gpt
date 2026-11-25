package org.teslasoft.assistant.ui.chat.setup

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.R

@RunWith(AndroidJUnit4::class)
class ExperimentalSetupLayoutInstrumentedTest {
    @Test fun setupAndSelectedModelRowsInflateInLightAndDarkThemes() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val app = ApplicationProvider.getApplicationContext<Context>()
            for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                val config = Configuration(app.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                }
                val context = ContextThemeWrapper(app.createConfigurationContext(config), R.style.UI_Material).apply {
                    theme.applyStyle(R.style.ThemeOverlay_Phosphor_Violet, true)
                }
                val inflater = LayoutInflater.from(context)
                // Inflate the real screen, including its initially hidden parallel controls.
                val root = inflater.inflate(R.layout.fragment_experimental_setup, FrameLayout(context), false)
                val choose = root.findViewById<MaterialButton>(R.id.experimental_choose_models)
                assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, choose.layoutParams.width)
                assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, choose.layoutParams.height)
                assertNotNull(root.findViewById<MaterialSwitch>(R.id.parallel_allow_image_generation))
                val models = root.findViewById<LinearLayout>(R.id.experimental_primary_models)
                val row = inflater.inflate(R.layout.view_experimental_primary_model, models, false)
                models.addView(row)
                assertNotNull(row.findViewById<RadioButton>(R.id.experimental_model_primary))
            }
        }
    }
}
