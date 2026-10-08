package org.teslasoft.assistant.imagegen

import org.junit.Assert.*
import org.junit.Test

class ImageMetadataTest {
    @Test fun invalidPublishedTariffBasesNeverEnterAccounting() {
        val huge = "1" + "0".repeat(400)
        val scaledOverflow = "1" + "0".repeat(308)
        val underflow = "0." + "0".repeat(400) + "1"
        for (basis in listOf(huge, scaledOverflow + "M", "0", underflow)) {
            val pricing = """<h2 id="future">Future</h2><h3>Standard</h3><table><tr><th></th><th>Free Tier</th><th>Paid Tier, per $basis tokens in USD</th></tr><tr><td>Input price</td><td>Not available</td><td>${'$'}1 (text/image)</td></tr><tr><td>Output price</td><td>Not available</td><td>${'$'}2 (images)</td></tr></table>"""
            val gemini = GeminiImagePricingParser.enrich(ImageModelMetadata("future"), pricing)
            assertFalse(gemini.tariffsComplete)
            assertTrue(gemini.tariffs.isEmpty())
            val document = """# Future
Model ID: `future`
- Output modalities: image
| Endpoint | Support |
| `v1/images/generations` | Supported |
## Pricing
### Text tokens
| Input | ${'$'}1 | $basis tokens |
"""
            assertTrue(OpenAiImageMetadataParser.model(document, "future", "fixture")!!.tariffs.isEmpty())
        }
        assertEquals(2_000_000.0, imagePricingBasis("2", 1e6)!!, 0.0)
        assertEquals(0.5, imagePricingBasis("0.5", 1.0)!!, 0.0)
    }

    @Test fun unreadableAdvertisedDescriptorsUseDefaultsInsteadOfBlockingSavedSettings() {
        val request = ImageGenerationRequest("p", ImageShape.AUTOMATIC, ImageQuality.AUTOMATIC, "e", "future",
            parameters = mapOf("quality" to "precise", "output_format" to "png"))
        val format = """"output_format":{"type":"enum","values":["png"]}"""
        for (bad in listOf("[]", "null", "7", "{}", """{"type":"future"}""",
                """{"type":"enum","values":[]}""", """{"type":"enum","values":["precise",7]}""",
                """{"type":"number","minimum":"unreadable"}""", """{"type":"number","minimum":9,"maximum":1}""",
                """{"type":"string","default":{}}""")) {
            val fields = """{"quality":$bad,$format}"""
            val catalog = ImageMetadataParser.catalog("""{"data":[{"id":"future","supported_parameters":$fields}]}""", "fixture").single()
            val endpoint = ImageMetadataParser.endpoints("""{"id":"future","endpoints":[{"supported_parameters":$fields}]}""", ImageModelMetadata("future"))
            for (model in listOf(catalog, endpoint)) {
                assertFalse(model.settingsVerified)
                assertEquals(mapOf("output_format" to "png"), ImageRequestOptions.resolve(
                    ImageRequestOptions.forMetadataFallback(request, model), model))
                try { ImageRequestOptions.resolve(ImageRequestOptions.forMetadataFallback(
                    request.copy(parameters = mapOf("output_format" to "jpeg")), model), model); fail("known format remains strict") }
                catch (failure: ImageGenerationException) { assertEquals(ImageErrorCause.UNSUPPORTED_OPTION, failure.errorCause) }
            }
        }
        for (bad in listOf("[]", "null", "7")) {
            val model = ImageMetadataParser.catalog("""{"data":[{"id":"future","supported_parameters":$bad}]}""", "fixture").single()
            assertFalse(model.settingsVerified)
            assertTrue(ImageRequestOptions.resolve(ImageRequestOptions.forMetadataFallback(request, model), model).isEmpty())
            assertFalse(ImageMetadataParser.endpoints("""{"id":"future","endpoints":[{"supported_parameters":$bad}]}""", ImageModelMetadata("future")).settingsVerified)
        }
        val valid = """{"quality":{"type":"enum","values":["precise"]},$format,"prompt":[]}"""
        assertTrue(ImageMetadataParser.catalog("""{"data":[{"id":"future","supported_parameters":$valid}]}""", "fixture").single().settingsVerified)
        val missing = ImageMetadataParser.catalog("""{"data":[{"id":"future"}]}""", "fixture").single()
        assertFalse(missing.settingsVerified)
        assertTrue(ImageRequestOptions.resolve(ImageRequestOptions.forMetadataFallback(request, missing), missing).isEmpty())
        assertEquals(mapOf("quality" to "precise", "output_format" to "png"), request.parameters)
    }

