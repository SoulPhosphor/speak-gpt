package org.teslasoft.assistant.preferences.backup

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/** Persistent results from the existing three-database integrity button. */
object DatabaseCheckHistory {
    enum class Outcome { PASSED, FAILED, UNAVAILABLE }
    data class Record(val atMillis: Long, val outcome: Outcome)
    val databases = listOf(BackupType.MEMORY, BackupType.LOREBOOK, BackupType.USER_IMAGE)
    private const val KEY = "database_checks.history"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("storage_health", Context.MODE_PRIVATE)

    fun get(context: Context): Map<BackupType, Record> = try {
        decode(prefs(context).getString(KEY, null))
    } catch (_: Exception) { emptyMap() }

    fun record(context: Context, results: List<DatabaseHealthChecker.Result>, atMillis: Long) {
        val previous = get(context)
        val updated = results.filter { it.type in databases }.associate { result ->
            result.type to Record(atMillis, when (result.status) {
                DatabaseHealthChecker.Status.OK -> Outcome.PASSED
                DatabaseHealthChecker.Status.DAMAGED -> Outcome.FAILED
                DatabaseHealthChecker.Status.UNAVAILABLE -> Outcome.UNAVAILABLE
            })
        }
        try { prefs(context).edit(commit = true) { putString(KEY, encode(merge(previous, updated))) } }
        catch (_: Exception) { }
    }

    internal fun merge(previous: Map<BackupType, Record>, updates: Map<BackupType, Record>): Map<BackupType, Record> =
        previous + updates.filterKeys { it in databases }

    internal fun encode(records: Map<BackupType, Record>): String = JSONObject().apply {
        records.forEach { (type, record) ->
            put(type.key, JSONObject().put("at", record.atMillis).put("outcome", record.outcome.name))
        }
    }.toString()

    internal fun decode(json: String?): Map<BackupType, Record> = try {
        val root = JSONObject(json ?: "{}")
        databases.mapNotNull { type ->
            val item = root.optJSONObject(type.key) ?: return@mapNotNull null
            val at = item.optLong("at", 0)
            val outcome = Outcome.entries.firstOrNull { it.name == item.optString("outcome") }
            if (at <= 0 || outcome == null) null else type to Record(at, outcome)
        }.toMap()
    } catch (_: Exception) { emptyMap() }
}
