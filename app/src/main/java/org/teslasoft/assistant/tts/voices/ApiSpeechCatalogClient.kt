package org.teslasoft.assistant.tts.voices

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import java.util.Locale
import java.util.concurrent.TimeUnit

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
    fun discover(endpoint: ApiEndpointObject): Result<ApiSpeechCatalog> = runCatching {
        val base = endpoint.host.toHttpUrlOrNull()
            ?: throw IllegalStateException("The active API endpoint has an invalid base URL.")
        val client = OkHttpClient.Builder()
            .connectTimeout(endpoint.connectTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(endpoint.responseTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()

        val modelsResponse = get(client, base.newBuilder().addPathSegment("models").build(), endpoint)
        if (modelsResponse.code !in 200..299) {
            throw IllegalStateException("The endpoint's model list failed with HTTP ${modelsResponse.code}.")
        }
        val modelsRoot = parseObject(modelsResponse.body, "model list")
        val modelObjects = modelsRoot.getAsJsonArray("data")
            ?: throw IllegalStateException("The endpoint's model list did not contain a data array.")
        val speechModels = speechModelIds(modelObjects)
        if (speechModels.isEmpty()) {
            throw IllegalStateException("The endpoint did not advertise any speech-capable models.")
        }

        val embeddedVoices = parseVoices(modelsRoot.get("voices"))
            .ifEmpty { modelObjects.flatMap { parseVoices(it.asJsonObjectOrNull()?.get("voices")) } }
            .distinctBy(ApiCatalogVoice::id)
        if (embeddedVoices.isNotEmpty()) return@runCatching ApiSpeechCatalog(speechModels, embeddedVoices)

        var discovered: List<ApiCatalogVoice>? = null
        for (segments in listOf(listOf("audio", "voices"), listOf("voices"))) {
            val url = base.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()
            val response = get(client, url, endpoint)
            when (response.code) {
                404, 405, 501 -> continue
                in 200..299 -> {
                    discovered = parseVoiceResponse(response.body)
                    if (discovered.isNotEmpty()) break
                }
                else -> throw IllegalStateException(
                    "The endpoint's voice list failed with HTTP ${response.code}."
                )
            }
        }
        ApiSpeechCatalog(speechModels, discovered?.takeIf { it.isNotEmpty() })
    }

    internal fun speechModelIds(data: JsonArray): List<String> = data.mapNotNull { element ->
        val model = element.asJsonObjectOrNull() ?: return@mapNotNull null
        model.string("id")?.takeIf { isSpeechModel(model, it) }
    }.distinct()

    internal fun parseVoiceResponse(body: String): List<ApiCatalogVoice> {
        val root = JsonParser.parseString(body)
        return when {
            root.isJsonArray -> parseVoices(root)
            root.isJsonObject -> {
                val obj = root.asJsonObject
                parseVoices(obj.get("voices")).ifEmpty { parseVoices(obj.get("data")) }
            }
            else -> emptyList()
        }.distinctBy(ApiCatalogVoice::id)
    }

    private fun isSpeechModel(model: JsonObject, id: String): Boolean {
        val excludedId = id.lowercase(Locale.ROOT)
        if (listOf("transcrib", "speech-to-text", "speech_to_text", "whisper", "stt").any(excludedId::contains)) {
            return false
        }
        val explicit = buildList<String> {
            listOf("type", "task", "capability", "capabilities", "modalities", "output_modalities", "supported_modalities")
                .mapNotNull(model::get)
                .forEach { collectCapabilityStrings(it, this) }
        }.map { it.lowercase(Locale.ROOT) }
        if (explicit.any { value ->
                value in setOf("tts", "speech", "speech-synthesis", "speech_synthesis", "text-to-speech", "text_to_speech") ||
                    value.contains("audio_output") || value.contains("audio-generation") || value.contains("audio_generation")
            }) return true
        return Regex("(^|[-_/.])(tts|speech)([-_/.]|$)").containsMatchIn(excludedId)
    }

    private fun collectCapabilityStrings(value: JsonElement, output: MutableList<String>) {
        when {
            value.isJsonPrimitive -> output += value.asString
            value.isJsonArray -> value.asJsonArray.forEach { collectCapabilityStrings(it, output) }
            value.isJsonObject -> value.asJsonObject.entrySet().forEach { (key, nested) ->
                if (nested.isJsonPrimitive && nested.asJsonPrimitive.isBoolean && nested.asBoolean) output += key
                collectCapabilityStrings(nested, output)
            }
        }
    }

    private fun parseVoices(value: JsonElement?): List<ApiCatalogVoice> {
        if (value == null || !value.isJsonArray) return emptyList()
        return value.asJsonArray.mapNotNull { item ->
            if (item.isJsonPrimitive) {
                item.asString.takeIf(String::isNotBlank)?.let { ApiCatalogVoice(it, friendly(it)) }
            } else {
                val voice = item.asJsonObjectOrNull() ?: return@mapNotNull null
                val id = voice.string("id") ?: voice.string("voice") ?: voice.string("name") ?: return@mapNotNull null
                ApiCatalogVoice(
                    id = id,
                    displayName = voice.string("display_name") ?: voice.string("name") ?: friendly(id),
                    language = facet(voice, "language"),
                    region = facet(voice, "region"),
                    gender = facet(voice, "gender"),
                    accent = facet(voice, "accent"),
                    style = facet(voice, "style")
                )
            }
        }
    }

    private fun facet(voice: JsonObject, key: String): VoiceFacetValue? =
        voice.string(key)?.let { VoiceFacetValue(it.lowercase(Locale.ROOT), friendly(it)) }

    private fun friendly(value: String): String = value.replace('_', ' ').replace('-', ' ')
        .split(' ').filter(String::isNotBlank)
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private data class HttpResult(val code: Int, val body: String)

    private fun get(client: OkHttpClient, url: HttpUrl, endpoint: ApiEndpointObject): HttpResult {
        val request = Request.Builder().url(url).header("Accept", "application/json").apply {
            if (endpoint.apiKey.isNotBlank() && endpoint.apiKey != "null") when (endpoint.authType) {
                ApiEndpointObject.AUTH_X_API_KEY -> header("x-api-key", endpoint.apiKey)
                ApiEndpointObject.AUTH_API_KEY -> header("api-key", endpoint.apiKey)
                else -> header("Authorization", "Bearer ${endpoint.apiKey}")
            }
        }.get().build()
        return client.newCall(request).execute().use { HttpResult(it.code, it.body?.string().orEmpty()) }
    }

    private fun parseObject(body: String, name: String): JsonObject = try {
        JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalStateException("The endpoint's $name response was not a JSON object.")
    } catch (error: IllegalStateException) {
        throw error
    } catch (_: Throwable) {
        throw IllegalStateException("The endpoint's $name response was not valid JSON.")
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.string(key: String): String? = get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString?.takeIf(String::isNotBlank)
}
