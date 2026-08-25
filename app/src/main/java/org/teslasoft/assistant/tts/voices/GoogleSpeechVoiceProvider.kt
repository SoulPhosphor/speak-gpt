package org.teslasoft.assistant.tts.voices

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import org.teslasoft.assistant.preferences.Preferences
import java.util.Locale

class GoogleSpeechVoiceProvider(
    context: Context,
    private val preferences: Preferences
) : VoiceBrowserProvider {
    override val id = "google"
    override val displayName = "Google Speech Services"
    override val exposesLocationFilter = true

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val numberRegistry = GoogleVoiceNumberRegistry(appContext)
    private var tts: TextToSpeech? = null
    private var initialized = false
    private val androidVoices = mutableMapOf<String, Voice>()
    private val pendingLoads = mutableListOf<(Result<List<BrowserVoice>>) -> Unit>()

    override fun loadVoices(onResult: (Result<List<BrowserVoice>>) -> Unit) {
        if (initialized) {
            onResult(readVoices())
            return
        }
        pendingLoads += onResult
        if (tts != null) return
        tts = TextToSpeech(appContext, { status ->
            mainHandler.post {
                initialized = status == TextToSpeech.SUCCESS
                val result = if (initialized) readVoices() else Result.failure(
                    IllegalStateException("Google Speech Services could not be initialized (status $status).")
                )
                if (!initialized) {
                    try { tts?.shutdown() } catch (_: Throwable) { }
                    tts = null
                    androidVoices.clear()
                }
                pendingLoads.toList().also { pendingLoads.clear() }.forEach { it(result) }
            }
        }, GOOGLE_ENGINE_PACKAGE)
    }

    override fun activeVoiceId(): String = preferences.getVoice()

    override fun activate(voice: BrowserVoice) {
        preferences.setVoice(voice.providerVoiceId)
        preferences.setTtsEngine(id)
    }

    override fun preview(voice: BrowserVoice, sampleText: String, onFailure: (String) -> Unit, onCatalogChanged: () -> Unit) {
        val engine = tts
        val androidVoice = androidVoices[voice.providerVoiceId]
        if (!initialized || engine == null || androidVoice == null) {
            onFailure("This voice is not ready to preview.")
            return
        }
        try {
            engine.stop()
            if (engine.setVoice(androidVoice) == TextToSpeech.ERROR) {
                onFailure("Google Speech Services could not use this voice.")
                return
            }
            val result = engine.speak(sampleText, TextToSpeech.QUEUE_FLUSH, null, PREVIEW_UTTERANCE_ID)
            if (result == TextToSpeech.ERROR) onFailure("Google Speech Services could not play this preview.")
        } catch (error: Throwable) {
            onFailure(error.message ?: "Google Speech Services could not play this preview.")
        }
    }

    override fun download(voice: BrowserVoice, onFailure: (String) -> Unit) {
        if (!voice.downloadable || voice.requiresNetwork == true) {
            onFailure("This voice does not have downloadable on-device data.")
            return
        }
        try {
            appContext.startActivity(
                Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                    .setPackage(GOOGLE_ENGINE_PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (error: Throwable) {
            onFailure(error.message ?: "The voice download screen could not be opened.")
        }
    }

    override fun stopPreview() {
        try { tts?.stop() } catch (_: Throwable) { }
    }

    override fun shutdown() {
        stopPreview()
        try { tts?.shutdown() } catch (_: Throwable) { }
        tts = null
        initialized = false
        pendingLoads.clear()
        androidVoices.clear()
    }

    private fun readVoices(): Result<List<BrowserVoice>> = runCatching {
        val returned = tts?.voices?.toList()
            ?: throw IllegalStateException("Google Speech Services did not return a voice list.")
        androidVoices.clear()
        returned.forEach { androidVoices[it.name] = it }

        val displayNames = numberRegistry.displayNamesFor(returned.map { it.name })
        returned.sortedBy { it.name }.map { voice ->
            val locale = voice.locale ?: Locale.ROOT
            val network = voice.isNetworkConnectionRequired
            val notInstalled = GoogleVoiceMetadata.isDownloadRequired(
                network, voice.features.orEmpty(), TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED
            )
            BrowserVoice(
                providerId = id,
                providerVoiceId = voice.name,
                displayName = displayNames.getValue(voice.name),
                language = locale.language.takeIf(String::isNotBlank)?.let {
                    VoiceFacetValue(it.lowercase(Locale.ROOT), locale.getDisplayLanguage(Locale.getDefault()))
                },
                region = locale.country.takeIf(String::isNotBlank)?.let {
                    VoiceFacetValue(it.uppercase(Locale.ROOT), locale.getDisplayCountry(Locale.getDefault()))
                },
                gender = GoogleVoiceMetadata.explicitGender((voice.features.orEmpty() + voice.name).toList()),
                quality = VoiceQualityLabels.fromAndroidQuality(voice.quality),
                requiresNetwork = network,
                installedLocally = if (network) null else !notInstalled,
                downloadable = notInstalled,
                canPreview = !notInstalled
            )
        }
    }

    companion object {
        private const val GOOGLE_ENGINE_PACKAGE = "com.google.android.tts"
        private const val PREVIEW_UTTERANCE_ID = "speak-gpt-voice-preview"
    }
}
