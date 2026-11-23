package org.teslasoft.assistant.ui.chat

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject

/** Taking a receipt transfers its ownership away from the running reply.
 * Keep the whole handoff (including IO, row attachment and retry ownership)
 * alive when deletion cancels that reply midway through it. */
internal object ReplyUsageCompletion {
    /** Only this request's profile can price its reported model. Keep the
     * catalog inputs independent of profile edits during the price wait. */
    fun pricingEndpoint(endpoint: ApiEndpointObject?, expectedHost: String): ApiEndpointObject? =
        endpoint?.takeIf { it.host == expectedHost }?.let {
            ApiEndpointObject(it.label, it.host, it.apiKey, authType = it.authType,
                id = it.id, providerDiscoveryPath = it.providerDiscoveryPath, identity = it.identity)
        }

    suspend fun <T> finish(block: suspend () -> T): T = withContext(NonCancellable) { block() }

    /** The price loader belongs to the screen and may already have been
     * cancelled on close. Its cancellation cannot discard a paid receipt. */
    suspend fun <T> awaitPricing(pricing: Deferred<T>?, timeoutMs: Long): T? = try {
        withTimeoutOrNull(timeoutMs) { pricing?.await() }
    } catch (e: CancellationException) {
        if (!currentCoroutineContext().isActive) throw e
        null
    }
}
