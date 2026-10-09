package org.teslasoft.assistant.ui.activities

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import org.teslasoft.assistant.R

/** Shared right-edge motion for the navigation stack rooted at Settings. */
abstract class SettingsPageActivity : FragmentActivity() {
    companion object {
        private const val EXTRA_SETTINGS_PAGE_MOTION = "org.teslasoft.assistant.SETTINGS_PAGE_MOTION"
    }

    // Keep Quick Settings and other entry points on their existing transitions,
    // including any editors opened from those entry points.
    protected val usesSettingsPageMotion: Boolean
        get() = this is SettingsActivity || intent.getBooleanExtra(EXTRA_SETTINGS_PAGE_MOTION, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!usesSettingsPageMotion) return
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.settings_slide_in, R.anim.settings_hold)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.settings_hold, R.anim.settings_slide_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.settings_slide_in, R.anim.settings_hold)
        }
    }

    // Activity Result launchers and ordinary startActivity calls both reach
    // this method. Pass the policy only to explicit screens within this app.
    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        if (usesSettingsPageMotion && intent.component?.packageName == packageName) {
            intent.putExtra(EXTRA_SETTINGS_PAGE_MOTION, true)
        }
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun finish() {
        super.finish()
        if (usesSettingsPageMotion && Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.settings_hold, R.anim.settings_slide_out)
        }
    }
}
