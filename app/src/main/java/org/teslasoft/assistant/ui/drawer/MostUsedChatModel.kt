package org.teslasoft.assistant.ui.drawer

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Locale
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.usage.TokenUsageAccounting
import org.teslasoft.assistant.usage.UsageCategory
import org.teslasoft.assistant.usage.UsageLogState
import org.teslasoft.assistant.usage.TurnUsageRecord

/** Counts conversation requests, with the most recent request breaking a tie. */
object MostUsedChatModel {
    data class Request(val id: String, val model: String, val time: Long)

    private val variantListType = object : TypeToken<List<Map<String, String>?>>() {}.type

    private fun responseVersions(message: Map<String, Any>): List<Map<String, *>> {
        val variants = try {
            Gson().fromJson<List<Map<String, String>?>>(
                message["variants"]?.toString(), variantListType
            )?.filterNotNull().orEmpty()
        } catch (_: Exception) { emptyList() }
        // Snapshots already include the canonical response; never add it again.
        return variants.ifEmpty { listOf(message) }
    }

    fun select(stored: UsageLogState, messages: List<Map<String, Any>>): String? {
        // Combine logged requests and individual snapshots in memory only.
        val chatRequests = stored.entries.filter { it.category == UsageCategory.CHAT }
            .distinctBy { it.record.requestId ?: it.id }
        val unmatched = chatRequests.toMutableList()
        val requests = chatRequests.map {
            Request(it.record.requestId ?: it.id, it.record.model,
                it.record.requestStartedAtMs ?: it.recordedAtMs)
        }.toMutableList()
        data class Frozen(val id: String, val record: TurnUsageRecord, val time: Long)
        data class Legacy(val request: Request, val messageId: String)
        val frozen = mutableListOf<Frozen>()
        val legacy = mutableListOf<Legacy>()
        messages.forEachIndexed { index, message ->
            responseVersions(message).forEachIndexed { versionIndex, version ->
                val time = version["messageTime"]?.toString()?.toLongOrNull() ?: 0L
                val records = TokenUsageAccounting.decodeRecords(version[TokenUsageAccounting.KEY_USAGE_RECORDS]?.toString()).filterNotNull()
                if (records.isNotEmpty()) {
                    records.forEachIndexed { recordIndex, record ->
                        frozen.add(Frozen("frozen:$index:$versionIndex:$recordIndex", record, time))
                    }
                } else if (message["isBot"] == true &&
                    MessageCompletionState.isComplete(version[MessageCompletionState.KEY_STATE]?.toString())) {
                    version["responseModel"]?.toString()?.takeIf { it.isNotBlank() }?.let { model ->
                        legacy.add(Legacy(Request("legacy:$index:$versionIndex", model, time), MessageIdentity.idOf(message)))
                    }
                }
            }
        }
        // Match real records first so a newly logged regeneration cannot consume
        // an older unlogged snapshot of the same model. Preserve multiplicity for
        // pre-request-ID records: identical records can represent separate requests.
        for (snapshot in frozen) {
            val record = snapshot.record
            val match = unmatched.indexOfFirst {
                (record.requestId != null && (it.record.requestId == record.requestId || it.id == record.requestId)) ||
                    it.record == record
            }
            if (match >= 0) unmatched.removeAt(match) else requests.add(Request(
                record.requestId ?: snapshot.id, record.model, record.requestStartedAtMs ?: snapshot.time
            ))
        }
        for (snapshot in legacy) {
            val match = unmatched.indexOfFirst {
                snapshot.messageId.isNotBlank() && it.messageId == snapshot.messageId &&
                    it.record.model.trim().equals(snapshot.request.model.trim(), ignoreCase = true)
            }
            if (match >= 0) unmatched.removeAt(match) else requests.add(snapshot.request)
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
