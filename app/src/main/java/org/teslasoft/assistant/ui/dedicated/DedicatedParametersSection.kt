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

package org.teslasoft.assistant.ui.dedicated

import android.view.View
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.DedicatedModelRouting
import org.teslasoft.assistant.preferences.FavoriteModelParameters
import org.teslasoft.assistant.preferences.ModelPickerScope
import org.teslasoft.assistant.preferences.RecommendedModelParameters
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.ui.util.SaveIconFlash
import org.teslasoft.assistant.ui.widgets.SamplingParameterControl
import org.teslasoft.assistant.ui.widgets.SamplingParameterSpec

/**
 * A background job's parameters on its settings screen
 * (view_dedicated_model_parameters.xml): Streaming and the four sampling
 * values its requests send. Like Quick Settings, they show what the job uses
 * now; selecting a model loads that model's saved settings (or the recommended
 * ones) while Enable Model Settings is on. They never come from an API
 * profile.
 *
 * [autoSave] saves each change at once (Summarizer Settings); otherwise the
 * screen's own Save calls [commit] (Advanced Memory Settings).
 *
 * The Save Parameters link above them (the Quick Settings wording) saves the
 * values on screen as the selected model's own settings: on the job's favorite
 * when it is one, otherwise on its Recent entry. It shows only while Enable
 * Model Settings is on and there is somewhere to save.
 */
class DedicatedParametersSection(
    root: View,
    private val scope: ModelPickerScope,
    private val autoSave: Boolean
) {

    private val context = root.context
    private val routing = DedicatedModelRouting.of(scope)
    private val saveLink: MaterialButton = root.findViewById(R.id.btn_save_model_parameters)
    private val streamingRow: View = root.findViewById(R.id.row_model_streaming)
    private val streamingCheck: MaterialCheckBox = root.findViewById(R.id.check_model_streaming)
    private val temperature: SamplingParameterControl = root.findViewById(R.id.model_temperature_slider)
    private val topP: SamplingParameterControl = root.findViewById(R.id.model_top_p_slider)
    private val frequencyPenalty: SamplingParameterControl = root.findViewById(R.id.model_frequency_penalty_slider)
    private val presencePenalty: SamplingParameterControl = root.findViewById(R.id.model_presence_penalty_slider)

    /** What the screen last showed or saved, to tell whether it was edited. */
    private var shown = ""

    init {
        streamingRow.setOnClickListener {
            streamingCheck.isChecked = !streamingCheck.isChecked
            changed()
        }
        saveLink.setOnClickListener { saveToModel() }
        show(FavoriteModelParameters.live(context, scope))
    }

    /** Shows the job's current parameters (after a model was selected) and
     *  the link for its selected model. */
    fun reload() {
        show(FavoriteModelParameters.live(context, scope))
    }

    /** After a model was selected: like Quick Settings, the model's
     *  parameters replace what is on screen only while Enable Model Settings
     *  is on (that is when selecting a model changes them). Otherwise any
     *  unsaved edits stay and only the link follows the new model. */
    fun onModelSelected() {
        if (FavoriteModelParameters.isEnabled(context)) reload() else refreshLink()
    }

    /** Puts the recommended parameters on screen (saved like any change). */
    fun showRecommended() {
        show(RecommendedModelParameters.forScope(scope))
        changed()
    }

    /** Saves the values on screen as the job's parameters. */
    fun commit() {
        FavoriteModelParameters.setLive(context, scope, currentValues())
        shown = snapshot(currentValues())
    }

    /**
     * On returning to the screen: shows the job's parameters again when they
     * were changed elsewhere (Model Settings saved for the selected model)
     * while nothing on screen was edited. Unsaved edits are kept.
     */
    fun syncIfUnedited() {
        if (snapshot(currentValues()) == shown) reload() else refreshLink()
    }

    private fun snapshot(values: FavoriteModelObject): String =
        "${values.streaming}|${values.temperature}|${values.topP}|${values.frequencyPenalty}|${values.presencePenalty}"

    private fun show(values: FavoriteModelObject) {
        val recommended = RecommendedModelParameters.forScope(scope)
        streamingCheck.isChecked = values.streaming ?: recommended.streaming!!
        temperature.configure(SamplingParameterSpec.TEMPERATURE, values.temperature ?: recommended.temperature!!) { changed() }
        topP.configure(SamplingParameterSpec.TOP_P, values.topP ?: recommended.topP!!) { changed() }
        frequencyPenalty.configure(
            SamplingParameterSpec.FREQUENCY_PENALTY, values.frequencyPenalty ?: recommended.frequencyPenalty!!
        ) { changed() }
        presencePenalty.configure(
            SamplingParameterSpec.PRESENCE_PENALTY, values.presencePenalty ?: recommended.presencePenalty!!
        ) { changed() }
        // The controls round each value to their precision; compare with that.
        shown = snapshot(currentValues())
        refreshLink()
    }

    private fun changed() {
        if (autoSave) commit()
    }

    private fun refreshLink() {
        val model = routing.modelId(context)
        val endpointId = routing.endpointId(context)
        val visible = FavoriteModelParameters.isEnabled(context) && model.isNotBlank() && endpointId.isNotBlank() &&
            FavoriteModelParameters.canSave(context, model, endpointId, scope)
        saveLink.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        saveLink.setText(
            if (FavoriteModelParameters.savesToFavorite(context, model, endpointId, scope)) {
                R.string.quick_save_parameters_favorite
            } else R.string.quick_save_parameters_recent
        )
    }

    /** The values on screen, including one still being typed. */
    private fun currentValues() = FavoriteModelObject(routing.modelId(context), routing.endpointId(context)).apply {
        streaming = streamingCheck.isChecked
        temperature = this@DedicatedParametersSection.temperature.value
        topP = this@DedicatedParametersSection.topP.value
        frequencyPenalty = this@DedicatedParametersSection.frequencyPenalty.value
        presencePenalty = this@DedicatedParametersSection.presencePenalty.value
    }

    private fun saveToModel() {
        val model = routing.modelId(context)
        val endpointId = routing.endpointId(context)
        if (model.isBlank() || endpointId.isBlank() || !FavoriteModelParameters.isEnabled(context)) return
        val saved = FavoriteModelParameters.save(context, model, endpointId, currentValues(), scope)
        refreshLink()
        if (!saved) return
        SaveIconFlash.flashText(saveLink)
        Toast.makeText(context, context.getString(R.string.quick_save_parameters_saved, model), Toast.LENGTH_SHORT).show()
    }
}
