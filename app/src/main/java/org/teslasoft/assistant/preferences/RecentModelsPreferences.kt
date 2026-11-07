package org.teslasoft.assistant.preferences

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.preferences.models.ModelIdentity

/** Device-local chat model history. Each entry retains the settings from its latest use. */
class RecentModelsPreferences internal constructor(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("model_picker_recents", Context.MODE_PRIVATE)
    )

    fun limit(): Int = preferences.getInt("limit", DEFAULT_LIMIT)

    fun models(): List<FavoriteModelObject> =
        synchronized(lock) { readModels().take(limit()) }

    fun setLimit(newLimit: Int) {
        require(newLimit in 0..MAX_LIMIT)
        synchronized(lock) {
            val editor = preferences.edit().putInt("limit", newLimit)
            if (newLimit < limit()) {
                editor.putString("models", gson.toJson(readModels().take(newLimit)))
            }
            editor.apply()
        }
    }

    fun record(model: FavoriteModelObject) {
        synchronized(lock) {
            val limit = limit()
            if (limit == 0 || model.modelId.isBlank() || model.endpointId.isBlank()) return
            val previous = readModels().filterNot {
                it.modelId == model.modelId && it.endpointId == model.endpointId
            }
            writeModels((listOf(model) + previous).take(limit))
        }
    }

    fun remove(target: ModelIdentity) = removeMatching {
        it.endpointId == target.endpointId && it.modelId == target.modelId
    }

    fun removeEndpoint(endpointId: String) = removeMatching { it.endpointId == endpointId }

    private fun removeMatching(matches: (FavoriteModelObject) -> Boolean) {
        synchronized(lock) {
            val models = readModels()
            val kept = models.filterNot(matches)
            if (kept.size != models.size) writeModels(kept)
        }
    }

    private fun readModels(): List<FavoriteModelObject> =
        gson.fromJson(preferences.getString("models", null), Array<FavoriteModelObject>::class.java)
            ?.toList().orEmpty()

    private fun writeModels(models: List<FavoriteModelObject>) {
        preferences.edit().putString("models", gson.toJson(models)).apply()
    }

    companion object {
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 99
        private val gson = Gson()
        private val lock = Any()
    }
}
