package org.teslasoft.assistant.preferences

/** User bookmarks belong to the message record, independently of reply versions. */
object MessageBookmarks {
    const val KEY = "bookmark"

    fun isBookmarked(message: Map<String, *>): Boolean =
        message[KEY] == true || message[KEY]?.toString() == "true"

    fun set(message: MutableMap<String, Any>, bookmarked: Boolean) {
        if (bookmarked) message[KEY] = true else message.remove(KEY)
    }

    /** The storage callback must roll back failed writes before returning false. */
    fun toggleAndSave(message: MutableMap<String, Any>, save: (bookmarkChanged: Boolean) -> Boolean): Boolean {
        val previous = message[KEY]
        set(message, !isBookmarked(message))
        if (save(true)) return true
        if (previous == null) message.remove(KEY) else message[KEY] = previous
        return false
    }
}
