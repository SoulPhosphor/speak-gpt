package org.teslasoft.assistant.imagegen

import org.junit.Assert.*
import org.junit.Test

class ImageMetadataTest {
    @Test fun receiptRetryDelaysRespectSecondsAndDatesWithoutCapping() {
        assertEquals(240_000L, ImageBillingRetry.delayMs("240"))
        assertEquals(30_000L, ImageBillingRetry.delayMs("Wed, 21 Oct 2015 07:28:00 GMT", 1445412450000L))
        assertNull(ImageBillingRetry.delayMs("invalid"))
    }

    @Test fun exactModelCatalogAndDefinitiveEndpointDescriptorsDetermineChoices() {
        val models = ImageMetadataParser.catalog("""{"data":[{"id":"future-model","supported_parameters":{"resolution":{"type":"enum","values":["small","huge"]},"n":{"type":"range","min":1,"max":9}}}]}""", "catalog")
        val resolved = ImageMetadataParser.endpoints("""{"id":"future-model","endpoints":[{"provider_name":"A","supported_parameters":{"resolution":{"type":"enum","values":["small","huge"]},"seed":{"type":"range","min":7,"max":15}},"pricing":[{"billable":"output_image","unit":"image","cost_usd":0.2}]},{"provider_name":"B","supported_parameters":{"resolution":{"type":"enum","values":["huge"]},"seed":{"type":"range","min":10,"max":20}},"pricing":[{"billable":"output_image","unit":"image","cost_usd":0.3}]}]}""", models.single())
        assertEquals(listOf("huge"), resolved.parameters.single { it.key == "resolution" }.values)
        val seed = resolved.parameters.single { it.key == "seed" }
        assertTrue(seed.accepts("12"))
        assertFalse(seed.accepts("8"))
        assertFalse(seed.accepts("16"))
        assertFalse(seed.accepts("12.5"))
        assertFalse(resolved.parameters.any { it.key == "n" })
        assertEquals(2, resolved.endpointRecords.size)
        val mismatch = ImageMetadataParser.endpoints("""{"id":"different","endpoints":[]}""", models.single())
        assertTrue(mismatch.parameters.isEmpty())
        assertFalse(mismatch.tariffsComplete)
    }

    @Test fun compatibleCatalogKeepsExplicitTariffsAndExcludesDeclaredEditOnlyModels() {
        val catalog = ImageMetadataParser.catalog("""{"data":[{"id":"new-generator","pricing":[{"billable":"output_image","unit":"image","cost_usd":0.07}]},{"id":"edit-only","capabilities":{"image_generation":false}}]}""", "fetched-source")
        assertEquals(1, catalog.size)
        assertTrue(catalog.single().tariffsComplete)
        assertEquals(0.07, catalog.single().tariffs.single().amount, 0.0)
    }

    @Test fun malformedPriceLineOrQuantityDoesNotBecomeCompletePricing() {
        val pricing = imageJson("""{"pricing":[{"billable":"output_image","unit":"image","cost_usd":0.2},{"billable":"input_image","unit":"image","cost_usd":0.1,"quantity":0}]}""")!!.get("pricing")
        assertEquals(1, ImageMetadataParser.tariffs(pricing).size)
        assertFalse(ImageMetadataParser.tariffsComplete(pricing))
        assertFalse(ImageMetadataParser.tariffsComplete(null))
    }

    @Test fun shapeShorthandUsesPublishedDimensionsAndSavedSettingsAreValidated() {
        val model = ImageModelMetadata("unknown", parameters = listOf(
            ImageParameter("size", ImageParameterType.ENUM, listOf("1888x944", "944x1888")),
            ImageParameter("quality", ImageParameterType.ENUM, listOf("medium", "precise"))))
        val request = ImageGenerationRequest("p", ImageShape.LANDSCAPE, ImageQuality.MEDIUM, "endpoint", "unknown")
        assertEquals(mapOf("size" to "1888x944", "quality" to "medium"), ImageRequestOptions.resolve(request, model))
        try {
            ImageRequestOptions.resolve(request.copy(parameters = mapOf("seed" to "10")), model)
            fail("unsupported saved setting must not be sent or silently discarded")
        } catch (failure: ImageGenerationException) { assertEquals(ImageErrorCause.UNSUPPORTED_OPTION, failure.errorCause) }
    }

