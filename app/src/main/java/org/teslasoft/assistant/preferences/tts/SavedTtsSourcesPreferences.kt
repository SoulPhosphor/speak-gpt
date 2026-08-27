package org.teslasoft.assistant.preferences.tts

import android.content.Context
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.models.ModelIdentity

/**
 * App-wide, endpoint-specific saved speech sources. No credentials or active selections.
 * All operations return explicit failures; callers must not use getOrDefault(emptyList()).
 * Disk operations are synchronous: UI callers should use their existing IO dispatcher.
 */
class SavedTtsSourcesPreferences internal constructor(private val storage: TtsStorage) {
    companion object {
        fun getPreferences(context: Context): SavedTtsSourcesPreferences =
            SavedTtsSourcesPreferences(TtsFileStorage(File(context.applicationContext.filesDir,
                "tts/saved_sources.json")))
    }

    fun load(): Result<List<SavedTtsSource>> = synchronized(TtsStorageLock) { runCatching { read() } }

    /** Appends only after validation and durable persistence. Never upserts endpoint/model. */
    fun add(endpointId: String, modelId: String, routing: TtsRoutingSettings): Result<SavedTtsSource> =
        synchronized(TtsStorageLock) {
            runCatching {
                val entries = read()
                val entry = SavedTtsSource("tts-${UUID.randomUUID()}", endpointId, modelId,
                    routing.copy(providerOrder = routing.providerOrder.toList()))
                validateSelection(entry)
                rejectDuplicate(entries, entry)
                write(entries + entry)
                entry
            }
        }

    /** Endpoint, model, ID and list position are immutable through a routing edit. */
    fun replaceRouting(entryId: String, routing: TtsRoutingSettings): Result<SavedTtsSource> =
        synchronized(TtsStorageLock) {
            runCatching {
                val entries = read()
                val index = entries.indexOfFirst { it.id == entryId }
                if (index < 0) throw TtsStorageException(TtsStorageFailure.NOT_FOUND)
                val updated = entries[index].copy(routing = routing.copy(providerOrder = routing.providerOrder.toList()))
                validateSelection(updated)
                rejectDuplicate(entries.filterNot { it.id == entryId }, updated)
                if (updated != entries[index]) write(entries.toMutableList().apply { set(index, updated) })
                updated
            }
        }

    fun removeEntryIds(entryIds: Set<String>): Result<Int> = removeWhere { it.id in entryIds }

    /** Exact endpoint/model pairs; every matching provider combination is removed together. */
    fun removeTargets(targets: Set<ModelIdentity>): Result<Int> =
        removeWhere { ModelIdentity(it.endpointId, it.modelId) in targets }

    private fun removeWhere(matches: (SavedTtsSource) -> Boolean): Result<Int> = synchronized(TtsStorageLock) {
        runCatching {
            val entries = read()
            val remaining = entries.filterNot(matches)
            val removed = entries.size - remaining.size
            if (removed > 0) write(remaining)
            removed
        }
    }

    private fun read(): List<SavedTtsSource> = readTts(storage, emptyList()) { content ->
        val root = parseTtsObject(content).apply { requireVersionOne() }
        val array = root.getJSONArray("entries")
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val route = item.getJSONObject("routing")
            SavedTtsSource(
                item.strictString("id"), item.strictString("endpointId"), item.strictString("modelId"),
                TtsRoutingSettings(
                    mode = TtsRoutingMode.entries.single { it.key == route.strictString("mode") },
                    selectedProvider = route.strictString("selectedProvider"),
                    providerOrder = route.strictStrings("providerOrder"),
                    allowFallbacks = route.strictBoolean("allowFallbacks")
                )
            ).also { it.validate() }
        }.also { entries -> require(entries.map { it.id }.distinct().size == entries.size) }
    }

    private fun write(entries: List<SavedTtsSource>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().put("id", entry.id).put("endpointId", entry.endpointId)
                .put("modelId", entry.modelId).put("routing", JSONObject()
                    .put("mode", entry.routing.mode.key)
                    .put("selectedProvider", entry.routing.selectedProvider)
                    .put("providerOrder", stringsJson(entry.routing.providerOrder))
                    .put("allowFallbacks", entry.routing.allowFallbacks)))
        }
        writeTts(storage, JSONObject().put("version", 1).put("entries", array).toString())
    }

    private fun validateSelection(entry: SavedTtsSource) {
        try { entry.validate() } catch (error: Exception) {
            throw TtsStorageException(TtsStorageFailure.INVALID_SELECTION, error)
        }
    }

    private fun rejectDuplicate(entries: List<SavedTtsSource>, candidate: SavedTtsSource) {
        if (entries.any { it.endpointId == candidate.endpointId && it.modelId == candidate.modelId &&
                it.routing.providerCombination() == candidate.routing.providerCombination() }) {
            throw TtsStorageException(TtsStorageFailure.DUPLICATE)
        }
    }
}
