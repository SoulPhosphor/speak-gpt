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

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import java.util.Base64

/**
 * OpenRouter Image API adapter (image-generation-rebuild-plan.md §9):
 * OpenRouter has no separate images endpoint — image generation goes
 * through its normal chat endpoint with the image-output request flag
 * (`modalities: ["image", "text"]`), and the generated image arrives in
 * the assistant message's `images` list, normally as a Base64 data URL.
 * See <https://openrouter.ai/docs/guides/overview/multimodal/image-generation>.
 *
 * OpenRouter's image mechanism exposes no normalized shape or quality
 * request fields, so both options are unsupported here: they fall back to
 * the provider default, and the calling layer reports the fallback rather
 * than silently ignoring it (§11).
 */
object OpenRouterImageAdapter : ImageProviderAdapter {

    override val providerName: String = "OpenRouter"

    override val capabilities: ImageAdapterCapabilities =
        ImageAdapterCapabilities(supportsShape = false, supportsQuality = false)

    /** The endpoint's own chat path under its base URL — the same
     *  composition rule the chat funnel uses. */
    fun chatUrl(endpoint: ApiEndpointObject): String {
        var base = endpoint.host.trim()
        if (!base.endsWith("/")) base += "/"
        val path = endpoint.chatEndpoint
            .ifBlank { ApiEndpointObject.DEFAULT_CHAT_ENDPOINT }
            .trim().trimStart('/')
        return base + path
    }

    /** Request body as JSON, visible for unit tests. One non-streamed
     *  request; the prompt is the single user message. */
    fun buildRequestBodyJson(request: ImageGenerationRequest): String {
        val body = JSONObject()
        body.put("model", request.modelId)
        val message = JSONObject()
        message.put("role", "user")
        message.put("content", request.prompt)
        body.put("messages", JSONArray().put(message))
        body.put("modalities", JSONArray().put("image").put("text"))
        return body.toString()
    }

    override fun buildHttpRequest(
        request: ImageGenerationRequest,
        endpoint: ApiEndpointObject
    ): Request {
        val builder = Request.Builder()
            .url(chatUrl(endpoint))
            .post(
                buildRequestBodyJson(request)
                    .toRequestBody("application/json".toMediaType())
            )
        for ((name, value) in ImageEndpointAuth.headers(endpoint)) {
            builder.header(name, value)
        }
        return builder.build()
    }

    override fun parseResponse(body: String): AdapterImageResponse {
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            throw ImageGenerationException(
                ImageErrorCause.NO_USABLE_IMAGE,
                "the response was not valid JSON"
            )
        }
        val message = root.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")
            ?: throw ImageGenerationException(
                ImageErrorCause.NO_USABLE_IMAGE,
                "the response contained no assistant message"
            )
        val imageUrl = message.optJSONArray("images")?.optJSONObject(0)
            ?.optJSONObject("image_url")?.optString("url", "").orEmpty()
        if (imageUrl.isBlank()) {
            throw ImageGenerationException(
                ImageErrorCause.NO_USABLE_IMAGE,
                "the model reply contained no image"
            )
        }
        val usage = root.optJSONObject("usage")?.toString()?.take(200)
        return AdapterImageResponse(payloadFromUrl(imageUrl), usage)
    }

    /** A data URL decodes in place; an http(s) URL becomes a separate,
     *  separately timed download step for the coordinator. */
    fun payloadFromUrl(url: String): ImagePayload {
        if (url.startsWith("data:", ignoreCase = true)) {
            val comma = url.indexOf(',')
            if (comma <= 0) {
                throw ImageGenerationException(
                    ImageErrorCause.NO_USABLE_IMAGE,
                    "the data URL was malformed"
                )
            }
            return try {
                ImagePayload.Bytes(Base64.getMimeDecoder().decode(url.substring(comma + 1)))
            } catch (_: IllegalArgumentException) {
                throw ImageGenerationException(
                    ImageErrorCause.NO_USABLE_IMAGE,
                    "the Base64 image data could not be decoded"
                )
            }
        }
        return ImagePayload.RemoteUrl(url)
    }

    override fun classifyHttpError(status: Int, body: String): ImageErrorCause {
        val lower = body.lowercase()
        return when {
            status == 401 -> ImageErrorCause.AUTHENTICATION_FAILED
            status == 403 && (lower.contains("moderation") || lower.contains("flagged")) ->
                ImageErrorCause.PROMPT_REFUSED
            status == 403 -> ImageErrorCause.AUTHENTICATION_FAILED
            status == 408 -> ImageErrorCause.TIMED_OUT
            status == 404 && lower.contains("model") -> ImageErrorCause.GENERATOR_MODEL_REJECTED
            status == 400 && lower.contains("model") -> ImageErrorCause.GENERATOR_MODEL_REJECTED
            lower.contains("content_policy") || lower.contains("content policy") ->
                ImageErrorCause.PROMPT_REFUSED
            else -> ImageErrorCause.PROVIDER_ERROR
        }
    }
}
