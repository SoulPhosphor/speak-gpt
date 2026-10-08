package org.teslasoft.assistant.imagegen

import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.usage.*
import kotlinx.coroutines.runBlocking

class ImageUsageLogTest {
    @Test fun negativeMeterValuesAndNonpositivePriceBasesPreserveTheOriginalLog() {
        val valid = UsageLog.encode(UsageLog.EMPTY.putRequest(entry()))
        val validMeters = UsageMeterCodec.encode(listOf(UsageMeter(UsageMeterComponent.IMAGES, UsageMeterUnit.IMAGE,
            1.0, UsageQuantitySource.PROVIDER_REPORTED, priceAmount = 0.1, priceQuantity = 1.0, currency = "USD", cost = 0.1)))
        val receipt = entry(ImageUsageReceipt(requestId = "real-id", images = 1.0, amount = 0.12, currency = "USD"))
        val invalid = listOf("quantity" to "-1", "priceAmount" to "-0.1", "cost" to "-5",
            "priceQuantity" to "-1", "priceQuantity" to "0", "priceQuantity" to "1e-999",
            "quantity" to "-1e-999", "priceAmount" to "-1e-999", "cost" to "-1e-999")
        for ((key, value) in invalid) {
            val meters = validMeters.deepCopy()
            meters.first().asJsonObject.add(key, com.google.gson.JsonParser.parseString(value))
            assertNull(UsageMeterCodec.decode(meters))
            val root = com.google.gson.JsonParser.parseString(valid).asJsonObject
            root.getAsJsonArray("entries").first().asJsonObject.getAsJsonObject("record").add("meters", meters)
            val original = root.toString()
            assertNull(UsageLog.decode(original))
            val restored = UsageLog.decode(UsageLog.encode(UsageLog.requestUpdate(original, receipt, existingOnly = true)!!))!!
            assertEquals(original, restored.quarantinedLog)
            assertEquals("real-id", restored.entries.single().record.requestId)
            assertEquals(0.12, restored.entries.single().record.totalCost!!, 0.0)
        }
    }

    @Test fun zeroMeterAmountsAndPositiveFractionalPriceBasesRemainReadable() {
        val meters = listOf(UsageMeter(UsageMeterComponent.IMAGES, UsageMeterUnit.IMAGE,
            0.0, UsageQuantitySource.PROVIDER_REPORTED, priceAmount = 0.0, priceQuantity = 0.5, currency = "USD", cost = 0.0))
        assertEquals(meters, UsageMeterCodec.decode(UsageMeterCodec.encode(meters)))
        val saved = entry().let { it.copy(record = it.record.copy(meters = meters, totalCost = 0.0)) }
        val restored = UsageLog.decode(UsageLog.encode(UsageLog.EMPTY.putRequest(saved)))!!
        assertNull(restored.quarantinedLog)
        assertEquals(meters, restored.entries.single().record.meters)
        assertEquals(0.0, MeteredUsageAccounting.aggregate(restored.entries.map { it.record })!!.single().cost, 0.0)
    }

    @Test fun negativeRecordCountsPricesAndChargesAreQuarantinedToo() {
        val valid = UsageLog.encode(UsageLog.EMPTY.putRequest(entry()))
        val counts = listOf("inputTokens", "outputTokens", "totalTokens", "cachedInputTokens", "cacheWriteInputTokens")
        val charges = listOf("inputPricePerToken", "outputPricePerToken", "cachedInputPricePerToken", "cacheWriteInputPricePerToken",
            "inputCost", "outputCost", "uncachedInputCost", "cachedInputCost", "totalCost", "reportedChargeAmount")
        val invalid = counts.map { it to "-1" } + charges.flatMap { listOf(it to "-1", it to "-1e-999") } +
            listOf("reportedChargeDecimal" to "\"-0.12\"", "reportedChargeDecimal" to "\"unreadable\"")
        for ((key, value) in invalid) {
            val root = com.google.gson.JsonParser.parseString(valid).asJsonObject
            root.getAsJsonArray("entries").first().asJsonObject.getAsJsonObject("record")
                .add(key, com.google.gson.JsonParser.parseString(value))
            val original = root.toString()
            assertNull(UsageLog.decode(original))
            val restored = UsageLog.decode(UsageLog.encode(UsageLog.requestUpdate(original, entry())!!))!!
            assertEquals(original, restored.quarantinedLog)
        }
        val zero = com.google.gson.JsonParser.parseString(valid).asJsonObject
        val record = zero.getAsJsonArray("entries").first().asJsonObject.getAsJsonObject("record")
        (counts + charges).forEach { record.addProperty(it, 0) }
        record.addProperty("reportedChargeDecimal", "0.000")
        assertNotNull(UsageLog.decode(zero.toString()))
    }

