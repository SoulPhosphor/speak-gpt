package org.teslasoft.assistant.imagegen

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject

class ImageSettingSupportTest {
    private fun request(saved: Map<String, String> = emptyMap()) = ImageGenerationRequest(
        "draw a tree", ImageShape.AUTOMATIC, ImageQuality.AUTOMATIC, "endpoint", "future", parameters = saved)
    private fun metadata(vararg fields: ImageParameter) = ImageModelMetadata("future", fields.toList())
    private fun quality(vararg choices: String) = ImageParameter("quality", ImageParameterType.ENUM, choices.toList())
    private fun expectBlocked(block: () -> Unit) {
        try { block(); fail("an invalid request must stop before dispatch") }
        catch (error: ImageGenerationException) { assertEquals(ImageErrorCause.UNSUPPORTED_OPTION, error.errorCause) }
    }

    @Test fun supportedSettingsRemainSelectableAndRetainTheirSelectedValues() {
        val saved = mapOf("quality" to "precise")
        val model = metadata(quality("draft", "precise"))
        val row = ImageSettingSupport.rows(saved, model).single()
        assertEquals(ImageSettingAvailability.SUPPORTED, row.availability)
        assertEquals(ImageSettingControl.DROPDOWN, row.control)
        assertEquals(listOf("draft", "precise"), row.choices)
        assertEquals("precise", row.selected)
        assertEquals(saved, ImageRequestOptions.prepare(request(saved), model).parameters)
    }

    @Test fun removedSavedParametersStayVisibleAndAreOmittedWithoutChangingPreferences() {
        val saved = linkedMapOf("quality" to "high", "background" to "transparent", "resolution" to "old-tier", "seed" to "42")
        val original = saved.toMap()
        val model = metadata(ImageParameter("seed", ImageParameterType.INTEGER))
        val rows = ImageSettingSupport.rows(saved, model).associateBy { it.key }
        for (key in listOf("quality", "background", "resolution")) {
            assertEquals(ImageSettingAvailability.UNSUPPORTED_PARAMETER, rows.getValue(key).availability)
            assertEquals(ImageSettingControl.STATUS, rows.getValue(key).control)
            assertEquals(saved[key], rows.getValue(key).selected)
        }
        val effective = ImageRequestOptions.prepare(request(saved), model)
        assertEquals(mapOf("seed" to "42"), effective.parameters)
        val json = JSONObject(CatalogImageAdapter.buildRequestBodyJson(effective))
        assertFalse(json.has("quality")); assertFalse(json.has("background")); assertFalse(json.has("resolution"))
        assertEquals(42, json.getInt("seed")); assertEquals(1, json.getInt("n"))
        assertEquals(original, saved)
    }

    @Test fun obsoleteValueIsVisibleAndReplaceableAndNeverSubstituted() {
        val saved = mapOf("quality" to "high", "seed" to "42")
        val model = metadata(quality("draft", "ultra"), ImageParameter("seed", ImageParameterType.INTEGER))
        val row = ImageSettingSupport.rows(saved, model).first()
        assertEquals("high", row.selected)
        assertEquals(ImageSettingAvailability.UNSUPPORTED_VALUE, row.availability)
        assertEquals(ImageSettingControl.DROPDOWN, row.control)
        assertEquals(listOf("draft", "ultra"), row.choices)
        assertEquals(mapOf("seed" to "42"), ImageRequestOptions.prepare(request(saved), model).parameters)
        val changed = ImageDimensionSettings.change(saved, "quality", "ultra", model)
        assertEquals(ImageSettingAvailability.SUPPORTED, ImageSettingSupport.rows(changed, model).first().availability)
        assertEquals("ultra", ImageRequestOptions.prepare(request(changed), model).parameters["quality"])
        assertEquals("high", saved["quality"])
        val format = metadata(ImageParameter("output_format", ImageParameterType.ENUM, listOf("png", "svg")))
        val formatRow = ImageSettingSupport.rows(mapOf("output_format" to "svg"), format).single()
        assertEquals(ImageSettingAvailability.UNSUPPORTED_VALUE, formatRow.availability)
        assertEquals(listOf("png"), formatRow.choices)
        expectBlocked { ImageRequestOptions.prepare(request(mapOf("output_format" to "svg")), format) }
    }

