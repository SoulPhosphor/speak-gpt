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

package org.teslasoft.assistant.preferences

import org.teslasoft.assistant.preferences.dto.FavoriteModelObject

/**
 * The recommended parameters for each background job: what its settings start
 * with, and what Restore Default Settings returns a model to. They never come
 * from an API profile.
 *
 * Summarizer: a low temperature keeps the summary close to what was said, and
 * no penalties, because a summary must repeat names and facts accurately.
 *
 * Memory Scanner: the same reasoning for pulling memories out of a
 * conversation as structured data; 0.3 was already its recommended
 * temperature.
 *
 * Streaming starts off for both because nobody watches the reply being
 * written; it stays adjustable for providers that require one setting or the
 * other.
 */
object RecommendedModelParameters {
    const val STREAMING = false
    const val TEMPERATURE = 0.3f
    const val TOP_P = 1.0f
    const val FREQUENCY_PENALTY = 0.0f
    const val PRESENCE_PENALTY = 0.0f

    /** [scope]'s recommended values for one model. */
    @Suppress("UNUSED_PARAMETER")
    fun forScope(scope: ModelPickerScope, modelId: String = "", endpointId: String = ""): FavoriteModelObject =
        FavoriteModelObject(modelId, endpointId).apply {
            streaming = STREAMING
            temperature = TEMPERATURE
            topP = TOP_P
            frequencyPenalty = FREQUENCY_PENALTY
            presencePenalty = PRESENCE_PENALTY
        }
}
