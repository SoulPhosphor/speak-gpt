package org.teslasoft.assistant.ui.chat

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject

class ReplyUsageCompletionTest {
    @Test fun anEndpointSwitchDuringPricingCannotRepriceTheOldRequest() = runBlocking {
        withTimeout(5_000) {
            val original = ApiEndpointObject("A", "https://a.invalid/", "fictional-key-a",
                id = "profile-a", providerDiscoveryPath = "/original",
                identity = ApiEndpointObject.IDENTITY_OPENROUTER)
            val waiting = CompletableDeferred<Unit>()
            val price = CompletableDeferred<String>()
            val used = CompletableDeferred<ApiEndpointObject>()
            val job = launch {
                ReplyUsageCompletion.finish {
                    val owner = ReplyUsageCompletion.pricingEndpoint(original, "https://a.invalid/")!!
                    waiting.complete(Unit)
                    ReplyUsageCompletion.awaitPricing(price, 2_000L)
                    used.complete(owner)
                }
            }
            waiting.await()
            original.host = "https://b.invalid/"
            original.apiKey = "fictional-key-b"
            original.providerDiscoveryPath = "/edited"
            original.identity = ApiEndpointObject.IDENTITY_GENERIC
            price.complete("loaded")
            job.join()
            assertEquals("https://a.invalid/", used.await().host)
            assertEquals("fictional-key-a", used.await().apiKey)
            assertEquals("/original", used.await().providerDiscoveryPath)
            assertTrue(used.await().isOpenRouterRouting())
            assertNull(ReplyUsageCompletion.pricingEndpoint(original, "https://a.invalid/"))
        }
    }

    @Test fun aPriceLoaderCancelledWithTheScreenCannotDiscardKnownUsage() = runBlocking {
        val pricing = CompletableDeferred<String>().apply { cancel() }
        val receipt = ReplyUsageCompletion.finish {
            val price = ReplyUsageCompletion.awaitPricing(pricing, 500L)
            27 to price
        }
        assertEquals(27, receipt.first)
        assertNull(receipt.second)
    }

    @Test fun cancellationDuringPricingStillAttachesAndTransfersTheFailedAppendToRetry() = runBlocking {
        withTimeout(5_000) {
            val pricingStarted = CompletableDeferred<Unit>()
            val releasePricing = CompletableDeferred<Unit>()
            val handedOff = CompletableDeferred<List<String>>()
            val attached = mutableListOf<String>()
            val job = launch {
                ReplyUsageCompletion.finish {
                    pricingStarted.complete(Unit)
                    releasePricing.await()
                    val receipt = "owned paid request"
                    val committed = withContext(Dispatchers.IO) { false }
                    attached += receipt
                    if (!committed) handedOff.complete(attached.toList())
                }
            }
            pricingStarted.await()
            job.cancel()
            releasePricing.complete(Unit)
            job.join()
            assertEquals(listOf("owned paid request"), attached)
            assertEquals(attached, handedOff.await())
        }
    }

    @Test fun cancellationDuringTheIoAppendStillFinishesTheHandoffOnReturn() = runBlocking {
        withTimeout(5_000) {
            val appending = CompletableDeferred<Unit>()
            val releaseAppend = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val job = launch {
                ReplyUsageCompletion.finish {
                    withContext(Dispatchers.IO) {
                        appending.complete(Unit)
                        releaseAppend.await()
                    }
                    finished.complete(Unit)
                }
            }
            appending.await()
            job.cancel()
            releaseAppend.complete(Unit)
            job.join()
            assertTrue(finished.isCompleted)
        }
    }
}