    @Test fun providerTariffsRejectUnderflowWithoutRejectingRealZeroOrRepresentableValues() {
        for (value in listOf("1e-999", "\"1e-999\"", "-1e-999", "\"-1e-999\"")) {
            val pricing = imageJson("""{"pricing":[{"billable":"output_image","unit":"image","cost_usd":$value}]}""")!!.get("pricing")
            assertTrue(ImageMetadataParser.tariffs(pricing).isEmpty())
            assertFalse(ImageMetadataParser.tariffsComplete(pricing))
        }
        for (value in listOf("0", "\"0.000\"", Double.MIN_VALUE.toString())) {
            val pricing = imageJson("""{"pricing":[{"billable":"output_image","unit":"image","cost_usd":$value}]}""")!!.get("pricing")
            assertTrue(ImageMetadataParser.tariffsComplete(pricing))
            assertEquals(imageDecimal(value.trim('"'))!!, ImageMetadataParser.tariffs(pricing).single().amount, 0.0)
        }
        assertNull(imageJson("""{"bound":1e-999}""")!!.imageBound("bound"))
        assertFalse(ImageParameter("strength", ImageParameterType.NUMBER).accepts("1e-999"))
        assertTrue(ImageParameter("strength", ImageParameterType.NUMBER).accepts(Double.MIN_VALUE.toString()))
    }

    @Test fun unavailableCompressionBoundsUseDefaultsAndKeepVerifiedSettings() {
        val fields = """{"output_compression":{"type":"boolean"},"output_format":{"type":"enum","values":["png"]}}"""
        val catalogBody = """{"data":[{"id":"future","supported_parameters":$fields}]}"""
        val endpointBody = """{"id":"future","endpoints":[{"supported_parameters":$fields}]}"""
        val request = ImageGenerationRequest("p", ImageShape.AUTOMATIC, ImageQuality.AUTOMATIC, "e", "future",
            parameters = mapOf("output_compression" to "63", "output_format" to "png"))
        val base = ImageModelMetadata("future")
        for (rule in listOf(null, OpenRouterImageConfigurationParser.compression("changed layout without limits"))) {
            val catalog = ImageMetadataParser.catalog(catalogBody, "fixture", rule).single()
            val endpoint = ImageMetadataParser.endpoints(endpointBody, base, rule)
            for (model in listOf(catalog, endpoint, ImageMetadataParser.endpoints(endpointBody, catalog, rule))) {
                assertFalse(model.settingsVerified)
                assertEquals(mapOf("output_format" to "png"), ImageRequestOptions.resolve(
                    ImageRequestOptions.forMetadataFallback(request, model), model))
            }
        }
        val rule = OpenRouterImageConfigurationParser.compression("* `output_compression` — 15-87 for webp/jpeg.")!!
        val catalog = ImageMetadataParser.catalog(catalogBody, "fixture", rule).single()
        val endpoint = ImageMetadataParser.endpoints(endpointBody, catalog, rule)
        assertTrue(catalog.settingsVerified); assertTrue(endpoint.settingsVerified)
        assertEquals(request.parameters, ImageRequestOptions.resolve(request, endpoint))
        val bounded = fields.replace("""{"type":"boolean"}""", """{"type":"integer","minimum":15,"maximum":87}""")
        assertTrue(ImageMetadataParser.catalog(catalogBody.replace(fields, bounded), "fixture").single().settingsVerified)
        assertTrue(ImageMetadataParser.endpoints(endpointBody.replace(fields, bounded), base).settingsVerified)
        try { ImageRequestOptions.resolve(request.copy(parameters = mapOf("output_compression" to "99")), endpoint); fail("known bounds remain strict") }
        catch (failure: ImageGenerationException) { assertEquals(ImageErrorCause.UNSUPPORTED_OPTION, failure.errorCause) }
    }

