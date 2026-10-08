package org.teslasoft.assistant.imagegen

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import java.io.File

class ImageCompatibilityPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val endpoint = ApiEndpointObject("test", "https://example.test/v1/", "private-key", id = "endpoint")
    private fun model() = ImageModelMetadata("future", listOf(
        ImageParameter("quality", ImageParameterType.ENUM, listOf("draft", "high"), defaultValue = "high"),
        ImageParameter("resolution", ImageParameterType.ENUM, listOf("small", "large"))))
    private fun cache(directory: File) = ImageCompatibilityEvidence(
        { scope -> File(directory, scope).takeIf { it.exists() }?.readText() },
        { scope, json -> File(directory, scope).writeText(json); true })
    private fun request(options: Map<String, String>) = ImageGenerationRequest(
        "private prompt", ImageShape.AUTOMATIC, ImageQuality.AUTOMATIC, endpoint.id, "future", parameters = options)
    private fun blocked(block: () -> Unit) {
        try { block(); fail("must not dispatch a rejected request") }
        catch (error: ImageGenerationException) { assertEquals(ImageErrorCause.UNSUPPORTED_OPTION, error.errorCause) }
    }

    @Test fun aNewCacheRecoversParameterEvidenceAndPreservesSelections() {
        val directory = temporary.newFolder()
        val saved = mapOf("quality" to "high", "resolution" to "large")
        assertTrue(cache(directory).remember(endpoint, "future", ImageOptionRejection("quality"), model()))
        val restarted = cache(directory)
        val evidence = restarted.confirmed(endpoint, "future")
        assertEquals(listOf(ImageOptionRejection("quality")), evidence)
        assertEquals("high", ImageSettingSupport.rows(saved, model(), evidence).first().selected)
        assertEquals(mapOf("resolution" to "large"), ImageRequestOptions.prepare(request(saved), model(), evidence).parameters)
        blocked { ImageRequestOptions.prepare(request(saved).copy(quality = ImageQuality.HIGH), model(), evidence) }
        assertEquals(mapOf("quality" to "high", "resolution" to "large"), saved)
        assertFalse(directory.listFiles()!!.single().readText().contains("private-key"))
        assertFalse(directory.listFiles()!!.single().readText().contains("private prompt"))
        assertTrue(directory.listFiles()!!.single().name.matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun aRestartRecoversOnlyTheExactRejectedCombinationIncludingDefaults() {
        val directory = temporary.newFolder()
        val saved = mapOf("quality" to "high", "resolution" to "large")
        val precise = ImageOptionRejection.fromError(400,
            """{"error":{"code":"unsupported_value","param":"quality"}}""", request(saved))!!
        cache(directory).remember(endpoint, "future", precise, model())
        val evidence = cache(directory).confirmed(endpoint, "future")
        blocked { ImageRequestOptions.prepare(request(saved), model(), evidence) }
        blocked { ImageRequestOptions.prepare(request(mapOf("resolution" to "large")), model(), evidence) }
        val changed = saved + ("resolution" to "small")
        assertEquals(changed, ImageRequestOptions.prepare(request(changed), model(), evidence).parameters)
        assertEquals(listOf("draft"), ImageSettingSupport.rows(saved, model(), evidence).first().choices)
    }

    @Test fun endpointPathProfileCredentialsAuthenticationAndModelRemainIsolatedAfterRestart() {
        val directory = temporary.newFolder()
        cache(directory).remember(endpoint, "future", ImageOptionRejection("quality"), model())
        val restarted = cache(directory)
        for (other in listOf(
            ApiEndpointObject("test", "https://different.test/v1/", endpoint.apiKey, id = endpoint.id),
            ApiEndpointObject("test", "https://example.test/v2/", endpoint.apiKey, id = endpoint.id),
            ApiEndpointObject("test", endpoint.host, endpoint.apiKey, id = "different"),
            ApiEndpointObject("test", endpoint.host, "different-key", id = endpoint.id),
            ApiEndpointObject("test", endpoint.host, endpoint.apiKey, authType = "api-key", id = endpoint.id)))
            assertTrue(restarted.confirmed(other, "future").isEmpty())
        assertTrue(restarted.confirmed(endpoint, "another-model").isEmpty())
        val renamed = ApiEndpointObject("renamed", endpoint.host, endpoint.apiKey, id = endpoint.id)
        assertEquals(restarted.confirmed(endpoint, "future"), restarted.confirmed(renamed, "future"))
    }

    @Test fun unchangedOrIncompleteMetadataRetainsEvidenceAcrossRepeatedRestarts() {
        val directory = temporary.newFolder()
        cache(directory).remember(endpoint, "future", ImageOptionRejection("quality"), model())
        cache(directory).refreshed(endpoint, model().copy(settingsVerified = false))
        val reordered = model().copy(parameters = model().parameters.reversed().map { it.copy(values = it.values.reversed()) })
        cache(directory).refreshed(endpoint, reordered)
        assertEquals(listOf(ImageOptionRejection("quality")), cache(directory).confirmed(endpoint, "future"))
        // Pricing or document location changes do not establish new setting support.
        cache(directory).refreshed(endpoint, model().copy(sourceUrl = "new-doc-location", tariffsComplete = false))
        assertEquals(listOf(ImageOptionRejection("quality")), cache(directory).confirmed(endpoint, "future"))
    }

    @Test fun updatedAuthoritativeSettingsDurablySupersedeOldEvidenceWithoutChangingOtherModels() {
        val directory = temporary.newFolder()
        cache(directory).remember(endpoint, "future", ImageOptionRejection("quality"), model())
        cache(directory).remember(endpoint, "other", ImageOptionRejection("quality"), model().copy(id = "other"))
        val updated = model().copy(parameters = model().parameters.map { if (it.key == "quality") it.copy(defaultValue = "draft") else it })
        assertTrue(cache(directory).refreshed(endpoint, updated))
        assertTrue(cache(directory).confirmed(endpoint, "future").isEmpty())
        assertEquals(listOf(ImageOptionRejection("quality")), cache(directory).confirmed(endpoint, "other"))
        val saved = mapOf("quality" to "high")
        assertEquals(saved, ImageRequestOptions.prepare(request(saved), updated,
            cache(directory).confirmed(endpoint, "future")).parameters)
    }

    @Test fun newAuthoritativeEvidenceSupersedesRejectionsLearnedWithoutMetadata() {
        val directory = temporary.newFolder()
        cache(directory).remember(endpoint, "future", ImageOptionRejection("quality"))
        cache(directory).refreshed(endpoint, model().copy(settingsVerified = false))
        assertFalse(cache(directory).confirmed(endpoint, "future").isEmpty())
        cache(directory).refreshed(endpoint, model())
        assertTrue(cache(directory).confirmed(endpoint, "future").isEmpty())
    }

    @Test fun ambiguousAndNativeFieldPathErrorsCreateNoDurableEvidence() {
        val directory = temporary.newFolder()
        val submitted = request(mapOf("quality" to "high"))
        for (body in listOf("quality is unsupported", """{"error":{"param":"quality","code":"invalid_request_error"}}""",
            """{"error":{"param":"generationConfig.quality","code":"unsupported_parameter"}}""")) {
            ImageOptionRejection.fromError(400, body, submitted)?.let { cache(directory).remember(endpoint, "future", it, model()) }
        }
        assertTrue(cache(directory).confirmed(endpoint, "future").isEmpty())
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun malformedOrUnknownVersionEvidenceNeverDisablesASetting() {
        val directory = temporary.newFolder()
        cache(directory).remember(endpoint, "future", ImageOptionRejection("quality"), model())
        val file = directory.listFiles()!!.single()
        for (bad in listOf("broken", """{"version":2,"rejections":[{"parameter":"quality"}]}""",
            """{"version":1,"rejections":[{"parameter":"quality","selection":{"quality":4}}]}""",
            """{"version":1,"rejections":[{"parameter":"quality","settings":"unverified"}]}""",
            """{"version":1,"rejections":[{"parameter":"quality","selection":{}}]}""")) {
            file.writeText(bad)
            assertTrue(cache(directory).confirmed(endpoint, "future").isEmpty())
        }
    }

    @Test fun failedWriteIsReportedAndRetriedWithoutLosingCurrentProcessEvidence() {
        val stored = mutableMapOf<String, String>()
        var fail = true
        val current = ImageCompatibilityEvidence(stored::get) { scope, json -> if (fail) false else { stored[scope] = json; true } }
        assertFalse(current.remember(endpoint, "future", ImageOptionRejection("quality"), model()))
        assertFalse(current.confirmed(endpoint, "future").isEmpty())
        assertTrue(ImageCompatibilityEvidence(stored::get).confirmed(endpoint, "future").isEmpty())
        fail = false
        assertTrue(current.refreshed(endpoint, model()))
        assertFalse(ImageCompatibilityEvidence(stored::get).confirmed(endpoint, "future").isEmpty())
    }

    @Test fun failedInvalidationIsReportedAndRetriedSoOldEvidenceDoesNotReturn() {
        val stored = mutableMapOf<String, String>()
        var fail = false
        val current = ImageCompatibilityEvidence(stored::get) { scope, json -> if (fail) false else { stored[scope] = json; true } }
        current.remember(endpoint, "future", ImageOptionRejection("quality"), model())
        val updated = model().copy(parameters = model().parameters.drop(1))
        fail = true
        assertFalse(current.refreshed(endpoint, updated))
        assertTrue(current.confirmed(endpoint, "future").isEmpty())
        fail = false
        assertTrue(current.refreshed(endpoint, updated))
        assertTrue(ImageCompatibilityEvidence(stored::get).confirmed(endpoint, "future").isEmpty())
    }
}
