package org.teslasoft.assistant.preferences

/** User bookmarks belong to the message record, independently of reply versions. */
object MessageBookmarks {
    const val KEY = "bookmark"

    fun isBookmarked(message: Map<String, *>): Boolean =
        message[KEY] == true || message[KEY]?.toString() == "true"

    fun set(message: MutableMap<String, Any>, bookmarked: Boolean) {
        if (bookmarked) message[KEY] = true else message.remove(KEY)
    }

    /** Failed Android commits may still install the changed value in process memory. */
    fun toggleAndSave(message: MutableMap<String, Any>, save: (bookmarkChanged: Boolean) -> Boolean): Boolean {
        val previous = message[KEY]
        set(message, !isBookmarked(message))
        if (save(true)) return true
        if (previous == null) message.remove(KEY) else message[KEY] = previous
        // Install the restored history in preferences too, even if disk writes fail again.
        save(false)
        return false
    }
}
