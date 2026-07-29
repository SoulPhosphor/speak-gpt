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

import org.teslasoft.assistant.preferences.dto.ApiEndpointObject

/**
 * Adapter selection (image-generation-rebuild-plan.md §9): the adapter is
 * chosen from SAVED ENDPOINT CONFIGURATION — the endpoint's host — and
 * never from the image model's name. An OpenRouter endpoint speaks the
 * chat-with-image-output mechanism; everything else speaks the
 * OpenAI-compatible generations path.
 */
object ImageProviderAdapters {

    fun isOpenRouter(endpoint: ApiEndpointObject): Boolean =
        endpoint.host.contains("openrouter.ai", ignoreCase = true)

    fun forEndpoint(endpoint: ApiEndpointObject): ImageProviderAdapter =
        if (isOpenRouter(endpoint)) OpenRouterImageAdapter else OpenAiImageAdapter
}
