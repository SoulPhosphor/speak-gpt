package org.teslasoft.assistant.tts.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.teslasoft.assistant.tts.voices.ApiCatalogVoice
import org.teslasoft.assistant.tts.voices.VoiceFacetValue
import java.util.Locale

sealed interface TtsVoiceCatalog {
    data class Known(val voices: List<ApiCatalogVoice>) : TtsVoiceCatalog
    data object Unavailable : TtsVoiceCatalog
    data class Invalid(val kind: TtsFailureKind) : TtsVoiceCatalog
}

data class TtsModel(val id: String, val name: String, val capabilityEvidence: Set<String>,
    val voices: TtsVoiceCatalog, val detailsLink: String?)
data class TtsModelCatalog(val models: List<TtsModel>, val complete: Boolean)
internal class TtsCatalogDataException(val kind: TtsFailureKind) : IllegalArgumentException()

/** Explicit synthesis evidence only; neither model names nor generic audio capability are evidence. */
object TtsCatalogParser {
    private val tasks = setOf("tts", "text-to-speech", "text_to_speech", "speech-synthesis", "speech_synthesis")

    fun models(body: String): TtsModelCatalog {
        val root = objectBody(body)
        val data = root.getAsJsonArrayOrNull("data") ?: throw IllegalArgumentException("Missing data array")
        var readable = true
        val models = data.mapNotNull { element ->
            val obj = element.objectOrNull() ?: run { readable = false; return@mapNotNull null }
            val id = obj.text("id") ?: run { readable = false; return@mapNotNull null }
            val evidence = evidence(obj)
            if (evidence.isEmpty()) return@mapNotNull null
            model(obj, id, evidence)
        }.distinctBy { it.id }
        if (!readable && models.isEmpty()) throw TtsCatalogDataException(TtsFailureKind.IDENTIFIERS_MISSING)
        return TtsModelCatalog(models, readable && complete(root) && models.isNotEmpty())
    }

    /** An exact lookup still needs synthesis evidence; aliases retain the caller's requested ID. */
    fun exact(body: String, requestedId: String): TtsModel? {
        val root = objectBody(body)
        val obj = root.get("data").objectOrNull() ?: throw IllegalArgumentException("Missing model")
        val evidence = evidence(obj)
        if (evidence.isEmpty() || obj.text("id") == null) return null
        return model(obj, requestedId, evidence)
    }

    private fun model(obj: JsonObject, id: String, evidence: Set<String>) = TtsModel(id,
        obj.text("name") ?: id, evidence,
        voices(obj.get("supported_voices") ?: obj.get("voices")), obj.get("links").objectOrNull()?.text("details"))

    internal fun evidence(obj: JsonObject): Set<String> {
        val architecture = obj.get("architecture").objectOrNull()
        val output = strings(architecture?.get("output_modalities")) + strings(obj.get("output_modalities"))
        val explicit = listOf("task", "type", "capability", "capabilities").flatMap { strings(obj.get(it)) }
        if (explicit.any { it in setOf("stt", "speech-to-text", "speech_to_text", "transcription") }) return emptySet()
        return buildSet {
            if ("speech" in output) add("output_modalities:speech")
            if (architecture?.text("modality") == "text->speech") add("architecture.modality:text->speech")
            explicit.filter { it in tasks }.forEach { add("task:$it") }
        }
    }

    private fun strings(value: JsonElement?): List<String> = when {
        value == null || value.isJsonNull -> emptyList()
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> listOf(value.asString.lowercase(Locale.ROOT))
        value.isJsonArray -> value.asJsonArray.flatMap(::strings)
        value.isJsonObject -> value.asJsonObject.entrySet().filter { (_, v) ->
            v.isJsonPrimitive && v.asJsonPrimitive.isBoolean && v.asBoolean
        }.map { it.key.lowercase(Locale.ROOT) }
        else -> emptyList()
    }

    fun voiceResponse(body: String): TtsVoiceCatalog {
        val root = try { JsonParser.parseString(body) } catch (_: Exception) {
            return TtsVoiceCatalog.Invalid(TtsFailureKind.MALFORMED)
        }
        if (root.isJsonArray) return voices(root)
        val obj = root.objectOrNull() ?: return TtsVoiceCatalog.Invalid(TtsFailureKind.MALFORMED)
        return voices(obj.get("supported_voices") ?: obj.get("voices") ?: obj.get("data"))
    }

    fun voices(value: JsonElement?): TtsVoiceCatalog {
        if (value == null || value.isJsonNull) return TtsVoiceCatalog.Unavailable
        if (!value.isJsonArray) return TtsVoiceCatalog.Invalid(TtsFailureKind.MALFORMED)
        val result = mutableListOf<ApiCatalogVoice>()
        for (item in value.asJsonArray) {
            if (item.isJsonPrimitive && item.asJsonPrimitive.isString && item.asString.isNotBlank()) {
                result += ApiCatalogVoice(item.asString, item.asString)
            } else {
                val voice = item.objectOrNull()
                    ?: return TtsVoiceCatalog.Invalid(TtsFailureKind.IDENTIFIERS_MISSING)
                val id = voice.text("id") ?: voice.text("voice_id") ?: voice.text("voice")
                    ?: return TtsVoiceCatalog.Invalid(TtsFailureKind.IDENTIFIERS_MISSING)
                fun facet(key: String) = voice.text(key)?.let { VoiceFacetValue(it.lowercase(Locale.ROOT), it) }
                result += ApiCatalogVoice(id, voice.text("display_name") ?: voice.text("name") ?: id,
                    facet("language"), facet("region"), facet("gender"), facet("accent"), facet("style"))
            }
        }
        return TtsVoiceCatalog.Known(result.distinctBy { it.id })
    }
}

internal fun JsonElement?.objectOrNull(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject
internal fun JsonObject.text(key: String): String? = get(key)?.takeIf {
    it.isJsonPrimitive && it.asJsonPrimitive.isString
}?.asString?.takeIf { it.isNotBlank() }
internal fun JsonObject.bool(key: String): Boolean? = get(key)?.takeIf {
    it.isJsonPrimitive && it.asJsonPrimitive.isBoolean
}?.asBoolean
internal fun JsonObject.getAsJsonArrayOrNull(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
internal fun objectBody(body: String): JsonObject = JsonParser.parseString(body).objectOrNull()
    ?: throw IllegalArgumentException("Expected object")
internal fun complete(root: JsonObject): Boolean {
    for (obj in listOfNotNull(root, root.get("data").objectOrNull())) {
        if (obj.bool("has_more") == true || obj.bool("truncated") == true) return false
        if (listOf("next", "next_page", "next_cursor", "cursor", "pagination", "page", "total_pages")
                .any { obj.has(it) && !obj.get(it).isJsonNull }) return false
        if (obj.get("links").objectOrNull()?.get("next")?.let { !it.isJsonNull } == true) return false
    }
    return true
}