    @Test fun malformedScalarFieldsAreQuarantinedInsteadOfBeingCoerced() {
        val valid = UsageLog.encode(UsageLog.EMPTY.putRequest(entry()))
        val originals = mutableListOf<String>()
        for ((key, bad) in listOf("seeded" to "\"true\"", "seeded" to "null", "quarantinedLog" to "7")) {
            val root = com.google.gson.JsonParser.parseString(valid).asJsonObject
            root.add(key, com.google.gson.JsonParser.parseString(bad))
            originals += root.toString()
        }
        for ((key, bad) in listOf("category" to "\"future\"", "category" to "7", "function" to "\"future\"",
                "function" to "7", "messageId" to "7", "recordedAtMs" to "1.5", "recordedAtMs" to "\"4\"",
                "recordedAtMs" to "9223372036854775808")) {
            val root = com.google.gson.JsonParser.parseString(valid).asJsonObject
            root.getAsJsonArray("entries").first().asJsonObject.add(key, com.google.gson.JsonParser.parseString(bad))
            originals += root.toString()
        }
        for ((key, bad) in listOf("inputTokens" to "1.5", "inputTokens" to "2147483648", "inputTokens" to "\"3\"",
                "requestStartedAtMs" to "1.5", "requestStartedAtMs" to "9223372036854775808", "totalCost" to "1e999",
                "totalCost" to "true", "totalCost" to "\"3\"", "requestId" to "7")) {
            val root = com.google.gson.JsonParser.parseString(valid).asJsonObject
            root.getAsJsonArray("entries").first().asJsonObject.getAsJsonObject("record").add(key, com.google.gson.JsonParser.parseString(bad))
            originals += root.toString()
        }
        for (original in originals) {
            assertNull(UsageLog.decode(original))
            val recovered = UsageLog.decode(UsageLog.encode(UsageLog.requestUpdate(original, entry())!!))!!
            assertEquals(original, recovered.quarantinedLog)
        }
        val legacy = com.google.gson.JsonParser.parseString(valid).asJsonObject
        val logged = legacy.getAsJsonArray("entries").first().asJsonObject
        logged.remove("recordedAtMs")
        logged.addProperty("category", "attachments")
        assertEquals(UsageCategory.SUMMARIZING, UsageLog.decode(legacy.toString())!!.entries.single().category)
        assertEquals(0L, UsageLog.decode(legacy.toString())!!.entries.single().recordedAtMs)
    }

    @Test fun unreadableNestedMetersQuarantineTheWholeLogBeforeReceiptRecovery() {
        val valid = UsageLog.encode(UsageLog.EMPTY.putRequest(entry()))
        val validMeters = UsageMeterCodec.encode(listOf(UsageMeter(UsageMeterComponent.IMAGES, UsageMeterUnit.IMAGE,
            1.0, UsageQuantitySource.PROVIDER_REPORTED)))
        val malformed = mutableListOf<com.google.gson.JsonElement>(com.google.gson.JsonNull.INSTANCE,
            com.google.gson.JsonObject(), com.google.gson.JsonPrimitive("not an array"), com.google.gson.JsonPrimitive(7))
        for (bad in listOf("null", "7", "[]", "{}", """{"component":"future","unit":"image"}""",
                """{"component":"images","unit":"future"}""")) {
            val meters = validMeters.deepCopy()
            meters.add(com.google.gson.JsonParser.parseString(bad))
            malformed.add(meters)
        }
        for (key in listOf("quantity", "priceAmount", "priceQuantity", "cost", "currency", "quantitySource")) {
            for (bad in listOf("true", "{}", "[]", "1e999")) {
                val meters = validMeters.deepCopy()
                meters.first().asJsonObject.add(key, com.google.gson.JsonParser.parseString(bad))
                malformed.add(meters)
            }
        }
        for (key in listOf("quantity", "priceAmount", "priceQuantity", "cost", "quantitySource")) {
            val meters = validMeters.deepCopy()
            meters.first().asJsonObject.addProperty(key, "unreadable")
            malformed.add(meters)
        }
        val receipt = entry(ImageUsageReceipt(requestId = "real-id", images = 1.0, amount = 0.12, currency = "USD"))
        for (meters in malformed) {
            assertNull(UsageMeterCodec.decode(meters))
            val root = com.google.gson.JsonParser.parseString(valid).asJsonObject
            root.getAsJsonArray("entries").first().asJsonObject.getAsJsonObject("record").add("meters", meters)
            val original = root.toString()
            assertNull(UsageLog.decode(original))
            val restored = UsageLog.decode(UsageLog.encode(UsageLog.requestUpdate(original, receipt, existingOnly = true)!!))!!
            assertEquals(original, restored.quarantinedLog)
            assertEquals("real-id", restored.entries.single().record.requestId)
            assertEquals(0.12, restored.entries.single().record.totalCost!!, 0.0)
        }
        val legacy = com.google.gson.JsonParser.parseString(valid).asJsonObject
        legacy.getAsJsonArray("entries").first().asJsonObject.getAsJsonObject("record").remove("meters")
        assertNull(UsageLog.decode(legacy.toString())!!.entries.single().record.meters)
        val meterRoot = com.google.gson.JsonParser.parseString(valid).asJsonObject
        meterRoot.getAsJsonArray("entries").first().asJsonObject.getAsJsonObject("record").add("meters", validMeters)
        assertEquals(1, UsageLog.decode(meterRoot.toString())!!.entries.single().record.meters!!.size)
    }

