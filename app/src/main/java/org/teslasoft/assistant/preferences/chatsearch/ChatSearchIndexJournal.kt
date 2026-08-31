package org.teslasoft.assistant.preferences.chatsearch

import android.content.Context
import org.teslasoft.assistant.preferences.SecurePrefs

class ChatSearchIndexJournal private constructor(context: Context) {
    private val prefs = SecurePrefs.get(context.applicationContext, FILE_NAME)

    fun record(chatId: String, revision: String): Boolean =
        prefs.edit().putString(chatId, revision).commit()

    fun revision(chatId: String): String? = try { prefs.getString(chatId, null) } catch (_: Exception) { null }

    fun isDirty(chatId: String, revision: String?): Boolean = revision(chatId)?.let { it != revision } ?: false

    fun clearExact(chatId: String, revision: String): Boolean {
        if (this.revision(chatId) != revision) return false
        return prefs.edit().remove(chatId).commit()
    }

    fun entries(): Map<String, String> = try {
        prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
    } catch (_: Exception) { emptyMap() }

    companion object {
        private const val FILE_NAME = "chat_search_journal"
        fun get(context: Context) = ChatSearchIndexJournal(context)
    }
}
