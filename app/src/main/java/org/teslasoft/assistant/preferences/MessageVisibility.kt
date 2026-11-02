package org.teslasoft.assistant.preferences

/**
 * Whether a stored chat message is hidden from the AI (Chat Behaviors,
 * owner ruling Nov 2 2026). A hidden message stays in the chat history on
 * screen; everything that sends the conversation to a model (the chat
 * request, the summarizer and compaction) skips its words and attachments.
 */
object MessageVisibility {
    const val KEY = "hiddenFromAi"

    fun isHidden(message: Map<String, *>): Boolean =
        message[KEY] == true || message[KEY]?.toString() == "true"

    fun set(message: MutableMap<String, Any>, hidden: Boolean) {
        if (hidden) message[KEY] = true else message.remove(KEY)
    }

    fun hiddenCount(messages: List<Map<String, *>>): Int = messages.count(::isHidden)

    /**
     * Which menu action a message offers, or null for none.
     *
     * Make Hidden is offered only while Allow Message Visibility is on. Make
     * Visible stays available on a message that is already hidden even after
     * the setting is turned off, so a choice the user made is never stranded.
     * Once a summary or compaction has condensed the message, its visibility
     * is fixed and neither action is offered.
     */
    fun menuAction(hidden: Boolean, allowHiding: Boolean, condensed: Boolean): Action? = when {
        condensed -> null
        hidden -> Action.MAKE_VISIBLE
        allowHiding -> Action.MAKE_HIDDEN
        else -> null
    }

    enum class Action { MAKE_HIDDEN, MAKE_VISIBLE }

    /** The storage callback must roll back failed writes before returning false. */
    fun toggleAndSave(message: MutableMap<String, Any>, save: () -> Boolean): Boolean {
        val previous = message[KEY]
        set(message, !isHidden(message))
        if (save()) return true
        if (previous == null) message.remove(KEY) else message[KEY] = previous
        return false
    }

    /** Clears every hidden mark; restores them all when the save fails. */
    fun showAllAndSave(messages: List<MutableMap<String, Any>>, save: () -> Boolean): Boolean {
        val changed = messages.filter(::isHidden)
        if (changed.isEmpty()) return true
        changed.forEach { set(it, false) }
        if (save()) return true
        changed.forEach { set(it, true) }
        return false
    }
}