    @Test fun authoritativeMetadataRestoresNormalControlsForPreservedSelections() {
        val saved = mapOf("quality" to "high")
        assertEquals(ImageSettingControl.STATUS, ImageSettingSupport.rows(saved, metadata()).single().control)
        val restored = ImageSettingSupport.rows(saved, metadata(quality("high", "draft"))).single()
        assertEquals(ImageSettingAvailability.SUPPORTED, restored.availability)
        assertEquals(ImageSettingControl.DROPDOWN, restored.control)
        assertEquals("high", restored.selected)
    }

    @Test fun discreteNumericMetadataUsesDropdownsAndNumericTransport() {
        val fields = imageJson("""{"quality":{"type":"integer","values":[1,3,7]},"strength":{"type":"number","enum":[0.125,0.875]},"steps":{"type":"enum","values":[11,19]}}""")!!
        val model = ImageModelMetadata("future", ImageMetadataParser.parameters(fields))
        val saved = mapOf("quality" to "3", "strength" to "0.875", "steps" to "19")
        val rows = ImageSettingSupport.rows(saved, model)
        assertTrue(rows.all { it.control == ImageSettingControl.DROPDOWN })
        assertEquals(ImageParameterType.INTEGER, rows.first { it.key == "quality" }.parameter!!.type)
        assertEquals(ImageParameterType.NUMBER, rows.first { it.key == "strength" }.parameter!!.type)
        assertEquals(listOf("1", "3", "7"), rows.first { it.key == "quality" }.choices)
        val body = JSONObject(CatalogImageAdapter.buildRequestBodyJson(ImageRequestOptions.prepare(request(saved), model)))
        assertTrue(body.get("quality") is Number)
        assertTrue(body.get("strength") is Number)
        assertEquals(0.875, body.getDouble("strength"), 0.0)
        assertFalse(model.parameters.first { it.key == "quality" }.accepts("4"))
    }

    @Test fun integralNumericChoicesAndNullDefaultsKeepTheirPublishedMeaning() {
        val fields = imageJson("""{"iterations":{"type":"integer","enum":[1.0,3.0],"default":null}}""")!!
        val model = ImageModelMetadata("future", ImageMetadataParser.parameters(fields))
        val row = ImageSettingSupport.rows(mapOf("iterations" to "3.0"), model).single()
        assertEquals(listOf("1.0", "3.0"), row.choices)
        assertEquals(ImageSettingControl.DROPDOWN, row.control)
        val effective = ImageRequestOptions.prepare(request(mapOf("iterations" to "3.0")), model)
        assertEquals("3.0", effective.parameters["iterations"])
        assertEquals(3, JSONObject(CatalogImageAdapter.buildRequestBodyJson(effective)).getInt("iterations"))
        assertFalse(row.parameter!!.accepts("3.5"))
    }

    @Test fun numericRangesAndArbitraryNumbersUseInputsWithPublishedConstraints() {
        val fields = imageJson("""{"seed":{"type":"range","min":-17,"max":23},"strength":{"type":"number","minimum":0.25,"maximum":0.75},"offset":{"type":"number"}}""")!!
        val model = ImageModelMetadata("future", ImageMetadataParser.parameters(fields))
        val rows = ImageSettingSupport.rows(emptyMap(), model)
        assertTrue(rows.all { it.control == ImageSettingControl.NUMBER })
        assertEquals(-17.0, rows.first { it.key == "seed" }.parameter!!.minimum!!, 0.0)
        val saved = mapOf("seed" to "-9", "strength" to "0.5", "offset" to "-1.25")
        assertEquals(saved, ImageRequestOptions.prepare(request(saved), model).parameters)
        assertEquals(ImageSettingAvailability.UNSUPPORTED_VALUE,
            ImageSettingSupport.rows(mapOf("strength" to "0.8"), model).first { it.key == "strength" }.availability)
        for (bad in listOf("NaN", "Infinity", "1e999", "1e-999"))
            assertFalse(model.parameters.first { it.key == "offset" }.accepts(bad))
    }

