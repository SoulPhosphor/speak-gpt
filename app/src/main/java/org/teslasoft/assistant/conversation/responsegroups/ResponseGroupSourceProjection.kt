package org.teslasoft.assistant.conversation.responsegroups

import org.json.JSONArray
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.preferences.MessageVisibility
import org.teslasoft.assistant.util.summarizer.SummarySections

/** Canonical sources for the existing summary/transcript reconciliation contracts, never the display projection. */
object ResponseGroupSourceProjection {
    // Turn keys of the legacy transcript format (the transcript store was
    // removed in Stage 6A; these keep the same values it used).
    private const val TURN_MESSAGE_ID = "messageId"
    private const val TURN_HIDDEN = "hiddenFromAi"

    fun summarySources(messages: List<Map<String, Any>>): List<SummarySections.SourceMessage> = messages.map { row ->
        SummarySections.SourceMessage(MessageIdentity.idOf(row), row["isBot"] == true,
            if (MessageVisibility.isHidden(row)) "" else row["message"]?.toString().orEmpty(),
            row["messageTime"]?.toString()?.toLongOrNull())
    }

    /** Reconcile only identified turns. No text guessing, new turns, sibling capture or mutation of the input JSON. */
    fun transcript(content: String, messages: List<Map<String, Any>>): String {
        val rows = messages.associateBy(MessageIdentity::idOf)
        val turns = JSONArray(content)
        for (i in 0 until turns.length()) {
            val turn = turns.optJSONObject(i) ?: continue
            val id = turn.optString(TURN_MESSAGE_ID)
            val row = rows[id] ?: continue
            if (!row.containsKey(ResponseGroupCodec.KEY)) continue
            if (ResponseGroupCodec.read(row) !is ResponseGroupRead.Valid) continue
            turn.put("content", row["message"]?.toString().orEmpty())
            if (MessageCompletionState.isComplete(row["state"]?.toString())) turn.remove("complete")
            else turn.put("complete", false)
            if (MessageVisibility.isHidden(row)) turn.put(TURN_HIDDEN, true)
            else turn.remove(TURN_HIDDEN)
        }
        return turns.toString()
    }
}
