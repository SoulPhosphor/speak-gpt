package org.teslasoft.assistant.ui.activities

import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import org.teslasoft.assistant.R

/** Shared right-edge navigation for Settings and its main destination pages. */
abstract class SettingsPageActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.settings_slide_in, R.anim.settings_hold)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.settings_hold, R.anim.settings_slide_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.settings_slide_in, R.anim.settings_hold)
        }
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.settings_hold, R.anim.settings_slide_out)
        }
    }
}
