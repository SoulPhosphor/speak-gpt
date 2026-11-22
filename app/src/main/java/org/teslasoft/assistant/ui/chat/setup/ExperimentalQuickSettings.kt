package org.teslasoft.assistant.ui.chat.setup

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import org.teslasoft.assistant.R
import org.teslasoft.assistant.conversation.setup.ExperimentalSetup
import org.teslasoft.assistant.conversation.setup.ExperimentalSetupAvailability
import org.teslasoft.assistant.preferences.Preferences

/** All presentation is behind one release gate. The legacy toggle remains intact until Stage 4. */
internal object ExperimentalQuickSettings {
    fun bind(host: Fragment, root: View, preferences: Preferences, chatId: String, updated: () -> Unit) {
        if (!ExperimentalSetupAvailability.PUBLIC_SETUP_ENABLED) return
        fun refresh() {
            val setup = preferences.readExperimentalSetup()
            root.findViewById<View>(R.id.experimental_legacy_controls).isVisible = false
            root.findViewById<View>(R.id.experimental_setup_controls).isVisible = true
            root.findViewById<TextView>(R.id.experimental_setup_status).setText(when (setup?.mode) {
                ExperimentalSetup.Mode.OFF -> R.string.experimental_setup_off
                ExperimentalSetup.Mode.SINGLE -> R.string.experimental_setup_single
                ExperimentalSetup.Mode.PARALLEL -> R.string.experimental_setup_parallel
                null -> R.string.experimental_setup_invalid
            })
            val parallel = setup?.mode == ExperimentalSetup.Mode.PARALLEL
            listOf(R.id.btn_select_model, R.id.row_api_endpoint).forEach {
                root.findViewById<View>(it).isVisible = !parallel
            }
            if (parallel) listOf(R.id.provider_mode_tile, R.id.provider_tile, R.id.row_reasoning).forEach {
                root.findViewById<View>(it).isVisible = false
            }
            root.findViewById<View>(R.id.experimental_parallel_summary).isVisible = parallel
            root.findViewById<TextView>(R.id.experimental_parallel_model_count).text =
                host.getString(R.string.experimental_setup_models, setup?.models?.size ?: 0)
        }
        val open = View.OnClickListener {
            ExperimentalSetupDialogFragment.newInstance(chatId).show(host.childFragmentManager, "experimental-setup")
        }
        root.findViewById<View>(R.id.experimental_setup_button).setOnClickListener(open)
        root.findViewById<View>(R.id.experimental_parallel_summary).setOnClickListener(open)
        host.childFragmentManager.setFragmentResultListener(ExperimentalSetupDialogFragment.RESULT, host.viewLifecycleOwner) { _, _ ->
            updated()
            refresh()
        }
        refresh()
    }
}