    @Test fun unparseableOrPartialSuccessfulReferencesUseUnverifiedSettingsFallback() {
        val model = ImageModelMetadata("future", parameters = listOf(ImageParameter("quality", ImageParameterType.ENUM, listOf("precise"))))
        val request = ImageGenerationRequest("p", ImageShape.AUTOMATIC, ImageQuality.AUTOMATIC, "e", "future",
            parameters = mapOf("quality" to "precise", "output_format" to "png", "output_compression" to "63"))
        for (body in listOf("layout changed", "Supported models include `future`.\n\n", "Supported models include `future`.\n\n- `output_format: string`\n")) {
            val unverified = OpenAiImageReferenceParser.enrich(model, body)
            assertFalse(unverified.settingsVerified)
            assertEquals(mapOf("quality" to "precise"), ImageRequestOptions.resolve(
                ImageRequestOptions.forMetadataFallback(request, unverified), unverified))
        }
        val complete = """Supported models include `future`.

- `background: string`
  - `"transparent"`
  - `"opaque"`
- `output_format: string`
  - `"png"`
  - `"jpeg"`
- `output_compression: integer`
  Compression (15-87%)
"""
        val verified = OpenAiImageReferenceParser.enrich(model, complete)
        assertTrue(verified.settingsVerified)
        assertEquals(request.parameters, ImageRequestOptions.resolve(request, verified))
        val partial = OpenAiImageReferenceParser.enrich(model, complete.substringBefore("- `output_compression"))
        assertFalse(partial.settingsVerified)
        assertEquals(mapOf("quality" to "precise", "output_format" to "png"), ImageRequestOptions.resolve(
            ImageRequestOptions.forMetadataFallback(request, partial), partial))
    }

    @Test fun metadataOutagesUseDefaultsForUnverifiableSavedFieldsButKeepKnownRestrictionsAndOverrides() {
        val request = ImageGenerationRequest("p", ImageShape.AUTOMATIC, ImageQuality.AUTOMATIC, "e", "future",
            parameters = mapOf("size" to "1888x944", "quality" to "precise", "output_format" to "png"))
        val unknown = ImageRequestOptions.forMetadataFallback(request, null)
        assertTrue(unknown.parameters.isEmpty())
        assertTrue(ImageRequestOptions.resolve(unknown, null).isEmpty())
        val known = ImageModelMetadata("future", parameters = listOf(ImageParameter("output_format", ImageParameterType.ENUM, listOf("png"))))
        val fallback = known.withoutEndpointEvidence()
        val safe = ImageRequestOptions.forMetadataFallback(request, fallback)
        assertEquals(mapOf("output_format" to "png"), ImageRequestOptions.resolve(safe, fallback))
        assertEquals(request.parameters, ImageRequestOptions.forMetadataFallback(request, known).parameters)
        for (override in listOf(request.copy(shape = ImageShape.LANDSCAPE), request.copy(quality = ImageQuality.HIGH))) {
            try { ImageRequestOptions.resolve(ImageRequestOptions.forMetadataFallback(override, fallback), fallback); fail("explicit override must remain strict") }
            catch (failure: ImageGenerationException) { assertEquals(ImageErrorCause.UNSUPPORTED_OPTION, failure.errorCause) }
        }
        val svg = known.copy(parameters = listOf(ImageParameter("output_format", ImageParameterType.ENUM, listOf("svg")))).withoutEndpointEvidence()
        try { ImageRequestOptions.resolve(ImageRequestOptions.forMetadataFallback(request, svg), svg); fail("known unusable output must remain blocked") }
        catch (failure: ImageGenerationException) { assertEquals(ImageErrorCause.UNSUPPORTED_OPTION, failure.errorCause) }
        assertEquals(mapOf("size" to "1888x944", "quality" to "precise", "output_format" to "png"), request.parameters)
    }

