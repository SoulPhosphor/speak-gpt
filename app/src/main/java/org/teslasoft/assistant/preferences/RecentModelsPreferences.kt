package org.teslasoft.assistant.preferences

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.preferences.models.ModelIdentity

/**
 * Device-local chat model history: the trial area of the model picker. Each
 * entry keeps the provider routing from its latest use. Its AI parameters are
 * kept separately and change only when the user saves them (Quick Settings'
 * Save Parameters to Recent Model Settings, or Model Settings); an entry that
 * leaves the list takes its saved parameters with it.
 */
class RecentModelsPreferences internal constructor(
    private val preferences: SharedPreferences,
    /** Where the list size is kept. The Summarizer's and Memory Scanner's
     *  lists follow the one Model Picker Recently Used setting, stored with the
     *  chat's list. */
    private val limitPreferences: SharedPreferences = preferences
) {
    constructor(context: Context) : this(context, ModelPickerScope.CHAT)

    constructor(context: Context, scope: ModelPickerScope) : this(
        context.applicationContext.getSharedPreferences(fileFor(scope), Context.MODE_PRIVATE),
        context.applicationContext.getSharedPreferences(fileFor(ModelPickerScope.CHAT), Context.MODE_PRIVATE)
    )

    fun limit(): Int = limitPreferences.getInt("limit", DEFAULT_LIMIT)

    /** The entries, newest first, each carrying its saved parameters (all
     *  null when none were saved). */
    fun models(): List<FavoriteModelObject> =
        synchronized(lock) {
            val saved = readSavedParameters()
            readModels().take(limit()).map { model ->
                model.also { saved[key(it)]?.copySamplingTo(it) ?: clearSampling(it) }
            }
        }

    /** This model's Recent entry (routing, favorite providers and saved
     *  parameters), or null when it is not listed. */
    fun entry(target: ModelIdentity): FavoriteModelObject? = synchronized(lock) {
        models().firstOrNull { it.matches(target) }
    }

    /** The parameters saved for this Recent entry, or null. */
    fun savedParameters(target: ModelIdentity): FavoriteModelObject? = synchronized(lock) {
        if (readModels().take(limit()).none { it.matches(target) }) return null
        readSavedParameters()[key(target.endpointId, target.modelId)]
    }

    /** Saves [parameters]' five sampling values on the Recent entry for
     *  [target], adding the entry when the model is not listed yet. Null
     *  removes them, so the model returns to its API profile's defaults. */
    fun setSavedParameters(target: ModelIdentity, parameters: FavoriteModelObject?) {
        synchronized(lock) {
            if (limit() == 0 || target.modelId.isBlank() || target.endpointId.isBlank()) return
            val models = readModels()
            if (models.none { it.matches(target) }) {
                writeModels((listOf(FavoriteModelObject(target.modelId, target.endpointId)) + models).take(limit()))
            }
            val saved = readSavedParameters().toMutableMap()
            val id = key(target.endpointId, target.modelId)
            if (parameters == null || !parameters.hasSamplingParameters()) {
                saved.remove(id)
            } else {
                saved[id] = FavoriteModelObject(target.modelId, target.endpointId)
                    .also { parameters.copySamplingTo(it) }
            }
            writeSavedParameters(saved)
        }
    }

    /** The providers the user marked Favorite for this Recent model. */
    fun favoriteProviders(target: ModelIdentity): List<String> = synchronized(lock) {
        readModels().take(limit()).firstOrNull { it.matches(target) }?.favoriteProviders.orEmpty()
    }

    /** Replaces this Recent model's favorite providers, adding the entry when
     *  the model is not listed yet. */
    fun setFavoriteProviders(target: ModelIdentity, providers: List<String>): Boolean {
        synchronized(lock) {
            if (limit() == 0 || target.modelId.isBlank() || target.endpointId.isBlank()) return false
            val cleaned = providers.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
            val models = readModels()
            val existing = models.firstOrNull { it.matches(target) }
            val updated = if (existing != null) {
                models.map { if (it === existing) it.also { e -> e.favoriteProviders = cleaned } else it }
            } else {
                (listOf(FavoriteModelObject(target.modelId, target.endpointId, favoriteProviders = cleaned)) + models)
                    .take(limit())
            }
            writeModels(updated)
            return true
        }
    }

    /** Updates the provider routing of [model]'s entry and moves it first,
     *  keeping its saved parameters. */
    fun recordRouting(model: FavoriteModelObject) = record(model)

    fun setLimit(newLimit: Int) {
        require(newLimit in 0..MAX_LIMIT)
        synchronized(lock) {
            limitPreferences.edit().putInt("limit", newLimit).apply()
            trimToLimit()
        }
    }

    /** Drops the models that no longer fit the list size, with their saved
     *  settings. */
    fun trimToLimit() {
        synchronized(lock) {
            val limit = limit()
            if (limit < readModels().size) writeModels(readModels().take(limit))
        }
    }

    fun record(model: FavoriteModelObject) {
        synchronized(lock) {
            val limit = limit()
            if (limit == 0 || model.modelId.isBlank() || model.endpointId.isBlank()) return
            val all = readModels()
            val earlier = all.firstOrNull { it.modelId == model.modelId && it.endpointId == model.endpointId }
            val previous = all.filterNot { it === earlier }
            // Only the routing is history; parameters are saved separately and
            // favorite providers change only through setFavoriteProviders.
            val entry = model.copy().also(::clearSampling)
            entry.favoriteProviders = earlier?.favoriteProviders.orEmpty()
            writeModels((listOf(entry) + previous).take(limit))
        }
    }

    /** Replaces the whole list (newest first) and the limit, as a restore
     *  does. Each model's sampling fields are its saved parameters. A list
     *  whose size follows another list's setting (the Summarizer's) leaves
     *  that setting alone. */
    fun replaceAll(limit: Int, models: List<FavoriteModelObject>) {
        synchronized(lock) {
            val kept = limit.coerceIn(0, MAX_LIMIT)
            val list = models.filter { it.modelId.isNotBlank() && it.endpointId.isNotBlank() }
                .distinctBy { key(it) }.take(kept)
            val saved = list.filter { it.hasSamplingParameters() }.associate { model ->
                key(model) to FavoriteModelObject(model.modelId, model.endpointId).also { model.copySamplingTo(it) }
            }
            preferences.edit()
                .putInt("limit", kept)
                .putString("models", gson.toJson(list.map { it.copy().also(::clearSampling) }))
                .putString("saved_parameters", gson.toJson(saved))
                .commit()
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
            ?.toList().orEmpty().onEach(::fillMissingLists)

    /** Gson skips constructor defaults, so a list field that an older entry
     *  never stored reads back as null. */
    @Suppress("SENSELESS_COMPARISON", "USELESS_CAST")
    private fun fillMissingLists(model: FavoriteModelObject) {
        if ((model.providerOrder as List<String>?) == null) model.providerOrder = emptyList()
        if ((model.ignoredProviders as List<String>?) == null) model.ignoredProviders = emptyList()
        if ((model.favoriteProviders as List<String>?) == null) model.favoriteProviders = emptyList()
        if ((model.routingType as String?) == null) model.routingType = FavoriteModelObject.ROUTING_AUTOMATIC
        if ((model.selectedProvider as String?) == null) model.selectedProvider = ""
        if ((model.reasoningEffort as String?) == null) model.reasoningEffort = FavoriteModelObject.REASONING_AUTO
    }

    private fun writeModels(models: List<FavoriteModelObject>) {
        // Saved parameters leave with their entry.
        val kept = models.mapTo(HashSet()) { key(it) }
        val saved = readSavedParameters()
        val editor = preferences.edit().putString("models", gson.toJson(models))
        if (saved.keys.any { it !in kept }) {
            editor.putString("saved_parameters", gson.toJson(saved.filterKeys { it in kept }))
        }
        editor.apply()
    }

    private fun readSavedParameters(): Map<String, FavoriteModelObject> = try {
        val json = preferences.getString("saved_parameters", null)
        if (json.isNullOrBlank()) emptyMap() else {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, FavoriteModelObject>>() {}.type
            gson.fromJson<Map<String, FavoriteModelObject>>(json, type).orEmpty()
        }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun writeSavedParameters(saved: Map<String, FavoriteModelObject>) {
        preferences.edit().putString("saved_parameters", gson.toJson(saved)).apply()
    }

    private fun FavoriteModelObject.matches(target: ModelIdentity) =
        modelId == target.modelId && endpointId == target.endpointId

    private fun key(model: FavoriteModelObject) = key(model.endpointId, model.modelId)
    private fun key(endpointId: String, modelId: String) = endpointId + "\u0000" + modelId

    private fun clearSampling(model: FavoriteModelObject) {
        model.streaming = null
        model.temperature = null
        model.topP = null
        model.frequencyPenalty = null
        model.presencePenalty = null
    }

    companion object {
        private fun fileFor(scope: ModelPickerScope): String = scope.filePrefix + "model_picker_recents"

        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 99
        private val gson = Gson()
        private val lock = Any()
    }
}
