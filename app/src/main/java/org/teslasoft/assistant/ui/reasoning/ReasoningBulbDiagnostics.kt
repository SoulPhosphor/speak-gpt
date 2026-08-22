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
import org.teslasoft.assistant.reasoning.ReasoningCapability

/**
 * Compatibility shim for the temporary model-row reasoning diagnostic.
 *
 * The bind-time diagnostic was useful while tracking missing reasoning bulbs,
 * but logging every model row polluted user-facing debug logs whenever model
 * lists were populated. Keep the call surface temporarily so the diagnostic can
 * be removed without changing model-list behavior; it intentionally records
 * nothing and performs no logging.
 */
object ReasoningBulbDiagnostics {

    @Suppress("UNUSED_PARAMETER")
    fun logResolvedOnce(
        context: Context,
        alreadyLogged: MutableSet<String>,
        surface: String,
        endpointId: String,
        modelId: String,
        capability: ReasoningCapability
    ) {
        // Intentionally empty. Do not log model-row capability enumeration.
    }
}