    @Test fun incompleteOrInvalidRangesAreNotInterpretedAsUnboundedEndpoints() {
        val model = ImageModelMetadata("future", parameters = listOf(ImageParameter("output_format", ImageParameterType.ENUM, listOf("svg"))))
        for (limits in listOf("\"min\":1", "\"max\":9", "\"min\":1,\"max\":\"bad\"", "\"min\":9,\"max\":1")) {
            val fields = """{"seed":{"type":"range",$limits}}"""
            assertTrue(ImageMetadataParser.parameters(imageJson(fields)).isEmpty())
            val fallback = ImageMetadataParser.endpoints("""{"id":"future","endpoints":[{"supported_parameters":$fields,"pricing":[{"billable":"output_image","unit":"image","cost_usd":0.1}]}]}""", model)
            assertFalse(fallback.settingsVerified)
            assertFalse(fallback.tariffsComplete)
            assertTrue(fallback.endpointRecords.isEmpty())
            assertEquals(model.parameters, fallback.parameters)
            assertFalse(fallback.hasDisplayableOutput())
        }
        val valid = ImageMetadataParser.parameters(imageJson("""{"seed":{"type":"range","min":1,"max":9}}""")).single()
        assertTrue(valid.accepts("9")); assertFalse(valid.accepts("10"))
    }

    @Test fun savedSnapshotReloadsItsCanonicalDocumentWithoutAProcessCache() {
        val alias = "future-snapshot-v7"
        val canonical = "future-base"
        val document = """# Future Image
Model ID: `future-base`
- Output modalities: image
| Endpoint | Support |
| `v1/images/generations` | Supported |
## Pricing
### Image generation
| Quality | precise | Price |
| 1888x944 | precise | illustrative |
### Text tokens
| Input | ${'$'}3 | 1000 tokens |
## Snapshots
- `future-snapshot-v7`
"""
        val index = "- [Future Image](/api/docs/models/future-base.md): image generation"
        val reads = mutableListOf<String>()
        val resolved = OpenAiImageMetadataParser.resolve(alias, read = { url ->
            reads += url
            when (url) {
                OpenAiImageMetadataParser.INDEX_URL -> index
                OpenAiImageMetadataParser.modelUrl(canonical) -> document
                else -> null
            }
        })!!
        assertEquals(OpenAiImageMetadataParser.modelUrl(alias), reads.first())
        assertTrue(reads.contains(OpenAiImageMetadataParser.INDEX_URL))
        assertEquals(OpenAiImageMetadataParser.modelUrl(canonical), resolved.sourceUrl)
        assertEquals(alias, resolved.id)
        assertEquals(setOf(alias, canonical), resolved.resolvedIds)
        assertEquals("Future Image", resolved.publishedName)
        assertEquals(3.0, resolved.tariffs.single().amount, 0.0)
        val options = mapOf("size" to "1888x944", "quality" to "precise")
        val request = ImageGenerationRequest("p", ImageShape.AUTOMATIC, ImageQuality.AUTOMATIC, "e", alias, parameters = options)
        assertEquals(options, ImageRequestOptions.resolve(request, resolved))
        assertNull(OpenAiImageMetadataParser.resolve("unpublished-alias", read = { url ->
            when (url) { OpenAiImageMetadataParser.INDEX_URL -> index
                OpenAiImageMetadataParser.modelUrl(canonical) -> document
                else -> null }
        }))
    }

