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

import okhttp3.Request
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject

/**
 * What one provider's raw response resolves to: image bytes directly, or a
 * temporary URL the coordinator must download as a separate, separately
 * timed step (§13). The URL lives only in memory and is never persisted
 * (§12).
 */
sealed class ImagePayload {
    class Bytes(val bytes: ByteArray) : ImagePayload()
    class RemoteUrl(val url: String) : ImagePayload()
}

/** A parsed 2xx provider response: the image payload plus an optional
 *  compact provider-reported usage summary (§9 normalized result). */
class AdapterImageResponse(
    val payload: ImagePayload,
    val usageSummary: String? = null
)

/**
 * Which normalized request options this provider's API can carry at all
 * (§11): an option the adapter cannot express falls back to the provider
 * default — reported, never silently ignored, by the calling layer.
 */
class ImageAdapterCapabilities(
    val supportsShape: Boolean,
    val supportsQuality: Boolean
)

/**
 * One provider-specific translation layer
 * (image-generation-rebuild-plan.md §9). Adapters are selected from saved
 * endpoint configuration — never from the image model's name — and every
 * generation path goes through one; nothing may bypass this layer (§11).
 */
interface ImageProviderAdapter {

    /** Technical fallback name for diagnostics when the endpoint profile
     *  has no free-text provider name of its own. */
    val providerName: String

    val capabilities: ImageAdapterCapabilities

    /** Build the provider-specific HTTP request for exactly one image. */
    fun buildHttpRequest(request: ImageGenerationRequest, endpoint: ApiEndpointObject): Request

    /** Parse a 2xx response body; throws [ImageGenerationException] with
     *  NO_USABLE_IMAGE when the body carries no usable image. */
    fun parseResponse(body: String): AdapterImageResponse

    /** Classify a non-2xx provider response into a §13 cause. */
    fun classifyHttpError(status: Int, body: String): ImageErrorCause
}

/** The endpoint's existing authentication modes, applied exactly as the
 *  chat funnel, Summarizer, and Memory Assistant apply them. */
object ImageEndpointAuth {
    fun headers(endpoint: ApiEndpointObject): Map<String, String> = when (endpoint.authType) {
        ApiEndpointObject.AUTH_X_API_KEY -> mapOf("x-api-key" to endpoint.apiKey)
        ApiEndpointObject.AUTH_API_KEY -> mapOf("api-key" to endpoint.apiKey)
        else -> mapOf("Authorization" to "Bearer ${endpoint.apiKey}")
    }
}
