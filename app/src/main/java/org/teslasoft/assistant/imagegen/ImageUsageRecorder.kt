package org.teslasoft.assistant.imagegen

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.usage.UsageCategory
import org.teslasoft.assistant.usage.UsageLogEntry
import org.teslasoft.assistant.usage.UsageLogStore

/** Independent of an activity or cancelled image job. Only GET receipts, never generation retries. */
object ImageUsageRecorder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = ImageMetadataHttp()
    private val destinations = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun rename(oldChatId: String, newChatId: String) {
        synchronized(ChatPreferences.CHAT_LIST_LOCK) {
            destinations.forEach { (imageId, chatId) ->
                if (chatId == oldChatId) destinations.replace(imageId, oldChatId, newChatId)
            }
        }
    }

    private fun save(context: Context, imageId: String, entry: UsageLogEntry, existingOnly: Boolean): Boolean =
        synchronized(ChatPreferences.CHAT_LIST_LOCK) {
            val chatId = destinations[imageId] ?: return@synchronized false
            val chats = ChatPreferences.getChatPreferences().getChatListResult(context, includeFirstMessage = false)
            if (chats.chats.none { ChatPreferences.storedChatId(it) == chatId }) return@synchronized false
            UsageLogStore.putRequest(Preferences.getPreferences(context, chatId), entry, existingOnly)
        }

    fun record(context: Context, chatId: String, imageId: String, endpoint: ApiEndpointObject, attempt: ImageUsageAttempt) {
        if (chatId.isBlank()) return
        val app = context.applicationContext
        destinations[imageId] = chatId
        val entry = UsageLogEntry("$imageId:image-generation", UsageCategory.IMAGE_GENERATION,
            imageId, attempt.startedAtMs, attempt.record())
        // Commit received evidence before image decoding/download/save can fail or the app can close.
        save(app, imageId, entry, existingOnly = false)
        if (!attempt.finalized) return
        val id = when (attempt.kind) {
            ImageProviderKind.OPENROUTER -> attempt.receipt.generationId
            ImageProviderKind.NANOGPT -> attempt.receipt.requestId
            else -> null
        } ?: run { destinations.remove(imageId); return }
        // A receipt also resolves the serving provider; fetch even when the response already reports cost.
        scope.launch {
            try {
            val base = ImageApiRoutes.base(endpoint).toHttpUrl()
            val url = when (attempt.kind) {
                ImageProviderKind.OPENROUTER -> base.newBuilder().addPathSegment("generation")
                    .addQueryParameter("id", id).build().toString()
                ImageProviderKind.NANOGPT -> base.newBuilder().addPathSegment("usage")
                    .addPathSegment("requests").addPathSegment(id).build().toString()
                else -> return@launch
            }
            var waitMs = if (attempt.kind == ImageProviderKind.NANOGPT) 2000L else 1500L
            repeat(3) {
                delay(waitMs)
                val response = runCatching { http.result(url, endpoint) }.getOrNull()
                if (response?.status == 200) {
                    val billing = ImageUsageParser.billing(attempt.kind, response.body, id) ?: return@launch
                    save(app, imageId, entry.copy(record = attempt.record(billing)), existingOnly = true)
                    return@launch
                }
                if (response != null && response.status !in setOf(404, 429, 503)) return@launch
                // Receipt APIs require respecting the complete Retry-After delay. A very
                // long delay ends this bounded lookup, preserving the ID and unknown cost.
                val requested = ImageBillingRetry.delayMs(response?.retryAfter)
                if (requested != null && requested > 120_000L) return@launch
                val backoff = if (attempt.kind == ImageProviderKind.NANOGPT) 30_000L else waitMs * 2
                waitMs = maxOf(backoff, requested ?: 0L) + kotlin.random.Random.nextLong(1L, 1000L)
            }
            } finally { destinations.remove(imageId) }
        }
    }
}
