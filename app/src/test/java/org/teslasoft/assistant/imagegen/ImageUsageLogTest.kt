package org.teslasoft.assistant.imagegen

import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.usage.*

class ImageUsageLogTest {
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
