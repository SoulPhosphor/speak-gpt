package org.teslasoft.assistant.tts.voices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject

data class ApiSpeechCatalog(
    val modelIds: List<String>,
    /** Null means the endpoint does not expose voice discovery. */
    val voices: List<ApiCatalogVoice>?
)

data class ApiCatalogVoice(
    val id: String,
    val displayName: String,
    val language: VoiceFacetValue? = null,
    val region: VoiceFacetValue? = null,
    val gender: VoiceFacetValue? = null,
    val accent: VoiceFacetValue? = null,
    val style: VoiceFacetValue? = null
)

/** Discovers speech models and voices from the active OpenAI-compatible endpoint. */
object ApiSpeechCatalogClient {
    fun discover(endpoint: ApiEndpointObject, selectedModelId: String = ""): Result<ApiSpeechCatalog> = runCatching {
        val discovery = org.teslasoft.assistant.tts.api.TtsDiscoveryClient()
        val profile = org.teslasoft.assistant.tts.api.TtsEndpoint.from(endpoint)
        val token = org.teslasoft.assistant.tts.api.TtsRequestGate().begin()
        val source = org.teslasoft.assistant.tts.api.ResolvedTtsSource(
            org.teslasoft.assistant.tts.api.TtsTarget(endpoint.id), profile)
        val models = discovery.models(source, token)
        val model = models.models.singleOrNull { it.id == selectedModelId } ?: models.models.firstOrNull()
            ?: throw IllegalStateException("The endpoint did not advertise any speech-capable models.")
        val selected = org.teslasoft.assistant.tts.api.ResolvedTtsSource(source.target.copy(modelId = model.id), profile)
        val voices = discovery.voices(selected, token) as? org.teslasoft.assistant.tts.api.TtsVoiceCatalog.Known
        ApiSpeechCatalog(listOf(model.id), voices?.voices)
    }

    internal fun speechModelIds(data: JsonArray): List<String> =
        org.teslasoft.assistant.tts.api.TtsCatalogParser.models(
            JsonObject().apply { add("data", data) }.toString()).models.map { it.id }

    internal fun parseVoiceResponse(body: String): List<ApiCatalogVoice> =
        (org.teslasoft.assistant.tts.api.TtsCatalogParser.voiceResponse(body) as?
            org.teslasoft.assistant.tts.api.TtsVoiceCatalog.Known)?.voices.orEmpty()
}