    @Test fun booleanCompressionCapabilityUsesFetchedBoundsAndRejectsUnboundedValues() {
        val descriptor = imageJson("""{"output_compression":{"type":"boolean"}}""")!!
        assertTrue(ImageMetadataParser.parameters(descriptor).isEmpty())
        val rule = OpenRouterImageConfigurationParser.compression("* `output_compression` — 0-100 for webp/jpeg.")!!
        val model = ImageModelMetadata("future", parameters = ImageMetadataParser.parameters(descriptor, rule))
        val request = ImageGenerationRequest("p", ImageShape.AUTOMATIC, ImageQuality.AUTOMATIC, "e", "future")
        for (valid in listOf("0", "63", "100")) assertEquals(mapOf("output_compression" to valid),
            ImageRequestOptions.resolve(request.copy(parameters = mapOf("output_compression" to valid)), model))
        for (invalid in listOf("-1", "101", "0.5")) {
            try { ImageRequestOptions.resolve(request.copy(parameters = mapOf("output_compression" to invalid)), model); fail("compression must respect the published bounds") }
            catch (failure: ImageGenerationException) { assertEquals(ImageErrorCause.UNSUPPORTED_OPTION, failure.errorCause) }
        }
        val changed = OpenRouterImageConfigurationParser.compression("* `output_compression` — 15-87 for webp/jpeg.")!!
        val next = ImageMetadataParser.parameters(descriptor, changed).single()
        assertTrue(next.accepts("15")); assertTrue(next.accepts("87"))
        assertFalse(next.accepts("0")); assertFalse(next.accepts("100"))
        val narrower = imageJson("""{"output_compression":{"type":"range","min":20,"max":75}}""")!!
        assertFalse(ImageMetadataParser.parameters(narrower, changed).single().accepts("15"))
        assertNull(OpenRouterImageConfigurationParser.compression("missing limits"))
        val body = """{"id":"future","endpoints":[{"supported_parameters":{"output_compression":{"type":"boolean"}}}]}"""
        assertEquals(model.parameters, ImageMetadataParser.endpoints(body, ImageModelMetadata("future"), rule).parameters)
        assertTrue(ImageMetadataParser.endpoints(body, ImageModelMetadata("future")).parameters.isEmpty())
    }

    @Test fun transparentBackgroundRejectsOpaqueFormatsInEitherSettingOrderAndWithPublishedDefaults() {
        val model = ImageModelMetadata("future", parameters = listOf(
            ImageParameter("background", ImageParameterType.ENUM, listOf("transparent", "opaque", "auto")),
            ImageParameter("output_format", ImageParameterType.ENUM, listOf("jpeg", "jpg", "image/jpeg", "png", "webp"))))
        val request = ImageGenerationRequest("p", ImageShape.AUTOMATIC, ImageQuality.AUTOMATIC, "e", "future")
        for (opaque in listOf("jpeg", "jpg", "image/jpeg")) {
            val options = listOf("background" to "transparent", "output_format" to opaque)
            for (ordered in listOf(options, options.reversed())) {
                try { ImageRequestOptions.resolve(request.copy(parameters = ordered.toMap()), model); fail("JPEG cannot retain transparency") }
                catch (failure: ImageGenerationException) { assertEquals(ImageErrorCause.UNSUPPORTED_OPTION, failure.errorCause) }
            }
        }
        val jpegDefault = model.copy(parameters = model.parameters.map {
            if (it.key == "output_format") it.copy(defaultValue = "jpeg") else it
        })
        try { ImageRequestOptions.resolve(request.copy(parameters = mapOf("background" to "transparent")), jpegDefault); fail("published opaque default cannot retain transparency") }
        catch (failure: ImageGenerationException) { assertEquals(ImageErrorCause.UNSUPPORTED_OPTION, failure.errorCause) }
        val transparentDefault = model.copy(parameters = model.parameters.map {
            if (it.key == "background") it.copy(defaultValue = "transparent") else it
        })
        try { ImageRequestOptions.resolve(request.copy(parameters = mapOf("output_format" to "jpeg")), transparentDefault); fail("background default must be considered") }
        catch (failure: ImageGenerationException) { assertEquals(ImageErrorCause.UNSUPPORTED_OPTION, failure.errorCause) }
        for (alpha in listOf("png", "webp")) {
            val options = mapOf("background" to "transparent", "output_format" to alpha)
            assertEquals(options, ImageRequestOptions.resolve(request.copy(parameters = options), model))
        }
        for (background in listOf("opaque", "auto")) {
            val options = mapOf("background" to background, "output_format" to "jpeg")
            assertEquals(options, ImageRequestOptions.resolve(request.copy(parameters = options), model))
        }
        val routed = ImageMetadataParser.endpoints("""{"id":"future","endpoints":[{"supported_parameters":{"background":{"type":"enum","values":["transparent","opaque"],"default":"transparent"},"output_format":{"type":"enum","values":["png","jpeg"],"default":"png"}}},{"supported_parameters":{"background":{"type":"enum","values":["transparent","opaque"],"default":"opaque"},"output_format":{"type":"enum","values":["png","jpeg"],"default":"png"}}}]}""", model)
        try { ImageRequestOptions.resolve(request.copy(parameters = mapOf("output_format" to "jpeg")), routed); fail("an unsafe route default cannot be hidden by the intersection") }
        catch (failure: ImageGenerationException) { assertEquals(ImageErrorCause.UNSUPPORTED_OPTION, failure.errorCause) }
        assertEquals(mapOf("background" to "opaque", "output_format" to "jpeg"), ImageRequestOptions.resolve(
            request.copy(parameters = mapOf("background" to "opaque", "output_format" to "jpeg")), routed))
    }

