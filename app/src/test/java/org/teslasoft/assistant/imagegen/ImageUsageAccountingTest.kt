package org.teslasoft.assistant.imagegen

import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.usage.*

class ImageUsageAccountingTest {
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

    @Test fun servingProviderAndModelMustMatchFrozenMetadata() {
        val a = ImageServingMetadata("A", "a", emptyList(), listOf(ImageTariff("output_image", "image", 0.1, 1.0, "USD")))
        val b = a.copy(providerName = "B", providerSlug = "b", tariffs = listOf(ImageTariff("output_image", "image", 0.3, 1.0, "USD")))
        val request = attempt(body = """{"data":[{}]}""", metadata = ImageModelMetadata("new-model", endpointRecords = listOf(a,b)))
        assertNull(request.record().totalCost)
        assertEquals(0.3, request.record(ImageUsageReceipt(provider = "B")).totalCost!!, 0.0)
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
