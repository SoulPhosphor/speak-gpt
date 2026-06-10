/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.fragments.dialogs

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.util.Hash

class EditApiEndpointDialogFragment : DialogFragment() {
    companion object {
        fun newInstance(
            label: String,
            host: String,
            apiKey: String,
            chatEndpoint: String,
            authType: String,
            model: String,
            temperature: Float,
            topP: Float,
            frequencyPenalty: Float,
            presencePenalty: Float,
            maxTokens: Int,
            endSeparator: String,
            prefix: String,
            position: Int
        ): EditApiEndpointDialogFragment {
            val editApiEndpointDialogFragment = EditApiEndpointDialogFragment()

            val args = Bundle()
            args.putString("label", label)
            args.putString("host", host)
            args.putString("apiKey", apiKey)
            args.putString("chatEndpoint", chatEndpoint)
            args.putString("authType", authType)
            args.putString("model", model)
            args.putFloat("temperature", temperature)
            args.putFloat("topP", topP)
            args.putFloat("frequencyPenalty", frequencyPenalty)
            args.putFloat("presencePenalty", presencePenalty)
            args.putInt("maxTokens", maxTokens)
            args.putString("endSeparator", endSeparator)
            args.putString("prefix", prefix)
            args.putInt("position", position)

            editApiEndpointDialogFragment.arguments = args

            return editApiEndpointDialogFragment
        }

        private val authTypes = arrayOf(
            ApiEndpointObject.AUTH_BEARER,
            ApiEndpointObject.AUTH_X_API_KEY,
            ApiEndpointObject.AUTH_API_KEY
        )
    }

    private var textDialogTitle: TextView? = null
    private var fieldLabel: TextInputEditText? = null
    private var fieldHost: TextInputEditText? = null
    private var hostInputLayout: TextInputLayout? = null
    private var fieldChatEndpoint: TextInputEditText? = null
    private var fieldApiKey: TextInputEditText? = null
    private var fieldAuthType: TextInputEditText? = null
    private var fieldModel: TextInputEditText? = null
    private var sliderTemperature: Slider? = null
    private var sliderTopP: Slider? = null
    private var sliderFrequencyPenalty: Slider? = null
    private var sliderPresencePenalty: Slider? = null
    private var fieldMaxTokens: TextInputEditText? = null
    private var fieldEndSeparator: TextInputEditText? = null
    private var fieldPrefix: TextInputEditText? = null
    private var apiNote: TextView? = null

    private var selectedAuthType: String = ApiEndpointObject.AUTH_BEARER
    private var selectedModel: String = ApiEndpointObject.DEFAULT_MODEL

    private var builder: AlertDialog.Builder? = null

