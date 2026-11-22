package org.teslasoft.assistant.ui.chat.setup

import android.app.Activity
import android.os.Bundle

/** Lets a caller distinguish a saved configuration from cancel, including after recreation. */
internal class ModelConfigurationResult(private val activity: Activity) {
    private var saved = false
    fun restore(state: Bundle?) { if (state?.getBoolean(KEY) == true) markSaved() }
    fun markSaved() { saved = true; activity.setResult(Activity.RESULT_OK) }
    fun saveState(state: Bundle) { state.putBoolean(KEY, saved) }
    private companion object { const val KEY = "modelConfigurationSaved" }
}
