package org.teslasoft.assistant.tts.voices

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.aallam.openai.api.audio.SpeechRequest
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.api.logging.Logger
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.LoggingConfig
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
    private val previewCache = mutableMapOf<String, ByteArray>()
    private val previewFiles = mutableSetOf<File>()
    private var previewJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null

    override fun loadVoices(onResult: (Result<List<BrowserVoice>>) -> Unit) {
        onResult(Result.success(VOICE_IDS.map { id ->
            BrowserVoice(
                providerId = this.id,
                providerVoiceId = id,
                displayName = id.replaceFirstChar(Char::uppercase),
                requiresNetwork = true,
                canPreview = true
            )
        }))
    }

    override fun activeVoiceId(): String = preferences.getOpenAIVoice()

    override fun activate(voice: BrowserVoice) {
        preferences.setOpenAIVoice(voice.providerVoiceId)
        preferences.setTtsEngine(id)
    }

    override fun preview(voice: BrowserVoice, onFailure: (String) -> Unit) {
        stopPreview()
        previewCache[voice.providerVoiceId]?.let {
            play(it, voice.providerVoiceId, onFailure)
            return
        }
        previewJob = scope.launch {
            try {
                val endpoint = ApiEndpointPreferences.getApiEndpointPreferences(appContext)
                    .getApiEndpoint(appContext, preferences.getApiEndpointId())
                if (endpoint.apiKey.isBlank() || endpoint.apiKey == "null") {
                    throw IllegalStateException("The active API endpoint does not have an API key for previewing OpenAI voices.")
                }
                val isBearer = endpoint.authType == ApiEndpointObject.AUTH_BEARER
                val headers = when (endpoint.authType) {
                    ApiEndpointObject.AUTH_X_API_KEY -> mapOf("x-api-key" to endpoint.apiKey)
                    ApiEndpointObject.AUTH_API_KEY -> mapOf("api-key" to endpoint.apiKey)
                    else -> emptyMap()
                }
                val client = OpenAI(OpenAIConfig(
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
                val audio = client.speech(SpeechRequest(
                    model = ModelId("tts-1"),
                    input = PREVIEW_TEXT,
                    voice = com.aallam.openai.api.audio.Voice(voice.providerVoiceId)
                ))
                previewCache[voice.providerVoiceId] = audio
                mainHandler.post { play(audio, voice.providerVoiceId, onFailure) }
            } catch (_: CancellationException) {
            } catch (error: Throwable) {
                mainHandler.post { onFailure(error.message ?: "The OpenAI voice preview failed.") }
            }
        }
    }

    override fun download(voice: BrowserVoice, onFailure: (String) -> Unit) {
        onFailure("OpenAI voices are network voices and do not download to the device.")
    }

    override fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        try { mediaPlayer?.stop() } catch (_: Throwable) { }
        try { mediaPlayer?.release() } catch (_: Throwable) { }
        mediaPlayer = null
    }

    override fun shutdown() {
        stopPreview()
        scope.cancel()
        previewFiles.forEach { try { it.delete() } catch (_: Throwable) { } }
        previewFiles.clear()
        previewCache.clear()
    }

    private fun play(audio: ByteArray, voiceId: String, onFailure: (String) -> Unit) {
        try {
            stopPreview()
            val file = File.createTempFile("voice-preview-${voiceId.hashCode()}", ".mp3", appContext.cacheDir)
            FileOutputStream(file).use { it.write(audio) }
            previewFiles += file
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    it.release()
                    if (mediaPlayer === it) mediaPlayer = null
                }
                setOnErrorListener { player, _, _ ->
                    player.release()
                    if (mediaPlayer === player) mediaPlayer = null
                    onFailure("The generated preview audio could not be played.")
                    true
                }
                prepare()
                start()
            }
        } catch (error: Throwable) {
            onFailure(error.message ?: "The generated preview audio could not be played.")
        }
    }

    companion object {
        private const val PREVIEW_TEXT = "Hello. This is a preview of this voice."
        private val VOICE_IDS = listOf("alloy", "echo", "fable", "nova", "onyx", "shimmer")
    }
}
