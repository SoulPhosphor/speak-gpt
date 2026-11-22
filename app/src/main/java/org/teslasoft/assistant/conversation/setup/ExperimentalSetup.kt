package org.teslasoft.assistant.conversation.setup

import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.preferences.models.ModelIdentity

/** Future-turn choices only. Never used to relabel a saved response group. */
@Keep
data class ExperimentalSetup(
    val mode: Mode = Mode.OFF,
    val lastMessageOnly: Boolean = true,
    val models: List<SetupModel> = emptyList(),
    val primaryId: String? = null,
    val allowImages: Boolean = true
) {
    @Keep
    enum class Mode { OFF, SINGLE, PARALLEL }
    val enabled get() = mode != Mode.OFF
    val usesLastMessageOnly get() = enabled && lastMessageOnly

    fun withoutModel(id: String): ExperimentalSetup = copy(
        models = models.filterNot { it.id == id }, primaryId = primaryId?.takeUnless { it == id }
    )

    fun validate() {
        require(models.size <= MAX_MODELS)
        require(models.map { it.id }.distinct().size == models.size)
        require(models.map { it.identity }.distinct().size == models.size)
        models.forEach { it.validate() }
        require(primaryId == null || models.any { it.id == primaryId })
        if (mode == Mode.PARALLEL) require(models.size >= MIN_MODELS && primaryId != null)
    }

    companion object {
        const val MIN_MODELS = 2
        const val MAX_MODELS = 4
        fun legacy(enabled: Boolean) = ExperimentalSetup(if (enabled) Mode.SINGLE else Mode.OFF)
    }
}

@Keep
data class SetupModel(
    val id: String,
    val endpointId: String,
    val modelId: String,
    val endpointHost: String,
    val chatPath: String,
    val authType: String,
    val endpointIdentity: String,
    /** Saved routing/reasoning/sampling values only; never credentials. */
    val settingsJson: String,
    val endpointLabel: String = endpointId
) {
    val identity get() = ModelIdentity(endpointId, modelId)
    fun settings(): FavoriteModelObject = Gson().fromJson(settingsJson, FavoriteModelObject::class.java)
    fun validate() {
        require(id.isNotBlank() && endpointId.isNotBlank() && modelId.isNotBlank())
        require(endpointHost.isNotBlank() && chatPath.isNotBlank() && authType.isNotBlank())
        val json = JsonParser.parseString(settingsJson).asJsonObject
        listOf("modelId", "endpointId", "routingType", "selectedProvider", "reasoningEffort").forEach {
            require(json.getAsJsonPrimitive(it).isString)
        }
        listOf("allowFallbacks", "showReasoning").forEach { require(json.getAsJsonPrimitive(it).isBoolean) }
        listOf("providerOrder", "ignoredProviders", "favoriteProviders").forEach { key ->
            require(json.getAsJsonArray(key).all { it.isJsonPrimitive && it.asJsonPrimitive.isString })
        }
        val settings = settings()
        require(settings.modelId == modelId && settings.endpointId == endpointId)
        require(settings.routingType in setOf("automatic", "preferred", "only"))
        require(settings.routingType != "only" || settings.selectedProvider.isNotBlank())
        listOf(settings.temperature, settings.topP, settings.frequencyPenalty, settings.presencePenalty)
            .forEach { require(it == null || it.isFinite()) }
    }
}

/** Invalid/unknown data is retained and blocks use; it must not silently become another mode. */
object ExperimentalSetupCodec {
    fun encode(setup: ExperimentalSetup): String {
        setup.validate()
        return JsonObject().apply {
            addProperty("schema", 1)
            addProperty("mode", setup.mode.name)
            addProperty("lastMessageOnly", setup.lastMessageOnly)
            addProperty("allowImages", setup.allowImages)
            setup.primaryId?.let { addProperty("primaryId", it) }
            add("models", JsonArray().apply { setup.models.forEach { add(Gson().toJsonTree(it)) } })
        }.toString()
    }

    fun decode(raw: String): ExperimentalSetup {
        val root = JsonParser.parseString(raw).asJsonObject
        require(root.get("schema").toString() == "1")
        val mode = ExperimentalSetup.Mode.valueOf(root.get("mode").asString)
        fun boolean(key: String): Boolean {
            val value = root.getAsJsonPrimitive(key)
            require(value.isBoolean)
            return value.asBoolean
        }
        val models = root.getAsJsonArray("models").map { element ->
            val item = element.asJsonObject
            fun string(key: String) = item.getAsJsonPrimitive(key).also { require(it.isString) }.asString
            SetupModel(string("id"), string("endpointId"), string("modelId"), string("endpointHost"),
                string("chatPath"), string("authType"), string("endpointIdentity"), string("settingsJson"), string("endpointLabel"))
        }
        return ExperimentalSetup(mode, boolean("lastMessageOnly"), models,
            root.get("primaryId")?.asString, boolean("allowImages")).also { it.validate() }
    }
}

/** Public entry remains closed until Stage 4 supplies response tabs and send integration. */
object ExperimentalSetupAvailability {
    const val PUBLIC_SETUP_ENABLED = false
}
