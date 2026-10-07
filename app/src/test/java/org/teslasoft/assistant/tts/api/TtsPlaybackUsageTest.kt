package org.teslasoft.assistant.tts.api

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.tts.SavedTtsSource
import org.teslasoft.assistant.preferences.tts.TtsRoutingSettings
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class TtsPlaybackUsageTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val profile = ApiEndpointObject("Speech", "https://speech.example/v1/", "key", id = "ep")
    private val source = SavedTtsSource("saved", "ep", "model", TtsRoutingSettings())
    private val receipts = mutableListOf<TtsBilledSynthesis>()
    private val failures = mutableListOf<TtsFailure>()
    private val players = mutableListOf<Player>()
    private val instances = mutableListOf<TtsPlayback>()
    private val requests = mutableListOf<okhttp3.Request>()
    private var respond: () -> TtsHttpResponse = {
        TtsHttpResponse(200, byteArrayOf(73, 68, 51, 4, 0, 0), "audio/mpeg", "gen-${requests.size}")
    }

    private fun playback() = TtsPlayback(context,
        TtsSourceResolver({ Result.success(listOf(source)) }, { listOf(profile) }),
        TtsSpeechTransport(object : TtsHttpExecutor {
            override fun execute(endpoint: TtsEndpoint, target: TtsTarget, operation: TtsOperation,
                request: okhttp3.Request, token: TtsRequestToken): TtsHttpResponse { requests += request; return respond() }
        }), Dispatchers.Unconfined, Dispatchers.Unconfined,
        { Player().also(players::add) }).also(instances::add)

    private fun speak(playback: TtsPlayback, text: String = "hello", operation: TtsOperation = TtsOperation.SPEECH) =
        playback.play(source.sourceId, "voice", text, operation, onFailure = { failures += it },
            onSynthesized = { receipts += it })

    @After fun cleanup() { instances.forEach { it.shutdown() } }

    @Test fun previewAndReadbackSynthesizeThroughTheSavedSourceAndReportTheExactText() {
        val playback = playback()
        speak(playback, "Preview sample", TtsOperation.PREVIEW)
        speak(playback, "Chat reply", TtsOperation.SPEECH)
        assertEquals(listOf("Preview sample", "Chat reply"), receipts.map { it.input })
        assertEquals(listOf(TtsOperation.PREVIEW, TtsOperation.SPEECH), receipts.map { it.operation })
        assertTrue(receipts.all { it.source.target.sourceId == source.sourceId && it.source.endpoint.id == "ep" })
        assertTrue(requests.all { it.url.toString() == "https://speech.example/v1/audio/speech" })
    }

    @Test fun playbackFailureAfterSynthesisKeepsTheCharge() {
        speak(playback())
        val player = players.single()
        player.failed!!.onError(player, 1, 2)
        assertEquals(TtsFailureKind.PLAYBACK, failures.single().kind)
        assertEquals(1, receipts.size)
    }

    @Test fun stoppingAfterAudioArrivedKeepsTheChargeAndRecordsItOnce() {
        val playback = playback()
        speak(playback)
        playback.stop()
        assertEquals(1, receipts.size)
        assertFalse(File(players.single().path).exists())
    }

    @Test fun eachRetryThatSynthesizesAgainIsCountedOnce() {
        val playback = playback()
        speak(playback); speak(playback); speak(playback)
        assertEquals(listOf("gen-1", "gen-2", "gen-3"), receipts.map { it.audio.generationId })
    }

    @Test fun failedRequestWithoutAudioCreatesNoCharge() {
        respond = { TtsHttpResponse(500, """{"error":{"message":"down"}}""".toByteArray(), "application/json") }
        speak(playback())
        assertTrue(receipts.isEmpty())
        assertEquals(1, failures.size)
    }

    @Test fun accountingFailureNeverBreaksSpeech() {
        val playback = playback()
        playback.play(source.sourceId, "voice", "hello", TtsOperation.SPEECH, onFailure = { failures += it },
            onSynthesized = { throw IllegalStateException("log unavailable") })
        assertTrue(failures.isEmpty())
        assertEquals(1, players.size)
    }

    private class Player : MediaPlayer() {
        var path = ""
        var failed: OnErrorListener? = null
        override fun setDataSource(path: String) { this.path = path }
        override fun prepareAsync() = Unit
        override fun start() = Unit
        override fun release() = Unit
        override fun setOnErrorListener(listener: OnErrorListener?) { failed = listener }
        override fun setOnPreparedListener(listener: OnPreparedListener?) = Unit
        override fun setOnCompletionListener(listener: OnCompletionListener?) = Unit
    }
}
