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

package org.teslasoft.assistant.imagegen

/**
 * The one shared internal image request (image-generation-rebuild-plan.md
 * §9/§11): both `/imagine` and model-initiated generation convert their
 * inputs into this normalized form, and the provider-specific adapter
 * translates it into values the selected provider accepts. Neither path
 * may bypass the adapter layer.
 *
 * Exactly one image per request (§6 cost control) — there is deliberately
 * no image-count field. The shape and quality here are the RESOLVED values
 * after §11 precedence (explicit per-request override, else the saved
 * default); AUTOMATIC defers to the provider.
 */
data class ImageGenerationRequest(
    val prompt: String,
    val shape: ImageShape,
    val quality: ImageQuality,
    val endpointId: String,
    val modelId: String
)
