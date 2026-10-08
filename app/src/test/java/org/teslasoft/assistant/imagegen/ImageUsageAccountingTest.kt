package org.teslasoft.assistant.imagegen

import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.usage.*

class ImageUsageAccountingTest {
    @Test fun unresolvedOutputIncludesThoughtsWithoutInventingAModalitySplit() {
        for ((rootKey, totalKey, splitKey, thoughtsKey, countKey) in listOf(
            listOf("usageMetadata", "candidatesTokenCount", "candidatesTokensDetails", "thoughtsTokenCount", "tokenCount"),
            listOf("usage", "total_output_tokens", "output_tokens_by_modality", "total_thought_tokens", "tokens"))) {
            for (details in listOf(null, "null", "{}", """[{"modality":"audio","$countKey":54}]""")) {
                val root = com.google.gson.JsonObject()
                val usage = com.google.gson.JsonObject()
                usage.addProperty(totalKey, 54)
                usage.addProperty(thoughtsKey, 2)
                if (rootKey == "usage") usage.addProperty("total_input_tokens", 0)
                if (details != null) usage.add(splitKey, com.google.gson.JsonParser.parseString(details))
                root.add(rootKey, usage)
                val record = attempt(ImageProviderKind.GEMINI, root.toString()).record()
                assertNull(record.totalCost)
                assertEquals(56.0, record.meters!!.single { it.component == UsageMeterComponent.OUTPUT }.quantity!!, 0.0)
                val log = UsageLog.EMPTY.append(listOf(UsageLog.entry(UsageCategory.IMAGE_GENERATION, "thoughts", record, 1L)))
                assertEquals(56.0, UsageLog.decode(UsageLog.encode(log))!!.entries.single().record.meters!!
                    .single { it.component == UsageMeterComponent.OUTPUT }.quantity!!, 0.0)
                usage.addProperty(totalKey, 9007199254740992L)
                usage.addProperty(thoughtsKey, 1)
                assertNull(attempt(ImageProviderKind.GEMINI, root.toString()).record().meters!!
                    .single { it.component == UsageMeterComponent.OUTPUT }.quantity)
            }
        }
    }

    @Test fun reconciledSplitsRejectInexactModalitySubtotals() {
        for ((rootKey, totalKey, splitKey, countKey) in listOf(
            listOf("usageMetadata", "candidatesTokenCount", "candidatesTokensDetails", "tokenCount"),
            listOf("usage", "total_output_tokens", "output_tokens_by_modality", "tokens"))) {
            val root = com.google.gson.JsonObject()
            val usage = com.google.gson.JsonObject()
            usage.addProperty(totalKey, 9007199254740994L)
            if (rootKey == "usage") usage.addProperty("total_input_tokens", 0)
            usage.add(splitKey, com.google.gson.JsonParser.parseString(
                """[{"modality":"text","$countKey":9007199254740992},{"modality":"text","$countKey":1},{"modality":"image","$countKey":1}]"""))
            root.add(rootKey, usage)
            val receipt = ImageUsageParser.response(ImageProviderKind.GEMINI, root.toString(), "request")
            assertFalse(receipt.usageVerified)
            assertEquals(9007199254740994.0, receipt.meters.single { it.component == UsageMeterComponent.OUTPUT }.quantity!!, 0.0)
            assertFalse(receipt.meters.any { it.component == UsageMeterComponent.TEXT_OUTPUT && it.quantity != null })
        }
    }

    @Test fun finiteInexactMeterAndConversationSumsStayUnknown() {
        assertNull(checkedUsageSum(listOf(9007199254740992.0, 1.0)))
        assertEquals(0.3, checkedUsageSum(listOf(0.1, 0.2))!!, 0.0)
        val rows = listOf(9007199254740992.0, 1.0).map { value ->
            TurnUsageRecord("model", "provider", source = TokenCountSource.PROVIDER_REPORTED.storedValue,
                totalCost = value, meters = listOf(UsageMeter(UsageMeterComponent.IMAGES,
                    UsageMeterUnit.IMAGE, value, UsageQuantitySource.PROVIDER_REPORTED, cost = value)))
        }
        val meters = MeteredUsageAccounting.aggregate(rows)!!.single()
        assertTrue(meters.hasUnknownQuantity)
        assertTrue(meters.hasUnknownCost)
        for (records in listOf(rows, listOf(rows[0], rows[1].copy(model = "other")))) {
            val summary = TokenUsageAccounting.aggregate(records)
            assertTrue(summary.hasUnknownCost)
            assertTrue(summary.totalCost.isFinite())
            assertEquals(9007199254740992.0, summary.totalCost, 0.0)
            assertTrue(TokenUsageAccounting.decodeSummary(TokenUsageAccounting.encodeSummary(summary)).hasUnknownCost)
        }
    }

