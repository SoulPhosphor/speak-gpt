package org.teslasoft.assistant.preferences.backup.readable

import org.json.JSONObject

/** Latest verified file save for each readable category; no content or filenames. */
object ReadableBackupHistory {
    data class Record(val atMillis: Long, val complete: Boolean)

    fun record(
        previous: Map<ReadableBackupCategory, Record>,
        exported: Set<ReadableBackupCategory>,
        complete: Boolean,
        atMillis: Long
    ): Map<ReadableBackupCategory, Record> {
        require(atMillis > 0)
        return previous + exported.associateWith { Record(atMillis, complete) }
    }

    fun encode(records: Map<ReadableBackupCategory, Record>): String = JSONObject().apply {
        records.forEach { (category, record) ->
            put(category.name, JSONObject().put("at", record.atMillis).put("complete", record.complete))
        }
    }.toString()

    fun decode(json: String?): Map<ReadableBackupCategory, Record> = try {
        val root = JSONObject(json ?: "{}")
        ReadableBackupCategory.entries.mapNotNull { category ->
            val item = root.optJSONObject(category.name) ?: return@mapNotNull null
            val at = item.optLong("at", 0)
            if (at <= 0 || !item.has("complete")) null
            else category to Record(at, item.getBoolean("complete"))
        }.toMap()
    } catch (_: Exception) { emptyMap() }
}
