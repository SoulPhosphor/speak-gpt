package org.teslasoft.assistant.ui.chat.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.conversation.setup.ExperimentalSetup
import org.teslasoft.assistant.conversation.setup.ExperimentalSetupCodec
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.ui.fragments.dialogs.AdvancedModelSelectorDialogFragment
import org.teslasoft.assistant.ui.util.ScreenChrome

/** No public launch until the Stage 4 release gate opens. Back discards this setup draft. */
class ExperimentalSetupDialogFragment : DialogFragment() {
    private lateinit var preferences: Preferences
    private var draft = ExperimentalSetup()
    private var unreadable = false
    private val chatId get() = requireArguments().getString(CHAT_ID).orEmpty()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setStyle(STYLE_NORMAL, R.style.UI_Material)
        preferences = Preferences.getPreferences(requireContext(), chatId)
        val restored = state?.getString(DRAFT)
        val saved = if (restored != null) runCatching {
            ExperimentalSetupCodec.decode(restored).copy(mode = ExperimentalSetup.Mode.valueOf(requireNotNull(state).getString(MODE)!!))
        }.getOrNull() else preferences.readExperimentalSetup()
        unreadable = state?.getBoolean(UNREADABLE) ?: (saved == null)
        draft = saved ?: ExperimentalSetup()
        childFragmentManager.setFragmentResultListener(
            AdvancedModelSelectorDialogFragment.PARALLEL_SELECTION_RESULT, this
        ) { _, result ->
            val models = ExperimentalSetupCodec.decode(result.getString(AdvancedModelSelectorDialogFragment.PARALLEL_MODELS)!!).models
            draft = draft.copy(models = models, primaryId = draft.primaryId?.takeIf { id -> models.any { it.id == id } })
            render()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_experimental_setup, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        ScreenChrome.fitSystemBarsAndKeyboard(view, view.findViewById(R.id.action_bar),
            view.findViewById(R.id.experimental_setup_scroll))
        view.findViewById<View>(R.id.btn_back).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.experimental_setup_save).setOnClickListener { save() }
        val modes = view.findViewById<RadioGroup>(R.id.experimental_setup_modes)
        modes.check(when (draft.mode) {
            ExperimentalSetup.Mode.OFF -> R.id.experimental_setup_off
            ExperimentalSetup.Mode.SINGLE -> R.id.experimental_setup_single
            ExperimentalSetup.Mode.PARALLEL -> R.id.experimental_setup_parallel
        })
        modes.setOnCheckedChangeListener { _, id ->
            draft = draft.copy(mode = when (id) {
                R.id.experimental_setup_parallel -> ExperimentalSetup.Mode.PARALLEL
                R.id.experimental_setup_single -> ExperimentalSetup.Mode.SINGLE
                else -> ExperimentalSetup.Mode.OFF
            })
            render()
        }
        view.findViewById<MaterialSwitch>(R.id.experimental_last_message_only).apply {
            isChecked = draft.lastMessageOnly
            setOnCheckedChangeListener { _, enabled -> draft = draft.copy(lastMessageOnly = enabled) }
        }
        view.findViewById<MaterialSwitch>(R.id.parallel_allow_image_generation).apply {
            isChecked = draft.allowImages
            setOnCheckedChangeListener { _, enabled -> draft = draft.copy(allowImages = enabled) }
        }
        view.findViewById<View>(R.id.experimental_choose_models).setOnClickListener {
            AdvancedModelSelectorDialogFragment.newParallelModelPicker(chatId,
                ExperimentalSetupCodec.encode(draft.copy(mode = ExperimentalSetup.Mode.OFF)))
                .show(childFragmentManager, "parallel-models")
        }
        render()
    }

    private fun render() {
        val root = view ?: return
        root.findViewById<View>(R.id.experimental_last_message_row).isVisible = draft.enabled
        root.findViewById<View>(R.id.experimental_parallel_controls).isVisible = draft.mode == ExperimentalSetup.Mode.PARALLEL
        root.findViewById<View>(R.id.experimental_setup_save).isEnabled = !unreadable
        val group = root.findViewById<RadioGroup>(R.id.experimental_primary_models)
        group.removeAllViews()
        draft.models.forEach { model ->
            val radio = layoutInflater.inflate(R.layout.view_experimental_primary_model, group, false) as RadioButton
            radio.id = View.generateViewId()
            radio.text = "${model.modelId}\n${model.endpointLabel}"
            radio.contentDescription = "${model.modelId}, ${model.endpointLabel}, ${model.endpointId}"
            radio.isChecked = model.id == draft.primaryId
            radio.setOnCheckedChangeListener { _, checked -> if (checked) draft = draft.copy(primaryId = model.id) }
            group.addView(radio)
        }
        error(if (unreadable) getString(R.string.experimental_setup_invalid) else null)
    }

    private fun save() {
        if (unreadable) return
        if (draft.mode == ExperimentalSetup.Mode.PARALLEL && preferences.getHandsFreeMode()) {
            error(getString(R.string.experimental_setup_hands_free)); return
        }
        if (runCatching { draft.validate() }.isFailure) {
            error(getString(R.string.experimental_setup_selection_required)); return
        }
        if (draft.mode == ExperimentalSetup.Mode.PARALLEL) {
            val unavailable = SetupModelResolver.unavailable(requireContext(), draft)
            if (unavailable.isNotEmpty()) {
                error(getString(R.string.experimental_setup_unavailable,
                    unavailable.joinToString { "${it.modelId} (${it.endpointLabel})" })); return
            }
        }
        if (!preferences.saveExperimentalSetup(draft)) {
            error(getString(R.string.message_save_failed)); return
        }
        parentFragmentManager.setFragmentResult(RESULT, Bundle.EMPTY)
        dismiss()
    }

    private fun error(message: String?) {
        view?.findViewById<TextView>(R.id.experimental_setup_error)?.apply {
            text = message
            isVisible = message != null
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putString(DRAFT, ExperimentalSetupCodec.encode(draft.copy(mode = ExperimentalSetup.Mode.OFF)))
        state.putString(MODE, draft.mode.name)
        state.putBoolean(UNREADABLE, unreadable)
    }

    companion object {
        const val RESULT = "experimentalSetupSaved"
        private const val CHAT_ID = "chatId"
        private const val DRAFT = "draft"
        private const val MODE = "mode"
        private const val UNREADABLE = "unreadable"
        fun newInstance(chatId: String) = ExperimentalSetupDialogFragment().apply {
            arguments = bundleOf(CHAT_ID to chatId)
        }
    }
}
