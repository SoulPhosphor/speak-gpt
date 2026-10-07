package org.teslasoft.assistant.imagegen

import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.usage.*
import kotlinx.coroutines.runBlocking

class ImageUsageLogTest {
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
