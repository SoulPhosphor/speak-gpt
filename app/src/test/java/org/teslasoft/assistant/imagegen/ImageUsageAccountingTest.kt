package org.teslasoft.assistant.imagegen

import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.usage.*

class ImageUsageAccountingTest {
    @Test fun preferredUsageChargeCannotBorrowADifferentRootAmount() {
        for (value in listOf("1e-999", "\"1e-999\"", "null", "\"unreadable\"")) {
            val request = attempt(body = """{"usage":{"cost":$value},"cost":0.25,"data":[{}]}""")
            assertNull(request.receipt.amount)
            assertNull(request.record().totalCost)
            if (value.contains("1e-999")) assertEquals("1e-999", request.record().reportedChargeDecimal)
        }
        val zero = attempt(body = """{"usage":{"cost":0},"cost":0.25}""").record()
        assertEquals(0.0, zero.totalCost!!, 0.0)
        assertEquals("0", zero.reportedChargeDecimal)
        assertEquals(0.25, attempt(body = """{"usage":{},"cost":0.25}""").record().totalCost!!, 0.0)
    }

    @Test fun unrepresentableProviderChargesStayUnknownAndRetainTheirDecimalEvidence() {
        for (value in listOf("1e-999", "\"1e-999\"")) {
            val initial = attempt(ImageProviderKind.NANOGPT, """{"cost":$value,"currency":"USD","data":[{}]}""")
            assertNull(initial.receipt.amount)
            assertNull(initial.record().totalCost)
            assertEquals("1e-999", initial.record().reportedChargeDecimal)
            val billing = ImageUsageParser.billing(ImageProviderKind.NANOGPT,
                """{"request_id":"request-1","cost":$value,"currency":"USD"}""", "request-1")!!
            assertNull(billing.amount)
            assertNull(initial.record(billing).totalCost)
            assertEquals("1e-999", initial.record(billing).reportedChargeDecimal)
            val earlierZero = attempt(ImageProviderKind.NANOGPT, """{"cost":0,"currency":"USD","data":[{}]}""")
            assertNull(earlierZero.record(billing).totalCost)
            val routed = ImageUsageParser.billing(ImageProviderKind.OPENROUTER,
                """{"id":"generation-1","total_cost":$value}""", "generation-1")!!
            assertNull(routed.usd)
            assertEquals("1e-999", routed.amountDecimal)
        }
        for (value in listOf("0", "\"0.000\"", Double.MIN_VALUE.toString())) {
            val initial = attempt(ImageProviderKind.NANOGPT, """{"cost":$value,"currency":"USD","data":[{}]}""")
            assertEquals(imageDecimal(value.trim('"'))!!, initial.record().totalCost!!, 0.0)
        }
    }

