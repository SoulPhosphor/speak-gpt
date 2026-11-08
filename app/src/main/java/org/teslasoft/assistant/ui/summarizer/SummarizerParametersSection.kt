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

package org.teslasoft.assistant.ui.summarizer

import android.view.View
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.FavoriteModelParameters
import org.teslasoft.assistant.preferences.ModelPickerScope
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.ui.util.SaveIconFlash
import org.teslasoft.assistant.ui.widgets.SamplingParameterControl
import org.teslasoft.assistant.ui.widgets.SamplingParameterSpec

/**
 * Summarizer Settings' default parameters: Streaming and the four sampling
 * values every Summarizer model uses unless it has its own saved settings.
 * They save as they are changed and never come from an API profile.
 *
 * The Save Parameters link above them (the Quick Settings wording) saves these
 * values as the selected Summarizer model's own settings: on its Summarizer
 * favorite when it is one, otherwise on its Summarizer Recent entry. It shows
 * only while Enable Model Settings is on and there is somewhere to save.
 */
class SummarizerParametersSection(root: View) {

    private val context = root.context
    private val prefs = Preferences.getPreferences(context, "")
    private val saveLink: MaterialButton = root.findViewById(R.id.btn_save_summarizer_parameters)
    private val streamingRow: View = root.findViewById(R.id.row_summarizer_streaming)
    private val streamingCheck: MaterialCheckBox = root.findViewById(R.id.check_summarizer_streaming)
    private val temperature: SamplingParameterControl = root.findViewById(R.id.summarizer_temperature_slider)
    private val topP: SamplingParameterControl = root.findViewById(R.id.summarizer_top_p_slider)
    private val frequencyPenalty: SamplingParameterControl = root.findViewById(R.id.summarizer_frequency_penalty_slider)
    private val presencePenalty: SamplingParameterControl = root.findViewById(R.id.summarizer_presence_penalty_slider)

    init {
        streamingCheck.isChecked = prefs.getSummarizerStreaming()
        streamingRow.setOnClickListener {
            val checked = !streamingCheck.isChecked
            streamingCheck.isChecked = checked
            prefs.setSummarizerStreaming(checked)
        }
        temperature.configure(SamplingParameterSpec.TEMPERATURE, prefs.getSummarizerTemperature()) {
            prefs.setSummarizerTemperature(it)
        }
        topP.configure(SamplingParameterSpec.TOP_P, prefs.getSummarizerTopP()) {
            prefs.setSummarizerTopP(it)
        }
        frequencyPenalty.configure(SamplingParameterSpec.FREQUENCY_PENALTY, prefs.getSummarizerFrequencyPenalty()) {
            prefs.setSummarizerFrequencyPenalty(it)
        }
        presencePenalty.configure(SamplingParameterSpec.PRESENCE_PENALTY, prefs.getSummarizerPresencePenalty()) {
            prefs.setSummarizerPresencePenalty(it)
        }
        saveLink.setOnClickListener { saveToModel() }
        refresh()
    }

    /** Shows or hides the link for the selected Summarizer model. */
    fun refresh() {
        val model = prefs.getSummarizerModel()
        val endpointId = prefs.getSummarizerEndpointId()
        val show = FavoriteModelParameters.isEnabled(context) && model.isNotBlank() && endpointId.isNotBlank() &&
            FavoriteModelParameters.canSave(context, model, endpointId, ModelPickerScope.SUMMARIZER)
        saveLink.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        saveLink.setText(
            if (FavoriteModelParameters.savesToFavorite(context, model, endpointId, ModelPickerScope.SUMMARIZER)) {
                R.string.quick_save_parameters_favorite
            } else R.string.quick_save_parameters_recent
        )
    }

    /** The values on screen, including one still being typed. */
    private fun currentValues() = FavoriteModelParameters.summarizerDefaults(
        context, prefs.getSummarizerModel(), prefs.getSummarizerEndpointId()
    ).apply {
        streaming = streamingCheck.isChecked
        temperature = this@SummarizerParametersSection.temperature.value
        topP = this@SummarizerParametersSection.topP.value
        frequencyPenalty = this@SummarizerParametersSection.frequencyPenalty.value
        presencePenalty = this@SummarizerParametersSection.presencePenalty.value
    }

    private fun saveToModel() {
        val model = prefs.getSummarizerModel()
        val endpointId = prefs.getSummarizerEndpointId()
        if (model.isBlank() || endpointId.isBlank() || !FavoriteModelParameters.isEnabled(context)) return
        val saved = FavoriteModelParameters.save(
            context, model, endpointId, currentValues(), ModelPickerScope.SUMMARIZER
        )
        refresh()
        if (!saved) return
        SaveIconFlash.flashText(saveLink)
        Toast.makeText(context, context.getString(R.string.quick_save_parameters_saved, model), Toast.LENGTH_SHORT).show()
    }
}