    @Test fun oneMalformedEndpointInvalidatesTheEntireEnrichment() {
        val model = ImageModelMetadata("future", parameters = listOf(
            ImageParameter("output_format", ImageParameterType.ENUM, listOf("svg"))),
            tariffs = listOf(ImageTariff("output_image", "image", 1.0, 1.0, "USD")))
        val valid = """{"supported_parameters":{"output_format":{"type":"enum","values":["png"],"default":"png"},"seed":{"type":"range","min":1,"max":9}},"pricing":[{"billable":"output_image","unit":"image","cost_usd":0.2}]}"""
        for (invalid in listOf("null", "42", "\"broken\"", "[]")) {
            for (routes in listOf("$valid,$invalid", "$invalid,$valid")) {
                val metadata = ImageMetadataParser.endpoints("""{"id":"future","endpoints":[$routes]}""", model)
                assertFalse(metadata.tariffsComplete)
                assertTrue(metadata.endpointRecords.isEmpty())
                assertTrue(metadata.tariffs.isEmpty())
                assertEquals(model.parameters, metadata.parameters)
                assertFalse(metadata.hasDisplayableOutput())
            }
        }
    }

    @Test fun endpointEnrichmentFailuresPreserveKnownOutputRestrictionsWithoutRatesOrExtraSettings() {
        val model = ImageModelMetadata("future", parameters = listOf(
            ImageParameter("output_format", ImageParameterType.ENUM, listOf("svg")),
            ImageParameter("seed", ImageParameterType.INTEGER)),
            tariffs = listOf(ImageTariff("output_image", "image", 1.0, 1.0, "USD")))
        for (body in listOf("not json", "{}", """{"id":"other","endpoints":[]}""", """{"id":"future","endpoints":[]}""")) {
            val fallback = ImageMetadataParser.endpoints(body, model)
            assertFalse(fallback.tariffsComplete)
            assertTrue(fallback.tariffs.isEmpty())
            assertEquals(listOf("output_format"), fallback.parameters.map { it.key })
            assertFalse(fallback.hasDisplayableOutput())
            val request = ImageGenerationRequest("p", ImageShape.AUTOMATIC, ImageQuality.AUTOMATIC, "e", "future")
            try { ImageRequestOptions.resolve(request, fallback); fail("known SVG-only output must not be forgotten") }
            catch (failure: ImageGenerationException) { assertEquals(ImageErrorCause.UNSUPPORTED_OPTION, failure.errorCause) }
        }
    }

