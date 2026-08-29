package org.teslasoft.assistant.providers

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.models.EndpointCatalogCheck
import java.io.IOException

class ModelCatalogAvailabilityClientTest {
    private val model = "vendor/speech:exact"
    private val profile = ApiEndpointObject("Speech", "https://openrouter.ai/api/v1/", "test-key", id = "ep", identity = ApiEndpointObject.IDENTITY_OPENROUTER)
    private val requests = mutableListOf<Request>()
    private fun check(endpoint: ApiEndpointObject = profile, speech: Set<String> = setOf(model),
        reply: (Request) -> Pair<Int, String>): EndpointCatalogCheck = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val (status, body) = reply(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
                .message("fixture").body(body.toResponseBody()).build()
        }.build()
        ModelCatalogAvailabilityClient.checkWithClient(endpoint, setOf(model), speech, client)
    }

    @Test fun speechUsesAllModalitiesAndIgnoresMissingVoiceAndProviderMetadata() {
        val result = check { 200 to """{"data":[{"id":"$model","voices":[],"endpoints":[]}]}""" }
        assertTrue(model in (result as EndpointCatalogCheck.Checked).modelIds)
        assertEquals("all", requests.single().url.queryParameter("output_modalities"))
        assertEquals("GET", requests.single().method)
    }

    @Test fun textOnlyCheckKeepsItsExistingRequest() {
        check(speech = emptySet()) { 200 to """{"data":[{"id":"$model"}]}""" }
        assertNull(requests.single().url.queryParameter("output_modalities"))
    }

    @Test fun aliasResolutionKeepsExactSavedIdAndOnlyChecksItOnce() {
        val result = check { request ->
            if (request.url.encodedPath.endsWith("/models")) 200 to """{"data":[{"id":"vendor/new"}]}"""
            else 200 to """{"data":{"id":"vendor/new"}}"""
        } as EndpointCatalogCheck.Checked
        assertTrue(model in result.modelIds)
        assertTrue(result.indeterminateModelIds.isEmpty())
        assertEquals(2, requests.size)
        assertEquals(listOf("api", "v1", "model", "vendor", "speech:exact"), requests.last().url.pathSegments)
    }

    @Test fun canonicalSlugAlreadyInCatalogNeedsNoExactRequest() {
        check { 200 to """{"data":[{"id":"vendor/new","canonical_slug":"$model"}]}""" }
        assertEquals(1, requests.size)
    }

    @Test fun exactOpenRouter404IsAbsentButFailedLookupIsIndeterminate() {
        for (status in listOf(404, 401, 403, 429, 500)) {
            val result = check { request ->
                if (request.url.encodedPath.endsWith("/models")) 200 to """{"data":[{"id":"text/other"}]}"""
                else status to """{"error":{"message":"not available"}}"""
            } as EndpointCatalogCheck.Checked
            assertFalse(model in result.modelIds)
            assertEquals(status != 404, model in result.indeterminateModelIds)
        }
    }

    @Test fun failedEmptyMalformedAndPartialCatalogsCannotEstablishAbsence() {
        val catalog = "\"data\":[{\"id\":\"text/other\"}]"
        val bodies = listOf("", "bad", "[]", "{\"data\":[]}", "{$catalog,\"has_more\":true}",
            "{$catalog,\"next_cursor\":\"next\"}", "{$catalog,\"pagination\":{\"total\":2}}",
            "{$catalog,\"links\":{\"next\":\"/page/2\"}}", "{\"data\":[{\"id\":\"text/other\"},{}]}",
            "{\"data\":[{\"id\":123}]}")
        for (body in bodies) assertEquals(body, EndpointCatalogCheck.Unchecked, check { 200 to body })
        for (status in listOf(401, 403, 429, 500)) {
            assertEquals(EndpointCatalogCheck.Unchecked, check { status to "{$catalog}" })
        }
        assertEquals(EndpointCatalogCheck.Unchecked, check { throw IOException("timeout") })
    }

    @Test fun malformedExactResponseRemainsIndeterminate() {
        val result = check { request ->
            if (request.url.encodedPath.endsWith("/models")) 200 to """{"data":[{"id":"other"}]}"""
            else 200 to "{}"
        } as EndpointCatalogCheck.Checked
        assertEquals(setOf(model), result.indeterminateModelIds)
    }

    @Test fun genericChatOnlyCatalogAndUnsupportedExactLookupDoNotDeleteSpeech() {
        val generic = ApiEndpointObject("Speech", "https://speech.example/v1/", "test-key", id = "ep")
        val result = check(generic) { request ->
            if (request.url.encodedPath.endsWith("/models")) 200 to """{"data":[{"id":"chat"}]}"""
            else 404 to """{"error":{"message":"path not found"}}"""
        } as EndpointCatalogCheck.Checked
        assertEquals(setOf(model), result.indeterminateModelIds)
        assertEquals(model, requests.last().url.pathSegments.last())
        assertTrue(requests.all { it.url.host == "speech.example" })
    }

    @Test fun genericExactModelEvidenceDistinguishesPresentFromExplicitlyMissing() {
        val generic = ApiEndpointObject("Speech", "https://speech.example/v1/", "test-key", id = "ep")
        for (present in listOf(true, false)) {
            val result = check(generic) { request ->
                if (request.url.encodedPath.endsWith("/models")) 200 to """{"data":[{"id":"chat"}]}"""
                else if (present) 200 to """{"id":"$model"}"""
                else 404 to """{"error":{"code":"model_not_found"}}"""
            } as EndpointCatalogCheck.Checked
            assertEquals(present, model in result.modelIds)
            assertTrue(result.indeterminateModelIds.isEmpty())
        }
    }

    @Test fun officialOpenAiCatalogCanEstablishAbsenceWithoutGuessingVoiceNames() {
        val result = check(ApiEndpointObject("Speech", "https://api.openai.com/v1/", "test-key", id = "ep")) {
            200 to """{"data":[{"id":"other"}]}"""
        } as EndpointCatalogCheck.Checked
        assertFalse(model in result.modelIds)
        assertTrue(result.indeterminateModelIds.isEmpty())
        assertEquals(1, requests.size)
    }
}
