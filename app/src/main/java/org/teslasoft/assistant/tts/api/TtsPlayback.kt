package org.teslasoft.assistant.tts.api

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import org.teslasoft.assistant.preferences.tts.SavedTtsSourcesPreferences
import java.io.File

/** A separate instance per consumer. Stop invalidates HTTP, prepared callbacks and playback. */
class TtsPlayback(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = TtsRequestGate()
    private var job: Job? = null
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    private var player: MediaPlayer? = null
    private var audioFile: File? = null
    private var stateChanged: (MediaPlayer?) -> Unit = {}
    private val main = Handler(Looper.getMainLooper())
    private val profilePrefs = app.getSharedPreferences("api_endpoint", Context.MODE_PRIVATE)
    private val profileListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> invalidate() }
    private val removeSourceObserver = SavedTtsSourcesPreferences.observeChanges(::invalidate)

    init { profilePrefs.registerOnSharedPreferenceChangeListener(profileListener) }

    private fun invalidate() {
        // Cancel synchronously, even if the UI cleanup is queued behind a prepared callback.
        val invalidated = epoch.get()
        gate.cancel()
        main.post { if (epoch.get() == invalidated) stop() }
    }

    fun play(sourceId: String, voiceId: String, text: String, operation: TtsOperation,
        stillCurrent: () -> Boolean = { true },
        onPlayer: (MediaPlayer?) -> Unit = {},
        onStart: () -> Unit = {}, onDone: () -> Unit = {},
        onFailure: (TtsFailure) -> Unit,
        onPlaybackError: (Int, Int) -> Unit = { _, _ -> }) {
        stop()
        stateChanged = onPlayer
        val token = gate.begin()
        job = scope.launch {
            var target = TtsTarget("", sourceId = sourceId, voiceId = voiceId)
            var endpointName = ""
            var staged: File? = null
            var preparing = false
            try {
                // Keep ownership in this coroutine until Main accepts the file. Cancellation
                // between dispatchers must not orphan it or let a late response start playback.
                withContext(Dispatchers.IO) {
                    val source = TtsAndroidServices.resolver(app).saved(sourceId, voiceId).getOrThrow()
                    target = source.target
                    endpointName = source.endpoint.label
                    val audio = TtsSpeechTransport().synthesize(source, text, token, operation)
                    token.check()
                    val latest = TtsAndroidServices.resolver(app).saved(sourceId, voiceId).getOrThrow()
                    if (latest.target != source.target || !latest.endpoint.sameConfiguration(source.endpoint))
                        throw CancellationException()
                    staged = File.createTempFile("tts-audio-", audio.extension, app.cacheDir)
                    staged!!.writeBytes(audio.bytes)
                }
                token.deliver {
                    if (!stillCurrent()) return@deliver
                    preparing = true
                    audioFile = staged
                    staged = null
                    val next = MediaPlayer()
                    player = next
                    stateChanged(next)
                    next.setDataSource(audioFile!!.absolutePath)
                    next.setOnPreparedListener { ready ->
                        token.deliver {
                            if (!stillCurrent()) { stop(); return@deliver }
                            try { ready.start(); onStart() }
                            catch (_: Exception) {
                                releaseAudio()
                                onFailure(TtsFailure(operation, target, endpointName, TtsFailureKind.PLAYBACK))
                            }
                        }
                    }
                    next.setOnCompletionListener {
                        token.deliver { releaseAudio(); if (stillCurrent()) onDone() }
                    }
                    next.setOnErrorListener { _, what, extra ->
                        token.deliver {
                            releaseAudio()
                            if (stillCurrent()) {
                                onPlaybackError(what, extra)
                                onFailure(TtsFailure(operation, target, endpointName, TtsFailureKind.PLAYBACK))
                            }
                        }
                        true
                    }
                    next.prepareAsync()
                }
            } catch (_: CancellationException) {
                // Stop and changed targets are not failures or implicit retries.
            } catch (error: Exception) {
                val failure = (error as? TtsException)?.failure?.copy(operation = operation)
                    ?: TtsFailure(operation, target, endpointName,
                        if (preparing) TtsFailureKind.PLAYBACK else TtsFailureKind.UNKNOWN)
                token.deliver { releaseAudio(); if (stillCurrent()) onFailure(failure) }
            } finally { staged?.delete() }
        }
    }

    fun stop() {
        epoch.incrementAndGet()
        gate.cancel()
        job?.cancel(); job = null
        releaseAudio()
    }

    private fun releaseAudio() {
        player?.let {
            it.setOnPreparedListener(null); it.setOnCompletionListener(null); it.setOnErrorListener(null)
            runCatching { it.release() }
        }
        player = null
        audioFile?.delete(); audioFile = null
        stateChanged(null)
    }

    fun shutdown() {
        stop()
        removeSourceObserver()
        profilePrefs.unregisterOnSharedPreferenceChangeListener(profileListener)
        scope.cancel()
        main.removeCallbacksAndMessages(null)
    }
}