    @Test fun changingDimensionControlsClearsPeersInBothOrdersAndKeepsUnrelatedSettings() {
        val metadata = ImageModelMetadata("future", parameters = listOf(ImageParameter("resolution", ImageParameterType.ENUM, listOf("9K"))))
        val original = mapOf("size" to "900x900", "seed" to "42")
        val ratio = ImageDimensionSettings.change(original, "aspect_ratio", "7:4", metadata)
        assertEquals(mapOf("aspect_ratio" to "7:4", "seed" to "42"), ratio)
        val tier = ImageDimensionSettings.change(ratio, "resolution", "9K", metadata)
        val pixel = ImageDimensionSettings.change(tier, "size", "900x900", metadata)
        assertEquals(original, pixel)
        assertEquals(mapOf("seed" to "42", "resolution" to "9K"),
            ImageDimensionSettings.change(pixel, "resolution", "9K", metadata))
        val shorthand = ImageDimensionSettings.change(ratio, "size", "9K", metadata)
        assertEquals(mapOf("aspect_ratio" to "7:4", "seed" to "42", "size" to "9K"), shorthand)
        assertEquals(mapOf("aspect_ratio" to "7:4", "seed" to "42", "resolution" to "9K"),
            ImageDimensionSettings.change(shorthand, "aspect_ratio", "7:4", metadata))
    }

    @Test fun historicalConflictingDimensionsAreRejectedWithoutAShapeOverride() {
        val model = ImageModelMetadata("future", parameters = listOf(
            ImageParameter("aspect_ratio", ImageParameterType.ENUM, listOf("7:4")),
            ImageParameter("size", ImageParameterType.ENUM, listOf("900x900"))))
        val request = ImageGenerationRequest("p", ImageShape.AUTOMATIC, ImageQuality.AUTOMATIC, "e", "future",
            parameters = mapOf("aspect_ratio" to "7:4", "size" to "900x900"))
        try { ImageRequestOptions.resolve(request, model); fail("contradictory settings must not be billed") }
        catch (failure: ImageGenerationException) { assertEquals(ImageErrorCause.UNSUPPORTED_OPTION, failure.errorCause) }
    }

    @Test fun googleTransportAndResponseChoicesComeFromPublishedExamplesAndSchema() {
        val guide = """<h2>Model selection</h2><ul><li><a href="/gemini-api/docs/models/future-native">Future Image</a></li></ul><h2>Examples</h2><pre>curl https://generativelanguage.googleapis.com/v1beta/interactions -d '{"model":"future-native"}'</pre><h3>Future Image</h3><table><tr><th>Aspect ratio</th><th>9K resolution</th></tr><tr><td>7:4</td><td>7x4</td></tr></table>"""
        val schema = """{"components":{"schemas":{"ImageResponseFormat":{"properties":{"aspect_ratio":{"enum":["7:4"]},"image_size":{"enum":["9K"]},"mime_type":{"enum":["image/jpeg"]}}}}}}"""
        val model = GeminiImageMetadataParser.models(guide, null, schema).single()
        assertTrue(model.settingsVerified)
        assertEquals(GeminiImageTransport.INTERACTIONS, model.geminiTransport)
        assertEquals(listOf("9K"), model.parameters.single { it.key == "resolution" }.values)
        assertEquals(listOf("image/jpeg"), model.parameters.single { it.key == "output_format" }.values)
        val mixed = guide.replace("</ul>", "<li><a href=\"/gemini-api/docs/models/legacy-native\">Legacy Image</a></li></ul>") +
            "<pre>curl https://generativelanguage.googleapis.com/v1beta/models/legacy-native:generateContent</pre>"
        val models = GeminiImageMetadataParser.models(mixed, null, schema).associateBy { it.id }
        assertEquals(GeminiImageTransport.INTERACTIONS, models["future-native"]!!.geminiTransport)
        assertEquals(GeminiImageTransport.GENERATE_CONTENT, models["legacy-native"]!!.geminiTransport)
    }