    @Test fun servingRoutesIntersectNumericAllowlistsAndRangesWithoutInventingChoices() {
        val model = ImageMetadataParser.endpoints("""{"id":"future","endpoints":[
            {"supported_parameters":{"quality":{"type":"number","values":[0.25,0.5,0.75]}}},
            {"supported_parameters":{"quality":{"type":"number","minimum":0.4,"maximum":0.6}}}
        ]}""", metadata())
        val row = ImageSettingSupport.rows(mapOf("quality" to "0.5"), model).single()
        assertEquals(listOf("0.5"), row.choices)
        assertEquals(ImageSettingControl.DROPDOWN, row.control)
        assertEquals(ImageSettingAvailability.SUPPORTED, row.availability)
        assertFalse(row.parameter!!.accepts("0.75"))
    }

    @Test fun actualBooleanSchemasUseBooleanControlsAndKeepAutomaticOmission() {
        val model = ImageMetadataParser.catalog("""{"data":[{"id":"future","supported_parameters":{"enhance":{"type":"boolean"}}}]}""",
            "fixture", booleanCapabilities = false).single()
        assertTrue(model.settingsVerified)
        val automatic = ImageSettingSupport.rows(emptyMap(), model).single()
        assertEquals(ImageSettingControl.BOOLEAN, automatic.control)
        assertEquals("true", automatic.nextBooleanSelection())
        val on = ImageSettingSupport.rows(mapOf("enhance" to "true"), model).single()
        assertEquals("false", on.nextBooleanSelection())
        assertNull(ImageSettingSupport.rows(mapOf("enhance" to "false"), model).single().nextBooleanSelection())
        val body = JSONObject(CatalogImageAdapter.buildRequestBodyJson(ImageRequestOptions.prepare(request(mapOf("enhance" to "false")), model)))
        assertFalse(body.getBoolean("enhance"))
        assertFalse(JSONObject(CatalogImageAdapter.buildRequestBodyJson(ImageRequestOptions.prepare(request(), model))).has("enhance"))
        // The dedicated Images API's boolean capability flag retains its protocol meaning.
        assertEquals(ImageParameterType.INTEGER, ImageMetadataParser.parameters(imageJson("""{"seed":{"type":"boolean"}}""")!!).single().type)
    }

    @Test fun explicitlyPublishedFreeTextUsesTextInputAndUnknownFieldsDoNot() {
        val model = ImageModelMetadata("future", ImageMetadataParser.parameters(imageJson("""{"style_note":{"type":"string"}}""")!!))
        val saved = mapOf("style_note" to "watercolor", "unknown" to "old-value")
        val rows = ImageSettingSupport.rows(saved, model).associateBy { it.key }
        assertEquals(ImageSettingControl.TEXT, rows.getValue("style_note").control)
        assertEquals(ImageSettingControl.STATUS, rows.getValue("unknown").control)
        assertEquals(mapOf("style_note" to "watercolor"), ImageRequestOptions.prepare(request(saved), model).parameters)
    }

    @Test fun incompleteOrUnavailableMetadataPreservesSelectionsAndDoesNotClaimUnsupportedParameters() {
        val saved = linkedMapOf("quality" to "high", "seed" to "-9")
        for (model in listOf(null, metadata().copy(settingsVerified = false))) {
            val rows = ImageSettingSupport.rows(saved, model)
            assertTrue(rows.all { it.availability == ImageSettingAvailability.UNVERIFIED })
            assertEquals(saved, rows.associate { it.key to it.selected })
            assertTrue(ImageRequestOptions.prepare(request(saved), model).parameters.isEmpty())
        }
        val partial = metadata(quality("high")).copy(settingsVerified = false)
        val rows = ImageSettingSupport.rows(saved, partial).associateBy { it.key }
        assertEquals(ImageSettingAvailability.SUPPORTED, rows.getValue("quality").availability)
        assertEquals(ImageSettingAvailability.UNVERIFIED, rows.getValue("seed").availability)
        assertEquals(mapOf("quality" to "high"), ImageRequestOptions.prepare(request(saved), partial).parameters)
        assertEquals(mapOf("quality" to "high", "seed" to "-9"), saved)
    }

