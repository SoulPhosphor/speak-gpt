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

package org.teslasoft.assistant.providers

import org.teslasoft.assistant.preferences.dto.FavoriteModelObject

/**
 * Decides what a brand-new chat should do with the last successfully-used
 * provider/model/routing (owner spec, Aug 8 2026). Pure and framework-free so
 * the decision is unit-tested on the JVM; the caller supplies the facts it has
 * already looked up locally (no network).
 *
 * The honesty rule the owner set: a missing local configuration is NEVER
 * reported as the model being unavailable. Deleting a provider profile or a
 * routing favorite only tells us the saved local setup is gone — the model may
 * still exist. So a missing-config outcome carries no claim about the model;
 * "model unavailable" is decided elsewhere, only from the provider's own
 * definite model-not-found response on send.
 */
object NewChatProviderRestore {

    enum class Outcome {
        /** Nothing was ever recorded (no successful reply yet) — send the user
         *  to the API Endpoints screen to set a provider/model up. Never a
         *  hardcoded default model. */
        NO_CONFIG,

        /** A config was recorded but the local setup needed to use it is gone:
         *  the provider profile was deleted, or the routing was Only/Preferred
         *  and its favorite no longer exists. Show the configuration dialog and
         *  open the Summoning Circle. Says nothing about the model itself. */
        MISSING_CONFIG,

        /** The saved provider + model + routing can be honored; restore them. */
        RESTORE
    }

    /**
     * @param endpointId     recorded last-successful endpoint id
     * @param model          recorded last-successful model
     * @param routing        recorded routing (FavoriteModelObject.ROUTING_*)
     * @param endpointExists whether that endpoint profile still exists locally
     * @param favoriteExists whether a favorite for (model, endpoint) still exists
     */
    fun decide(
        endpointId: String,
        model: String,
        routing: String,
        endpointExists: Boolean,
        favoriteExists: Boolean
    ): Outcome = when {
        endpointId.isBlank() || model.isBlank() -> Outcome.NO_CONFIG
        !endpointExists -> Outcome.MISSING_CONFIG
        // Automatic needs no saved favorite — it is "any provider", so a missing
        // favorite is not a lost configuration. Only and Preferred keep their
        // provider choice on the favorite; if that is gone the configuration is
        // genuinely lost.
        routing != FavoriteModelObject.ROUTING_AUTOMATIC && !favoriteExists -> Outcome.MISSING_CONFIG
        else -> Outcome.RESTORE
    }
}
