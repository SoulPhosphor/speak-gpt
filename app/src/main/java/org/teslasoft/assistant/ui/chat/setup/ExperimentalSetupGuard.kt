package org.teslasoft.assistant.ui.chat.setup

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R
import org.teslasoft.assistant.conversation.setup.ExperimentalSetup
import org.teslasoft.assistant.preferences.Preferences

/** Runs before composer/history mutation. Valid parallel setup proceeds to the owned controller. */
internal object ExperimentalSetupGuard {
    fun block(context: Context, preferences: Preferences?, handsFree: Boolean = false): Boolean {
        preferences ?: return false
        val setup = preferences.readExperimentalSetup()
        val message = when {
            setup == null -> context.getString(R.string.experimental_setup_invalid)
            setup.mode != ExperimentalSetup.Mode.PARALLEL -> return false
            handsFree -> context.getString(R.string.experimental_parallel_hands_free)
            else -> {
                val unavailable = SetupModelResolver.unavailable(context, setup)
                if (unavailable.isNotEmpty()) context.getString(R.string.experimental_setup_unavailable,
                    unavailable.joinToString { "${it.modelId} (${it.endpointLabel})" })
                else return false
            }
        }
        MaterialAlertDialogBuilder(context, R.style.App_MaterialAlertDialog)
            .setMessage(message).setPositiveButton(R.string.btn_ok, null).show()
        return true
    }
}
