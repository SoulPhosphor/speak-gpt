package org.teslasoft.assistant.ui.drawer

import java.util.Locale
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.usage.TokenUsageAccounting
import org.teslasoft.assistant.usage.UsageCategory
import org.teslasoft.assistant.usage.UsageLogState

/** Counts conversation requests, with the most recent request breaking a tie. */
object MostUsedChatModel {
    data class Request(val id: String, val model: String, val time: Long)

    fun select(stored: UsageLogState, messages: List<Map<String, Any>>): String? {
        // Seed in memory only: the Usage & Cost screen and its stored data are unchanged.
        val state = stored.seed(messages, 0L)
        val chatRequests = state.entries.filter { it.category == UsageCategory.CHAT }
        val loggedMessageIds = chatRequests.mapNotNull { it.messageId }.toSet()
        val requests = chatRequests.map {
            Request(it.id, it.record.model, it.record.requestStartedAtMs ?: it.recordedAtMs)
        }.toMutableList()
        // Old replies without durable usage can still identify the producing model.
        messages.forEachIndexed { index, message ->
            if (message["isBot"] == true &&
                MessageCompletionState.isComplete(message[MessageCompletionState.KEY_STATE]?.toString()) &&
                MessageIdentity.idOf(message) !in loggedMessageIds &&
                TokenUsageAccounting.durableRecordsOf(message).isEmpty()) {
                message["responseModel"]?.toString()?.takeIf { it.isNotBlank() }?.let { model ->
                    requests.add(Request("legacy:$index", model,
                        message["messageTime"]?.toString()?.toLongOrNull() ?: 0L))
                }
            }
        }
        return select(requests)
    }

    fun select(requests: List<Request>): String? {
        val distinct = requests.filter { it.model.isNotBlank() }.distinctBy { it.id }
        return distinct.withIndex().groupBy { it.value.model.trim().lowercase(Locale.ROOT) }
            .values.maxWithOrNull(compareBy<List<IndexedValue<Request>>> { it.size }
                .thenBy { group -> group.maxOf { it.value.time } }
                .thenBy { group -> group.maxOf { it.index } })
            ?.maxWithOrNull(compareBy<IndexedValue<Request>> { it.value.time }.thenBy { it.index })
            ?.value?.model?.trim()
    }
}