    @Test fun unresolvedInteractionCacheSplitsKeepTheirReportedAggregate() {
        val body = """{"usage":{"total_input_tokens":10,"input_tokens_by_modality":[{"modality":"text","tokens":10}],"total_output_tokens":54,"output_tokens_by_modality":[{"modality":"image","tokens":50},{"modality":"text","tokens":4}],"total_cached_tokens":3},"steps":[{"type":"model_output","content":[{"type":"image","mime_type":"image/png","data":"Ag=="}]}]}"""
        val metadata = ImageModelMetadata("new-model", tariffs = listOf(
            ImageTariff("text_input", "token", 1.0, 1000.0, "USD"),
            ImageTariff("text_output", "token", 2.0, 1000.0, "USD"),
            ImageTariff("image_output", "token", 3.0, 1000.0, "USD")))
        for (bad in listOf(null, "null", "{}", "[]", """[{"modality":"audio","tokens":3}]""",
                """[{"modality":"text","tokens":"bad"}]""")) {
            val root = com.google.gson.JsonParser.parseString(body).asJsonObject
            if (bad != null) root.getAsJsonObject("usage").add("cached_tokens_by_modality", com.google.gson.JsonParser.parseString(bad))
            val request = attempt(ImageProviderKind.GEMINI, root.toString(), metadata)
            assertFalse(request.receipt.usageVerified)
            val record = request.record()
            assertNull(record.totalCost)
            assertEquals(3.0, record.meters!!.single { it.component == UsageMeterComponent.CACHED_INPUT }.quantity!!, 0.0)
            val log = UsageLog.EMPTY.append(listOf(UsageLog.entry(UsageCategory.IMAGE_GENERATION, "cached", record, 1L)))
            val restored = UsageLog.decode(UsageLog.encode(log))!!.entries.single().record
            assertEquals(3.0, restored.meters!!.single { it.component == UsageMeterComponent.CACHED_INPUT }.quantity!!, 0.0)
        }
    }

    @Test fun thoughtTokenAdditionRequiresAnExactlyRepresentableCombinedCount() {
        val native = """{"usageMetadata":{"promptTokenCount":10,"promptTokensDetails":[{"modality":"TEXT","tokenCount":10}],"candidatesTokenCount":9007199254740992,"candidatesTokensDetails":[{"modality":"TEXT","tokenCount":9007199254740992},{"modality":"IMAGE","tokenCount":0}],"thoughtsTokenCount":1},"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/png","data":"AQ=="}}]}}]}"""
        val interactions = """{"usage":{"total_input_tokens":10,"input_tokens_by_modality":[{"modality":"text","tokens":10}],"total_output_tokens":9007199254740992,"output_tokens_by_modality":[{"modality":"text","tokens":9007199254740992},{"modality":"image","tokens":0}],"total_thought_tokens":1,"total_cached_tokens":0},"steps":[{"type":"model_output","content":[{"type":"image","mime_type":"image/png","data":"Ag=="}]}]}"""
        val metadata = ImageModelMetadata("new-model", tariffs = listOf(
            ImageTariff("text_input", "token", 1.0, 1000.0, "USD"),
            ImageTariff("text_output", "token", 2.0, 1000.0, "USD"),
            ImageTariff("image_output", "token", 3.0, 1000.0, "USD")))
        for (body in listOf(native, interactions)) {
            val request = attempt(ImageProviderKind.GEMINI, body, metadata)
            assertFalse(request.receipt.usageVerified)
            assertNull(request.record().totalCost)
            assertNull(request.record().meters!!.single { it.component == UsageMeterComponent.TEXT_OUTPUT }.quantity)
            val representable = body.replace("\"thoughtsTokenCount\":1", "\"thoughtsTokenCount\":2")
                .replace("\"total_thought_tokens\":1", "\"total_thought_tokens\":2")
            val valid = attempt(ImageProviderKind.GEMINI, representable, metadata)
            assertTrue(valid.receipt.usageVerified)
            assertEquals(9007199254740994.0,
                valid.record().meters!!.single { it.component == UsageMeterComponent.TEXT_OUTPUT }.quantity!!, 0.0)
            assertNotNull(valid.record().totalCost)
        }
    }

