package org.teslasoft.assistant.preferences.generatedimages

import android.content.Context

/** Durable retry queue for chat ids whose generated image rows need rescanning. */
object GeneratedImageCatalogRebaseQueue {
    private const val HEALTH_FILE = "storage_health"
    private const val KEY_PENDING_CHAT_IDS = "generated_images.pending_rebase_chat_ids"

    fun enqueue(context: Context, chatIds: Set<String>): Boolean {
        if (chatIds.isEmpty()) return true
        val preferences = preferences(context)
        val pending = LinkedHashSet(preferences.getStringSet(KEY_PENDING_CHAT_IDS, emptySet()).orEmpty())
        pending.addAll(chatIds.filter(String::isNotBlank))
        return preferences.edit().putStringSet(KEY_PENDING_CHAT_IDS, pending).commit()
    }

    fun retry(context: Context): Boolean {
        val preferences = preferences(context)
        val pending = LinkedHashSet(preferences.getStringSet(KEY_PENDING_CHAT_IDS, emptySet()).orEmpty())
        if (pending.isEmpty()) return true
        val result = GeneratedImageCatalogStore.requeueBackfill(context, pending)
        if (!result.success) return false
        val latest = LinkedHashSet(preferences.getStringSet(KEY_PENDING_CHAT_IDS, emptySet()).orEmpty())
        latest.removeAll(pending)
        return preferences.edit().run {
            if (latest.isEmpty()) remove(KEY_PENDING_CHAT_IDS)
            else putStringSet(KEY_PENDING_CHAT_IDS, latest)
        }.commit()
    }

    internal fun pending(context: Context): Set<String> =
        preferences(context).getStringSet(KEY_PENDING_CHAT_IDS, emptySet()).orEmpty().toSet()

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(HEALTH_FILE, Context.MODE_PRIVATE)
}
