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

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import java.util.Base64

/**
 * The OpenAI-compatible adapter (image-generation-rebuild-plan.md §9):
 * one image per request, Automatic omitting the optional parameters
 * entirely (§11 — omission over guessing), both Base64 and URL response
 * data accepted (§4.5), and provider errors classified into §13 causes.
 */
class OpenAiImageAdapterTest {

    private fun request(
        shape: ImageShape = ImageShape.AUTOMATIC,
        quality: ImageQuality = ImageQuality.AUTOMATIC
    ) = ImageGenerationRequest(
        prompt = "a fox sleeping beneath glowing mushrooms",
        shape = shape,
        quality = quality,
        endpointId = "endpoint-1",
        modelId = "example/image-model"
    )

    private fun endpoint(
        host: String = "https://api.openai.com/v1/",
        authType: String = ApiEndpointObject.AUTH_BEARER
    ) = ApiEndpointObject("My Service", host, "test-key", authType = authType)

    @Test
    fun automaticShapeAndQualityAreOmittedFromTheRequest() {
        val body = JSONObject(OpenAiImageAdapter.buildRequestBodyJson(request()))
        assertEquals("example/image-model", body.getString("model"))
        assertEquals("a fox sleeping beneath glowing mushrooms", body.getString("prompt"))
        assertEquals(1, body.getInt("n"))
        assertFalse(body.has("size"))
        assertFalse(body.has("quality"))
    }

    @Test
    fun explicitShapeAndQualityAreSent() {
        val body = JSONObject(
            OpenAiImageAdapter.buildRequestBodyJson(
                request(shape = ImageShape.LANDSCAPE, quality = ImageQuality.HIGH)
            )
        )
        assertEquals("1536x1024", body.getString("size"))
        assertEquals("high", body.getString("quality"))
    }

    @Test
    fun everyNonAutomaticShapeMapsToASize() {
        assertEquals("1024x1024", OpenAiImageAdapter.sizeFor(ImageShape.SQUARE))
        assertEquals("1536x1024", OpenAiImageAdapter.sizeFor(ImageShape.LANDSCAPE))
        assertEquals("1024x1536", OpenAiImageAdapter.sizeFor(ImageShape.PORTRAIT))
        assertNull(OpenAiImageAdapter.sizeFor(ImageShape.AUTOMATIC))
    }

    @Test
    fun urlUsesTheGenerationsPathUnderTheEndpointBase() {
        assertEquals(
            "https://api.openai.com/v1/images/generations",
            OpenAiImageAdapter.imagesUrl(endpoint())
        )
        assertEquals(
            "https://example.com/v1/images/generations",
            OpenAiImageAdapter.imagesUrl(endpoint(host = "https://example.com/v1"))
        )
    }

    @Test
    fun authHeaderFollowsTheEndpointAuthMode() {
        val bearer = OpenAiImageAdapter.buildHttpRequest(request(), endpoint())
        assertEquals("Bearer test-key", bearer.header("Authorization"))

        val xApiKey = OpenAiImageAdapter.buildHttpRequest(
            request(), endpoint(authType = ApiEndpointObject.AUTH_X_API_KEY)
        )
        assertEquals("test-key", xApiKey.header("x-api-key"))
        assertNull(xApiKey.header("Authorization"))
    }

    @Test
    fun base64ResponseDataDecodesToBytes() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val body = JSONObject()
            .put("data", org.json.JSONArray().put(
                JSONObject().put("b64_json", Base64.getEncoder().encodeToString(bytes))
            ))
            .toString()
        val parsed = OpenAiImageAdapter.parseResponse(body)
        assertArrayEquals(bytes, (parsed.payload as ImagePayload.Bytes).bytes)
    }

    @Test
    fun urlResponseDataBecomesARemoteUrl() {
        val body = JSONObject()
            .put("data", org.json.JSONArray().put(
                JSONObject().put("url", "https://images.example.com/tmp/abc.png")
            ))
            .toString()
        val parsed = OpenAiImageAdapter.parseResponse(body)
        assertEquals(
            "https://images.example.com/tmp/abc.png",
            (parsed.payload as ImagePayload.RemoteUrl).url
        )
    }

    @Test
    fun emptyOrInvalidResponsesAreNoUsableImage() {
        for (body in listOf("{}", "not json", "{\"data\":[]}", "{\"data\":[{}]}")) {
            try {
                OpenAiImageAdapter.parseResponse(body)
                fail("expected NO_USABLE_IMAGE for: $body")
            } catch (e: ImageGenerationException) {
                assertEquals(ImageErrorCause.NO_USABLE_IMAGE, e.errorCause)
            }
        }
    }

    @Test
    fun httpErrorsClassifyIntoTheirCauses() {
        assertEquals(
            ImageErrorCause.AUTHENTICATION_FAILED,
            OpenAiImageAdapter.classifyHttpError(401, "{\"error\":{\"message\":\"bad key\"}}")
        )
        assertEquals(
            ImageErrorCause.PROMPT_REFUSED,
            OpenAiImageAdapter.classifyHttpError(
                400,
                "{\"error\":{\"code\":\"moderation_blocked\",\"message\":\"rejected\"}}"
            )
        )
        assertEquals(
            ImageErrorCause.GENERATOR_MODEL_REJECTED,
            OpenAiImageAdapter.classifyHttpError(
                404,
                "{\"error\":{\"code\":\"model_not_found\",\"message\":\"the model does not exist\"}}"
            )
        )
        assertEquals(
            ImageErrorCause.UNSUPPORTED_OPTION,
            OpenAiImageAdapter.classifyHttpError(
                400,
                "{\"error\":{\"param\":\"size\",\"message\":\"invalid value\"}}"
            )
        )
        assertEquals(
            ImageErrorCause.PROVIDER_ERROR,
            OpenAiImageAdapter.classifyHttpError(500, "internal error")
        )
    }

    @Test
    fun adapterDeclaresShapeAndQualitySupport() {
        assertTrue(OpenAiImageAdapter.capabilities.supportsShape)
        assertTrue(OpenAiImageAdapter.capabilities.supportsQuality)
    }
}
