package org.teslasoft.assistant.tts.voices

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
    private val downloadingVoiceIds = mutableSetOf<String>()
    private val recentlyDownloadedVoiceIds = mutableSetOf<String>()
    private val downloadChecks = mutableMapOf<String, Runnable>()

    // Preview playback tracking, so a row can flip to Stop while it is sounding.
    private var pendingPreviewVoiceId: String? = null
    private var onPreviewStateChanged: (String?) -> Unit = {}
    private val previewProgressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            if (utteranceId != PREVIEW_UTTERANCE_ID) return
            val voiceId = pendingPreviewVoiceId
            mainHandler.post { onPreviewStateChanged(voiceId) }
        }
        override fun onDone(utteranceId: String?) = notifyStopped(utteranceId)
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = notifyStopped(utteranceId)
        override fun onError(utteranceId: String?, errorCode: Int) = notifyStopped(utteranceId)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = notifyStopped(utteranceId)
        private fun notifyStopped(utteranceId: String?) {
            if (utteranceId != PREVIEW_UTTERANCE_ID) return
            mainHandler.post { onPreviewStateChanged(null) }
        }
    }

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

    override fun preview(
        voice: BrowserVoice,
        sampleText: String,
        onFailure: (String) -> Unit,
        onCatalogChanged: () -> Unit,
        onPlaybackChanged: (String?) -> Unit
    ) {
        val engine = tts
        val androidVoice = androidVoices[voice.providerVoiceId]
        if (!initialized || engine == null || androidVoice == null) {
            onFailure("This voice is not ready to preview.")
            return
        }
        onPreviewStateChanged = onPlaybackChanged
        pendingPreviewVoiceId = voice.providerVoiceId
        engine.setOnUtteranceProgressListener(previewProgressListener)
        try {
            engine.stop()
            when (engine.setVoice(androidVoice)) {
                TextToSpeech.ERROR_NOT_INSTALLED_YET -> {
                    beginDownloadMonitoring(voice.providerVoiceId, onFailure, onCatalogChanged)
                    return
                }
                TextToSpeech.SUCCESS -> Unit
                else -> {
                    onFailure("Google Speech Services could not use this voice.")
                    return
                }
            }
            val result = engine.speak(sampleText, TextToSpeech.QUEUE_FLUSH, null, PREVIEW_UTTERANCE_ID)
            when (result) {
                TextToSpeech.ERROR_NOT_INSTALLED_YET ->
                    beginDownloadMonitoring(voice.providerVoiceId, onFailure, onCatalogChanged)
                TextToSpeech.SUCCESS -> Unit
                else -> onFailure("Google Speech Services could not play this preview.")
            }
        } catch (error: Throwable) {
            onFailure(error.message ?: "Google Speech Services could not play this preview.")
        }
    }

    override fun download(
        voice: BrowserVoice,
        onFailure: (String) -> Unit,
        onCatalogChanged: () -> Unit
    ) {
        if (!voice.downloadable || voice.requiresNetwork == true) {
            onFailure("This voice does not have downloadable on-device data.")
            return
        }
        if (voice.providerVoiceId in downloadingVoiceIds) return
        val engine = tts
        val selectedVoice = androidVoices[voice.providerVoiceId]
        if (!initialized || engine == null || selectedVoice == null) {
            onFailure("This exact Google voice is not ready to download.")
            return
        }

        try {
            val result = engine.setVoice(selectedVoice)
            if (!GoogleVoiceDownloadPolicy.targetAccepted(result)) {
                openGenericVoiceDataFallback(onFailure)
                return
            }

            beginDownloadMonitoring(voice.providerVoiceId, onFailure, onCatalogChanged)
        } catch (error: Throwable) {
            openGenericVoiceDataFallback(onFailure, error)
        }
    }

    private fun beginDownloadMonitoring(
        voiceId: String,
        onFailure: (String) -> Unit,
        onCatalogChanged: () -> Unit
    ) {
        if (voiceId in downloadingVoiceIds) return
        downloadingVoiceIds += voiceId
        recentlyDownloadedVoiceIds -= voiceId
        onCatalogChanged()
        scheduleDownloadCheck(
            voiceId = voiceId,
            startedAt = SystemClock.elapsedRealtime(),
            onFailure = onFailure,
            onCatalogChanged = onCatalogChanged
        )
    }

    private fun scheduleDownloadCheck(
        voiceId: String,
        startedAt: Long,
        onFailure: (String) -> Unit,
        onCatalogChanged: () -> Unit
    ) {
        downloadChecks.remove(voiceId)?.let(mainHandler::removeCallbacks)
        val check = object : Runnable {
            override fun run() {
                if (!initialized || tts == null) return finishDownloadCheck(voiceId, onCatalogChanged)
                val refreshed = try {
                    tts?.voices?.firstOrNull { it.name == voiceId }
                } catch (_: Throwable) {
                    null
                }
                if (refreshed != null) androidVoices[voiceId] = refreshed
                val stillMissing = refreshed == null || GoogleVoiceDownloadPolicy.isNotInstalled(
                    refreshed.features.orEmpty(),
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED
                )
                if (!stillMissing) {
                    downloadingVoiceIds -= voiceId
                    recentlyDownloadedVoiceIds += voiceId
                    downloadChecks.remove(voiceId)
                    onCatalogChanged()
                    return
                }

                if (SystemClock.elapsedRealtime() - startedAt >= DOWNLOAD_CONFIRM_TIMEOUT_MS) {
                    finishDownloadCheck(voiceId, onCatalogChanged)
                    openGenericVoiceDataFallback(onFailure)
                    return
                }
                mainHandler.postDelayed(this, DOWNLOAD_CHECK_INTERVAL_MS)
            }
        }
        downloadChecks[voiceId] = check
        mainHandler.postDelayed(check, DOWNLOAD_CHECK_INTERVAL_MS)
    }

    private fun finishDownloadCheck(voiceId: String, onCatalogChanged: () -> Unit) {
        downloadChecks.remove(voiceId)?.let(mainHandler::removeCallbacks)
        if (downloadingVoiceIds.remove(voiceId)) onCatalogChanged()
    }

    private fun openGenericVoiceDataFallback(onFailure: (String) -> Unit, cause: Throwable? = null) {
        try {
            appContext.startActivity(
                Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                    .setPackage(GOOGLE_ENGINE_PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (fallbackError: Throwable) {
            onFailure(
                cause?.message
                    ?: fallbackError.message
                    ?: "Google Speech Services could not target this voice or open voice data settings."
            )
        }
    }

    override fun stopPreview() {
        try { tts?.stop() } catch (_: Throwable) { }
        pendingPreviewVoiceId = null
        onPreviewStateChanged(null)
    }

    override fun shutdown() {
        stopPreview()
        downloadChecks.values.forEach(mainHandler::removeCallbacks)
        downloadChecks.clear()
        downloadingVoiceIds.clear()
        recentlyDownloadedVoiceIds.clear()
        try { tts?.shutdown() } catch (_: Throwable) { }
        tts = null
        initialized = false
        pendingLoads.clear()
        androidVoices.clear()
        onPreviewStateChanged = {}
        pendingPreviewVoiceId = null
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
                downloadInProgress = voice.name in downloadingVoiceIds,
                downloadedRecently = voice.name in recentlyDownloadedVoiceIds && !notInstalled,
                canPreview = !notInstalled
            )
        }
    }

    companion object {
        private const val GOOGLE_ENGINE_PACKAGE = "com.google.android.tts"
        private const val PREVIEW_UTTERANCE_ID = "speak-gpt-voice-preview"
        private const val DOWNLOAD_CHECK_INTERVAL_MS = 1_000L
        private const val DOWNLOAD_CONFIRM_TIMEOUT_MS = 120_000L
    }
}

object GoogleVoiceDownloadPolicy {
    fun targetAccepted(result: Int): Boolean =
        result == TextToSpeech.SUCCESS || result == TextToSpeech.ERROR_NOT_INSTALLED_YET

    fun isNotInstalled(features: Set<String>, notInstalledFeature: String): Boolean =
        features.any { it.equals(notInstalledFeature, ignoreCase = true) }
}
