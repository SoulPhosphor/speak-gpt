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

package org.teslasoft.assistant.ui.reasoning

import android.content.Context
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.reasoning.ReasoningCapability

/**
 * Bind-time reasoning-capability diagnostics for the model-row lists.
 *
 * The reasoning lightbulb on a model row is driven entirely by the capability
 * that resolves for that row's endpoint id + model id at bind time. When a row
 * that should show a bulb does not, the question is exactly what capability
 * reached the row — was it Unknown (a resolution/data-flow miss) or Known (a
 * display problem)? This logs that answer, once per model id per adapter
 * instance, into the Voice Debug Log (the same channel other reasoning
 * capability diagnostics already use).
 *
 * It records the endpoint id and model id the row resolved against, the
 * capability facts, and the capability source, so a mismatch between what a row
 * sees and what Quick Settings sees for the same model is visible side by side.
 * It never records any reasoning text — capability facts only.
 */
object ReasoningBulbDiagnostics {

    fun logResolvedOnce(
        context: Context,
        alreadyLogged: MutableSet<String>,
        surface: String,
        endpointId: String,
        modelId: String,
        capability: ReasoningCapability
    ) {
        if (!alreadyLogged.add(modelId)) return
        val message = buildString {
            append("Reasoning bulb bind — $surface\n")
            append("Endpoint: ${endpointId.ifBlank { "(blank)" }}\n")
            append("Model: $modelId\n")
            append("Bulb shown: ${capability.isReasoningCapable}\n")
            append("Actionable: ${capability.hasConfigurableSetting}\n")
            append("support=${capability.support}, ")
            append("effortConfigurable=${capability.effortConfigurable}, ")
            append("canDisable=${capability.canDisableReasoning}, ")
            append("canReturnVisible=${capability.canReturnVisibleReasoning}, ")
            append("mandatory=${capability.reasoningMandatory}\n")
            append("efforts=${capability.supportedEfforts.map { it.serialized }}, ")
            append("authoritative=${capability.effortsAuthoritative}, ")
            append("source=${capability.source}")
        }
        Logger.logAsync(context, "event", "ReasoningBulb", "info", message)
    }
}
