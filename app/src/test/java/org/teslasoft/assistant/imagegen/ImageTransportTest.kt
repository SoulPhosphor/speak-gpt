package org.teslasoft.assistant.imagegen

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject

class ImageTransportTest {
    private fun request() = ImageGenerationRequest("draw a tree", ImageShape.AUTOMATIC, ImageQuality.AUTOMATIC, "e", "future/image")

    @Test fun editedOpenRouterProfilesUseTheCurrentHostForImageDiscoveryAndGeneration() {
        MockWebServer().use { server ->
            val endpoint = ApiEndpointObject("edited", "https://openrouter.ai/api/v1/", "secret",
                identity = ApiEndpointObject.IDENTITY_OPENROUTER)
            assertEquals(ImageProviderKind.OPENROUTER, ImageProviderKind.forEndpoint(endpoint))
            endpoint.host = server.url("/custom/v1/").toString()
            assertTrue(endpoint.isOpenRouterRouting())
            assertEquals(ImageProviderKind.COMPATIBLE, ImageProviderKind.forEndpoint(endpoint))
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"future/image"}]}"""))
            assertEquals("future/image", ImageCatalogClient.models(endpoint, fresh = true).single().id)
            assertEquals("/custom/v1/models", server.takeRequest().path)
            assertEquals("/custom/v1/images/generations", OpenAiImageAdapter.buildHttpRequest(request(), endpoint).url.encodedPath)
            endpoint.host = "https://api.openai.com/v1/"
            assertEquals(ImageProviderKind.OPENAI, ImageProviderKind.forEndpoint(endpoint))
            endpoint.host = "https://generativelanguage.googleapis.com/v1beta/"
            assertEquals(ImageProviderKind.GEMINI, ImageProviderKind.forEndpoint(endpoint))
        }
    }

    @Test fun knownRootHostsResolveTheirImageBaseWithoutChangingCustomBases() {
        assertEquals("https://api.openai.com/v1/images/generations", OpenAiImageAdapter.imagesUrl(ApiEndpointObject("O", "https://api.openai.com", "k")))
        assertEquals("https://openrouter.ai/api/v1/", ImageApiRoutes.base(ApiEndpointObject("R", "https://openrouter.ai", "k")))
        assertEquals("https://proxy.example/custom/", ImageApiRoutes.base(ApiEndpointObject("C", "https://proxy.example/custom", "k")))
    }

    @Test fun catalogAdapterSendsPublishedTypesAndExactlyOneImage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"AQID"}],"usage":{"cost":0.001}}"""))
            val endpoint = ApiEndpointObject("test", server.url("/api/v1/").toString(), "secret", authType = ApiEndpointObject.AUTH_X_API_KEY)
            val parameters = request().copy(parameters = mapOf("resolution" to "17K", "seed" to "812", "output_compression" to "63"),
                parameterTypes = mapOf("seed" to ImageParameterType.INTEGER, "output_compression" to ImageParameterType.INTEGER))
            val response = okhttp3.OkHttpClient().newCall(CatalogImageAdapter.buildHttpRequest(parameters, endpoint)).execute()
            response.use { assertArrayEquals(byteArrayOf(1,2,3), (CatalogImageAdapter.parseResponse(it.body!!.string()).payload as ImagePayload.Bytes).bytes) }
            val actual = server.takeRequest()
            assertEquals("/api/v1/images", actual.path)
            assertEquals("secret", actual.getHeader("x-api-key"))
            assertNull(actual.getHeader("Authorization"))
            val body = JSONObject(actual.body.readUtf8())
            assertEquals(1, body.getInt("n"))
            assertEquals("17K", body.getString("resolution"))
            assertTrue(body.get("seed") is Number)
            assertEquals(812, body.getInt("seed"))
        }
    }

    @Test fun nativeGeminiUsesItsOwnRouteAuthAndConfigurationEvenForChatCompatibleProfiles() {
        val endpoint = ApiEndpointObject("Google", "https://generativelanguage.googleapis.com/v1beta/openai/", "secret")
        val request = GeminiImageAdapter.buildHttpRequest(request().copy(parameters = mapOf("resolution" to "new-size", "aspect_ratio" to "7:4"),
            geminiTransport = GeminiImageTransport.GENERATE_CONTENT), endpoint)
        assertEquals("/v1beta/models/future%2Fimage:generateContent", request.url.encodedPath)
        assertEquals("secret", request.header("x-goog-api-key"))
        assertNull(request.header("Authorization"))
        val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
        val config = JSONObject(buffer.readUtf8()).getJSONObject("generationConfig")
        assertEquals("new-size", config.getJSONObject("imageConfig").getString("imageSize"))
        assertEquals("7:4", config.getJSONObject("imageConfig").getString("aspectRatio"))
    }

    @Test fun interactionsUsesPublishedNativeRouteAndImageResponseFormat() {
        val endpoint = ApiEndpointObject("Google", "https://generativelanguage.googleapis.com/v1beta/openai/", "secret")
        val http = GeminiImageAdapter.buildHttpRequest(request().copy(geminiTransport = GeminiImageTransport.INTERACTIONS,
            parameters = mapOf("resolution" to "17K", "aspect_ratio" to "7:4", "output_format" to "image/jpeg")), endpoint)
        assertEquals("/v1beta/interactions", http.url.encodedPath)
        assertEquals("secret", http.header("x-goog-api-key"))
        assertNull(http.header("Authorization"))
        val buffer = okio.Buffer(); http.body!!.writeTo(buffer)
        val body = JSONObject(buffer.readUtf8())
        assertEquals("future/image", body.getString("model"))
        assertEquals("draw a tree", body.getString("input"))
        assertFalse(body.has("generationConfig"))
        assertFalse(body.getBoolean("store"))
        assertFalse(body.getBoolean("stream"))
        assertFalse(body.getBoolean("background"))
        val format = body.getJSONObject("response_format")
        assertEquals("image", format.getString("type"))
        assertEquals("17K", format.getString("image_size"))
        assertEquals("7:4", format.getString("aspect_ratio"))
        assertEquals("image/jpeg", format.getString("mime_type"))
    }

    @Test fun unknownGeminiTransportCannotDispatchAnAssumedLegacyRequest() {
        try { GeminiImageAdapter.buildHttpRequest(request(), ApiEndpointObject("Google", "https://generativelanguage.googleapis.com", "k")); fail("route must be verified") }
        catch (failure: ImageGenerationException) { assertEquals(ImageErrorCause.GENERATOR_MODEL_REJECTED, failure.errorCause) }
    }

    @Test fun interactionsReadsOnlyFinalModelImageAndSupportsUriDelivery() {
        val prefix = """{"status":"completed","steps":[{"type":"user_input","content":[{"type":"image","mime_type":"image/png","data":"AQ=="}]},{"type":"thought","content":[{"type":"image","mime_type":"image/png","data":"AQ=="}]},{"type":"model_output","content":["""
        val inline = prefix + """{"type":"image","mime_type":"image/png","data":"Ag=="}]}]}"""
        assertArrayEquals(byteArrayOf(2), (GeminiImageAdapter.parseResponse(inline).payload as ImagePayload.Bytes).bytes)
        assertEquals(1.0, ImageUsageParser.response(ImageProviderKind.GEMINI, inline, null).images!!, 0.0)
        val uri = prefix + """{"type":"image","mime_type":"image/png","uri":"https://media.example/result"}]}]}"""
        assertEquals("https://media.example/result", (GeminiImageAdapter.parseResponse(uri).payload as ImagePayload.RemoteUrl).url)
    }

    @Test fun geminiReadsFinalImageAndSkipsUnbilledThinkingImage() {
        val parsed = GeminiImageAdapter.parseResponse("""{"candidates":[{"content":{"parts":[{"thought":true,"inlineData":{"mimeType":"image/png","data":"AQ=="}},{"inlineData":{"mimeType":"image/png","data":"Ag=="}}]}}]}""")
        assertArrayEquals(byteArrayOf(2), (parsed.payload as ImagePayload.Bytes).bytes)
    }

    @Test fun metadataFetchNeverForwardsKeyToAnotherOrigin() {
        MockWebServer().use { allowed -> MockWebServer().use { other ->
            other.start()
            val endpoint = ApiEndpointObject("test", allowed.url("/").toString(), "secret")
            assertNull(ImageMetadataHttp().get(other.url("/models").toString(), endpoint))
            assertEquals(0, other.requestCount)
        } }
    }

    @Test fun malformedImagePayloadDoesNotEraseSeparateBillingEvidence() {
        val body = """{"data":[{"b64_json":"A"}],"usage":{"cost":0.17}}"""
        val receipt = ImageUsageParser.response(ImageProviderKind.OPENROUTER, body, "r")
        try { CatalogImageAdapter.parseResponse(body); fail("expected image error") }
        catch (_: ImageGenerationException) { assertEquals(0.17, receipt.usd!!, 0.0) }
    }
}