    @Test fun incompleteGeminiModelTablesUseDefaultsWhileRetainingVerifiedSchemaFields() {
        val selection = """<h2>Model selection</h2><ul><li><a href="/gemini-api/docs/models/future-native">Future Image</a></li></ul><h2>Examples</h2><pre>curl https://generativelanguage.googleapis.com/v1beta/interactions -d '{"model":"future-native"}'</pre>"""
        val table = """<h3>Future Image</h3><table><tr><th>Aspect ratio</th><th>9K resolution</th></tr><tr><td>7:4</td><td>7x4</td></tr></table>"""
        val schema = """{"components":{"schemas":{"ImageResponseFormat":{"properties":{"aspect_ratio":{"enum":["7:4"]},"image_size":{"enum":["9K"]},"mime_type":{"enum":["image/jpeg"]}}}}}}"""
        val request = ImageGenerationRequest("p", ImageShape.AUTOMATIC, ImageQuality.AUTOMATIC, "e", "future-native",
            parameters = mapOf("resolution" to "9K", "aspect_ratio" to "7:4", "output_format" to "image/jpeg"))
        val complete = GeminiImageMetadataParser.models(selection + table, null, schema).single()
        assertTrue(complete.settingsVerified)
        assertEquals(request.parameters, ImageRequestOptions.resolve(request, complete))
        for (broken in listOf("", table.replace("Future Image", "Changed heading"), table.substringBefore("<tr><td>"),
                table.replace("<tr><td>7:4</td><td>7x4</td></tr>", ""), table.replace("9K resolution", "Changed column"))) {
            val fallback = GeminiImageMetadataParser.models(selection + broken, null, schema).single()
            assertFalse(fallback.settingsVerified)
            assertEquals(GeminiImageTransport.INTERACTIONS, fallback.geminiTransport)
            val retained = ImageRequestOptions.forMetadataFallback(request, fallback)
            assertEquals(retained.parameters, ImageRequestOptions.resolve(retained, fallback))
            assertEquals("image/jpeg", retained.parameters["output_format"])
            if (broken.isEmpty() || broken.contains("Changed heading") || !broken.contains("</table>")) {
                assertFalse(retained.parameters.containsKey("resolution"))
                assertFalse(retained.parameters.containsKey("aspect_ratio"))
            }
        }
        assertFalse(GeminiImageMetadataParser.models(selection + table, null, schema.replace("\"9K\"", "\"other\"")).single().settingsVerified)
    }

    @Test fun interactionsDiscoveryDoesNotRequireAnOlderGenerateContentMethodOrCatalogEntry() {
        val published = ImageModelMetadata("future-native", geminiTransport = GeminiImageTransport.INTERACTIONS)
        val native = imageJson("""{"name":"models/future-native","supportedGenerationMethods":[]}""")!!
        assertEquals(listOf("future-native"), GeminiImageMetadataParser.withNativeIds(published, listOf(native)).map { it.id })
        assertEquals(listOf(published), GeminiImageMetadataParser.withNativeIds(published, emptyList()))
        assertTrue(GeminiImageMetadataParser.withNativeIds(published.copy(geminiTransport = GeminiImageTransport.GENERATE_CONTENT), listOf(native)).isEmpty())
    }

    @Test fun differingEndpointDefaultsCannotHideAnUnsafeOutputBehindAPngIntersection() {
        val model = ImageModelMetadata("future")
        val body = """{"id":"future","endpoints":[{"supported_parameters":{"output_format":{"type":"enum","values":["png","svg"],"default":"svg"}}},{"supported_parameters":{"output_format":{"type":"enum","values":["png"],"default":"png"}}}]}"""
        val metadata = ImageMetadataParser.endpoints(body, model)
        assertTrue(metadata.hasDisplayableOutput())
        assertTrue(metadata.requiresExplicitOutputFormat)
        val request = ImageGenerationRequest("p", ImageShape.AUTOMATIC, ImageQuality.AUTOMATIC, "e", "future")
        try { ImageRequestOptions.resolve(request, metadata); fail("automatic cannot pick a possibly unsafe route default") }
        catch (failure: ImageGenerationException) { assertEquals(ImageErrorCause.UNSUPPORTED_OPTION, failure.errorCause) }
        assertEquals(mapOf("output_format" to "png"), ImageRequestOptions.resolve(request.copy(parameters = mapOf("output_format" to "png")), metadata))
    }
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
