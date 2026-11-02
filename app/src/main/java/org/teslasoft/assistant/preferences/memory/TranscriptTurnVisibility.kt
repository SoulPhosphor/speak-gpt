package org.teslasoft.assistant.preferences.memory

import org.json.JSONArray
import org.json.JSONObject

/**
 * Links captured transcript turns to the chat message they came from, and
 * carries that message's hidden-from-AI mark (Chat Behaviors, owner ruling
 * Nov 2 2026). A hidden turn keeps its text in the row, so making the message
 * visible again restores it, but the Memory Assistant skips it. The other
 * side of the same exchange is unaffected: a message the user did not hide is
 * still analyzed.
 */
object TranscriptTurnVisibility {
    const val MESSAGE_ID = "messageId"
    const val HIDDEN = "hiddenFromAi"

    fun stamp(turn: JSONObject, messageId: String, hidden: Boolean) {
        if (messageId.isNotBlank()) turn.put(MESSAGE_ID, messageId)
        if (hidden) turn.put(HIDDEN, true)
    }

    fun isHidden(turn: JSONObject): Boolean = turn.optBoolean(HIDDEN, false)

    /**
     * The row content with every turn of the marked message set to its hidden
     * state, or null when nothing changed (no such turn, already in that
     * state, or content that is not a turn list). A turn carrying a message ID
     * matches by ID. A turn captured before IDs were recorded matches by the
     * same role and exact text only when [allowTextMatch] (the caller found
     * exactly one such turn), and is given the ID so later changes find it.
     */
    fun withMessageHidden(
        contentJson: String,
        mark: MemoryStore.TranscriptMessageMark,
        allowTextMatch: Boolean
    ): String? {
        val turns = try {
            JSONArray(contentJson)
        } catch (_: Exception) {
            return null
        }
        var changed = false
        for (i in 0 until turns.length()) {
            val turn = turns.optJSONObject(i) ?: continue
            if (!matchesId(turn, mark) && !(allowTextMatch && matchesText(turn, mark))) continue
            if (!turn.has(MESSAGE_ID) && mark.messageId.isNotBlank()) {
                turn.put(MESSAGE_ID, mark.messageId)
                changed = true
            }
            if (isHidden(turn) == mark.hidden) continue
            if (mark.hidden) turn.put(HIDDEN, true) else turn.remove(HIDDEN)
            changed = true
        }
        return if (changed) turns.toString() else null
    }

    /** How many turns without a message ID match [mark] by role and text. */
    fun textMatchCount(contentJson: String, mark: MemoryStore.TranscriptMessageMark): Int {
        val turns = try {
            JSONArray(contentJson)
        } catch (_: Exception) {
            return 0
        }
        return (0 until turns.length()).count { i ->
            turns.optJSONObject(i)?.let { matchesText(it, mark) } == true
        }
    }

    private fun matchesId(turn: JSONObject, mark: MemoryStore.TranscriptMessageMark): Boolean =
        mark.messageId.isNotBlank() && turn.optString(MESSAGE_ID) == mark.messageId

    private fun matchesText(turn: JSONObject, mark: MemoryStore.TranscriptMessageMark): Boolean =
        !turn.has(MESSAGE_ID) && mark.text.isNotBlank() &&
            turn.optString("role") == mark.role &&
            turn.optString("content") == mark.text
}
