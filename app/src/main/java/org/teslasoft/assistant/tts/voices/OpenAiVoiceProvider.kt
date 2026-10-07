package org.teslasoft.assistant.tts.voices

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.aallam.openai.api.audio.SpeechRequest
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.logging.Logger
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.RetryStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import java.io.File
import java.io.FileOutputStream
import kotlin.time.Duration.Companion.seconds

/** The only fallback catalog. No other code may assume a compatible server supports these voices. */
internal val OPENAI_COMPATIBLE_FALLBACK_VOICE_NAMES = listOf(
    "alloy", "echo", "fable", "nova", "onyx", "shimmer"
)

class OpenAiVoiceProvider(
    context: Context,
    private val preferences: Preferences
) : VoiceBrowserProvider {
    override val id = "openai"
    override val displayName = "OpenAI"
    override val exposesLocationFilter = false

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val endpointPreferences = ApiEndpointPreferences.getApiEndpointPreferences(appContext)
    private val previewCache = mutableMapOf<String, ByteArray>()
    private val previewFiles = mutableSetOf<File>()
    private var previewJob: Job? = null
    private var loadJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var loadedEndpointId: String? = null
    private var loadedModelId: String? = null
    private var onPreviewStateChanged: (String?) -> Unit = {}

    override fun loadVoices(onResult: (Result<List<BrowserVoice>>) -> Unit) {
        loadJob?.cancel()
        loadJob = scope.launch {
            val result = runCatching {
                val endpointId = preferences.getApiEndpointId()
                val endpoint = endpointPreferences.getApiEndpoint(appContext, endpointId)
                val catalog = ApiSpeechCatalogClient.discover(endpoint).getOrThrow()
                val modelId = preferences.getOpenAITtsModel().takeIf(catalog.modelIds::contains)
                    ?: catalog.modelIds.first()
                val rejected = endpointPreferences.getRejectedTtsVoices(endpointId)
                val catalogVoices = catalog.voices ?: OPENAI_COMPATIBLE_FALLBACK_VOICE_NAMES.map {
                    ApiCatalogVoice(it, it.replaceFirstChar(Char::uppercase))
                }
                val available = catalogVoices.filterNot { it.id in rejected }
                if (available.isEmpty()) throw IllegalStateException("The endpoint did not return any usable voices.")
                loadedEndpointId = endpointId
                loadedModelId = modelId
                available.map { voice ->
                    BrowserVoice(
                        providerId = id,
                        providerVoiceId = voice.id,
                        displayName = voice.displayName,
                        providerModelId = modelId,
                        language = voice.language,
                        region = voice.region,
                        gender = voice.gender,
                        accent = voice.accent,
                        style = voice.style,
                        requiresNetwork = true,
                        canPreview = true
                    )
                }
            }
            mainHandler.post { onResult(result) }
        }
    }

    override fun activeVoiceId(): String? = preferences.getOpenAIVoice().takeIf(String::isNotBlank)

    override fun activate(voice: BrowserVoice) {
        val modelId = voice.providerModelId ?: return
        preferences.setOpenAIVoice(voice.providerVoiceId)
        preferences.setOpenAITtsModel(modelId)
        preferences.setTtsEngine(id)
    }

    override fun preview(
        voice: BrowserVoice,
        sampleText: String,
        onFailure: (String) -> Unit,
        onCatalogChanged: () -> Unit,
        onPlaybackChanged: (String?) -> Unit
    ) {
        stopPreview()
        onPreviewStateChanged = onPlaybackChanged
        val endpointId = loadedEndpointId
        val modelId = voice.providerModelId ?: loadedModelId
        if (endpointId == null || modelId == null) {
            onFailure("The endpoint's speech model has not finished loading.")
            return
        }
        val cacheKey = "$endpointId\u0000$modelId\u0000${voice.providerVoiceId}\u0000$sampleText"
        previewCache[cacheKey]?.let {
            play(it, cacheKey, voice.providerVoiceId, onFailure)
            return
        }
        previewJob = scope.launch {
            try {
                val endpoint = endpointPreferences.getApiEndpoint(appContext, endpointId)
                val audio = createClient(endpoint).speech(SpeechRequest(
                    model = ModelId(modelId),
                    input = sampleText,
                    voice = com.aallam.openai.api.audio.Voice(voice.providerVoiceId)
                ))
                previewCache[cacheKey] = audio
                mainHandler.post { play(audio, cacheKey, voice.providerVoiceId, onFailure) }
            } catch (_: CancellationException) {
            } catch (error: Throwable) {
                val message = error.message ?: "The API voice preview failed."
                val rejected = isUnknownVoiceFailure(message)
                if (rejected) endpointPreferences.rejectTtsVoice(endpointId, voice.providerVoiceId)
                mainHandler.post {
                    onFailure(message)
                    if (rejected) onCatalogChanged()
                }
            }
        }
    }

    override fun download(voice: BrowserVoice, onFailure: (String) -> Unit, onCatalogChanged: () -> Unit) {
        onFailure("API voices are network voices and do not download to the device.")
    }

    override fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        try { mediaPlayer?.stop() } catch (_: Throwable) { }
        try { mediaPlayer?.release() } catch (_: Throwable) { }
        mediaPlayer = null
        onPreviewStateChanged(null)
    }

    override fun shutdown() {
        stopPreview()
        loadJob?.cancel()
        scope.cancel()
        previewFiles.forEach { try { it.delete() } catch (_: Throwable) { } }
        previewFiles.clear()
        previewCache.clear()
    }

    private fun createClient(endpoint: ApiEndpointObject): OpenAI {
        val isBearer = endpoint.authType == ApiEndpointObject.AUTH_BEARER
        val headers = when (endpoint.authType) {
            ApiEndpointObject.AUTH_X_API_KEY -> mapOf("x-api-key" to endpoint.apiKey)
            ApiEndpointObject.AUTH_API_KEY -> mapOf("api-key" to endpoint.apiKey)
            else -> emptyMap()
        }
        return OpenAI(OpenAIConfig(
            token = if (isBearer) endpoint.apiKey else "",
            logging = LoggingConfig(LogLevel.None, Logger.Simple),
            timeout = Timeout(
                connect = endpoint.connectTimeoutSeconds.seconds,
                socket = endpoint.responseTimeoutSeconds.seconds
            ),
            organization = null,
            headers = headers,
            host = OpenAIHost(endpoint.host),
            proxy = null,
            retry = RetryStrategy(maxRetries = 0)
        ))
    }

    private fun play(audio: ByteArray, cacheKey: String, voiceId: String, onFailure: (String) -> Unit) {
        try {
            stopPreview()
            val file = File.createTempFile("voice-preview-${cacheKey.hashCode()}", ".mp3", appContext.cacheDir)
            FileOutputStream(file).use { it.write(audio) }
            previewFiles += file
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    it.release()
                    if (mediaPlayer === it) mediaPlayer = null
                    onPreviewStateChanged(null)
                }
                setOnErrorListener { player, _, _ ->
                    player.release()
                    if (mediaPlayer === player) mediaPlayer = null
                    onFailure("The generated preview audio could not be played.")
                    onPreviewStateChanged(null)
                    true
                }
                prepare()
                start()
            }
            onPreviewStateChanged(voiceId)
        } catch (error: Throwable) {
            onFailure(error.message ?: "The generated preview audio could not be played.")
            onPreviewStateChanged(null)
        }
    }

    companion object {
        internal fun isUnknownVoiceFailure(message: String): Boolean {
            val normalized = message.lowercase()
            return listOf(
                "unknown voice", "invalid voice", "voice not found", "unsupported voice",
                "does not support voice", "not a valid voice"
            ).any(normalized::contains)
        }
    }
}
