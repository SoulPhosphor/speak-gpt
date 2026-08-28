package org.teslasoft.assistant.tts.api

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
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
class TtsPlaybackTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val profile = ApiEndpointObject("Speech", "https://speech.example/v1/", "key", id = "ep")
    private val source = SavedTtsSource("saved", "ep", "model", TtsRoutingSettings())
    private val failures = mutableListOf<TtsFailure>()
    private val players = mutableListOf<RecordingPlayer>()
    private val instances = mutableListOf<TtsPlayback>()
    private var starts = 0
    private var completions = 0

    private fun playback() = TtsPlayback(context,
        TtsSourceResolver({ Result.success(listOf(source)) }, { listOf(profile) }),
        TtsSpeechTransport(object : TtsHttpExecutor {
            override fun execute(endpoint: TtsEndpoint, target: TtsTarget, operation: TtsOperation,
                request: okhttp3.Request, token: TtsRequestToken) =
                TtsHttpResponse(200, byteArrayOf(73, 68, 51, 4, 0, 0), "audio/mpeg")
        }), Dispatchers.Unconfined, Dispatchers.Unconfined,
        { RecordingPlayer().also(players::add) }).also(instances::add)

    private fun start(playback: TtsPlayback) = playback.play(source.sourceId, "voice", "hello", TtsOperation.PREVIEW,
        onStart = { starts++ }, onDone = { completions++ }, onFailure = { failures += it })

    @After fun cleanup() { instances.forEach { it.shutdown() } }

    @Test fun stopBeforePreparedDiscardsQueuedCallbackAndDeletesAudio() {
        val playback = playback()
        start(playback)
        val player = players.single()
        val queued = player.prepared!!
        assertTrue(File(player.path).exists())
        playback.stop()
        queued.onPrepared(player)
        assertEquals(0, starts)
        assertEquals(0, player.starts)
        assertTrue(player.released)
        assertFalse(File(player.path).exists())
        assertTrue(failures.isEmpty())
    }

    @Test fun newPreviewInvalidatesOldPreparedAndCompletionCallbacks() {
        val playback = playback()
        start(playback)
        val old = players.single()
        val queuedPrepared = old.prepared!!
        val queuedCompletion = old.completed!!
        start(playback)
        queuedPrepared.onPrepared(old)
        queuedCompletion.onCompletion(old)
        val current = players.last()
        current.prepared!!.onPrepared(current)
        assertEquals(1, starts)
        assertEquals(0, old.starts)
        assertEquals(0, completions)
        assertTrue(old.released)
        assertTrue(File(current.path).exists())
    }

    @Test fun completingAudioReleasesPlayerAndFileAndCallsCompletionOnce() {
        val playback = playback()
        start(playback)
        val player = players.single()
        player.prepared!!.onPrepared(player)
        val queuedCompletion = player.completed!!
        queuedCompletion.onCompletion(player)
        queuedCompletion.onCompletion(player)
        assertEquals(1, starts)
        assertEquals(1, completions)
        assertTrue(player.released)
        assertFalse(File(player.path).exists())
        assertTrue(failures.isEmpty())
    }

    @Test fun localPlayerErrorHasNoInventedProviderFaultAndCleansResources() {
        start(playback())
        val player = players.single()
        assertTrue(player.failed!!.onError(player, 1, 2))
        assertEquals(TtsFailureKind.PLAYBACK, failures.single().kind)
        assertNull(failures.single().evidence)
        assertTrue(player.released)
        assertFalse(File(player.path).exists())
    }

    @Test fun profileEditCancelsQueuedPlaybackBeforeTheUiCleanupRuns() {
        val playback = playback()
        start(playback)
        val player = players.single()
        val queued = player.prepared!!
        context.getSharedPreferences("api_endpoint", Context.MODE_PRIVATE).edit().putString("ep_host", "changed").commit()
        queued.onPrepared(player)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, starts)
        assertTrue(player.released)
        assertFalse(File(player.path).exists())
    }

    @Test fun unrelatedProfileAndChatModelEditsDoNotStopSpeech() {
        start(playback())
        val player = players.single()
        val settings = context.getSharedPreferences("api_endpoint", Context.MODE_PRIVATE)
        settings.edit().putString("other_host", "changed").putString("ep_model", "different-chat-model").commit()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        player.prepared!!.onPrepared(player)
        assertEquals(1, starts)
        assertFalse(player.released)
    }

    private class RecordingPlayer : MediaPlayer() {
        var path = ""
        var prepared: OnPreparedListener? = null
        var completed: OnCompletionListener? = null
        var failed: OnErrorListener? = null
        var starts = 0
        var released = false
        override fun setDataSource(path: String) { this.path = path }
        override fun prepareAsync() = Unit
        override fun start() { starts++ }
        override fun release() { released = true }
        override fun setOnPreparedListener(listener: OnPreparedListener?) { prepared = listener }
        override fun setOnCompletionListener(listener: OnCompletionListener?) { completed = listener }
        override fun setOnErrorListener(listener: OnErrorListener?) { failed = listener }
    }
}
