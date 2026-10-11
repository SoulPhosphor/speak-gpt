package org.teslasoft.assistant.ui.drawer

import android.content.Context
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.usage.UsageLogStore

/** Worker-thread only. Shares the transcript read used for chat-list decorations. */
object ChatListDetailsReader {
    fun read(context: Context, chatId: String, startedAt: Long?, includeModel: Boolean): ChatListDetails {
        val storage = ChatPreferences.getChatPreferences()
        val messages = storage.getChatByIdResult(context, chatId).messages
        return fromMessages(context, chatId, messages, startedAt, includeModel).also {
            storage.rememberChatStartDate(context, chatId, it.startedAt)
        }
    }

    fun fromMessages(
        context: Context, chatId: String, messages: List<Map<String, Any>>,
        startedAt: Long?, includeModel: Boolean
    ): ChatListDetails {
        val details = ChatListDetails.fromMessages(messages, startedAt)
        if (!includeModel) return details
        return details.copy(mostUsedModel = MostUsedChatModel.select(
            UsageLogStore.read(Preferences.getPreferences(context, chatId)), messages
        ))
    }
}