    @Test fun unsupportedUsageLogVersionsPreserveAllOriginalFields() {
        val valid = UsageLog.encode(UsageLog.EMPTY.putRequest(entry()))
        val originals = mutableListOf<String>()
        for (version in listOf("2", "0", "-1", "1.5", "1e30", "null", "\"1\"", "true", "{}", "[]")) {
            val root = com.google.gson.JsonParser.parseString(valid).asJsonObject
            root.add("version", com.google.gson.JsonParser.parseString(version))
            root.addProperty("futureEvidence", "keep this unchanged")
            originals += root.toString()
        }
        val missing = com.google.gson.JsonParser.parseString(valid).asJsonObject
        missing.remove("version")
        originals += missing.toString()
        for (original in originals) {
            assertNull(UsageLog.decode(original))
            val restored = UsageLog.decode(UsageLog.encode(UsageLog.requestUpdate(original, entry())!!))!!
            assertEquals(original, restored.quarantinedLog)
            assertEquals(1, restored.entries.size)
        }
        for (version in listOf("1", "1.0", "1e0")) {
            val root = com.google.gson.JsonParser.parseString(valid).asJsonObject
            root.add("version", com.google.gson.JsonParser.parseString(version))
            assertNotNull(UsageLog.decode(root.toString()))
        }
    }

    @Test fun requiredUsageRecordStringsAreValidatedBeforeRecovery() {
        val valid = UsageLog.encode(UsageLog.EMPTY.putRequest(entry()))
        val originals = mutableListOf<String>()
        for (key in listOf("model", "provider", "source")) {
            for (bad in listOf("null", "7", "true", "{}", "[]")) {
                val root = com.google.gson.JsonParser.parseString(valid).asJsonObject
                root.getAsJsonArray("entries").first().asJsonObject.getAsJsonObject("record")
                    .add(key, com.google.gson.JsonParser.parseString(bad))
                originals += root.toString()
            }
        }
        for (key in listOf("model", "provider")) {
            val root = com.google.gson.JsonParser.parseString(valid).asJsonObject
            root.getAsJsonArray("entries").first().asJsonObject.getAsJsonObject("record").remove(key)
            originals += root.toString()
        }
        val empty = com.google.gson.JsonParser.parseString(valid).asJsonObject
        empty.getAsJsonArray("entries").first().asJsonObject.add("record", com.google.gson.JsonObject())
        originals += empty.toString()
        val receipt = entry(ImageUsageReceipt(requestId = "real-id", images = 1.0, amount = 0.12, currency = "USD"))
        for (original in originals) {
            assertNull(UsageLog.decode(original))
            val restored = UsageLog.decode(UsageLog.encode(UsageLog.requestUpdate(original, receipt, existingOnly = true)!!))!!
            assertEquals(original, restored.quarantinedLog)
            assertEquals(1, TokenUsageAccounting.aggregate(restored.entries.map { it.record }).groups.size)
            assertEquals("real-id", restored.entries.single().record.requestId)
        }
        val legacy = com.google.gson.JsonParser.parseString(valid).asJsonObject
        legacy.getAsJsonArray("entries").first().asJsonObject.getAsJsonObject("record").remove("source")
        val decoded = UsageLog.decode(legacy.toString())!!
        assertEquals(TokenCountSource.ESTIMATED_CL100K.storedValue, decoded.entries.single().record.source)
        assertEquals(1, TokenUsageAccounting.aggregate(decoded.entries.map { it.record }).groups.size)
    }