    @Test fun geminiNativeSplitsMustFullyReconcileBeforeCalculatingCost() {
        val body = """{"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":54,"promptTokensDetails":[{"modality":"TEXT","tokenCount":10}],"candidatesTokensDetails":[{"modality":"IMAGE","tokenCount":50},{"modality":"TEXT","tokenCount":4}],"thoughtsTokenCount":2},"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/png","data":"AQ=="}}]}}]}"""
        val metadata = ImageModelMetadata("new-model", tariffs = listOf(
            ImageTariff("text_input", "token", 1.0, 1000.0, "USD"),
            ImageTariff("text_output", "token", 2.0, 1000.0, "USD"),
            ImageTariff("image_output", "token", 3.0, 1000.0, "USD")))
        assertEquals(0.172, attempt(ImageProviderKind.GEMINI, body, metadata).record().totalCost!!, 1e-12)
        for (key in listOf("promptTokensDetails", "candidatesTokensDetails")) {
            for (bad in listOf("null", "{}", "7", """{"modality":"AUDIO","tokenCount":1}""",
                    """{"modality":"TEXT","tokenCount":"bad"}""", """{"modality":"TEXT","tokenCount":1.00000000000000001}""")) {
                val root = com.google.gson.JsonParser.parseString(body).asJsonObject
                root.getAsJsonObject("usageMetadata").getAsJsonArray(key).add(com.google.gson.JsonParser.parseString(bad))
                val request = attempt(ImageProviderKind.GEMINI, root.toString(), metadata)
                assertFalse(request.receipt.usageVerified)
                val record = request.record()
                assertNull(record.totalCost)
                val component = if (key == "promptTokensDetails") UsageMeterComponent.INPUT else UsageMeterComponent.OUTPUT
                assertEquals(if (key == "promptTokensDetails") 10.0 else 56.0,
                    record.meters!!.single { it.component == component }.quantity!!, 0.0)
                root.addProperty("cost", 0.25)
                root.addProperty("currency", "USD")
                assertEquals(0.25, attempt(ImageProviderKind.GEMINI, root.toString(), metadata).record().totalCost!!, 0.0)
            }
        }
        val inconsistent = com.google.gson.JsonParser.parseString(body).asJsonObject
        inconsistent.getAsJsonObject("usageMetadata").addProperty("promptTokenCount", 20)
        assertNull(attempt(ImageProviderKind.GEMINI, inconsistent.toString(), metadata).record().totalCost)
        val duplicated = com.google.gson.JsonParser.parseString(body).asJsonObject
        duplicated.getAsJsonObject("usageMetadata").add("promptTokensDetails",
            com.google.gson.JsonParser.parseString("""[{"modality":"TEXT","tokenCount":5},{"modality":"TEXT","tokenCount":5}]"""))
        val merged = attempt(ImageProviderKind.GEMINI, duplicated.toString(), metadata)
        assertTrue(merged.receipt.usageVerified)
        assertEquals(10.0, merged.record().meters!!.single { it.component == UsageMeterComponent.TEXT_INPUT }.quantity!!, 0.0)
        assertEquals(0.172, merged.record().totalCost!!, 1e-12)
        val cached = com.google.gson.JsonParser.parseString(body).asJsonObject
        cached.getAsJsonObject("usageMetadata").addProperty("cachedContentTokenCount", 5)
        val cachedRecord = attempt(ImageProviderKind.GEMINI, cached.toString(), metadata).record()
        assertNull(cachedRecord.totalCost)
        assertEquals(5.0, cachedRecord.meters!!.single { it.component == UsageMeterComponent.CACHED_INPUT }.quantity!!, 0.0)
        for (bad in listOf("-1", "1.5")) {
            val malformedThoughts = com.google.gson.JsonParser.parseString(body).asJsonObject
            malformedThoughts.getAsJsonObject("usageMetadata").add("thoughtsTokenCount", com.google.gson.JsonParser.parseString(bad))
            val record = attempt(ImageProviderKind.GEMINI, malformedThoughts.toString(), metadata).record()
            assertNull(record.totalCost)
            assertNull(record.meters!!.single { it.component == UsageMeterComponent.TEXT_OUTPUT }.quantity)
        }
    }

