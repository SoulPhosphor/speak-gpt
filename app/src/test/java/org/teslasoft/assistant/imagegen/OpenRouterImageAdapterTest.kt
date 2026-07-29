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

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import java.util.Base64

/**
 * The OpenRouter adapter (image-generation-rebuild-plan.md §9): image
 * generation goes through the normal chat endpoint with the image-output
 * modalities flag — not an OpenAI-style generations path — and the image
 * arrives in the assistant message's images list, normally as a data URL.
 * Shape and quality have no request fields there, so the adapter declares
 * them unsupported rather than silently inventing parameters (§11).
 */
class OpenRouterImageAdapterTest {

    private fun request() = ImageGenerationRequest(
        prompt = "a luminous forest temple",
        shape = ImageShape.AUTOMATIC,
        quality = ImageQuality.AUTOMATIC,
        endpointId = "endpoint-2",
        modelId = "google/example-image-model"
    )

    private fun endpoint() = ApiEndpointObject(
        "OpenRouter", "https://openrouter.ai/api/v1/", "or-key"
    )

    @Test
    fun requestGoesThroughTheChatEndpointWithImageModalities() {
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            OpenRouterImageAdapter.chatUrl(endpoint())
        )
        val body = JSONObject(OpenRouterImageAdapter.buildRequestBodyJson(request()))
        assertEquals("google/example-image-model", body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("a luminous forest temple", messages.getJSONObject(0).getString("content"))
        val modalities = body.getJSONArray("modalities")
        assertEquals("image", modalities.getString(0))
        assertEquals("text", modalities.getString(1))
    }

    @Test
    fun shapeAndQualityAreDeclaredUnsupported() {
        assertFalse(OpenRouterImageAdapter.capabilities.supportsShape)
        assertFalse(OpenRouterImageAdapter.capabilities.supportsQuality)
    }

    @Test
    fun dataUrlImageDecodesToBytes() {
        val bytes = byteArrayOf(9, 8, 7, 6)
        val dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
        val payload = OpenRouterImageAdapter.payloadFromUrl(dataUrl)
        assertArrayEquals(bytes, (payload as ImagePayload.Bytes).bytes)
    }

    @Test
    fun httpUrlImageBecomesARemoteUrl() {
        val payload = OpenRouterImageAdapter.payloadFromUrl("https://cdn.example.com/x.webp")
        assertEquals("https://cdn.example.com/x.webp", (payload as ImagePayload.RemoteUrl).url)
    }

    @Test
    fun responseImageIsReadFromTheAssistantMessage() {
        val bytes = byteArrayOf(5, 4, 3)
        val dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
        val body = JSONObject().put(
            "choices", JSONArray().put(
                JSONObject().put(
                    "message", JSONObject()
                        .put("role", "assistant")
                        .put("images", JSONArray().put(
                            JSONObject().put("type", "image_url")
                                .put("image_url", JSONObject().put("url", dataUrl))
                        ))
                )
            )
        ).toString()
        val parsed = OpenRouterImageAdapter.parseResponse(body)
        assertArrayEquals(bytes, (parsed.payload as ImagePayload.Bytes).bytes)
    }

    @Test
    fun textOnlyReplyIsNoUsableImage() {
        val body = JSONObject().put(
            "choices", JSONArray().put(
                JSONObject().put(
                    "message", JSONObject()
                        .put("role", "assistant")
                        .put("content", "I cannot draw that.")
                )
            )
        ).toString()
        try {
            OpenRouterImageAdapter.parseResponse(body)
            fail("expected NO_USABLE_IMAGE")
        } catch (e: ImageGenerationException) {
            assertEquals(ImageErrorCause.NO_USABLE_IMAGE, e.errorCause)
        }
    }

    @Test
    fun httpErrorsClassifyIntoTheirCauses() {
        assertEquals(
            ImageErrorCause.AUTHENTICATION_FAILED,
            OpenRouterImageAdapter.classifyHttpError(401, "{\"error\":{\"message\":\"no key\"}}")
        )
        assertEquals(
            ImageErrorCause.PROMPT_REFUSED,
            OpenRouterImageAdapter.classifyHttpError(
                403, "{\"error\":{\"message\":\"your input was flagged by moderation\"}}"
            )
        )
        assertEquals(
            ImageErrorCause.TIMED_OUT,
            OpenRouterImageAdapter.classifyHttpError(408, "{\"error\":{\"message\":\"timed out\"}}")
        )
        assertEquals(
            ImageErrorCause.GENERATOR_MODEL_REJECTED,
            OpenRouterImageAdapter.classifyHttpError(
                400, "{\"error\":{\"message\":\"not a valid model ID\"}}"
            )
        )
        assertEquals(
            ImageErrorCause.PROVIDER_ERROR,
            OpenRouterImageAdapter.classifyHttpError(
                402, "{\"error\":{\"message\":\"insufficient credits\"}}"
            )
        )
    }
}