    @Test fun calculatedPricesThatUnderflowOrOverflowRemainUnknown() {
        val meter = UsageMeter(UsageMeterComponent.IMAGES, UsageMeterUnit.IMAGE, 1.0,
            UsageQuantitySource.PROVIDER_REPORTED, Double.MIN_VALUE, 1000.0, "USD")
        assertNull(meter.unitPrice)
        assertNull(meter.withCalculatedCost().cost)
        assertNull(MeteredUsageAccounting.record("model", "provider", null, listOf(meter), null).totalCost)
        val tooLarge = meter.copy(quantity = Double.MAX_VALUE, priceAmount = Double.MAX_VALUE, priceQuantity = 1.0)
        assertNull(tooLarge.withCalculatedCost().cost)
        assertNull(MeteredUsageAccounting.record("model", "provider", null,
            listOf(meter.copy(cost = Double.MAX_VALUE), meter.copy(cost = Double.MAX_VALUE)), null).totalCost)
        assertEquals(0.0, meter.copy(priceAmount = 0.0).withCalculatedCost().cost!!, 0.0)
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertNull(meter.copy(priceAmount = invalid).unitPrice)
            assertNull(meter.copy(priceAmount = invalid).withCalculatedCost().cost)
            assertNull(meter.copy(priceQuantity = invalid).unitPrice)
            assertNull(meter.copy(priceQuantity = invalid).withCalculatedCost().cost)
            assertNull(meter.copy(quantity = invalid).withCalculatedCost().cost)
            assertNull(meter.copy(cost = invalid).withCalculatedCost().cost)
        }
    }

    @Test fun cachePolicyMapsPublishedFamilyNamesToDocumentedVariantIdsWithoutIdPrefixes() {
        val guide = """### Cached input pricing
For Future Image 8 and Future Image 8.5, cached input pricing applies only to the image generation tool in the Responses API. It doesn't apply to direct Images API requests.

### Available models
Use `published-bloom-id` or `published-ember-id` directly.
"""
        for ((id, name) in listOf("published-bloom-id" to "Future-Image-8.5 Bloom", "published-ember-id" to "Future Image 8.5 Ember")) {
            val model = ImageModelMetadata(id, publishedName = name)
            assertTrue(OpenAiImageCachePolicyParser.enrich(model, guide).directCachedInputExcluded)
            assertFalse(OpenAiImageCachePolicyParser.enrich(model, guide.replace("`$id`", "`other-id`")).directCachedInputExcluded)
        }
        val unmentionedFamily = ImageModelMetadata("published-bloom-id", publishedName = "Future Image 8.7 Bloom")
        assertFalse(OpenAiImageCachePolicyParser.enrich(unmentionedFamily, guide).directCachedInputExcluded)
        val prefixOnly = ImageModelMetadata("future-image-8.5-unsupported")
        assertFalse(OpenAiImageCachePolicyParser.enrich(prefixOnly, guide).directCachedInputExcluded)
    }

    @Test fun interactionsUsageRecordsModalitiesThoughtsCacheAndFinalImageWithFrozenPrices() {
        val body = """{"model":"new-model","id":"interaction-1","status":"completed","usage":{"input_tokens_by_modality":[{"modality":"text","tokens":10}],"output_tokens_by_modality":[{"modality":"image","tokens":50},{"modality":"text","tokens":4}],"total_input_tokens":10,"total_output_tokens":54,"total_thought_tokens":2,"total_cached_tokens":0},"steps":[{"type":"model_output","content":[{"type":"image","mime_type":"image/png","data":"Ag=="}]}]}"""
        val prices = listOf(ImageTariff("text_input", "token", 1.0, 1000.0, "USD"), ImageTariff("text_output", "token", 2.0, 1000.0, "USD"), ImageTariff("image_output", "token", 3.0, 1000.0, "USD"))
        val metadata = ImageModelMetadata("new-model", tariffs = prices, geminiTransport = GeminiImageTransport.INTERACTIONS)
        val record = attempt(ImageProviderKind.GEMINI, body, metadata).record()
        assertEquals(0.172, record.totalCost!!, 1e-12)
        assertEquals(1.0, record.meters!!.single { it.component == UsageMeterComponent.IMAGES }.quantity!!, 0.0)
        assertEquals(6.0, record.meters!!.single { it.component == UsageMeterComponent.TEXT_OUTPUT }.quantity!!, 0.0)
        val missingThoughts = body.replace(",\"total_thought_tokens\":2", "")
        val withoutThoughts = attempt(ImageProviderKind.GEMINI, missingThoughts, metadata).record()
        assertEquals(4.0, withoutThoughts.meters!!.single { it.component == UsageMeterComponent.TEXT_OUTPUT }.quantity!!, 0.0)
        assertEquals(0.168, withoutThoughts.totalCost!!, 1e-12)
        assertNull(attempt(ImageProviderKind.GEMINI, body.replace("\"total_thought_tokens\":2", "\"total_thought_tokens\":-1"), metadata).record().totalCost)
        val missingCache = body.replace(",\"total_cached_tokens\":0", "")
        assertNull(attempt(ImageProviderKind.GEMINI, missingCache, metadata).record().totalCost)
        val missingModalities = body.replace("\"output_tokens_by_modality\":[{\"modality\":\"image\",\"tokens\":50},{\"modality\":\"text\",\"tokens\":4}],", "")
        val incomplete = attempt(ImageProviderKind.GEMINI, missingModalities, metadata).record()
        assertNull(incomplete.totalCost)
        assertEquals(54.0, incomplete.meters!!.single { it.component == UsageMeterComponent.OUTPUT }.quantity!!, 0.0)
        assertEquals(1.0, incomplete.meters!!.single { it.component == UsageMeterComponent.IMAGES }.quantity!!, 0.0)
        assertNull(attempt(ImageProviderKind.GEMINI, body.replace("\"total_output_tokens\":54", "\"total_output_tokens\":99"), metadata).record().totalCost)
    }
    private fun attempt(kind: ImageProviderKind = ImageProviderKind.OPENROUTER, body: String = "{}",
                        metadata: ImageModelMetadata? = null, parameters: Map<String, String> = emptyMap()) =
        ImageUsageAttempt(kind, "new-model", "provider", "https://service.example/v1", 1234, parameters,
            metadata, httpStatus = 200, receipt = ImageUsageParser.response(kind, body, "request-1"))

    @Test fun reportedUsdWinsAndKeepsImagesOutsideTextTokenFields() {
        val record = attempt(body = """{"id":"generation-1","data":[{}],"usage":{"cost":0.0245,"output_tokens":83}}""").record()
        assertEquals(0.0245, record.totalCost!!, 0.0)
        assertEquals(CostSource.PROVIDER_REPORTED.storedValue, record.costSource)
        assertNull(record.inputTokens)
        assertNull(record.outputTokens)
        assertEquals(1.0, record.meters!!.single { it.component == UsageMeterComponent.IMAGES }.quantity!!, 0.0)
        assertEquals("request-1", record.requestId)
        assertEquals(1234L, record.requestStartedAtMs)
    }

    @Test fun creditsRemainFractionalAndNeverBecomeDollarsWithoutAPrice() {
        val record = attempt(ImageProviderKind.COMPATIBLE, """{"usage":{"credits":1.375}}""").record()
        val credits = record.meters!!.single { it.unit == UsageMeterUnit.CREDIT }
        assertEquals(1.375, credits.quantity!!, 0.0)
        assertNull(record.totalCost)
    }

    @Test fun nanoChargeRequiresCurrencyAndExactRequestId() {
        val initial = attempt(ImageProviderKind.NANOGPT, """{"cost":0.19,"data":[{}]}""")
        assertNull(initial.record().totalCost)
        assertNull(ImageUsageParser.billing(ImageProviderKind.NANOGPT, """{"request_id":"wrong","cost":"0.19","currency":"USD"}""", "request-1"))
        val usd = ImageUsageParser.billing(ImageProviderKind.NANOGPT, """{"request_id":"request-1","cost":"0.19","currency":"USD"}""", "request-1")!!
        assertEquals(0.19, initial.record(usd).totalCost!!, 0.0)
        val xno = usd.copy(currency = "XNO")
        val native = initial.record(xno)
        assertNull(native.totalCost)
        assertEquals(0.19, native.reportedChargeAmount!!, 0.0)
        assertEquals("XNO", native.reportedChargeCurrency)
    }

    @Test fun frozenRatesRequireCompleteUsageAndNeverGuessTheCacheSplit() {
        val prices = listOf(ImageTariff("text_input", "token", 9.0, 1000.0, "USD"),
            ImageTariff("text_cached_input", "token", 1.0, 1000.0, "USD"),
            ImageTariff("image_output", "token", 17.0, 1000.0, "USD"))
        val metadata = ImageModelMetadata("new-model", tariffs = prices, sourceUrl = "https://docs.example/model", outputModalities = setOf("image"))
        val body = """{"usage":{"input_tokens_details":{"text_tokens":100,"image_tokens":0,"cached_tokens_details":{"text_tokens":40}},"output_tokens":200},"data":[{}]}"""
        val record = attempt(ImageProviderKind.OPENAI, body, metadata).record()
        assertNotNull(record.toString(), record.totalCost)
        assertEquals(3.98, record.totalCost!!, 1e-12)
        assertTrue(record.pricingEvidence!!.contains("17.0"))
        val missing = body.replace(",\"cached_tokens_details\":{\"text_tokens\":40}", "")
        assertNull(attempt(ImageProviderKind.OPENAI, missing, metadata).record().totalCost)
        assertEquals(3.98, record.totalCost!!, 1e-12) // later metadata changes cannot reprice this record
    }

    @Test fun zeroTextInputHasAnExactZeroCachedSubset() {
        val prices = listOf(ImageTariff("text_input", "token", 9.0, 1000.0, "USD"),
            ImageTariff("text_cached_input", "token", 1.0, 1000.0, "USD"),
            ImageTariff("image_output", "token", 17.0, 1000.0, "USD"))
        val model = ImageModelMetadata("new-model", tariffs = prices, outputModalities = setOf("image"))
        val body = """{"usage":{"input_tokens_details":{"text_tokens":0,"image_tokens":0},"output_tokens":200},"data":[{}]}"""
        assertEquals(3.4, attempt(ImageProviderKind.OPENAI, body, model).record().totalCost!!, 1e-12)
    }

    @Test fun publishedDirectCacheExclusionAllowsNonzeroUncachedTextWithoutGuessingOtherModels() {
        val prices = listOf(ImageTariff("text_input", "token", 9.0, 1000.0, "USD"),
            ImageTariff("text_cached_input", "token", 1.0, 1000.0, "USD"),
            ImageTariff("image_output", "token", 17.0, 1000.0, "USD"))
        val guide = """### Cached input pricing

For Future Image 8 and Future Image 8.5, cached input pricing applies only to the image generation tool in the Responses API. It doesn't apply to direct Images API requests.
"""
        val base = ImageModelMetadata("future-image-8", tariffs = prices, outputModalities = setOf("image"))
        val documented = OpenAiImageCachePolicyParser.enrich(base, guide)
        assertTrue(documented.directCachedInputExcluded)
        val body = """{"usage":{"input_tokens_details":{"text_tokens":100,"image_tokens":0},"output_tokens":200},"data":[{}]}"""
        val direct = attempt(ImageProviderKind.OPENAI, body, documented).copy(model = base.id)
        val record = direct.record()
        assertEquals(4.3, record.totalCost!!, 1e-12)
        val cache = record.meters!!.single { it.component == UsageMeterComponent.CACHED_TEXT_INPUT }
        assertEquals(0.0, cache.quantity!!, 0.0)
        assertEquals(UsageQuantitySource.LOCAL_EXACT, cache.quantitySource)
        assertTrue(record.pricingEvidence!!.contains(OpenAiImageCachePolicyParser.URL))
        assertFalse(OpenAiImageCachePolicyParser.enrich(base.copy(id = "future-image-80", resolvedIds = setOf("future-image-80")), guide).directCachedInputExcluded)
        assertFalse(OpenAiImageCachePolicyParser.enrich(documented, guide.replace("doesn't apply", "applies")).directCachedInputExcluded)
        val contradictoryCache = body.replace("\"image_tokens\":0", "\"image_tokens\":0,\"cached_tokens_details\":{\"text_tokens\":40}")
        assertNull(attempt(ImageProviderKind.OPENAI, contradictoryCache, documented).copy(model = base.id).record().totalCost)
        assertNull(direct.copy(metadata = base).record().totalCost)
        assertNull(direct.copy(kind = ImageProviderKind.COMPATIBLE, receipt = ImageUsageReceipt()).record().totalCost)
        assertNull(direct.copy(receipt = ImageUsageReceipt(model = "different")).record().totalCost)
    }

    @Test fun servingProviderAndModelMustMatchFrozenMetadata() {
        val a = ImageServingMetadata("A", "a", emptyList(), listOf(ImageTariff("output_image", "image", 0.1, 1.0, "USD")))
        val b = a.copy(providerName = "B", providerSlug = "b", tariffs = listOf(ImageTariff("output_image", "image", 0.3, 1.0, "USD")))
        val request = attempt(body = """{"data":[{}]}""", metadata = ImageModelMetadata("new-model", endpointRecords = listOf(a,b)))
        assertNull(request.record().totalCost)
        val matched = request.record(ImageUsageReceipt(provider = "B"))
        assertEquals(0.3, matched.totalCost!!, 0.0)
        val frozen = imageJson(matched.pricingEvidence!!)!!.imageArray("endpointRecords")!!
        assertEquals(listOf("A", "B"), frozen.map { it.imageObject()!!.imageText("providerName") })
        assertNull(request.record(ImageUsageReceipt(model = "unrelated", provider = "B")).totalCost)
    }

    @Test fun tierAndIncompleteMetadataNeverProduceSubtotalAsTotal() {
        val prices = listOf(ImageTariff("output_image", "image", 0.02, 1.0, "USD", mapOf("variant" to "2k")))
        val metadata = ImageModelMetadata("new-model", tariffs = prices)
        assertNull(attempt(body = """{"data":[{}]}""", metadata = metadata).record().totalCost)
        assertEquals(0.02, attempt(body = """{"data":[{}]}""", metadata = metadata, parameters = mapOf("resolution" to "2K")).record().totalCost!!, 0.0)
        assertNull(attempt(body = """{"data":[{}]}""", metadata = metadata.copy(tariffsComplete = false), parameters = mapOf("resolution" to "2K")).record().totalCost)
    }

    @Test fun megapixelsUseActualReturnedAreaAndNeverRequestedSize() {
        val model = ImageModelMetadata("new-model", tariffs = listOf(ImageTariff("output_image", "megapixel", 0.11, 1.0, "USD")))
        val measured = attempt(body = """{"data":[{"width":1250,"height":800}]}""", metadata = model).record()
        assertEquals(0.11, measured.totalCost!!, 1e-12)
        assertNull(attempt(body = """{"data":[{}]}""", metadata = model, parameters = mapOf("size" to "1250x800")).record().totalCost)
    }

    @Test fun geminiKeepsModalitiesAndThinkingSeparateFromImageCount() {
        val body = """{"usageMetadata":{"promptTokensDetails":[{"modality":"TEXT","tokenCount":10}],"candidatesTokensDetails":[{"modality":"IMAGE","tokenCount":50},{"modality":"TEXT","tokenCount":4}],"thoughtsTokenCount":2},"candidates":[{"content":{"parts":[{"thought":true,"inlineData":{"mimeType":"image/png","data":"AQ=="}},{"inlineData":{"mimeType":"image/png","data":"AQ=="}}]}}]}"""
        val prices = listOf(ImageTariff("text_input", "token", 1.0, 1000.0, "USD"), ImageTariff("text_output", "token", 2.0, 1000.0, "USD"),ImageTariff("image_output", "token", 3.0, 1000.0, "USD"))
        val record = attempt(ImageProviderKind.GEMINI, body, ImageModelMetadata("new-model", tariffs = prices)).record()
        assertEquals(0.172, record.totalCost!!, 1e-12)
        assertEquals(1.0, record.meters!!.single { it.component == UsageMeterComponent.IMAGES }.quantity!!, 0.0)
        assertEquals(6.0, record.meters!!.single { it.component == UsageMeterComponent.TEXT_OUTPUT }.quantity!!, 0.0)
    }

    @Test fun missingOrInvalidNumbersStayUnknownIncludingFailedAttempts() {
        for (value in listOf("-1", "\"NaN\"", "\"Infinity\"", "true")) {
            assertNull(attempt(body = """{"usage":{"cost":$value}}""").record().totalCost)
        }
        val model = ImageModelMetadata("new-model", tariffs = listOf(ImageTariff("output_image", "image", 0.1, 1.0, "USD")))
        assertNull(attempt(body = "{}", metadata = model).copy(httpStatus = 400).record().totalCost)
        assertEquals(500, attempt(body = "{}").copy(httpStatus = 500).record().httpStatus)
    }
}