    private var listener: StateChangesListener? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_edit_api_endpoint, container, false)
    }

    private fun authLabel(authType: String): String {
        return when (authType) {
            ApiEndpointObject.AUTH_X_API_KEY -> getString(R.string.auth_mode_x_api_key)
            ApiEndpointObject.AUTH_API_KEY -> getString(R.string.auth_mode_api_key)
            else -> getString(R.string.auth_mode_bearer)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        builder = MaterialAlertDialogBuilder(this.requireContext(), R.style.App_MaterialAlertDialog)

        val view: View = this.layoutInflater.inflate(R.layout.fragment_edit_api_endpoint, null)

        textDialogTitle = view.findViewById(R.id.text_dialog_title)
        fieldLabel = view.findViewById(R.id.field_label)
        fieldHost = view.findViewById(R.id.field_host)
        fieldChatEndpoint = view.findViewById(R.id.field_chat_endpoint)
        fieldApiKey = view.findViewById(R.id.field_api_key)
        fieldAuthType = view.findViewById(R.id.field_auth_type)
        fieldModel = view.findViewById(R.id.field_model)
        sliderTemperature = view.findViewById(R.id.slider_temperature)
        sliderTopP = view.findViewById(R.id.slider_top_p)
        sliderFrequencyPenalty = view.findViewById(R.id.slider_frequency_penalty)
        sliderPresencePenalty = view.findViewById(R.id.slider_presence_penalty)
        fieldMaxTokens = view.findViewById(R.id.field_max_tokens)
        fieldEndSeparator = view.findViewById(R.id.field_end_separator)
        fieldPrefix = view.findViewById(R.id.field_prefix)
        apiNote = view.findViewById(R.id.api_note)

        fieldLabel?.setText(requireArguments().getString("label"))
        fieldHost?.setText(requireArguments().getString("host"))

        val chatEndpoint = requireArguments().getString("chatEndpoint").let {
            if (it.isNullOrBlank()) ApiEndpointObject.DEFAULT_CHAT_ENDPOINT else it
        }
        fieldChatEndpoint?.setText(chatEndpoint)

        selectedAuthType = requireArguments().getString("authType").let {
            if (it.isNullOrBlank()) ApiEndpointObject.AUTH_BEARER else it
        }
        fieldAuthType?.setText(authLabel(selectedAuthType))

        selectedModel = requireArguments().getString("model").let {
            if (it.isNullOrBlank()) ApiEndpointObject.DEFAULT_MODEL else it
        }
        fieldModel?.setText(selectedModel)

        sliderTemperature?.value = (requireArguments().getFloat("temperature", ApiEndpointObject.DEFAULT_TEMPERATURE) * 10f)
            .coerceIn(0f, 20f)
        sliderTemperature?.setLabelFormatter { "${it / 10.0}" }

        sliderTopP?.value = (requireArguments().getFloat("topP", ApiEndpointObject.DEFAULT_TOP_P) * 10f)
            .coerceIn(0f, 10f)
        sliderTopP?.setLabelFormatter { "${it / 10.0}" }

        sliderFrequencyPenalty?.value = (requireArguments().getFloat("frequencyPenalty", ApiEndpointObject.DEFAULT_FREQUENCY_PENALTY) * 10f)
            .coerceIn(-20f, 20f)
        sliderFrequencyPenalty?.setLabelFormatter { "${it / 10.0}" }

        sliderPresencePenalty?.value = (requireArguments().getFloat("presencePenalty", ApiEndpointObject.DEFAULT_PRESENCE_PENALTY) * 10f)
            .coerceIn(-20f, 20f)
        sliderPresencePenalty?.setLabelFormatter { "${it / 10.0}" }

        fieldMaxTokens?.setText(requireArguments().getInt("maxTokens", ApiEndpointObject.DEFAULT_MAX_TOKENS).toString())
        fieldEndSeparator?.setText(requireArguments().getString("endSeparator", ""))
        fieldPrefix?.setText(requireArguments().getString("prefix", ""))

        // Persistent inline warning while the host field contains a plain
        // http:// address — the save flow additionally asks for explicit
        // confirmation before accepting an unencrypted endpoint.
        hostInputLayout = view.findViewById(R.id.textInputLayout11)
        updateHostWarning()
        fieldHost?.doAfterTextChanged { updateHostWarning() }

        fieldAuthType?.setOnClickListener { showAuthTypeChooser() }
        view.findViewById<TextInputLayout>(R.id.textInputLayoutAuth)?.setOnClickListener { showAuthTypeChooser() }

        fieldModel?.setOnClickListener { showModelChooser() }
        view.findViewById<TextInputLayout>(R.id.textInputLayoutModel)?.setOnClickListener { showModelChooser() }

        if (requireArguments().getInt("position") == -1) {
            textDialogTitle?.text = getString(R.string.label_add_api_endpoint)
        }

        builder!!.setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_save) { _, _ -> validateForm() }
            .setNegativeButton(R.string.btn_cancel) { _, _ ->  }

        if (requireArguments().getInt("position") != -1) {
            builder!!.setNeutralButton(R.string.btn_delete) { _, _ -> run {
                MaterialAlertDialogBuilder(this.requireContext(), R.style.App_MaterialAlertDialog)
                    .setTitle(R.string.label_delete_api_endpoint)
                    .setMessage(R.string.message_delete_api_endpoint)
                    .setPositiveButton(R.string.yes) { _, _ -> listener!!.onDelete(requireArguments().getInt("position"), Hash.hash(requireArguments().getString("label")!!)) }
                    .setNegativeButton(R.string.no) { _, _ ->  listener!!.onCancel(requireArguments().getInt("position"))}
                    .show()
            }}
        }

        return builder!!.create()
    }

    private fun showAuthTypeChooser() {
        val labels = authTypes.map { authLabel(it) }.toTypedArray()
        val current = authTypes.indexOf(selectedAuthType).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this.requireContext(), R.style.App_MaterialAlertDialog)
            .setTitle(R.string.api_endpoint_auth_mode)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                selectedAuthType = authTypes[which]
                fieldAuthType?.setText(authLabel(selectedAuthType))
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun showModelChooser() {
        val modelDialog = AdvancedModelSelectorDialogFragment.newInstance(selectedModel, "")
        modelDialog.setModelSelectedListener { model ->
            selectedModel = model
            fieldModel?.setText(model)
        }
        modelDialog.show(parentFragmentManager, "ProfileModelSelector")
    }

    private fun normalizedChatEndpoint(): String {
        val value = fieldChatEndpoint?.text.toString().trim()
        return if (value.isEmpty()) ApiEndpointObject.DEFAULT_CHAT_ENDPOINT else value
    }

    private fun buildEndpointObject(): ApiEndpointObject {
        return ApiEndpointObject(
            label = fieldLabel?.text.toString(),
            host = fieldHost?.text.toString(),
            apiKey = fieldApiKey?.text.toString(),
            chatEndpoint = normalizedChatEndpoint(),
            authType = selectedAuthType,
            model = selectedModel.ifBlank { ApiEndpointObject.DEFAULT_MODEL },
            temperature = (sliderTemperature?.value ?: (ApiEndpointObject.DEFAULT_TEMPERATURE * 10f)) / 10f,
            topP = (sliderTopP?.value ?: (ApiEndpointObject.DEFAULT_TOP_P * 10f)) / 10f,
            frequencyPenalty = (sliderFrequencyPenalty?.value ?: (ApiEndpointObject.DEFAULT_FREQUENCY_PENALTY * 10f)) / 10f,
            presencePenalty = (sliderPresencePenalty?.value ?: (ApiEndpointObject.DEFAULT_PRESENCE_PENALTY * 10f)) / 10f,
            maxTokens = fieldMaxTokens?.text.toString().toIntOrNull() ?: ApiEndpointObject.DEFAULT_MAX_TOKENS,
            endSeparator = fieldEndSeparator?.text.toString(),
            prefix = fieldPrefix?.text.toString()
        )
    }

    private fun updateHostWarning() {
        val host = fieldHost?.text.toString().trim()
        hostInputLayout?.error = if (host.startsWith("http://")) {
            getString(R.string.warning_http_endpoint_inline)
        } else {
            null
        }
    }

    private fun isValidEndpointUrl(url: String): Boolean {
        return try {
            val uri = java.net.URI(url)
            (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    private fun validateForm() {
        if (fieldLabel?.text.toString().isEmpty()) {
            listener!!.onError(getString(R.string.label_error_api_endpoint_empty), requireArguments().getInt("position"))
            return
        }

        val host = fieldHost?.text.toString().trim()

        if (host.isEmpty()) {
            listener!!.onError(getString(R.string.label_error_api_endpoint_empty), requireArguments().getInt("position"))
            return
        }

        if (!isValidEndpointUrl(host)) {
            listener!!.onError(getString(R.string.label_error_api_endpoint_invalid_url), requireArguments().getInt("position"))
            return
        }

        if (fieldApiKey?.text.toString().isEmpty()) {
            fieldApiKey?.setText(requireArguments().getString("apiKey"))
        }

        // Plain-http endpoints send the API key and all chat content
        // unencrypted. Allowed (local/LAN servers are a legitimate use),
        // but only after the user explicitly accepts the risk.
        if (host.startsWith("http://")) {
            MaterialAlertDialogBuilder(this.requireContext(), R.style.App_MaterialAlertDialog)
                .setTitle(R.string.title_http_endpoint_warning)
                .setMessage(R.string.message_http_endpoint_warning)
                .setPositiveButton(R.string.btn_http_endpoint_accept) { _, _ -> commitForm() }
                .setNegativeButton(R.string.btn_cancel) { _, _ -> listener!!.onCancel(requireArguments().getInt("position")) }
                .show()
            return
        }

        commitForm()
    }

    private fun commitForm() {
        if (requireArguments().getInt("position") == -1) {
            listener!!.onAdd(buildEndpointObject())
        } else {
            listener!!.onEdit(
                requireArguments().getString("label")!!,
                buildEndpointObject(),
                requireArguments().getInt("position")
            )
        }
    }

    fun setListener(listener: StateChangesListener) {
        this.listener = listener
    }

    interface StateChangesListener {
        fun onAdd(apiEndpoint: ApiEndpointObject) { /* default */ }
        fun onEdit(oldLabel: String, apiEndpoint: ApiEndpointObject, position: Int) { /* default */ }
        fun onDelete(position: Int, id: String) { /* default */ }
        fun onError(message: String, position: Int) { /* default */ }
        fun onCancel(position: Int) { /* default */ }
    }
}