    @Test fun shapeOverrideRemovesPixelSizeAndPreservesCompatibleResolutionTier() {
        val model = ImageModelMetadata("future", parameters = listOf(
            ImageParameter("aspect_ratio", ImageParameterType.ENUM, listOf("1:1", "7:4")),
            ImageParameter("size", ImageParameterType.ENUM, listOf("900x900")),
            ImageParameter("resolution", ImageParameterType.ENUM, listOf("9K"))))
        val saved = mapOf("size" to "900x900", "aspect_ratio" to "1:1", "resolution" to "9K")
        val request = ImageGenerationRequest("p", ImageShape.LANDSCAPE, ImageQuality.AUTOMATIC, "e", "future", parameters = saved)
        assertEquals(mapOf("aspect_ratio" to "7:4", "resolution" to "9K"), ImageRequestOptions.resolve(request, model))
        assertEquals("900x900", saved["size"])
    }

    @Test fun pixelShapeOverrideClearsOtherDimensionControlsWhenRatioHasNoPublishedMatch() {
        val model = ImageModelMetadata("future", parameters = listOf(
            ImageParameter("aspect_ratio", ImageParameterType.ENUM, listOf("auto")),
            ImageParameter("size", ImageParameterType.ENUM, listOf("900x900", "1200x900")),
            ImageParameter("resolution", ImageParameterType.ENUM, listOf("9K")),
            ImageParameter("seed", ImageParameterType.INTEGER)))
        val request = ImageGenerationRequest("p", ImageShape.LANDSCAPE, ImageQuality.AUTOMATIC, "e", "future",
            parameters = mapOf("aspect_ratio" to "auto", "resolution" to "9K", "seed" to "42"))
        assertEquals(mapOf("size" to "1200x900", "seed" to "42"), ImageRequestOptions.resolve(request, model))
    }

    @Test fun aspectOverridePreservesSizeShorthandWhichIsAlsoAPublishedResolutionTier() {
        val model = ImageModelMetadata("future", parameters = listOf(
            ImageParameter("aspect_ratio", ImageParameterType.ENUM, listOf("7:4")),
            ImageParameter("size", ImageParameterType.ENUM, listOf("9K", "900x900")),
            ImageParameter("resolution", ImageParameterType.ENUM, listOf("9K"))))
        val request = ImageGenerationRequest("p", ImageShape.LANDSCAPE, ImageQuality.AUTOMATIC, "e", "future",
            parameters = mapOf("size" to "9K"))
        assertEquals(mapOf("resolution" to "9K", "aspect_ratio" to "7:4"), ImageRequestOptions.resolve(request, model))
    }

    @Test fun explicitSavedDimensionsTakePrecedenceOverHistoricalShapeDefaults() {
        val model = ImageModelMetadata("future", parameters = listOf(
            ImageParameter("aspect_ratio", ImageParameterType.ENUM, listOf("1:1")),
            ImageParameter("size", ImageParameterType.ENUM, listOf("1200x900"))))
        val request = ImageGenerationRequest("p", ImageShape.AUTOMATIC, ImageQuality.AUTOMATIC, "e", "future",
            parameters = mapOf("size" to "1200x900"), defaultShape = ImageShape.SQUARE)
        assertEquals(mapOf("size" to "1200x900"), ImageRequestOptions.resolve(request, model))
    }

    @Test fun svgOnlyOutputsAreNotSelectableAndAreRejectedBeforeDispatch() {
        val format = ImageParameter("output_format", ImageParameterType.ENUM, listOf("svg"))
        val model = ImageModelMetadata("future", parameters = listOf(format))
        assertTrue(format.selectableValues().isEmpty())
        assertFalse(model.hasDisplayableOutput())
        for (settings in listOf(emptyMap(), mapOf("output_format" to "svg"))) {
            val request = ImageGenerationRequest("p", ImageShape.AUTOMATIC, ImageQuality.AUTOMATIC, "e", "future", parameters = settings)
            try { ImageRequestOptions.resolve(request, model); fail("unusable output must not be dispatched") }
            catch (failure: ImageGenerationException) { assertEquals(ImageErrorCause.UNSUPPORTED_OPTION, failure.errorCause) }
        }
    }

