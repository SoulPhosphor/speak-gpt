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
import org.json.JSONObject
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import java.util.Base64

/**
 * OpenAI-compatible Image API adapter
 * (image-generation-rebuild-plan.md §9): POSTs the standard generations
 * path under the endpoint's base URL and accepts Base64 or URL response
 * data. Behavior comes from this adapter having been selected for the
 * endpoint — never from a substring of the model name (§4.3).
 *
 * Automatic shape or quality omits the parameter entirely (§11: omission
 * is always preferred over sending a value an endpoint may reject), and
 * no response_format is requested for the same reason — both Base64 and
 * URL replies are handled.
 */
object OpenAiImageAdapter : ImageProviderAdapter {

    override val providerName: String = "OpenAI-compatible"

    override val capabilities: ImageAdapterCapabilities =
        ImageAdapterCapabilities(supportsShape = true, supportsQuality = true)

    /** The generations path under the endpoint's base URL. */
    fun imagesUrl(endpoint: ApiEndpointObject): String {
        var base = endpoint.host.trim()
        if (!base.endsWith("/")) base += "/"
        return base + "images/generations"
    }

    /** Canonical size per shape (the current OpenAI image sizes); a
     *  provider that cannot accept one answers with the §11
     *  unsupported-option flow rather than being silently second-guessed. */
    fun sizeFor(shape: ImageShape): String? = when (shape) {
        ImageShape.AUTOMATIC -> null
        ImageShape.SQUARE -> "1024x1024"
        ImageShape.LANDSCAPE -> "1536x1024"
        ImageShape.PORTRAIT -> "1024x1536"
    }

    fun qualityFor(quality: ImageQuality): String? = when (quality) {
        ImageQuality.AUTOMATIC -> null
        ImageQuality.LOW -> "low"
        ImageQuality.MEDIUM -> "medium"
        ImageQuality.HIGH -> "high"
    }

    /** Request body as JSON, visible for unit tests. Exactly one image. */
    fun buildRequestBodyJson(request: ImageGenerationRequest): String {
        val body = JSONObject()
        body.put("model", request.modelId)
        body.put("prompt", request.prompt)
        body.put("n", 1)
        sizeFor(request.shape)?.let { body.put("size", it) }
        qualityFor(request.quality)?.let { body.put("quality", it) }
        return body.toString()
    }

    override fun buildHttpRequest(
        request: ImageGenerationRequest,
        endpoint: ApiEndpointObject
    ): Request {
        val builder = Request.Builder()
            .url(imagesUrl(endpoint))
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
        val first = root.optJSONArray("data")?.optJSONObject(0)
            ?: throw ImageGenerationException(
                ImageErrorCause.NO_USABLE_IMAGE,
                "the response contained no image data"
            )
        val b64 = first.optString("b64_json", "")
        val url = first.optString("url", "")
        val payload = when {
            b64.isNotBlank() -> try {
                ImagePayload.Bytes(Base64.getMimeDecoder().decode(b64))
            } catch (_: IllegalArgumentException) {
                throw ImageGenerationException(
                    ImageErrorCause.NO_USABLE_IMAGE,
                    "the Base64 image data could not be decoded"
                )
            }
            url.isNotBlank() -> ImagePayload.RemoteUrl(url)
            else -> throw ImageGenerationException(
                ImageErrorCause.NO_USABLE_IMAGE,
                "the response carried neither image data nor an image URL"
            )
        }
        val usage = root.optJSONObject("usage")?.toString()?.take(200)
        return AdapterImageResponse(payload, usage)
    }

    override fun classifyHttpError(status: Int, body: String): ImageErrorCause {
        val lower = body.lowercase()
        val errorCode = try {
            JSONObject(body).optJSONObject("error")?.optString("code", "").orEmpty()
        } catch (_: Exception) { "" }
        val errorParam = try {
            JSONObject(body).optJSONObject("error")?.optString("param", "").orEmpty()
        } catch (_: Exception) { "" }
        return when {
            status == 401 || status == 403 -> ImageErrorCause.AUTHENTICATION_FAILED
            errorCode == "moderation_blocked" ||
                lower.contains("content_policy") ||
                lower.contains("content policy") ||
                lower.contains("moderation") ||
                lower.contains("safety system") -> ImageErrorCause.PROMPT_REFUSED
            errorCode == "model_not_found" || errorParam == "model" ||
                (lower.contains("model") &&
                    (lower.contains("does not exist") || lower.contains("not found") ||
                        lower.contains("do not have access") ||
                        lower.contains("invalid model"))) -> ImageErrorCause.GENERATOR_MODEL_REJECTED
            status == 400 && (errorParam == "size" || errorParam == "quality") ->
                ImageErrorCause.UNSUPPORTED_OPTION
            else -> ImageErrorCause.PROVIDER_ERROR
        }
    }
}
