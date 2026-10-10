package org.teslasoft.assistant.preferences

/** User bookmarks belong to the message record, independently of reply versions. */
object MessageBookmarks {
    const val KEY = "bookmark"

    fun isBookmarked(message: Map<String, *>): Boolean =
        message[KEY] == true || message[KEY]?.toString() == "true"

    fun set(message: MutableMap<String, Any>, bookmarked: Boolean) {
        if (bookmarked) message[KEY] = true else message.remove(KEY)
    }
}