    @Test fun mixedOutputsUseOnlyDecodableChoicesAndRequireASafeKnownDefaultOrExplicitFormat() {
        val format = ImageParameter("output_format", ImageParameterType.ENUM, listOf("svg", "png"))
        val model = ImageModelMetadata("future", parameters = listOf(format))
        val request = ImageGenerationRequest("p", ImageShape.AUTOMATIC, ImageQuality.AUTOMATIC, "e", "future")
        assertEquals(listOf("png"), format.selectableValues())
        assertTrue(model.hasDisplayableOutput())
        assertEquals(mapOf("output_format" to "png"), ImageRequestOptions.resolve(request.copy(parameters = mapOf("output_format" to "png")), model))
        assertTrue(ImageRequestOptions.resolve(request, model.copy(parameters = listOf(format.copy(defaultValue = "png")))).isEmpty())
        for (metadata in listOf(model, model.copy(parameters = listOf(format.copy(defaultValue = "svg"))))) {
            try { ImageRequestOptions.resolve(request, metadata); fail("an unsafe or unknown default must not be dispatched") }
            catch (failure: ImageGenerationException) { assertEquals(ImageErrorCause.UNSUPPORTED_OPTION, failure.errorCause) }
        }
        try { ImageRequestOptions.resolve(request.copy(parameters = mapOf("output_format" to "svg")), model); fail("SVG is not an app-decodable format") }
        catch (failure: ImageGenerationException) { assertEquals(ImageErrorCause.UNSUPPORTED_OPTION, failure.errorCause) }
    }

    @Test fun googleModelLinksResolveIdsWithoutCodeTagsAndRejectOtherOrigins() {
        val guide = """<h2>Model selection</h2><ul><li><a href="/gemini-api/docs/models/future-native">Gemini Future Image</a></li><li><a href="https://ai.google.dev/gemini-api/docs/models/future-alias">Alias</a></li><li><a href="https://elsewhere.example/gemini-api/docs/models/not-authoritative">Other</a></li></ul><h2>Options</h2><h3>Future Image</h3><table><tr><th>Aspect ratio</th><th>9K resolution</th></tr><tr><td>7:4</td><td>7x4</td></tr></table>"""
        val schema = """{"schemas":{"ImageConfig":{"properties":{"aspectRatio":{"enum":["7:4"]},"imageSize":{"enum":["9K"]}}}}}"""
        val models = GeminiImageMetadataParser.models(guide, schema)
        assertEquals(listOf("future-native", "future-alias"), models.map { it.id })
        assertEquals(listOf("9K"), models.first().parameters.single { it.key == "resolution" }.values)
    }

    @Test fun googleTablesAndSchemaSupplyValuesForNewIdsAndPricesUseOnlyStandardPaidTokens() {
        val guide = """<h2>Model selection</h2><ul><li><a>Gemini Future Image</a> (<code>future-id</code>)</li></ul><h2>Options</h2><h3>Future Image</h3><table><tr><th>Aspect ratio</th><th>9K resolution</th></tr><tr><td>7:4</td><td>7x4</td></tr></table>"""
        val schema = """{"schemas":{"ImageConfig":{"properties":{"aspectRatio":{"enum":["7:4","1:1"]},"imageSize":{"enum":["9K","1K"]}}}}}"""
        val model = GeminiImageMetadataParser.models(guide, schema).single()
        assertEquals("future-id", model.id)
        assertEquals(listOf("9K"), model.parameters.single { it.key == "resolution" }.values)
        assertEquals(listOf("7:4"), model.parameters.single { it.key == "aspect_ratio" }.values)
        val pricing = """<h2 id="future-id">Future</h2><h3>Standard</h3><table><tr><th></th><th>Free Tier</th><th>Paid Tier, per 2M tokens in USD</th></tr><tr><td>Input price</td><td>Not available</td><td>\$4.12 (text/image)</td></tr><tr><td>Output price</td><td>Not available</td><td>\$13 (text and thinking) \$71 (images) Equivalent to \$9 per image</td></tr></table><h3>Batch</h3>""".replace("\\$", "$")
        val priced = GeminiImagePricingParser.enrich(model, pricing)
        assertTrue(priced.tariffsComplete)
        assertEquals(71.0, priced.tariffs.single { it.billable == "image_output" }.amount, 0.0)
        assertEquals(2_000_000.0, priced.tariffs.first().quantity, 0.0)
        val withTiers = GeminiImagePricingParser.enrich(model.copy(parameters = emptyList(), nativeSizes = listOf("8K", "9K", "1K")),
            pricing.replace("per image", "per 8K/9K image"))
        assertEquals(listOf("8K", "9K"), withTiers.parameters.single { it.key == "resolution" }.values)
        assertFalse(GeminiImagePricingParser.enrich(model, pricing.replace("Not available", "Free of charge")).tariffsComplete)
        assertFalse(GeminiImagePricingParser.enrich(model.copy(id = "other", resolvedIds = setOf("other")), pricing).tariffsComplete)
    }
}
