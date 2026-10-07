package org.teslasoft.assistant.imagegen

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject

class ImageTransportTest {
    private fun request() = ImageGenerationRequest("draw a tree", ImageShape.AUTOMATIC, ImageQuality.AUTOMATIC, "e", "future/image")

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
        val request = GeminiImageAdapter.buildHttpRequest(request().copy(parameters = mapOf("resolution" to "new-size", "aspect_ratio" to "7:4")), endpoint)
        assertEquals("/v1beta/models/future%2Fimage:generateContent", request.url.encodedPath)
        assertEquals("secret", request.header("x-goog-api-key"))
        assertNull(request.header("Authorization"))
        val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
        val config = JSONObject(buffer.readUtf8()).getJSONObject("generationConfig")
        assertEquals("new-size", config.getJSONObject("imageConfig").getString("imageSize"))
        assertEquals("7:4", config.getJSONObject("imageConfig").getString("aspectRatio"))
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