    @Test fun legacySelectionsRemainVisibleWithoutARefreshMigration() {
        val original = request().copy(defaultShape = ImageShape.LANDSCAPE, defaultQuality = ImageQuality.HIGH)
        val saved = ImageRequestOptions.savedSelections(original, metadata())
        assertEquals(mapOf("shape" to "landscape", "quality" to "high"), saved)
        assertTrue(ImageSettingSupport.rows(saved, metadata()).all { it.availability == ImageSettingAvailability.UNSUPPORTED_PARAMETER })
        assertTrue(ImageRequestOptions.prepare(original, metadata()).parameters.isEmpty())
        val model = metadata(ImageParameter("size", ImageParameterType.ENUM, listOf("1600x800")), quality("high"))
        assertEquals(mapOf("size" to "1600x800", "quality" to "high"), ImageRequestOptions.savedSelections(original, model))
        assertEquals(ImageShape.LANDSCAPE, original.defaultShape)
        assertEquals(ImageQuality.HIGH, original.defaultQuality)
    }

    @Test fun explicitRequestOverridesRemainStrictAndKeepTheirPrecedence() {
        expectBlocked { ImageRequestOptions.prepare(request(mapOf("quality" to "draft")).copy(quality = ImageQuality.HIGH), metadata(quality("draft"))) }
        val model = metadata(quality("high", "low"), ImageParameter("aspect_ratio", ImageParameterType.ENUM, listOf("2:1")),
            ImageParameter("resolution", ImageParameterType.ENUM, listOf("new-tier")))
        val effective = ImageRequestOptions.prepare(request(mapOf("quality" to "low", "size" to "old-size", "resolution" to "obsolete"))
            .copy(shape = ImageShape.LANDSCAPE, quality = ImageQuality.HIGH), model)
        assertEquals(mapOf("quality" to "high", "aspect_ratio" to "2:1"), effective.parameters)
        expectBlocked { ImageRequestOptions.prepare(request().copy(quality = ImageQuality.HIGH), model, listOf(ImageOptionRejection("quality"))) }
    }

    @Test fun requiredSettingsWithoutAUsableDefaultStopBeforeDispatch() {
        val field = ImageParameter("strength", ImageParameterType.NUMBER, minimum = 0.25, maximum = 0.75, required = true)
        val model = metadata(field)
        assertFalse(ImageSettingSupport.rows(emptyMap(), model).single().allowsAutomatic)
        assertTrue(ImageSettingSupport.rows(emptyMap(), metadata(field.copy(defaultValue = "0.5"))).single().allowsAutomatic)
        expectBlocked { ImageRequestOptions.prepare(request(), model) }
        expectBlocked { ImageRequestOptions.prepare(request(mapOf("strength" to "9")), model) }
        assertTrue(ImageRequestOptions.prepare(request(), metadata(field.copy(defaultValue = "0.5"))).parameters.isEmpty())
    }