    @Test fun openAiContradictoryAggregateCountsPreventPartialFrozenTotals() {
        val metadata = ImageModelMetadata("new-model", tariffs = listOf(
            ImageTariff("text_input", "token", 1.0, 1000.0, "USD"),
            ImageTariff("image_input", "token", 1.0, 1000.0, "USD"),
            ImageTariff("text_output", "token", 2.0, 1000.0, "USD"),
            ImageTariff("image_output", "token", 3.0, 1000.0, "USD")))
        val body = """{"usage":{"input_tokens":10,"input_tokens_details":{"text_tokens":10,"image_tokens":0},"output_tokens":54,"output_tokens_details":{"text_tokens":4,"image_tokens":50}},"data":[{}]}"""
        val valid = attempt(ImageProviderKind.OPENAI, body, metadata)
        assertTrue(valid.receipt.usageVerified)
        assertEquals(0.168, valid.record().totalCost!!, 1e-12)
        for ((key, count) in listOf("input_tokens" to 11, "output_tokens" to 55)) {
            val root = com.google.gson.JsonParser.parseString(body).asJsonObject
            root.getAsJsonObject("usage").addProperty(key, count)
            val inconsistent = attempt(ImageProviderKind.OPENAI, root.toString(), metadata)
            assertFalse(inconsistent.receipt.usageVerified)
            assertNull(inconsistent.record().totalCost)
        }
    }

    @Test fun overflowingMeterAndConversationAggregatesAreUnknownAndSerializable() {
        val meter = UsageMeter(UsageMeterComponent.IMAGES, UsageMeterUnit.IMAGE, Double.MAX_VALUE,
            UsageQuantitySource.PROVIDER_REPORTED, priceAmount = 1.0, priceQuantity = 1.0, currency = "USD", cost = Double.MAX_VALUE)
        val record = TurnUsageRecord("model", "provider", inputTokens = Int.MAX_VALUE, outputTokens = Int.MAX_VALUE,
            cachedInputTokens = 0, source = TokenCountSource.PROVIDER_REPORTED.storedValue,
            inputCost = Double.MAX_VALUE, outputCost = Double.MAX_VALUE, uncachedInputCost = Double.MAX_VALUE,
            cachedInputCost = 0.0, totalCost = Double.MAX_VALUE, meters = listOf(meter))
        val rows = listOf(record, record)
        val total = MeteredUsageAccounting.aggregate(rows)!!.single()
        assertTrue(total.hasUnknownQuantity)
        assertTrue(total.hasUnknownCost)
        assertTrue(total.quantity.isFinite())
        assertTrue(total.cost.isFinite())
        assertFalse(UsageMeterCodec.encodeTotals(listOf(total)).toString().contains("Infinity"))
        val grouped = TokenUsageAccounting.aggregate(rows)
        assertTrue(grouped.hasUnknownCost)
        assertTrue(grouped.hasUnknownInputTokens)
        assertTrue(grouped.hasUnknownOutputTokens)
        assertTrue(grouped.hasUnknownUncachedInputTokens)
        assertTrue(grouped.groups.single().hasUnknownInputCost)
        assertTrue(grouped.groups.single().hasUnknownOutputCost)
        assertTrue(grouped.totalCost.isFinite())
        assertTrue(grouped.totalInputTokens >= 0)
        val encoded = TokenUsageAccounting.encodeSummary(grouped)
        assertFalse(encoded.contains("Infinity"))
        assertTrue(TokenUsageAccounting.decodeSummary(encoded).hasUnknownCost)
        val acrossGroups = TokenUsageAccounting.aggregate(listOf(record, record.copy(model = "other")))
        assertTrue(acrossGroups.hasUnknownCost)
        assertTrue(acrossGroups.hasUnknownInputTokens)
        assertTrue(acrossGroups.totalCost.isFinite())
        assertFalse(TokenUsageAccounting.encodeSummary(acrossGroups).contains("Infinity"))
    }

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
        assertEquals(56.0, incomplete.meters!!.single { it.component == UsageMeterComponent.OUTPUT }.quantity!!, 0.0)
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
        val body = """{"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":54,"promptTokensDetails":[{"modality":"TEXT","tokenCount":10}],"candidatesTokensDetails":[{"modality":"IMAGE","tokenCount":50},{"modality":"TEXT","tokenCount":4}],"thoughtsTokenCount":2},"candidates":[{"content":{"parts":[{"thought":true,"inlineData":{"mimeType":"image/png","data":"AQ=="}},{"inlineData":{"mimeType":"image/png","data":"AQ=="}}]}}]}"""
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