    @Test fun malformedEntriesQuarantineTheWholeLogInsteadOfSilentlyDiscardingEvidence() {
        val valid = UsageLog.encode(UsageLog.EMPTY.putRequest(entry()))
        val receipt = entry(ImageUsageReceipt(requestId = "real-id", images = 1.0, amount = 0.12, currency = "USD"))
        val originals = mutableListOf("{}", """{"entries":{}}""")
        for (bad in listOf("null", "7", """{}""", """{"id":"image-1:image-generation","record":"wrong"}""",
                """{"id":"","record":{}}""", """{"id":7,"record":{}}""")) {
            val root = com.google.gson.JsonParser.parseString(valid).asJsonObject
            root.getAsJsonArray("entries").add(com.google.gson.JsonParser.parseString(bad))
            originals += root.toString()
        }
        val duplicated = com.google.gson.JsonParser.parseString(valid).asJsonObject
        duplicated.getAsJsonArray("entries").add(duplicated.getAsJsonArray("entries").first().deepCopy())
        originals += duplicated.toString()
        for (original in originals) {
            assertNull(UsageLog.decode(original))
            assertEquals(original, UsageLog.recover(original).quarantinedLog)
            val received = UsageLog.requestUpdate(original, receipt, existingOnly = true)!!
            val restored = UsageLog.decode(UsageLog.encode(received))!!
            assertEquals(original, restored.quarantinedLog)
            assertEquals(1, restored.entries.size)
            assertEquals("real-id", restored.entries.single().record.requestId)
            assertEquals(0.12, restored.entries.single().record.totalCost!!, 0.0)
        }
        assertNotNull(UsageLog.decode(valid))
        assertNotNull(UsageLog.decode(UsageLog.encode(UsageLog.EMPTY)))
    }

    @Test fun malformedLogsArePreservedThroughNewEntriesReceiptsAndSeeding() {
        for (original in listOf("{broken", UsageLog.encode(UsageLog.EMPTY).dropLast(1))) {
            val initial = UsageLog.requestUpdate(original, entry())!!
            assertEquals(original, initial.quarantinedLog)
            val seeded = UsageLog.decode(UsageLog.encode(initial))!!.seed(emptyList(), 456)
            val appended = seeded.append(listOf(entry().copy(id = "image-2:image-generation")))
            val received = entry(ImageUsageReceipt(requestId = "real-id", images = 1.0, amount = 0.12, currency = "USD"))
            val updated = UsageLog.requestUpdate(UsageLog.encode(appended), received, existingOnly = true)!!
            val restored = UsageLog.decode(UsageLog.encode(updated))!!
            assertEquals(original, restored.quarantinedLog)
            assertTrue(restored.seeded)
            assertEquals(2, restored.entries.size)
            assertEquals(0.12, restored.entries.single { it.id == received.id }.record.totalCost!!, 0.0)
            assertEquals("real-id", restored.entries.single { it.id == received.id }.record.requestId)
        }
    }

    @Test fun corruptReceiptLogsRecoverAfterFailedCommitsButValidDeletionsStayDeleted() = runBlocking {
        val original = "{unreadable old usage"
        var stored = original
        var writes = 0
        val pauses = mutableListOf<Long>()
        val received = entry(ImageUsageReceipt(requestId = "real-id", images = 1.0, amount = 0.12, currency = "USD"))
        assertTrue(ImageUsagePersistence.commit(save = {
            val updated = UsageLog.requestUpdate(stored, received, existingOnly = true)!!
            if (++writes < 3) {
                assertEquals(original, stored)
                ImageUsageSaveResult.RETRY
            } else {
                stored = UsageLog.encode(updated)
                ImageUsageSaveResult.SAVED
            }
        }, pause = { pauses += it }))
        assertEquals(listOf(1_000L, 2_000L), pauses)
        val restored = UsageLog.decode(stored)!!
        assertEquals(original, restored.quarantinedLog)
        assertEquals(1, restored.entries.size)
        assertEquals(0.12, restored.entries.single().record.totalCost!!, 0.0)
        assertNull(UsageLog.requestUpdate(UsageLog.encode(restored.copy(entries = emptyList())), received, existingOnly = true))
        assertNull(UsageLog.requestUpdate(UsageLog.encode(UsageLog.EMPTY), received, existingOnly = true))
    }