    @Test fun conflictingDimensionsTransparencyAndPublishedDependenciesNeverDispatch() {
        MockWebServer().use { server ->
            val model = metadata(ImageParameter("size", ImageParameterType.ENUM, listOf("1200x800")),
                ImageParameter("resolution", ImageParameterType.ENUM, listOf("tier")),
                ImageParameter("background", ImageParameterType.ENUM, listOf("transparent", "opaque")),
                ImageParameter("output_format", ImageParameterType.ENUM, listOf("png", "jpeg"), defaultValue = "png"),
                ImageParameter("output_compression", ImageParameterType.INTEGER, minimum = 0.0, maximum = 90.0,
                    requiresValues = mapOf("output_format" to listOf("jpeg"))))
            val endpoint = ApiEndpointObject("test", server.url("/").toString(), "key")
            for (saved in listOf(mapOf("size" to "1200x800", "resolution" to "tier"),
                    mapOf("background" to "transparent", "output_format" to "jpeg"),
                    mapOf("output_compression" to "50", "output_format" to "png"))) {
                expectBlocked {
                    val effective = ImageRequestOptions.prepare(request(saved), model)
                    okhttp3.OkHttpClient().newCall(CatalogImageAdapter.buildHttpRequest(effective, endpoint)).execute().close()
                }
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun compressionDependenciesAreReadFromThePublishedReferenceRatherThanAFormatCatalog() {
        val reference = """Supported models include `future`.

- `background: string`
  - `"opaque"`
- `output_format: string`
  - `"image/png"`
  - `"image/jpeg"`
  - `"image/webp"`
- `output_compression: integer`
  Compression (13-87%). Only supported with the `image/jpeg` or `image/webp` output formats.
"""
        val model = OpenAiImageReferenceParser.enrich(metadata(), reference)
        val compression = model.parameters.first { it.key == "output_compression" }
        assertEquals(mapOf("output_format" to listOf("image/jpeg", "image/webp")), compression.requiresValues)
        expectBlocked { ImageRequestOptions.prepare(request(mapOf("output_format" to "image/png", "output_compression" to "50")), model) }
        val saved = mapOf("output_format" to "image/webp", "output_compression" to "50")
        assertEquals(saved, ImageRequestOptions.prepare(request(saved), model).parameters)
    }

    @Test fun onlyPreciseStructuredProviderRejectionsBecomeCompatibilityEvidence() {
        val submitted = request(mapOf("quality" to "high", "resolution" to "17K"))
        val explicit = """{"error":{"code":"unsupported_parameter","param":"quality"}}"""
        assertEquals(ImageOptionRejection("quality"), CatalogImageAdapter.confirmedIncompatibility(400, explicit, submitted))
        assertEquals(ImageOptionRejection("quality"), OpenAiImageAdapter.confirmedIncompatibility(422, explicit, submitted))
        for (status in listOf(401, 403, 429, 500)) assertNull(CatalogImageAdapter.confirmedIncompatibility(status, explicit, submitted))
        for (body in listOf("quality unsupported", """{"error":{"param":"quality","message":"invalid value"}}""",
                """{"error":{"code":"unsupported_parameter","param":"model"}}""",
                """{"error":{"code":"invalid_request_error","param":"quality"}}"""))
            assertNull(CatalogImageAdapter.confirmedIncompatibility(400, body, submitted))
        // Native field-path errors have no verified unsupported-setting translation.
        assertNull(GeminiImageAdapter.confirmedIncompatibility(400, explicit, submitted))
    }

    @Test fun cachedRejectionsAreScopedToEndpointIdentityHostCredentialsAndExactModel() {
        val endpoint = ApiEndpointObject("first", "https://first.example/v1/", "key", id = "settings-evidence-scope")
        val rejection = ImageOptionRejection("quality")
        ImageCatalogClient.rememberIncompatibility(endpoint, "future", rejection)
        assertEquals(listOf(rejection), ImageCatalogClient.confirmedIncompatibilities(endpoint, "future"))
        assertTrue(ImageCatalogClient.confirmedIncompatibilities(endpoint, "other-model").isEmpty())
        for (other in listOf(ApiEndpointObject("same host", endpoint.host, "key", id = "different-id"),
                ApiEndpointObject("different host", "https://second.example/v1/", "key", id = endpoint.id),
                ApiEndpointObject("different account", endpoint.host, "other-key", id = endpoint.id)))
            assertTrue(ImageCatalogClient.confirmedIncompatibilities(other, "future").isEmpty())
        val saved = mapOf("quality" to "high", "seed" to "42")
        val model = metadata(quality("high"), ImageParameter("seed", ImageParameterType.INTEGER))
        assertEquals(mapOf("seed" to "42"), ImageRequestOptions.prepare(request(saved), model,
            ImageCatalogClient.confirmedIncompatibilities(endpoint, "future")).parameters)
        assertEquals(ImageSettingControl.STATUS, ImageSettingSupport.rows(saved, model, listOf(rejection)).first().control)
    }

    @Test fun unsupportedValuesAreRememberedOnlyForTheRejectedCombination() {
        val saved = mapOf("quality" to "high", "resolution" to "17K")
        val submitted = request(saved)
        val rejection = CatalogImageAdapter.confirmedIncompatibility(400,
            """{"error":{"code":"unsupported_value","param":"quality"}}""", submitted)!!
        assertEquals(saved, rejection.selection)
        val model = metadata(quality("high"), ImageParameter("resolution", ImageParameterType.ENUM, listOf("17K", "3K")))
        expectBlocked { ImageRequestOptions.prepare(submitted, model, listOf(rejection)) }
        val row = ImageSettingSupport.rows(saved, model, listOf(rejection)).first()
        assertEquals(ImageSettingAvailability.UNSUPPORTED_VALUE, row.availability)
        assertEquals(ImageSettingControl.DROPDOWN, row.control)
        assertTrue(row.choices.isEmpty())
        assertEquals(ImageSettingAvailability.UNSUPPORTED_VALUE, ImageSettingSupport.rows(saved + ("obsolete_field" to "old"),
            model, listOf(rejection)).first().availability)
        val changed = saved + ("resolution" to "3K")
        assertEquals(changed, ImageRequestOptions.prepare(request(changed), model, listOf(rejection)).parameters)
        assertEquals(ImageSettingAvailability.SUPPORTED, ImageSettingSupport.rows(changed, model, listOf(rejection)).first().availability)
    }

    @Test fun unchangedAuthoritativeMetadataDoesNotRepeatAConfirmedRejectedRequest() {
        val endpoint = ApiEndpointObject("test", "https://same-metadata.example/v1/", "key", id = "same-settings-evidence")
        val model = metadata(quality("high"))
        val rejection = ImageOptionRejection("quality")
        ImageCatalogClient.rememberIncompatibility(endpoint, "future", rejection, model)
        ImageCatalogClient.refreshedSettings(endpoint, model)
        assertEquals(listOf(rejection), ImageCatalogClient.confirmedIncompatibilities(endpoint, "future"))
        ImageCatalogClient.refreshedSettings(endpoint, metadata(quality("high", "precise")))
        assertTrue(ImageCatalogClient.confirmedIncompatibilities(endpoint, "future").isEmpty())
    }

    @Test fun failedRefreshPreservesEvidenceAndPreferencesAndFreshMetadataSupersedesIt() {
        MockWebServer().use { server ->
            val endpoint = ApiEndpointObject("test", server.url("/v1/").toString(), "key", id = "settings-refresh-evidence")
            val saved = mapOf("quality" to "high")
            val rejection = ImageOptionRejection("quality")
            ImageCatalogClient.rememberIncompatibility(endpoint, "future", rejection)
            server.enqueue(MockResponse().setResponseCode(503))
            assertTrue(runCatching { ImageCatalogClient.model(endpoint, "future", fresh = true) }.isFailure)
            assertEquals(listOf(rejection), ImageCatalogClient.confirmedIncompatibilities(endpoint, "future"))
            ImageCatalogClient.refreshedSettings(endpoint, metadata(quality("high")).copy(settingsVerified = false))
            assertEquals(listOf(rejection), ImageCatalogClient.confirmedIncompatibilities(endpoint, "future"))
            ImageCatalogClient.refreshedSettings(endpoint, metadata(quality("high")).copy(id = "other-model"))
            assertEquals(listOf(rejection), ImageCatalogClient.confirmedIncompatibilities(endpoint, "future"))
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"future","supported_parameters":{"quality":{"type":"enum","values":["high"]}}}]}"""))
            val refreshed = ImageCatalogClient.model(endpoint, "future", fresh = true)!!
            assertTrue(ImageCatalogClient.confirmedIncompatibilities(endpoint, "future").isEmpty())
            assertEquals(ImageSettingAvailability.SUPPORTED, ImageSettingSupport.rows(saved, refreshed).single().availability)
            assertEquals(saved, ImageRequestOptions.prepare(request(saved), refreshed).parameters)
            assertEquals(mapOf("quality" to "high"), saved)
        }
    }
}