    @Test fun fetchedChargeIsRetriedUntilCommitSucceedsWithoutDuplicatingIt() = runBlocking {
        val received = entry(ImageUsageReceipt(requestId = "real-id", images = 1.0, amount = 0.12, currency = "USD"))
        var state = UsageLog.EMPTY.putRequest(entry())
        var writes = 0
        val pauses = mutableListOf<Long>()
        assertTrue(ImageUsagePersistence.commit(save = {
            writes++
            if (writes < 3) ImageUsageSaveResult.RETRY else {
                state = state.putRequest(received, existingOnly = true)
                ImageUsageSaveResult.SAVED
            }
        }, pause = { pauses += it }))
        assertEquals(listOf(1_000L, 2_000L), pauses)
        val restored = UsageLog.decode(UsageLog.encode(state))!!
        assertEquals(1, restored.entries.size)
        assertEquals(0.12, restored.entries.single().record.totalCost!!, 0.0)
        assertEquals("real-id", restored.entries.single().record.requestId)
    }

    @Test fun deletedDestinationStopsPersistenceRetry() = runBlocking {
        var writes = 0
        assertFalse(ImageUsagePersistence.commit(save = {
            writes++
            if (writes == 1) ImageUsageSaveResult.RETRY else ImageUsageSaveResult.REMOVED
        }, pause = {}))
        assertEquals(2, writes)
    }

    @Test fun longStorageOutageRetainsEvidenceAndCapsOnlyThePause() = runBlocking {
        var writes = 0
        val pauses = mutableListOf<Long>()
        assertTrue(ImageUsagePersistence.commit(save = {
            if (++writes < 9) ImageUsageSaveResult.RETRY else ImageUsageSaveResult.SAVED
        }, pause = { pauses += it }))
        assertEquals(9, writes)
        assertEquals(30_000L, pauses.last())
        assertTrue(pauses.all { it <= 30_000L })
    }

    private fun entry(receipt: ImageUsageReceipt = ImageUsageReceipt()): UsageLogEntry {
        val attempt = ImageUsageAttempt(ImageProviderKind.COMPATIBLE, "future-model", "Provider", "https://example/v1", 123,
            mapOf("resolution" to "large"), null, httpStatus = 200, receipt = receipt)
        return UsageLogEntry("image-1:image-generation", UsageCategory.IMAGE_GENERATION, "image-1", 123, attempt.record())
    }

    @Test fun delayedReceiptUpdatesOneRequestAndSurvivesEncodingAndMessageDeletion() {
        val started = UsageLog.EMPTY.putRequest(entry())
        val finished = started.putRequest(entry(ImageUsageReceipt(requestId = "real-id", images = 1.0, amount = 0.12, currency = "USD")), existingOnly = true)
        val restored = UsageLog.decode(UsageLog.encode(finished))!!
        assertEquals(1, restored.entries.size)
        assertEquals(UsageCategory.IMAGE_GENERATION, restored.entries.single().category)
        assertEquals(0.12, restored.entries.single().record.totalCost!!, 0.0)
        assertEquals("real-id", restored.entries.single().record.requestId)
        assertTrue(restored.entries.single().record.requestParameters!!.contains("large"))
        assertEquals(1, restored.seed(emptyList(), 456).entries.size)
        val meters = restored.entries.single().record.meters!!
        assertEquals(UsageMeterUnit.IMAGE, meters.single().unit)
        assertEquals(1.0, meters.single().quantity!!, 0.0)
    }

    @Test fun receiptCannotRecreateAnEntryDeletedWithItsChat() {
        val empty = UsageLog.EMPTY
        assertSame(empty, empty.putRequest(entry(), existingOnly = true))
        assertTrue(empty.entries.isEmpty())
    }

    @Test fun unknownChargeIsRetainedAndMakesAnAggregateUnknown() {
        val unknown = entry()
        val paid = entry(ImageUsageReceipt(images = 1.0, amount = 0.2, currency = "USD")).copy(id = "image-2")
        val state = UsageLog.EMPTY.putRequest(unknown).putRequest(paid)
        assertEquals(2, UsageLog.decode(UsageLog.encode(state))!!.entries.size)
        val summary = TokenUsageAccounting.aggregate(state.entries.map { it.record })
        assertEquals(2, summary.groups.sumOf { it.recordCount })
        assertTrue(summary.hasUnknownCost)
    }
}
