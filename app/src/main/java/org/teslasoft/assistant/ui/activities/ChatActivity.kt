/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Configuration.KEYBOARD_QWERTY
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.Editable
import android.text.TextWatcher
import android.transition.TransitionInflater
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.core.util.Pair
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.interpolator.view.animation.FastOutLinearInInterpolator
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aallam.ktoken.Encoding
import com.aallam.ktoken.Tokenizer
import com.aallam.openai.api.audio.SpeechRequest
import com.aallam.openai.api.audio.TranscriptionRequest
import com.aallam.openai.api.chat.ChatCompletionChunk
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ContentPart
import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.chat.TextPart
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.chat.ToolChoice
import com.aallam.openai.api.chat.chatCompletionRequest
import com.aallam.openai.api.completion.CompletionRequest
import com.aallam.openai.api.completion.TextCompletion
import com.aallam.openai.api.core.Role
import com.aallam.openai.api.file.FileSource
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.image.ImageCreation
import com.aallam.openai.api.image.ImageSize
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.logging.Logger
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.RetryStrategy
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.gson.Gson
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.images.Image
import com.openai.models.images.ImageGenerateParams
import eightbitlab.com.blurview.BlurView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
// import kotlinx.io.files.Path
// import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.ActivationPromptPreferences
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.profileimages.ProfileImageStore
import org.teslasoft.assistant.preferences.LogitBiasPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.TranscriptRecorder
import org.teslasoft.assistant.preferences.lorebook.LoreBookInjectionLog
import org.teslasoft.assistant.preferences.lorebook.LoreBookMatch
import org.teslasoft.assistant.preferences.lorebook.LoreBookStore
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.stt.LocalWhisperEngine
import org.teslasoft.assistant.stt.LocalWhisperModels
import org.teslasoft.assistant.stt.LocalWhisperStorage
import org.teslasoft.assistant.service.GenerationForegroundService
import org.teslasoft.assistant.service.HandsFreeService
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.adapters.chat.ChatAdapter
import org.teslasoft.assistant.ui.fragments.dialogs.EditApiEndpointDialogFragment
import org.teslasoft.assistant.ui.fragments.dialogs.QuickSettingsBottomSheetDialogFragment
import org.teslasoft.assistant.ui.onboarding.WelcomeActivity
import org.teslasoft.assistant.ui.permission.CameraPermissionActivity
import org.teslasoft.assistant.ui.permission.MicrophonePermissionActivity
import org.teslasoft.assistant.util.Hash
import org.teslasoft.assistant.util.GenErrorResult
import org.teslasoft.assistant.util.GenerationErrorClassifier
import org.teslasoft.assistant.util.LocaleParser
import org.teslasoft.assistant.util.WindowInsetsUtil
import org.teslasoft.assistant.util.chatMessage
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.URL
import java.util.EnumSet
import java.util.Locale
import java.util.Optional
import kotlin.time.Duration.Companion.seconds
import androidx.core.content.edit
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.flow.flowOn
import okio.FileSystem
import okio.Path.Companion.toPath

class ChatActivity : FragmentActivity(), ChatAdapter.OnUpdateListener {

    companion object {
        // Broadcast action posted by the keep-alive notifications' "Hang Up"
        // action and handled by the live ChatActivity. Package-scoped and
        // non-exported; see hangUpReceiver.
        const val ACTION_HANG_UP = "org.teslasoft.assistant.action.HANG_UP"

        // Once-per-PROCESS guard for the soft memory-degraded notice
        // (notifyMemoryDegradedOnce). Static so a new ChatActivity instance —
        // e.g. after a rotation/recreation mid-session — does not re-arm it and
        // toast the same degraded session again. compareAndSet keeps it correct
        // if the notice ever fires off more than one thread.
        private val memoryDegradedNotified = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    // Init UI
    private var messageInput: EditText? = null
    private var btnSend: ImageButton? = null
    private var btnMicro: ImageButton? = null
    private var btnSettings: ImageButton? = null
    private var progress: CircularProgressIndicator? = null
    private var chat: RecyclerView? = null
    private var activityTitle: TextView? = null
    private var btnExport: ImageButton? = null
    private var fileContents: ByteArray? = null
    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var btnDebugLog: ImageButton? = null
    private var keyboardFrame: ConstraintLayout? = null
    private var root: ConstraintLayout? = null
    private var threadLoader: LinearLayout? = null
    private var btnAttachFile: ImageButton? = null
    private var attachedImage: LinearLayout? = null
    private var selectedImage: ImageView? = null
    private var btnRemoveImage: ImageButton? = null
    private var visionActions: LinearLayout? = null
    private var btnVisionActionCamera: ImageButton? = null
    private var btnVisionActionGallery: ImageButton? = null
    private var bulkContainer: ConstraintLayout? = null
    private var btnSelectAll: ImageButton? = null
    private var btnDeselectAll: ImageButton? = null
    private var btnDeleteSelected: ImageButton? = null
    private var btnCopySelected: ImageButton? = null
    private var btnShareSelected: ImageButton? = null
    private var selectedCount: TextView? = null
    private var expandableWindowRoot: CoordinatorLayout? = null
    private var blurSelectorView: BlurView? = null

    // Init chat
    private var messages: ArrayList<HashMap<String, Any>> = arrayListOf()

    /** True when this chat's stored history is LOCKED or CORRUPT (Round 4):
     *  the owner-approved "Chat unavailable" state is showing, and sending,
     *  saving and generation are refused so the preserved encrypted value
     *  can never be overwritten by this screen's (empty) in-memory view. */
    private var chatStorageUnavailable = false
    private var messagesSelectionProjection: ArrayList<HashMap<String, Any>> = arrayListOf()
    private var messagesUsageProjection: ArrayList<HashMap<String, Any>> = arrayListOf()
    private var adapter: ChatAdapter? = null
    private var chatMessages: ArrayList<ChatMessage> = arrayListOf()

    // The user's most recent outgoing message (captured in generateResponse, which
    // every input path flows through). Used by the lorebook to match triggers.
    private var lastUserMessageForLore = ""

    private var chatId = ""
    private var chatName = ""
    private var languageIdentifier: LanguageIdentifier? = null

    // Mid-stream persistence throttle (see the collect block in
    // regularGPTResponse): a full-history encrypt per streamed chunk scaled
    // with conversation length and made long chats slower every turn.
    private var lastStreamSaveUptime = 0L
    private val STREAM_SAVE_INTERVAL_MS = 2000L

    // Init states
    private var isRecording = false
    private var keyboardMode = false
    private var isTTSInitialized = false
    private var silenceMode = false
    private var autoLangDetect = false
    private var cancelState = false
    private var disableAutoScroll = false
    private var imageIsSelected = false
    private var inCost: Float = 0.0f
    private var outCost: Float = 0.0f
    private var usageIn: Int = 0
    private var usageOut: Int = 0
    private var priceIn: Float = 0.0f
    private var priceOut: Float = 0.0f
    private var bulkSelectionMode: Boolean = false

    // init AI
    private var ai: OpenAI? = null
    private var openAIAI: OpenAI? = null
    private var key: String? = null
    private var openAIKey: String? = null
    private var model = ""
    private var endSeparator = ""
    private var prefix = ""
    private var apiEndpointPreferences: ApiEndpointPreferences? = null
    private var logitBiasPreferences: LogitBiasPreferences? = null
    private var apiEndpointObject: ApiEndpointObject? = null
    private var chatStartupComplete = false

    private data class PreparedChatStartup(
        val chatId: String,
        val chatName: String,
        val preferences: Preferences,
        val apiEndpointPreferences: ApiEndpointPreferences,
        val logitBiasPreferences: LogitBiasPreferences,
        val apiEndpointObject: ApiEndpointObject,
        val historyResult: ChatPreferences.ChatHistoryResult
    )

    private data class ChatStartupResult(
        val storageLocked: Boolean,
        val preparedChat: PreparedChatStartup? = null
    )

    // Init DALL-e
    private var resolution = "512x152"

    // Auto-naming attempts this screen instance. Used to be a one-shot
    // "messageCounter == 0" gate: a single transient failure (network blip,
    // model briefly unavailable) left the chat named "_autoname_…" for the
    // whole session with no retry. Now each turn retries while the
    // placeholder name remains, capped so a permanently broken endpoint
    // can't fire a naming request forever.
    private var autoNameAttempts = 0
    private val AUTO_NAME_MAX_ATTEMPTS = 3

    // True while an auto-name rename's off-main storage work is in flight, so a
    // following turn can't launch an overlapping rename. Touched only on the
    // main dispatcher (set before the IO hop, cleared in its finally).
    private var renameInProgress = false

    // Init audio
    private var recognizer: SpeechRecognizer? = null
    private var recorder: MediaRecorder? = null

    // Hands-free conversation loop state
    private var handsFreeUserSpoke = false
    private var handsFreeStopped = false
    private var handsFreeListenDeadline = 0L
    private val handsFreeHandler = Handler(Looper.getMainLooper())

    // Hands-free silence-aware submission. The native recognizer ignores
    // EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS on most devices and
    // cuts off after ~2s of silence, so we buffer each fragment, restart the
    // mic, and only submit once the user has been quiet for the configured
    // silence window.
    private var handsFreeBuffer: String = ""
    private var handsFreeSubmitRunnable: Runnable? = null

    // Monotonic token guarding the readback→listen handoff. The mic can be
    // re-armed by either the TTS completion callback or the playback-state
    // watchdog (whichever notices the reply finished first); bumping this token
    // invalidates the other so the next turn is never started twice, and so a
    // stale watchdog from a previous reply can't fire after the loop moved on.
    // Volatile: written from the TTS completion callback (a binder thread) and
    // read by the watchdog poll on the main thread.
    @Volatile private var handsFreeReadbackToken = 0L

    // True only while a hands-free *loop* readback is in flight. The TTS and
    // MediaPlayer completion callbacks fire for every utterance — including
    // manual speaker-button re-reads — and only a loop readback's completion
    // may re-arm the mic, so the completion handlers are gated on this flag.
    // Volatile for the same binder-thread/main-thread split as the token.
    @Volatile private var handsFreeReadbackExpected = false

    // Consecutive failed attempts to open the mic for the next hands-free
    // turn. The recognition service / capture device can refuse a session
    // right after a readback (most often with the screen off); we rebuild and
    // retry a couple of times before declaring the loop dead.
    private var handsFreeTurnRetries = 0

    // Guards the whisper engine's per-turn callbacks (end-of-turn, no-speech,
    // capture error) against arriving late, twice, or after the turn they
    // belong to was already torn down: each arm bumps the token, every
    // callback closure captures it, and a mismatch means "a different turn
    // owns the mic now — drop it". Without this, a stale callback from a dead
    // turn could end (or error out) the NEXT turn's capture. Main thread only.
    private var whisperTurnToken = 0

    // Mid-turn capture failures (the engine's typed capture-error callback).
    // Deliberately separate from handsFreeTurnRetries: that budget covers
    // failures to OPEN the mic and resets on a successful arm — which would
    // let arm-ok/die-mid-turn cycles retry forever. This one only resets when
    // a turn actually completes (end of turn reached), so a capture that
    // keeps dying can never become an infinite automatic recovery loop.
    private val whisperCaptureErrorBudget = org.teslasoft.assistant.stt.BoundedRetryBudget(2)

    // Readback watchdog cadence: how often to poll playback state, and how long
    // to wait for speech to start before assuming the utterance was lost.
    private val HANDS_FREE_READBACK_POLL_MS = 250L
    private val HANDS_FREE_READBACK_START_TIMEOUT_MS = 6000L
    private val HANDS_FREE_HARD_FALLBACK_MS = 20_000L
    // How many consecutive "nothing audible" polls count as end of readback.
    // A single poll isn't trusted: tts.isSpeaking can blip false mid-utterance
    // (engine buffer underrun), and reopening the mic on that blip is what
    // used to mute the readback halfway through.
    private val HANDS_FREE_READBACK_STOP_POLLS = 3

    // Media player for OpenAI TTS
    private var mediaPlayer: MediaPlayer? = null

    // Keep-alive that spans the read-aloud *after* generation in the plain
    // (non-hands-free) path. The generation keep-alive is released the instant
    // the text stream ends (the generateResponse finally), but TTS playback
    // starts right after and would otherwise run with no foreground importance —
    // switch apps or turn the screen off and Android freezes the process, cutting
    // the reply off mid-sentence. Hands-free is already protected by
    // HandsFreeService, so this only guards plain read-aloud. It rides on the
    // ref-counted GenerationForegroundService and is driven by real playback
    // state plus a hard timeout (not the TTS completion callback, which is
    // unreliable across engines) so it can neither leak the wake lock nor release
    // while audio is still playing.
    private var readbackKeepAliveActive = false
    private var readbackKeepAliveToken = 0
    // Dedicated handler so the poll is never swept away by the hands-free
    // teardown's removeCallbacksAndMessages(null); release is always explicit.
    private val readbackKeepAliveHandler = Handler(Looper.getMainLooper())

    // Receiver for the notification "Hang Up" action (GenerationForegroundService
    // / HandsFreeService). A package-scoped, non-exported broadcast is the way the
    // service reaches the live activity that owns the TTS / recognizer / loop
    // state; on receipt it runs the same teardown as the in-app stop control.
    private val hangUpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_HANG_UP) return
            runOnUiThread {
                // Always persisted: a Hang Up can be fired without any human
                // tap on this phone (a paired watch/car surface, or any app
                // granted notification access can press notification buttons).
                // When a session ends "by itself", this line is the evidence.
                logVoiceEventAlways("Hang Up received from the notification action " +
                        "(the notification button, a paired device, or an app with notification access) — stopping everything")
                cancelAllAiActivity("notification Hang Up action")
                restoreUIState()
            }
        }
    }

    // Init preferences
    private var preferences: Preferences? = null

    private var onSpeechResultsScope: CoroutineScope? = null
    private var whisperScope: CoroutineScope? = null
    private var whisperPreloadScope: CoroutineScope? = null
    private var processRecordingScope: CoroutineScope? = null
    private var setupScope: CoroutineScope? = null
    private var imageRequestScope: CoroutineScope? = null
    private var speakScope: CoroutineScope? = null
    // Typed-send / regenerate generation (parseMessage). Was an anonymous
    // CoroutineScope, which meant NOTHING could cancel it — the stop control
    // could not reach a typed turn's generation at all. Stored so
    // killAllProcesses()/cancelAllAiActivity() can cancel it like every other
    // generation path.
    private var parseMessageScope: CoroutineScope? = null
    private var generateGptImageJob: Job? = null

    private fun killAllProcesses() {
        onSpeechResultsScope?.coroutineContext?.cancel(CancellationException("Killed"))
        whisperScope?.coroutineContext?.cancel(CancellationException("Killed"))
        whisperPreloadScope?.coroutineContext?.cancel(CancellationException("Killed"))
        processRecordingScope?.coroutineContext?.cancel(CancellationException("Killed"))
        setupScope?.coroutineContext?.cancel(CancellationException("Killed"))
        imageRequestScope?.coroutineContext?.cancel(CancellationException("Killed"))
        speakScope?.coroutineContext?.cancel(CancellationException("Killed"))
        parseMessageScope?.coroutineContext?.cancel(CancellationException("Killed"))
        generateGptImageJob?.cancel(CancellationException("Killed"))
        generateGptImageJob = null
        handsFreeStopped = true
        handsFreeReadbackExpected = false
        handsFreeHandler.removeCallbacksAndMessages(null)
        handsFreeSubmitRunnable = null
        handsFreeBuffer = ""
    }

    private fun restoreUIState() {
        runOnUiThread {
            progress?.visibility = View.GONE
            btnMicro?.isEnabled = true
            btnSend?.isEnabled = true
            isRecording = false
            // If a plain read-aloud is now playing (non-hands-free), keep the mic
            // as a STOP control rather than resetting it to idle — this runs in the
            // generateResponse finally right after pronounce() started the readback.
            // micIdle otherwise handles hiding the mic during a live conversation.
            if (readbackKeepAliveActive && !isHandsFreeEngaged()) micReadbackStop() else micIdle()
            // Return the conversation/send button to its resting look (waveform
            // or up-arrow depending on the box) after any turn/cancel finishes.
            refreshConversationButton()
            cancelState = false
            adapter?.clearSpeakingPosition()
            // The top action bar can get stuck INVISIBLE when a shared-element
            // transition is interrupted (see onResume). The onResume heal only
            // runs when the screen comes back to the foreground — a bar lost
            // mid-session (e.g. around a regenerate) stayed gone until the user
            // left and returned. Re-assert it at the end of every generation
            // too; it's an idempotent no-op when the bar is fine.
            restoreTopBarVisibility()
        }
    }

    // ---- Mic button visual states ------------------------------------------
    // The mic button doubles as a status light. Idle = plain mic (no tint);
    // listening = kelly-green stop icon + "Listening…" hint; hands-free stop =
    // red ✕ so the user can end the loop even while the reply is being read
    // back (the existing touch listener turns that tap into a full cancel).
    // These wrap the raw setImageResource() calls so a single helper owns both
    // the icon and the tint/hint; the apply{} form keeps the literal
    // setImageResource strings out of here so the bulk swap below is safe.
    private fun micIdle() {
        btnMicro?.apply {
            setImageResource(R.drawable.ic_microphone)
            clearColorFilter()
            backgroundTintList = null
            // Hidden while a hands-free conversation is live so it can't be tapped
            // by accident (the conversation button is the only control then); it
            // reappears the moment the loop stops. handsFreeStopped is already set
            // true by the stop funnels before they call micIdle, so isHandsFreeEngaged
            // is false here on a stop → the mic is shown again.
            visibility = if (isHandsFreeEngaged()) View.GONE else View.VISIBLE
        }
        messageInput?.hint = getString(R.string.hint_message)
    }

    private fun micRecording() {
        btnMicro?.apply {
            visibility = View.VISIBLE
            setImageResource(R.drawable.ic_stop_recording)
            setColorFilter(ResourcesCompat.getColor(resources, R.color.mic_listening_green, theme))
            backgroundTintList = null
        }
        messageInput?.hint = getString(R.string.hint_listening)
    }

    /**
     * The mic button turned into a STOP control while a plain (non-hands-free)
     * read-aloud is playing — the auto read-after-reply OR a manual speaker-button
     * re-read. A tap stops the readback (the mic's click/touch listeners already
     * route a tap during playback through cancelAllAiActivity, which silences it).
     * A red stop glyph on the normal button background, distinct from hands-free's
     * white-on-red conversation button so the two modes never look alike.
     */
    private fun micReadbackStop() {
        btnMicro?.apply {
            visibility = View.VISIBLE
            setImageResource(R.drawable.ic_stop_recording)
            setColorFilter(ResourcesCompat.getColor(resources, R.color.hands_free_active_red, theme))
            backgroundTintList = null
        }
    }

    /**
     * The CONVERSATION button (btnSend, the rightmost input-bar button) while a
     * hands-free conversation is live. A deep-red background (not just an icon
     * tint) is the always-on signal that the loop is running and a tap ends it —
     * the loop will not reopen on its own afterwards. Held for the whole session,
     * both while listening for the user and while the reply is being read back,
     * so the cue never flickers between turns (and so the user can stop the loop
     * at any point, including mid-readback, where btnSend's touch listener turns
     * the tap into a full cancel). The MIC button stays idle during hands-free —
     * it is single-turn only now; the conversation button owns the loop.
     *
     * @param listening true while the mic is actually open for the user; false
     *   while the assistant's reply is being read back (no barge-in: the
     *   recognizer is closed, so user speech can't interrupt the readback).
     */
    private fun micHandsFreeActive(listening: Boolean) {
        btnSend?.apply {
            setImageResource(R.drawable.ic_stop_recording)
            setColorFilter(ResourcesCompat.getColor(resources, R.color.white, theme))
            backgroundTintList = ColorStateList.valueOf(
                ResourcesCompat.getColor(resources, R.color.hands_free_active_red, theme)
            )
        }
        // Hide the mic entirely while the conversation is live so it can't be
        // tapped by accident; it comes back when the loop stops (micIdle).
        btnMicro?.visibility = View.GONE
        messageInput?.hint = getString(if (listening) R.string.hint_listening else R.string.hint_message)
    }

    /** True while a hands-free conversation is engaged (started from the
     *  conversation button and not yet stopped). The whole voice pipeline still
     *  gates its re-arm on [Preferences.getHandsFreeMode]; the conversation
     *  button is what flips that flag at runtime now (there is no settings
     *  toggle), and it is reset to false on every chat open so hands-free never
     *  auto-resumes. */
    private fun isHandsFreeEngaged(): Boolean =
        preferences?.getHandsFreeMode() == true && !handsFreeStopped

    /**
     * Resting look of the conversation/send button (btnSend) when NO hands-free
     * loop is running: the upward-arrow SEND glyph when the input box has text
     * (tap sends), otherwise the conversation waveform (tap starts hands-free).
     * The red "loop live" look is owned by [micHandsFreeActive], which paints
     * this same button — so this is a no-op while a conversation is engaged, to
     * avoid stomping that cue.
     */
    private fun refreshConversationButton() {
        if (isHandsFreeEngaged()) return
        btnSend?.apply {
            clearColorFilter()
            backgroundTintList = null
            setImageResource(
                if (!messageInput?.text.isNullOrEmpty()) R.drawable.ic_arrow_up
                else R.drawable.ic_conversation
            )
        }
    }

    /**
     * Token counting for the usage/cost display. BPE-encoding the ENTIRE
     * conversation history is real CPU work that grows with every exchange —
     * running it on the main thread (as this did, once or twice per turn,
     * right when the readback starts) froze the whole UI for seconds in long
     * conversations. A frozen main thread drops taps outright: the owner's
     * "the stop button just stayed red, like I wasn't hitting it" while the
     * voice kept talking — the TTS engine renders audio in its own process,
     * so speech keeps flowing while the app can't respond. The encode (and
     * the O(n²) usage summation in calculateCost) now run on a worker
     * dispatcher over an immutable snapshot; only the field assignments
     * happen on the main thread.
     */
    private suspend fun tokenizeArray() {
        if (chatMessages == null) chatMessages = arrayListOf()

        // Snapshot on the main thread: chatMessages is main-thread state and
        // can be edited while the encode runs on the worker.
        val snapshot = chatMessages.map { (it.role == Role.Assistant) to it.content.toString() }

        messagesUsageProjection = withContext(Dispatchers.Default) {
            // One tokenizer for the whole pass: constructing it inside the
            // loop rebuilt the BPE tables once per message, per turn.
            val tokenizer = Tokenizer.of(encoding = Encoding.CL100K_BASE)
            val projection = arrayListOf<HashMap<String, Any>>()
            for ((isBot, content) in snapshot) {
                val tokens = tokenizer.encode(content).size

                projection.add(
                    hashMapOf(
                        "isBot" to isBot,
                        "tokens" to if (content.trim().startsWith("~file:")) 0 else tokens
                    )
                )
            }
            projection
        }
    }

    private fun calculateCost() {
        CoroutineScope(Dispatchers.Main).launch {
            tokenizeArray()

            val projection = messagesUsageProjection

            // The summation is O(n²) over the message count — trivial for a
            // short chat, another main-thread stall for a months-long one.
            // Same math as always, just off the UI thread.
            val (totalIn, totalOut) = withContext(Dispatchers.Default) {
                var tIn = 0
                var tOut = 0

                var i = projection.size - 1

                while (i > 0) {
                    var j = 0
                    var c = 0

                    while (j < i) {
                        c += projection[j]["tokens"] as Int
                        j++
                    }

                    tIn += c
                    i--
                }

                for (m in projection) {
                    val msgUsage = if (m["isBot"] == true) m["tokens"] as Int else 0

                    tOut += msgUsage
                }

                tIn to tOut
            }

            usageIn = totalIn
            usageOut = totalOut
            inCost = usageIn * priceIn
            outCost = usageOut * priceOut
        }
    }

    private val speechListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { /* unused */ }
        override fun onBeginningOfSpeech() {
            handsFreeUserSpoke = true
            handsFreeTurnRetries = 0
            // User is talking again before the silence window elapsed; hold
            // the buffered transcript and wait for this fragment instead of
            // sending what we already have.
            handsFreeSubmitRunnable?.let { handsFreeHandler.removeCallbacks(it) }
            handsFreeSubmitRunnable = null
        }
        override fun onRmsChanged(rmsdB: Float) { /* unused */ }
        override fun onBufferReceived(buffer: ByteArray?) { /* unused */ }
        override fun onPartialResults(partialResults: Bundle?) { /* unused */ }
        override fun onEvent(eventType: Int, params: Bundle?) { /* unused */ }

        override fun onEndOfSpeech() {
            // In hands-free mode the loop manages the recording state itself.
            if (preferences?.getHandsFreeMode() == true && !handsFreeStopped) return
            isRecording = false
            micIdle()
        }

        override fun onError(error: Int) {
            if (preferences?.getHandsFreeMode() == true && !cancelState && !handsFreeStopped && isRecording) {
                val harmless = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                val waitingForFirstWord = !handsFreeUserSpoke && System.currentTimeMillis() < handsFreeListenDeadline
                val midUtterance = handsFreeBuffer.isNotEmpty() || handsFreeSubmitRunnable != null
                if (harmless && (waitingForFirstWord || midUtterance)) {
                    handsFreeHandler.postDelayed({
                        if (!isFinishing && !isDestroyed && isRecording && !handsFreeStopped && !cancelState) {
                            startRecognition(false)
                        }
                    }, 350)
                    return
                }
                // A session that dies with a real error before the user has
                // spoken is usually the recognition service refusing/dropping
                // the connection right after a readback re-arm (commonly with
                // the screen off, or ERROR_RECOGNIZER_BUSY while the previous
                // session is still releasing). Rebuild the recognizer and retry
                // before declaring the loop dead — silently stopping here is
                // the "mic never reopens after restarting a conversation" bug.
                if (!harmless && !handsFreeUserSpoke && handsFreeBuffer.isEmpty() &&
                    handsFreeTurnRetries < 2
                ) {
                    handsFreeTurnRetries++
                    logVoiceEvent("recognizer error $error before speech; " +
                            "rebuilding recognizer (retry $handsFreeTurnRetries)")
                    handsFreeHandler.postDelayed({
                        if (!isFinishing && !isDestroyed && isRecording && !handsFreeStopped && !cancelState) {
                            try { recognizer?.destroy() } catch (_: Exception) { /* ignore */ }
                            initSpeechListener()
                            startRecognition(false)
                        }
                    }, 400)
                    return
                }
                stopHandsFreeLoop("recognizer error $error (after ${handsFreeTurnRetries} rebuild retries)", notify = true)
                return
            }
            isRecording = false
            micIdle()
        }

        override fun onResults(results: Bundle?) {
            if (cancelState) {
                cancelState = false

                btnMicro?.isEnabled = true
                btnSend?.isEnabled = true
                progress?.visibility = View.GONE
                isRecording = false
                micIdle()
                return
            }

            // No barge-in: in hands-free mode, ignore any recognizer result that
            // lands while we're not actively listening — the reply is being read
            // back (handsFreeReadbackExpected), the loop has stopped, or this is
            // a late callback after cancel(). Acting on it would either
            // double-submit the turn or transcribe the assistant's own voice and
            // tear down the dark-red mic state mid-readback.
            if (preferences?.getHandsFreeMode() == true &&
                (handsFreeStopped || handsFreeReadbackExpected || !isRecording)
            ) {
                return
            }

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val recognizedText = matches?.firstOrNull().orEmpty().trim()

            if (preferences?.getHandsFreeMode() == true && !handsFreeStopped && isRecording) {
                // Hands-free: buffer this fragment, keep the mic open, and
                // schedule submission after the configured silence window so
                // we honour the user's "give me time to think" setting even
                // though the OS recognizer cut us off early.
                if (recognizedText.isNotEmpty()) {
                    handsFreeBuffer = if (handsFreeBuffer.isEmpty()) recognizedText
                                      else "$handsFreeBuffer $recognizedText"
                }
                // Always (re)schedule submission when the buffer has content.
                // onBeginningOfSpeech cancels the pending submit when the user
                // starts talking again, but if the recognizer then returns
                // empty text (cough, hiccup) the submit was never rescheduled
                // and the buffered text was orphaned.
                if (handsFreeBuffer.isNotEmpty()) {
                    scheduleHandsFreeSubmit()
                }
                if (!isFinishing && !isDestroyed && !cancelState) {
                    handsFreeHandler.postDelayed({
                        if (!isFinishing && !isDestroyed && isRecording && !handsFreeStopped && !cancelState) {
                            startRecognition(false)
                        }
                    }, 80)
                }
                return
            }

            isRecording = false
            micIdle()
            if (recognizedText.isNotEmpty()) submitRecognizedText(recognizedText)
        }
    }

    private fun scheduleHandsFreeSubmit() {
        handsFreeSubmitRunnable?.let { handsFreeHandler.removeCallbacks(it) }
        val silenceMs = (preferences?.getHandsFreeSilenceSeconds() ?: 5).coerceAtLeast(1) * 1000L
        val runnable = Runnable {
            val text = handsFreeBuffer
            handsFreeBuffer = ""
            handsFreeSubmitRunnable = null
            if (text.isEmpty()) return@Runnable
            try { recognizer?.cancel() } catch (_: Exception) { /* ignore */ }
            isRecording = false
            micIdle()
            submitRecognizedText(text)
        }
        handsFreeSubmitRunnable = runnable
        handsFreeHandler.postDelayed(runnable, silenceMs)
    }

    /**
     * Whether a just-finished SINGLE-TURN transcription should be sent
     * automatically. Rules (owner, July 2026):
     *   - hands-free engaged → always send (the loop needs it; the Auto-send
     *     setting only governs the manual mic button),
     *   - manual mic turn → honor Auto-send ONLY when the box was empty. If the
     *     user had already typed something, the transcript must always be left
     *     for review and never auto-sent — it is inserted at the cursor instead.
     * [boxWasEmpty] must be sampled BEFORE the transcript is inserted.
     */
    private fun shouldAutoSendTranscription(boxWasEmpty: Boolean): Boolean {
        if (isHandsFreeEngaged()) return true
        return preferences?.autoSend() == true && boxWasEmpty
    }

    /** Insert a transcript at the current cursor position (replacing any
     *  selection), leaving it for the user to review/send. Used when Auto-send is
     *  off, or whenever the box already had text (that case never auto-sends). */
    private fun insertTranscriptIntoBox(text: String) {
        val editable = messageInput?.text
        if (editable == null) {
            messageInput?.setText(text)
            return
        }
        val a = (messageInput?.selectionStart ?: editable.length).coerceIn(0, editable.length)
        val b = (messageInput?.selectionEnd ?: editable.length).coerceIn(0, editable.length)
        val start = minOf(a, b)
        val end = maxOf(a, b)
        editable.replace(start, end, text)
        messageInput?.setSelection((start + text.length).coerceAtMost(messageInput?.text?.length ?: 0))
        messageInput?.requestFocus()
    }

    private fun submitRecognizedText(recognizedText: String) {
        playTranscriptionDoneSignal()

        // Sample the box BEFORE inserting: an already-typed message must never be
        // auto-sent, even with Auto-send on.
        val boxWasEmpty = messageInput?.text.isNullOrEmpty()
        if (!shouldAutoSendTranscription(boxWasEmpty)) {
            restoreUIState()
            insertTranscriptIntoBox(recognizedText)
            return
        }

        putMessage(prefix + recognizedText + endSeparator, false)

        chatMessages.add(
            ChatMessage(
                role = ChatRole.User,
                content = prefix + recognizedText + endSeparator
            )
        )

        saveSettings()

        btnMicro?.isEnabled = false
        btnSend?.isEnabled = false
        progress?.visibility = View.VISIBLE

        onSpeechResultsScope = CoroutineScope(Dispatchers.Main)
        onSpeechResultsScope?.launch {
            progress?.setOnClickListener {
                cancel()
                restoreUIState()
            }

            try {
                generateResponse(prefix + recognizedText + endSeparator, true)
            } catch (_: CancellationException) {
                restoreUIState()
            }
        }
    }

    override fun onResume() {
        super.onResume()

        preloadAmoled()
        reloadAmoled()

        if (chatStartupComplete && chatId != "") {
            preferences = Preferences.getPreferences(this, chatId)
            apiEndpointPreferences = ApiEndpointPreferences.getApiEndpointPreferences(this)
            logitBiasPreferences = LogitBiasPreferences(this, preferences?.getLogitBiasesConfigId()!!)
            apiEndpointObject = apiEndpointPreferences?.getApiEndpoint(this, preferences?.getApiEndpointId()!!)
        }

        // Diagnostics may have been toggled in Settings while we were away.
        updateDebugLogButtonVisibility()

        // The mic permission can be revoked while we're away (system settings,
        // a one-time grant expiring). A session that thinks it's listening
        // without the permission must be shut down as a NAMED permission
        // failure, not left to surface later as mysterious silence.
        if (isRecording && !hasRecordAudioPermission()) {
            logVoiceEventAlways("microphone permission was revoked while a voice session was active — stopping capture")
            try { LocalWhisperEngine.get().cancel() } catch (_: Exception) { /* ignore */ }
            try { recognizer?.cancel() } catch (_: Exception) { /* ignore */ }
            if (preferences?.getHandsFreeMode() == true && !handsFreeStopped) {
                stopHandsFreeLoop("microphone permission revoked", notify = false)
            } else {
                isRecording = false
                micIdle()
            }
        }

        // Safety net for the top action bar. The settings cog is a shared-element
        // scene-transition target, so Android hides it (and can leave the bar in a
        // half-transitioned state) during the animation, restoring it when the
        // transition finishes. If that transition is interrupted — backgrounding
        // the app or killing the screen mid-animation — those views can get stuck
        // INVISIBLE until a manual redraw. Re-assert the bar shortly after we're
        // back in the foreground: a no-op once a normal transition has completed,
        // a fix when one was left dangling. The delay lets a legitimate return
        // animation play out instead of snapping.
        actionBar?.postDelayed({ restoreTopBarVisibility() }, 500)

        // A Companion picture / Default Shape may have changed while away (the
        // Companion editor, Profile Image settings). Re-resolve display-only.
        refreshCompanionAvatar()
    }

    /** Force the chat's top action bar and its buttons back to fully visible. */
    private fun restoreTopBarVisibility() {
        for (v in listOf(actionBar, btnBack, activityTitle, btnExport, btnSettings)) {
            v?.visibility = View.VISIBLE
            v?.alpha = 1f
        }
    }

    /**
     * Resolves the active Companion's assigned picture off the main thread and
     * hands it (with the current Default Shape) to ChatAdapter, which renders
     * it on the assistant side ahead of the legacy per-chat avatar and the
     * built-in glyph (profile-images-plan.md, CHAT AND CHAT-LIST DISPLAY).
     * Display-only and best-effort - it never touches generation or a turn.
     * Called on resume and whenever the chat's companion or the Default Shape
     * may have changed; a missing file resolves to null and falls through.
     */
    private fun refreshCompanionAvatar() {
        if (adapter == null) return
        val personaId = preferences?.getPersonaId().orEmpty()
        val shape = GlobalPreferences.getPreferences(this).getProfileImageShape()
        CoroutineScope(Dispatchers.Main).launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    if (personaId.isEmpty()) return@withContext null
                    val ref = PersonaPreferences.getPersonaPreferences(this@ChatActivity).getPersona(personaId).avatarRef
                    if (ref.isEmpty()) null else ProfileImageStore.getInstance(this@ChatActivity).imageFile(ref)
                } catch (_: Exception) {
                    null
                }
            }
            if (isFinishing || isDestroyed) return@launch
            adapter?.setCompanionAvatar(file, shape)
        }
    }

    @Suppress("deprecation")
    private fun preloadAmoled() {
        if (isDarkThemeEnabled() && GlobalPreferences.getPreferences(this).getAmoledPitchBlack()) {
            threadLoader?.backgroundTintList = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))

            if (Build.VERSION.SDK_INT < 30) {
                window.statusBarColor = ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme)
                window.navigationBarColor = ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme)
            }
        } else {
            threadLoader?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_1.getColor(this))

            if (Build.VERSION.SDK_INT < 30) {
                window.statusBarColor = SurfaceColors.SURFACE_1.getColor(this)
                window.navigationBarColor = SurfaceColors.SURFACE_1.getColor(this)
            }
        }
    }

    fun resizeBitmapToMaxHeight(bitmap: Bitmap, maxHeight: Int = 100): Bitmap {
        val originalHeight = bitmap.height
        val originalWidth = bitmap.width

        if (originalHeight <= maxHeight) {
            // Return a copy of the original bitmap if already smaller than or equal to maxHeight
            return bitmap.copy(bitmap.config ?: return bitmap, true)
        }

        // Calculate the new dimensions while keeping the aspect ratio
        val aspectRatio = originalWidth.toFloat() / originalHeight.toFloat()
        val newWidth = (maxHeight * aspectRatio).toInt()

        // Create the scaled bitmap
        return bitmap.scale(newWidth, maxHeight)
    }


    private var cameraIntentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val imageFile = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "tmp.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", imageFile)

            bitmap = readFile(uri)

            if (bitmap != null) {
                attachedImage?.visibility = View.VISIBLE

                val bitmapResizedForPreview = resizeBitmapToMaxHeight(bitmap!!, 100)

                selectedImage?.setImageBitmap(roundCorners(bitmapResizedForPreview))
                imageIsSelected = true

                val mimeType = contentResolver.getType(uri)
                val format = when {
                    mimeType.equals("image/png", ignoreCase = true) -> {
                        selectedImageType = "png"
                        Bitmap.CompressFormat.PNG
                    }
                    else -> {
                        selectedImageType = "jpg"
                        Bitmap.CompressFormat.JPEG
                    }
                }

                // Step 3: Convert the Bitmap to a Base64-encoded string
                val outputStream = ByteArrayOutputStream()
                bitmap!!.compress(format, 100, outputStream) // Note: Adjust the quality as necessary
                val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)

                // Step 4: Generate the data URL
                val imageType = when(format) {
                    Bitmap.CompressFormat.JPEG -> "jpeg"
                    Bitmap.CompressFormat.PNG -> "png"
                    // Add more mappings as necessary
                    else -> ""
                }

                baseImageString = "data:image/$imageType;base64,$base64Image"
            }
        }
    }

    private val permissionResultLauncherCamera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        run {
            if (result.resultCode == RESULT_OK) {
                val intent = Intent().setAction(MediaStore.ACTION_IMAGE_CAPTURE)
                intent.putExtra("android.intent.extra.quickCapture", true)
                val externalFilesDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                val imageFile = File(externalFilesDir, "tmp.jpg")
                intent.putExtra(MediaStore.EXTRA_OUTPUT, FileProvider.getUriForFile(this, "$packageName.fileprovider", imageFile))
                cameraIntentLauncher.launch(intent)
            }
        }
    }

    @Suppress("deprecation")
    private fun reloadAmoled() {
        ThemeManager.getThemeManager().applyTheme(this, isDarkThemeEnabled() && GlobalPreferences.getPreferences(this).getAmoledPitchBlack())
        window.statusBarColor = 0x00000000
        if (isDarkThemeEnabled() && GlobalPreferences.getPreferences(this).getAmoledPitchBlack()) {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S) {
                window.navigationBarColor = getColor(R.color.amoled_accent_100)
            }
            progress?.setBackgroundResource(R.drawable.assistant_clear_amoled)
            keyboardFrame?.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.amoled_accent_100, theme))
            actionBar?.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.amoled_accent_100, theme))
            activityTitle?.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.amoled_accent_100, theme))
            messageInput?.setHintTextColor(ResourcesCompat.getColor(resources, R.color.amoled_accent_900, theme))
            btnBack?.background = getAmoledAccentDrawable(
                AppCompatResources.getDrawable(
                    this,
                    R.drawable.btn_accent_tonal_v5_amoled
                )!!, this
            )

            btnExport?.background = getAmoledAccentDrawable(
                AppCompatResources.getDrawable(
                    this,
                    R.drawable.btn_accent_tonal_v5_amoled
                )!!, this
            )

            btnSettings?.background = getAmoledAccentDrawable(
                AppCompatResources.getDrawable(
                    this,
                    R.drawable.btn_accent_tonal_v5_amoled
                )!!, this
            )

            messageInput?.background = getAmoledAccentDrawableV2(
                AppCompatResources.getDrawable(
                    this,
                    R.drawable.btn_accent_tonal_selector_v6_amoled
                )!!, this
            )

            btnMicro?.background = getAmoledAccentDrawableV2(
                AppCompatResources.getDrawable(
                    this,
                    R.drawable.btn_accent_tonal_v5_amoled
                )!!, this
            )

            btnSend?.background = getAmoledAccentDrawableV2(
                AppCompatResources.getDrawable(
                    this,
                    R.drawable.btn_accent_tonal_v5_amoled
                )!!, this
            )
            btnAttachFile?.background = getAmoledAccentDrawableV2(
                AppCompatResources.getDrawable(
                    this,
                    R.drawable.btn_accent_tonal_v5_amoled
                )!!, this
            )
        } else {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                window.navigationBarColor = getColor(R.color.accent_100)
            }
            progress?.setBackgroundResource(R.drawable.assistant_clear_v2)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                keyboardFrame?.setBackgroundColor(SurfaceColors.SURFACE_2.getColor(this))
                actionBar?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
                activityTitle?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
            } else {
                keyboardFrame?.setBackgroundColor(getColor(R.color.accent_100))
                actionBar?.setBackgroundColor(getColor(R.color.accent_250))
                activityTitle?.setBackgroundColor(getColor(R.color.accent_250))
            }

            messageInput?.setHintTextColor(ResourcesCompat.getColor(resources, R.color.accent_900, theme))
            btnBack?.background = getDarkAccentDrawable(
                AppCompatResources.getDrawable(
                    this,
                    R.drawable.btn_accent_tonal_v4
                )!!, this
            )

            btnExport?.background = getDarkAccentDrawable(
                AppCompatResources.getDrawable(
                    this,
                    R.drawable.btn_accent_tonal_v4
                )!!, this
            )

            btnSettings?.background = getDarkAccentDrawable(
                AppCompatResources.getDrawable(
                    this,
                    R.drawable.btn_accent_tonal_v4
                )!!, this
            )

            messageInput?.background = getDarkAccentDrawable(
                AppCompatResources.getDrawable(
                    this,
                    R.drawable.btn_accent_tonal_selector_v6
                )!!, this
            )

            btnMicro?.background = getDarkAccentDrawable(
                AppCompatResources.getDrawable(
                    this,
                    R.drawable.btn_accent_tonal_v5
                )!!, this
            )

            btnSend?.background = getDarkAccentDrawable(
                AppCompatResources.getDrawable(
                    this,
                    R.drawable.btn_accent_tonal_v5
                )!!, this
            )
            btnAttachFile?.background = getDarkAccentDrawable(
                AppCompatResources.getDrawable(
                    this,
                    R.drawable.btn_accent_tonal_v5
                )!!, this
            )
        }
    }

    private fun isDarkThemeEnabled(): Boolean {
        return when (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            Configuration.UI_MODE_NIGHT_NO -> false
            Configuration.UI_MODE_NIGHT_UNDEFINED -> false
            else -> false
        }
    }

    // Init TTS
    private var tts: TextToSpeech? = null
    private var pendingSpeak: String? = null
    private var pendingSpeakSession: Int? = null
    private var ttsUtteranceCounter: Long = 0
    // Readback session stamp, bumped by every user stop (stopReadback()).
    // speak() can be reached through async hops — ML Kit language detection,
    // a main-looper post, a TTS re-init — and a stop that lands inside one of
    // those hops used to lose the race: the queued speak() fired anyway and
    // the reply was read out AFTER the user said stop. Every readback captures
    // the stamp at pronounce()/speak() time and re-checks it right before
    // handing text to the engine; a stale stamp means "the user stopped this"
    // and the utterance is dropped.
    private var readbackSession = 0
    // Text handed to each queued TTS utterance, kept by id so an asynchronous
    // failure reports the chunk the engine actually rejected.
    private val ttsUtteranceText = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Only the final chunk completing means the whole reply finished. Earlier
    // chunks must not reopen the hands-free mic.
    private var finalTtsUtteranceId: String? = null
    // Text not yet handed to the engine. Queue it only after the current chunk
    // finishes so recovery cannot replay completed text.
    private var ttsRemainingText = ""
    private var ttsChunkSession = 0
    // How far the engine actually got through the current utterance: the
    // character offset of the last speech range it reported via onRangeStart.
    // The failure retry resumes from here instead of replaying audio the user
    // already heard — an error arriving late in a long single-chunk reply used
    // to hand the ENTIRE already-spoken text back to the retry, so the whole
    // reply was read out twice, start to finish. An engine that never reports
    // ranges leaves this at 0, which keeps the old full-chunk retry as the
    // fallback. Guarded by the utterance id so a late callback from a flushed
    // utterance can't inflate the offset of the current one.
    @Volatile private var ttsRangeUtteranceId: String? = null
    @Volatile private var ttsSpokenRangeStart = 0
    // Did the current utterance actually begin speaking (onStart) before it
    // failed? Distinguishes "engine rejected it outright" from "failed
    // mid-synthesis" — the two have very different causes for the same -8.
    private var lastTtsUtteranceStarted = false
    // Consecutive failures for the current readback. A reply the engine keeps
    // rejecting is capped so it gives up after three errors instead of
    // re-initialising forever. Reset when a readback completes or a new one
    // starts.
    private var ttsErrorRetries = 0
    private val TTS_MAX_ERROR_RETRIES = 3
    private val ttsListener: TextToSpeech.OnInitListener =
        TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsPostInit()
                tts?.setOnUtteranceProgressListener(ttsProgressListener)
                isTTSInitialized = true
                val retainedText = pendingSpeak
                val retainedSession = pendingSpeakSession
                if (retainedText != null && retainedSession != null) {
                    pendingSpeak = null
                    pendingSpeakSession = null
                    Handler(Looper.getMainLooper()).post {
                        speak(retainedText, retainedSession)
                    }
                }
            } else {
                isTTSInitialized = false
                Log.w("TTS", "TextToSpeech init failed with status $status")
            }
        }

    private val ttsProgressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { lastTtsUtteranceStarted = true }
        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            // Progress marker for the failure retry: the engine is about to
            // speak [start, end) of the current utterance, so everything
            // before `start` has been said. Monotonic per utterance.
            if (utteranceId != null && utteranceId == ttsRangeUtteranceId &&
                start > ttsSpokenRangeStart
            ) {
                ttsSpokenRangeStart = start
            }
        }
        override fun onDone(utteranceId: String?) {
            if (utteranceId == null || ttsUtteranceText.remove(utteranceId) == null) return
            if (utteranceId != finalTtsUtteranceId) {
                val remainingText = ttsRemainingText
                ttsRemainingText = ""
                Handler(Looper.getMainLooper()).post {
                    if (remainingText.isNotEmpty()) speak(remainingText, ttsChunkSession)
                }
                return
            }
            finalTtsUtteranceId = null
            // A real readback completed — clear the failure budget.
            ttsErrorRetries = 0
            runOnUiThread { adapter?.clearSpeakingPosition() }
            onHandsFreeReadbackFinished()
        }
        @Suppress("OverridingDeprecatedMember")
        override fun onError(utteranceId: String?) {
            Log.w("TTS", "TTS utterance error: $utteranceId")
            handleTtsReadbackError(utteranceId, null)
        }
        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.w("TTS", "TTS utterance error code $errorCode: $utteranceId")
            handleTtsReadbackError(utteranceId, errorCode)
        }
    }

    /**
     * Common TTS readback-failure handler. Records the factual state at the
     * moment of failure (error code/name, the length of the text vs the engine's
     * max input length, the engine package, the voice/language) so a failure is
     * diagnosable instead of an opaque code, then either re-initialises the
     * engine and lets the loop continue, or — after three errors — gives up and
     * plays the existing response-failure tone. The hands-free handoff remains
     * the same as before.
     */
    private fun handleTtsReadbackError(utteranceId: String?, errorCode: Int?) {
        // Ignore any extra callbacks after this readback has already given up.
        if (ttsErrorRetries >= TTS_MAX_ERROR_RETRIES) return

        // Claim this queued chunk exactly once. Some engines invoke both
        // onError overloads; removing the entry makes the duplicate a no-op.
        val failedId: String?
        val failedText = if (utteranceId != null) {
            failedId = utteranceId
            ttsUtteranceText.remove(utteranceId) ?: return
        } else {
            val entry = ttsUtteranceText.entries.firstOrNull() ?: return
            if (!ttsUtteranceText.remove(entry.key, entry.value)) return
            failedId = entry.key
            entry.value
        }
        val retrySession = ttsChunkSession
        // Resume from where the engine actually stopped speaking, not from the
        // top of the chunk. failedText is the WHOLE current chunk, so a failure
        // near the end of a long reply used to replay everything the user had
        // already heard ("it read the entire reply twice"). The last reported
        // speech range is the only reliable spoken-this-far marker; resuming at
        // its start repeats at most the final word or sentence. Offset 0 (no
        // ranges reported) keeps the old full-chunk retry.
        val spokenOffset = if (failedId == ttsRangeUtteranceId) {
            ttsSpokenRangeStart.coerceIn(0, failedText.length)
        } else 0
        val unsaidText = failedText.substring(spokenOffset) + ttsRemainingText
        ttsRemainingText = ""
        finalTtsUtteranceId = null
        ttsErrorRetries++
        val codeText = errorCode?.let { "$it ${ttsErrorName(it)}" } ?: "unknown"
        val maxLen = try { TextToSpeech.getMaxSpeechInputLength() } catch (_: Throwable) { -1 }
        val engineName = try { tts?.defaultEngine ?: "?" } catch (_: Throwable) { "?" }
        val voiceName = try { tts?.voice?.name ?: "?" } catch (_: Throwable) { "?" }
        val langName = try { tts?.voice?.locale?.toString() ?: "?" } catch (_: Throwable) { "?" }
        // Factual descriptors of the failing text. The log is local-only, but
        // keep the sample short and newline-free so it's one readable line. A
        // blank text or a non-ASCII character are the usual content causes of a
        // -8, and "started" tells reject-outright apart from fail-mid-synthesis.
        val len = failedText.length
        val blank = failedText.isBlank()
        val nonAscii = failedText.any { it.code > 127 }
        val sample = failedText.take(80).replace("\n", " ").replace("\r", " ")
        val sampleSuffix = if (len > 80) "…" else ""
        runOnUiThread {
            logVoiceEventAlways(
                "TTS readback failed (error $codeText), attempt $ttsErrorRetries/$TTS_MAX_ERROR_RETRIES: " +
                "text=$len chars${if (blank) " BLANK" else ""}${if (nonAscii) " has-non-ASCII" else ""}, " +
                "spokenBeforeFailure=$spokenOffset chars, " +
                "started=$lastTtsUtteranceStarted, engine max $maxLen, engine=$engineName, " +
                "voice=$voiceName, lang=$langName, sample=\"$sample$sampleSuffix\""
            )
            adapter?.clearSpeakingPosition()
        }
        if (ttsErrorRetries >= TTS_MAX_ERROR_RETRIES) {
            pendingSpeak = null
            pendingSpeakSession = null
            ttsUtteranceText.clear()
            runOnUiThread {
                logVoiceEventAlways("TTS gave up on this readback after $ttsErrorRetries failures; continuing without reading it aloud")
                playErrorSignal()
            }
            onHandsFreeReadbackFinished()
            return
        }
        if (unsaidText.isBlank()) {
            // The engine had already spoken the entire text when it failed (an
            // error delivered in place of onDone) — there is nothing left to
            // say, so finish the readback instead of re-initialising and
            // replaying it.
            runOnUiThread {
                logVoiceEventAlways("TTS failed after speaking the whole text; treating readback as finished")
                adapter?.clearSpeakingPosition()
            }
            onHandsFreeReadbackFinished()
            return
        }
        pendingSpeak = unsaidText
        pendingSpeakSession = retrySession
        // The current playback watchdog must not mistake the retry gap for a
        // completed readback and reopen the mic.
        handsFreeReadbackToken++
        Handler(Looper.getMainLooper()).post {
            if (retrySession == readbackSession &&
                pendingSpeak != null &&
                pendingSpeakSession == retrySession
            ) {
                reinitTTS()
            }
        }
    }

    private fun ttsErrorName(code: Int): String = when (code) {
        TextToSpeech.ERROR -> "ERROR"
        TextToSpeech.ERROR_SYNTHESIS -> "ERROR_SYNTHESIS"
        TextToSpeech.ERROR_SERVICE -> "ERROR_SERVICE"
        TextToSpeech.ERROR_OUTPUT -> "ERROR_OUTPUT"
        TextToSpeech.ERROR_NETWORK -> "ERROR_NETWORK"
        TextToSpeech.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        TextToSpeech.ERROR_INVALID_REQUEST -> "ERROR_INVALID_REQUEST"
        TextToSpeech.ERROR_NOT_INSTALLED_YET -> "ERROR_NOT_INSTALLED_YET"
        else -> "(unknown)"
    }

    /**
     * Re-arm the mic after the assistant finishes reading a reply, so the
     * hands-free conversation keeps looping. Called both from the device-TTS
     * UtteranceProgressListener and from the OpenAI-voice MediaPlayer
     * completion — previously only the device-TTS path restarted, so picking
     * a cloud voice silently broke hands-free. Logs why it skipped a restart
     * to make this diagnosable from logcat.
     */
    private fun maybeRestartHandsFreeAfterReadback() {
        val handsFree = preferences?.getHandsFreeMode() == true
        val effModel = preferences?.getEffectiveAudioModel()
        val sttSupported = effModel == "google" || effModel == "whisper-local"
        val auto = preferences?.autoSend() == true
        if (handsFree && sttSupported && auto && !cancelState && !handsFreeStopped && !isRecording &&
            !isFinishing && !isDestroyed
        ) {
            // If audio is somehow still audible (the watchdog can race the real
            // completion), opening the mic now would mute the rest of the
            // readback and let the recognizer transcribe the assistant's own
            // voice. Re-arm the watch and wait for playback to drain instead.
            val stillPlaying = (try { tts?.isSpeaking == true } catch (_: Exception) { false }) ||
                               (try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false })
            if (stillPlaying) {
                handsFreeReadbackExpected = true
                beginHandsFreeReadbackWatch()
                return
            }
            handsFreeTurnRetries = 0
            logVoiceEvent("readback finished; reopening mic ($effModel)")
            if (effModel == "whisper-local") {
                // Re-arm an on-device Whisper turn; the service and
                // loop are already running so this is not a fresh turn.
                startLocalWhisperHandsFreeTurn(freshTurn = false)
            } else {
                isRecording = true
                micHandsFreeActive(listening = true)
                startRecognition(true)
            }
        } else if (handsFree) {
            logVoiceEvent("mic NOT reopened after readback: engine=$effModel autoSend=$auto " +
                    "cancelled=$cancelState loopStopped=$handsFreeStopped alreadyRecording=$isRecording")
        }
    }

    /**
     * Single funnel for "the reply finished, open the mic for the next turn".
     * Both the TTS completion callback and the playback watchdog call this;
     * bumping the token means whichever arrives first wins and the other
     * becomes a no-op, so the next turn is started exactly once. Completions
     * for playback that is not a loop readback (a manual speaker-button
     * re-read) are ignored entirely — those must never reopen the mic.
     */
    private fun onHandsFreeReadbackFinished() {
        if (!handsFreeReadbackExpected) return
        handsFreeReadbackExpected = false
        handsFreeReadbackToken++
        // TTS completion arrives on a binder thread, but all loop state
        // (isRecording, cancelState, …) is owned by the main thread. Deciding
        // off-thread on stale values could skip the restart permanently (the
        // token above already killed the watchdog), so hop threads first.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            maybeRestartHandsFreeAfterReadback()
        } else {
            handsFreeHandler.post { maybeRestartHandsFreeAfterReadback() }
        }
    }

    /**
     * Safety net for the readback→listen handoff. The loop's primary trigger is
     * the TTS completion callback (device [ttsProgressListener] onDone or the
     * OpenAI MediaPlayer onCompletion), but those callbacks are not reliable
     * across the many TTS engines — a dropped one silently strands the mic,
     * which is the long-standing "hands-free never reopens the mic" bug. This
     * poller instead watches the real playback state: once it has seen audio
     * actually start and then stop, it re-arms the next turn. If speech never
     * starts within a hard timeout (engine swallowed the utterance entirely),
     * it re-arms anyway so the conversation can't dead-end. The token makes the
     * faster of the two paths win; the others no-op. Re-armed for every
     * hands-free readback from [pronounce].
     */
    private fun beginHandsFreeReadbackWatch(
        startTimeoutMs: Long = HANDS_FREE_READBACK_START_TIMEOUT_MS
    ) {
        if (preferences?.getHandsFreeMode() != true) return
        // Manual speaker-button re-reads are not loop readbacks and get no
        // watchdog — finishing one must not reopen the mic.
        if (!handsFreeReadbackExpected) return
        val token = ++handsFreeReadbackToken
        val startedAt = System.currentTimeMillis()
        var everPlaying = false
        var quietPolls = 0
        lateinit var poll: Runnable
        poll = Runnable {
            // Superseded by a faster completion path, or the loop ended.
            if (token != handsFreeReadbackToken) return@Runnable
            if (isFinishing || isDestroyed || cancelState || handsFreeStopped || isRecording) return@Runnable
            val playing = (try { tts?.isSpeaking == true } catch (_: Exception) { false }) ||
                          (try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false })
            if (playing) {
                everPlaying = true
                quietPolls = 0
            } else if (everPlaying) {
                quietPolls++
            }
            val elapsed = System.currentTimeMillis() - startedAt
            when {
                // Readback was heard and has stayed quiet for several polls —
                // continue the loop. A single quiet poll isn't enough: engines
                // blip isSpeaking false mid-utterance, and reopening the mic on
                // a blip used to cut the readback off halfway through.
                everPlaying && quietPolls >= HANDS_FREE_READBACK_STOP_POLLS -> onHandsFreeReadbackFinished()
                // Speech never started in time and nothing is queued behind a
                // TTS re-init; assume the utterance was lost and re-arm so the
                // conversation doesn't dead-end.
                !everPlaying && pendingSpeak == null && elapsed > startTimeoutMs -> {
                    logVoiceEvent("readback never became audible within ${elapsed}ms; continuing the loop anyway")
                    onHandsFreeReadbackFinished()
                }
                // Absolute cap so a stuck TTS re-init can't strand the loop.
                !everPlaying && elapsed > HANDS_FREE_HARD_FALLBACK_MS -> {
                    logVoiceEvent("readback never started after ${elapsed}ms (hard fallback); continuing the loop anyway")
                    onHandsFreeReadbackFinished()
                }
                else -> handsFreeHandler.postDelayed(poll, HANDS_FREE_READBACK_POLL_MS)
            }
        }
        handsFreeHandler.postDelayed(poll, HANDS_FREE_READBACK_POLL_MS)
    }

    private fun reinitTTS() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) { /* ignore */ }
        isTTSInitialized = false
        tts = TextToSpeech(this, ttsListener)
    }

    // One-shot guard for the delivery-tuning retry so overlapping ttsPostInit
    // calls (init listener + the language-detect reset path) can't stack
    // multiple scheduled re-applications. Main thread only.
    private var ttsTuningRetryPending = false

    /**
     * Applies the saved speech rate / pitch to the device TTS engine and
     * checks whether the engine ACCEPTED them. The Google engine can reject a
     * call made at the exact moment init completes (returns ERROR, throws
     * nothing) — the old code ignored the result, so the whole session spoke
     * at the engine's default rate, faster than the saved value, with no
     * trace ("the readback suddenly talks faster on a new session", owner
     * report July 11 2026). On rejection the SAME saved values are re-applied
     * exactly once, shortly after init (never per utterance, never a loop);
     * only rejection/fallback is logged — success is silent. The saved value,
     * UI, defaults and playback behavior are untouched. The system-wide
     * Android speech rate (Accessibility settings) multiplies the app's rate
     * and is external — it cannot be seen or changed from here.
     */
    private fun applyTtsDeliveryTuning(isRetry: Boolean) {
        val engine = tts ?: return
        val prefs = preferences
        if (prefs == null) {
            // Settings not loaded yet — the engine would run at its default.
            // The one-shot retry re-reads the saved values once loaded.
            logVoiceEventAlways("TTS engine initialized before settings loaded — saved speech rate not applied" +
                    if (isRetry) " (retry also ran too early; this session may use the engine's default rate)"
                    else "; re-applying once shortly")
            if (!isRetry) scheduleTtsTuningRetry()
            return
        }
        val rate = prefs.getTtsSpeechRate()
        val pitch = prefs.getTtsPitch()
        val rateResult = try { engine.setSpeechRate(rate) } catch (_: Throwable) { TextToSpeech.ERROR }
        val pitchResult = try { engine.setPitch(pitch) } catch (_: Throwable) { TextToSpeech.ERROR }
        when (org.teslasoft.assistant.stt.TtsTuningPolicy.afterApply(rateResult, pitchResult, isRetry)) {
            org.teslasoft.assistant.stt.TtsTuningPolicy.Next.DONE -> {
                /* accepted — deliberately no successful-operation logging */
            }
            org.teslasoft.assistant.stt.TtsTuningPolicy.Next.RETRY_ONCE -> {
                logVoiceEventAlways("TTS engine rejected the saved delivery tuning at init " +
                        "(rate=$rate result=$rateResult, pitch=$pitch result=$pitchResult) — re-applying once")
                scheduleTtsTuningRetry()
            }
            org.teslasoft.assistant.stt.TtsTuningPolicy.Next.GIVE_UP -> {
                logVoiceEventAlways("TTS engine rejected the saved delivery tuning again on the retry " +
                        "(rate=$rate result=$rateResult, pitch=$pitch result=$pitchResult) — " +
                        "this session may speak at the engine's default rate")
            }
        }
    }

    private fun scheduleTtsTuningRetry() {
        if (ttsTuningRetryPending) return
        ttsTuningRetryPending = true
        Handler(Looper.getMainLooper()).postDelayed({
            ttsTuningRetryPending = false
            if (!isFinishing && !isDestroyed) applyTtsDeliveryTuning(isRetry = true)
        }, 750)
    }

    private fun ttsPostInit() {
        // Delivery tuning (advanced voice settings). Device-TTS only; the
        // OpenAI voice renders server-side and ignores these. Applied with
        // accept/reject verification — see applyTtsDeliveryTuning.
        applyTtsDeliveryTuning(isRetry = false)
        if (!autoLangDetect) {
            val result = tts!!.setLanguage(
                LocaleParser.parse(
                    preferences!!.getLanguage()
                )
            )

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w("TTS", "Language missing or unsupported: ${preferences!!.getLanguage()}")
            }

            // tts.voices is declared non-null but the platform can return null
            // (engine reports init success before voice metadata is ready, or
            // doesn't support enumeration), which previously crashed the app
            // with an NPE on the TTS init thread. Guard it and fail soft.
            val voices: Set<Voice>? = try {
                tts!!.voices
            } catch (t: Throwable) {
                Log.w("TTS", "Could not query voices", t)
                null
            }
            if (voices != null) {
                for (v: Voice in voices) {
                    if (v.name == preferences!!.getVoice()) {
                        tts!!.voice = v
                    }
                }
            }
        }
    }

    // Init permissions screen
    private val permissionResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        run {
            if (result.resultCode == RESULT_OK) {
                startRecognition()
            }
        }
    }

    private val permissionResultLauncherV2 = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        run {
            if (result.resultCode == RESULT_OK) {
                startWhisper()
            }
        }
    }

    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { recreate() }

    // Opened from [promptCreateFirstCompanion] when a new chat has no companion
    // to open with because none exist yet. On a companion being created the
    // list returns it; adopt it as this chat's companion AND as the last-used
    // default, and mark seeding done so it isn't re-run.
    private val createFirstCompanionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val personaId = result.data?.getStringExtra("personaId")
            if (!personaId.isNullOrEmpty()) {
                preferences?.setPersonaId(personaId)
                preferences?.setLastUsedPersonaId(personaId)
                preferences?.setPersonaActivationSeeded(true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= 30) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
            )
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val transition = TransitionInflater.from(this).inflateTransition(android.R.transition.move).apply {
            interpolator = LinearOutSlowInInterpolator()
            duration = 300
        }

        val transition2 = TransitionInflater.from(this).inflateTransition(android.R.transition.move).apply {
            interpolator = FastOutLinearInInterpolator()
            duration = 200
        }

        // Set the transition as the shared element enter transition
        window.sharedElementEnterTransition = transition
        window.sharedElementExitTransition = transition2

        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)

        Thread {
            // Round 4 ordering is load-bearing: resolve the storage lock before
            // touching an encrypted API key or chat history. All Keystore-backed
            // work stays on this worker; only the final UI branch runs on main.
            val startupAttempt = runCatching { prepareChatStartup() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val startupResult = startupAttempt.getOrElse { throw it }

                if (startupResult.storageLocked) {
                    startActivity(Intent(this, ChatStorageLockedActivity::class.java).setAction(Intent.ACTION_VIEW))
                    finish()
                    return@runOnUiThread
                }

                initializeChatUi(startupResult.preparedChat!!, savedInstanceState)
            }
        }.start()
    }

    /**
     * Cold-start storage work for a chat. The lock gate remains first so a
     * Keystore outage can never masquerade as an empty API key or empty chat.
     */
    private fun prepareChatStartup(): ChatStartupResult {
        if (SecurePrefs.isChatStorageLocked(this)) {
            return ChatStartupResult(storageLocked = true)
        }

        val extras: Bundle? = intent.extras
        val preparedChatId = extras?.getString("chatId", "") ?: ""
        val preparedChatName = extras?.getString("name", "") ?: ""
        val preparedPreferences = Preferences.getPreferences(this, preparedChatId)
        val preparedEndpointPreferences = ApiEndpointPreferences.getApiEndpointPreferences(this)
        val preparedLogitBiasPreferences = LogitBiasPreferences(
            this,
            preparedPreferences.getLogitBiasesConfigId()
        )
        val preparedEndpoint = preparedEndpointPreferences.getApiEndpoint(
            this,
            preparedPreferences.getApiEndpointId()
        )
        val historyResult = ChatPreferences.getChatPreferences()
            .getChatByIdResult(this, preparedChatId)

        return ChatStartupResult(
            storageLocked = false,
            preparedChat = PreparedChatStartup(
                preparedChatId,
                preparedChatName,
                preparedPreferences,
                preparedEndpointPreferences,
                preparedLogitBiasPreferences,
                preparedEndpoint,
                historyResult
            )
        )
    }

    /** Build the chat screen only after its encrypted startup data is ready. */
    private fun initializeChatUi(prepared: PreparedChatStartup, savedInstanceState: Bundle?) {
        chatId = prepared.chatId
        chatName = prepared.chatName
        preferences = prepared.preferences
        apiEndpointPreferences = prepared.apiEndpointPreferences
        logitBiasPreferences = prepared.logitBiasPreferences
        apiEndpointObject = prepared.apiEndpointObject
        title = chatName

        // Hands-free is a live, per-session control started from the conversation
        // button — never a persisted setting (there is no settings toggle any
        // more). Opening a chat always starts disengaged; the flag is only ever
        // turned on by an explicit button tap, so a value left over from a
        // previous session (or a hard kill mid-loop) can never auto-resume a
        // conversation the moment the chat opens.
        preferences?.setHandsFreeMode(false)
        handsFreeStopped = false

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                if (bulkSelectionMode) {
                    deselectAll()
                } else {
                    finishActivity()
                }
            }
        } else {
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (bulkSelectionMode) {
                        deselectAll()
                    } else {
                        finishActivity()
                    }
                }
            })
        }

        setContentView(R.layout.activity_chat)

        // Listen for the notification "Hang Up" action. Registered for the life of
        // the activity (not just the foreground window) so it still fires while the
        // chat is backgrounded with the screen off — exactly when the keep-alive
        // bar is the only way to stop a readback. Not exported: only our own
        // services post this package-scoped broadcast.
        ContextCompat.registerReceiver(
            this,
            hangUpReceiver,
            IntentFilter(ACTION_HANG_UP),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        preloadAmoled()
        reloadAmoled()

        mediaPlayer = MediaPlayer()
        threadLoader = findViewById(R.id.thread_loader)
        threadLoader?.visibility = View.VISIBLE

        val chatActivityTitle: TextView = findViewById(R.id.chat_activity_title)
        val keyboardInput: LinearLayout = findViewById(R.id.keyboard_input)

        chatActivityTitle.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
        keyboardInput.setBackgroundColor(SurfaceColors.SURFACE_5.getColor(this))

        initSettings(prepared.historyResult)

        // The Activity window may already be attached because startup storage
        // now loads on a worker. Apply insets explicitly once the chat views
        // exist; onAttachedToWindow may have run before setContentView.
        adjustPaddings()

        if (savedInstanceState != null) {
            onRestoredState(savedInstanceState)
        }

        chatStartupComplete = true
    }

    public override fun onDestroy() {
        // Tombstone for the event log: when the OS (or a navigation flow)
        // destroys this screen while a voice conversation is live, everything
        // below silently kills the readback and the loop. Without this line
        // the user sees "the voice just stopped / the mic never came back"
        // with no trace anywhere.
        val voiceWasLive = isRecording || handsFreeReadbackExpected ||
                (try { tts?.isSpeaking == true } catch (_: Exception) { false }) ||
                (try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false })
        if (voiceWasLive) {
            logVoiceEvent("chat screen destroyed while voice was active — readback and mic loop torn down" +
                    if (isFinishing) " (screen was closed)" else " (destroyed by the system)")
        }
        if (tts != null) {
            tts!!.stop()
            tts!!.shutdown()
        }
        // Null-safe: when the locked-storage gate finishes onCreate early,
        // mediaPlayer was never constructed but onDestroy still runs.
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer!!.stop()
            mediaPlayer!!.reset()
        }

        try { unregisterReceiver(hangUpReceiver) } catch (_: Exception) { /* not registered */ }
        // Release the last ML Kit language-detector client (see pronounce()).
        try { languageIdentifier?.close() } catch (_: Exception) { /* ignore */ }
        // The read-aloud keep-alive must not outlive the activity: its poll runs on
        // a handler tied to this instance, so without this the service could hold a
        // wake lock with nothing to release it.
        releaseReadbackKeepAlive()
        readbackKeepAliveHandler.removeCallbacksAndMessages(null)

        killAllProcesses()
        stopHandsFreeService()

        // Fully release the microphone when the chat is destroyed (app closed).
        // The SpeechRecognizer holds a live binding to the system recognition
        // service; if it's never destroyed it can keep the mic/recognizer tied
        // up system-wide and starve other apps' voice input (keyboard voice
        // typing, other AI voice). Releasing the whisper AudioRecord here covers
        // the on-device path the same way. Background/screen-off hands-free is
        // intentionally untouched — this only runs when the activity is gone.
        try { recognizer?.cancel() } catch (_: Exception) { /* ignore */ }
        try { recognizer?.destroy() } catch (_: Exception) { /* ignore */ }
        recognizer = null
        try { LocalWhisperEngine.get().cancel() } catch (_: Exception) { /* ignore */ }

        super.onDestroy()
    }

    /** SYSTEM INITIALIZATION START **/
    /** Reload path used after an in-chat image update; storage stays off main. */
    private fun initSettings() {
        val appContext = applicationContext
        val currentChatId = chatId
        Thread {
            val historyResult = ChatPreferences.getChatPreferences()
                .getChatByIdResult(appContext, currentChatId)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) initSettings(historyResult)
            }
        }.start()
    }

    @Suppress("unchecked")
    private fun initSettings(historyResult: ChatPreferences.ChatHistoryResult) {
        key = apiEndpointObject?.apiKey!!
        // The auxiliary client (cloud Whisper, TTS, image generation,
        // function calling) must follow the active chat's endpoint. It used
        // to grab a key from any saved api.openai.com endpoint, which leaked
        // audio and message content to OpenAI while chatting with a
        // local/custom endpoint.
        openAIKey = apiEndpointObject?.apiKey

        endSeparator = preferences!!.getEndSeparator()
        prefix = preferences!!.getPrefix()

        loadResolution()

        if (key == null) {
            startActivity(Intent(this, WelcomeActivity::class.java).setAction(Intent.ACTION_VIEW))
            finishActivity()
        } else {
            silenceMode = preferences!!.getSilence()
            autoLangDetect = preferences!!.getAutoLangDetect()
            messages = historyResult.messages

            // A LOCKED/CORRUPT/FAILED history must never render as an empty
            // conversation the user can talk into (Round 4): the encrypted
            // value is preserved and write-blocked in ChatPreferences; here
            // the owner-approved blocking state covers the screen and every
            // send/save path checks chatStorageUnavailable.
            if (!ChatStorageHealth.isAuthoritative(historyResult.state)) {
                chatStorageUnavailable = true
                MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                    .setTitle(R.string.chat_unavailable_title)
                    .setMessage(R.string.chat_unavailable_body)
                    .setCancelable(false)
                    .setPositiveButton(R.string.chat_unavailable_back) { _, _ -> finishActivity() }
                    .show()
            }

            // R8 fix
            if (messages == null) messages = arrayListOf()
            if (chatMessages == null) chatMessages = arrayListOf()

            // A reply still marked "streaming" on disk means the previous
            // session died mid-generation and nothing wrote a terminal state
            // (a hard process kill runs no code on the way out). Reconcile it
            // to "interrupted" once so it can't masquerade as a finished reply,
            // and persist. Idempotent; the partial text is untouched.
            var reconciledStreaming = false
            for (message: HashMap<String, Any> in messages) {
                val reconciled = MessageCompletionState.reconcileOnLoad(
                    message[MessageCompletionState.KEY_STATE]?.toString()
                )
                if (reconciled != null) {
                    message[MessageCompletionState.KEY_STATE] = reconciled
                    message[MessageCompletionState.KEY_STATE_DETAIL] = MessageCompletionState.DETAIL_PROCESS_DEATH
                    reconciledStreaming = true
                }
            }
            if (reconciledStreaming) saveSettings()

            for (message: HashMap<String, Any> in messages) {
                if (!message["message"].toString().contains("data:image")) {
                    if (message["isBot"] == true) {
                        chatMessages.add(
                            ChatMessage(
                                role = ChatRole.Assistant,
                                content = modelFacingContent(message)
                            )
                        )
                    } else {
                        chatMessages.add(
                            ChatMessage(
                                role = ChatRole.User,
                                content = message["message"].toString()
                            )
                        )
                    }
                }
            }

            updateMessagesSelectionProjection()

            calculateCost()

            adapter = ChatAdapter(messages, messagesSelectionProjection,this, preferences!!, false, chatId)
            adapter?.setOnUpdateListener(this)

            initUI()
            reloadAmoled()
            initSpeechListener()
            initTTS()
            initLogic()
            initAI()
        }
    }

    @SuppressLint("SetTextI18n", "ClickableViewAccessibility", "NotifyDataSetChanged")
    private fun initUI() {
        btnMicro = findViewById(R.id.btn_micro)
        btnSettings = findViewById(R.id.btn_settings)
        chat = findViewById(R.id.messages)
        messageInput = findViewById(R.id.message_input)
        btnSend = findViewById(R.id.btn_send)
        progress = findViewById(R.id.progress)
        activityTitle = findViewById(R.id.chat_activity_title)
        btnExport = findViewById(R.id.btn_export)
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        btnDebugLog = findViewById(R.id.btn_debug_log)
        keyboardFrame = findViewById(R.id.keyboard_frame)
        root = findViewById(R.id.root)
        btnAttachFile = findViewById(R.id.btn_attach)
        attachedImage = findViewById(R.id.attachedImage)
        selectedImage = findViewById(R.id.selectedImage)
        btnRemoveImage = findViewById(R.id.btnRemoveImage)
        visionActions = findViewById(R.id.vision_action_selector)
        btnVisionActionCamera = findViewById(R.id.action_camera)
        btnVisionActionGallery = findViewById(R.id.action_gallery)
        bulkContainer = findViewById(R.id.bulk_container)
        btnSelectAll = findViewById(R.id.btn_select_all)
        btnDeselectAll = findViewById(R.id.btn_deselect_all)
        btnDeleteSelected = findViewById(R.id.btn_delete_selected)
        btnCopySelected = findViewById(R.id.btn_copy_selected)
        btnShareSelected = findViewById(R.id.btn_share_selected)
        selectedCount = findViewById(R.id.text_selected_count)
        expandableWindowRoot = findViewById(R.id.expandable_window_root)
        blurSelectorView = findViewById(R.id.attach_bg)

        val radius = 16f
        val decorView = window.decorView
        val rootView = decorView.findViewById<ViewGroup>(android.R.id.content)
        val windowBackground = decorView.background
        blurSelectorView?.setupWith(rootView)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(radius)

        blurSelectorView?.outlineProvider = ViewOutlineProvider.BACKGROUND
        blurSelectorView?.setClipToOutline(true)

        if (isDarkThemeEnabled() && GlobalPreferences.getPreferences(this).getAmoledPitchBlack()) {
            expandableWindowRoot?.backgroundTintList = ColorStateList.valueOf(getColor(R.color.amoled_window_background))
        } else {
            expandableWindowRoot?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_1.getColor(this))
        }

        bulkContainer?.visibility = View.GONE

        chat?.itemAnimator = null

        visionActions?.visibility = View.GONE

        attachedImage?.visibility = View.GONE

        btnExport?.setImageResource(R.drawable.ic_upload)
        btnBack?.setImageResource(R.drawable.ic_back)

        activityTitle?.text = if (chatName.trim().contains("_autoname_")) "Untitled chat" else chatName

        activityTitle?.isSelected = true

        progress?.visibility = View.GONE

        micIdle()
        // Initial resting look for the conversation/send button (empty box → the
        // conversation waveform).
        refreshConversationButton()
        btnSettings?.setImageResource(R.drawable.ic_settings)

        btnSelectAll?.setOnClickListener {
            selectAll()
        }

        btnDeselectAll?.setOnClickListener {
            deselectAll()
        }

        btnDeleteSelected?.setOnClickListener {
            deleteSelectedMessages()
        }

        btnCopySelected?.setOnClickListener {
            copySelectedMessages()
        }

        btnShareSelected?.setOnClickListener {
            shareSelectedMessages()
        }

        btnExport?.background = getDarkAccentDrawable(
            AppCompatResources.getDrawable(
                this,
                R.drawable.btn_accent_tonal_v4
            )!!, this
        )

        btnBack?.background = getDarkAccentDrawable(
            AppCompatResources.getDrawable(
                this,
                R.drawable.btn_accent_tonal_v4
            )!!, this
        )

        btnSettings?.background = getDarkAccentDrawable(
            AppCompatResources.getDrawable(
                this,
                R.drawable.btn_accent_tonal_v4
            )!!, this
        )

        btnBack?.setOnClickListener {
            finishActivity()
        }

        activityTitle?.setOnClickListener {
            val quickSettingsBottomSheetDialogFragment = QuickSettingsBottomSheetDialogFragment
                .newInstance(
                    chatId,
                    usageIn,
                    usageOut,
                    priceIn,
                    priceOut
                )
            quickSettingsBottomSheetDialogFragment.setOnUpdateListener(object : QuickSettingsBottomSheetDialogFragment.OnUpdateListener {
                override fun onUpdate() {
                    // The chat's Companion may have changed here without a full
                    // reload; re-resolve the assistant-side picture (display-only).
                    refreshCompanionAvatar()
                }

                override fun onForceUpdate() {
                    startActivity(Intent(this@ChatActivity, ChatActivity::class.java).putExtra("chatId", chatId).putExtra("name", chatName).setAction(Intent.ACTION_VIEW))
                    finishActivity()
                }
            })
            quickSettingsBottomSheetDialogFragment.show(supportFragmentManager, "QuickSettingsBottomSheetDialogFragment")
        }

        val linearLayoutManager = LinearLayoutManager(this)
        // linearLayoutManager.stackFromEnd = true

        chat?.setLayoutManager(linearLayoutManager)

        val itemTouchHelper = ItemTouchHelper(itemTouchCallback)
        itemTouchHelper.attachToRecyclerView(chat)

        chat?.adapter = adapter

        adapter?.notifyDataSetChanged()

        // First paint of the assistant-side Companion picture (resolved off-main).
        refreshCompanionAvatar()

        chat?.post {
            chat?.scrollToPosition(adapter?.itemCount!! - 1)
        }

        chat?.setOnTouchListener { _, event -> run {
            if (event.action == MotionEvent.ACTION_SCROLL || event.action == MotionEvent.ACTION_UP) {
                // chat?.transcriptMode = ListView.TRANSCRIPT_MODE_DISABLED
                disableAutoScroll = true
            }
            return@setOnTouchListener false
        }}

        Handler(Looper.getMainLooper()).postDelayed({
            val fadeOut: Animation = AnimationUtils.loadAnimation(this, R.anim.fade_out)
            threadLoader?.startAnimation(fadeOut)

            fadeOut.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation) { /* UNUSED */ }
                override fun onAnimationEnd(animation: Animation) {
                    runOnUiThread {
                        threadLoader?.visibility = View.GONE
                        threadLoader?.elevation = 0.0f
                        reloadAmoled()
                    }
                }

                override fun onAnimationRepeat(animation: Animation) { /* UNUSED */ }
            })
        }, 50)
    }

    private val itemTouchCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            return false
        }

        @SuppressLint("NotifyDataSetChanged")
        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, swipeDir: Int) {
            val position = viewHolder.bindingAdapterPosition

            viewHolder.itemView.post {
                adapter?.notifyItemChanged(position)
                adapter?.notifyDataSetChanged() // ??? ...

                if (viewHolder is ChatAdapter.ViewHolder) {
                    viewHolder.resetView()
                }

                if (swipeDir == ItemTouchHelper.LEFT && !bulkSelectionMode) {
                    MaterialAlertDialogBuilder(this@ChatActivity, R.style.App_MaterialAlertDialog)
                        .setTitle(R.string.label_confirm_deletion)
                        .setMessage(R.string.msg_confirm_deletion_chat)
                        .setPositiveButton(R.string.btn_delete) { _, _ -> run {
                            adapter?.onDelete(position)
                        }}
                        .setNegativeButton(R.string.btn_cancel) { _, _ -> }
                        .show()
                }
            }
        }

        override fun onChildDraw(c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                                 dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {

            val iconDRight = if (maxX(dX.toInt() / 5) == dpToPx(-32)) {
                ResourcesCompat.getDrawable(resources, R.drawable.ic_delete_action_active, theme)!!
            } else {
                ResourcesCompat.getDrawable(resources, R.drawable.ic_delete_action, theme)!!
            }
            val itemView = viewHolder.itemView
            val background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(ResourcesCompat.getColor(resources, R.color.transparent, theme))
                cornerRadius = dpToPx(128).toFloat()
            }

            if (dX < 0) { // Swiping to the left
                val iconMargin = 48
                val iconTop = itemView.top + (itemView.height - iconDRight.intrinsicHeight) / 2
                val iconBottom = iconTop + iconDRight.intrinsicHeight
                val iconLeft = itemView.right - iconMargin - iconDRight.intrinsicWidth
                val iconRight = itemView.right - iconMargin
                iconDRight.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                background.setColor(ResourcesCompat.getColor(resources, R.color.delete_tint, theme))
                if (maxX(dX.toInt() / 5) == dpToPx(-32)) {
                    background.setColor(ResourcesCompat.getColor(resources, R.color.delete_tint_active, theme))
                }

                background.setBounds(iconLeft + maxX(dX.toInt() / 5), iconTop + maxX(dX.toInt() / 5), iconRight - maxX(dX.toInt() / 5), iconBottom - maxX(dX.toInt() / 5))
                background.draw(c)
                iconDRight.draw(c)
            }

            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && !isCurrentlyActive) {
                getDefaultUIUtil().clearView(viewHolder.itemView)
            }
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources?.displayMetrics?.density!!).toInt()
    }

    private fun maxX(x: Int) : Int {
        if (x < dpToPx(-32)) return dpToPx(-32)
        else if (x < dpToPx(32)) return x
        return dpToPx(32)
    }

    private fun getDarkAccentDrawable(drawable: Drawable, context: Context) : Drawable {
        DrawableCompat.setTint(DrawableCompat.wrap(drawable), getSurfaceColor(context))
        return drawable
    }

    private fun getAmoledAccentDrawable(drawable: Drawable, context: Context) : Drawable {
        DrawableCompat.setTint(DrawableCompat.wrap(drawable), getAmoledSurfaceColor(context))
        return drawable
    }

    private fun getAmoledAccentDrawableV2(drawable: Drawable, context: Context) : Drawable {
        DrawableCompat.setTint(DrawableCompat.wrap(drawable), getAmoledSurfaceColorV2(context))
        return drawable
    }

    private fun getSurfaceColor(context: Context) : Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SurfaceColors.SURFACE_4.getColor(context)
        } else {
            getColor(R.color.accent_250)
        }
    }

    private fun getAmoledSurfaceColor(context: Context) : Int {
        return ResourcesCompat.getColor(context.resources, R.color.amoled_accent_100, null)
    }

    private fun getAmoledSurfaceColorV2(context: Context) : Int {
        return ResourcesCompat.getColor(context.resources, R.color.amoled_accent_200, null)
    }

    private var bitmap: Bitmap? = null
    private var baseImageString: String? = null
    private var selectedImageType: String? = null

    private fun roundCorners(bitmap: Bitmap): Bitmap {
        // Create a bitmap with the same size as the original.
        val output = createBitmap(bitmap.width, bitmap.height)

        // Prepare a canvas with the new bitmap.
        val canvas = Canvas(output)

        // The paint used to draw the original bitmap onto the new one.
        val paint = Paint().apply {
            isAntiAlias = true
            color = -0xbdbdbe
        }

        // The rectangle bounds for the original bitmap.
        val rect = Rect(0, 0, bitmap.width, bitmap.height)
        val rectF = RectF(rect)

        // Draw rounded rectangle as background.
        canvas.drawRoundRect(rectF, 16f, 16f, paint)

        // Change the paint mode to draw the original bitmap on top.
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

        // Draw the original bitmap.
        canvas.drawBitmap(bitmap, rect, rect, paint)

        return output
    }

    private val fileIntentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        run {
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.also { uri ->
                    bitmap = readFile(uri)

                    if (bitmap != null) {
                        attachedImage?.visibility = View.VISIBLE

                        val resizedBitmap = resizeBitmapToMaxHeight(bitmap!!, 100)

                        selectedImage?.setImageBitmap(roundCorners(resizedBitmap))
                        imageIsSelected = true

                        val mimeType = contentResolver.getType(uri)
                        val format = when {
                            mimeType.equals("image/png", ignoreCase = true) -> {
                                selectedImageType = "png"
                                Bitmap.CompressFormat.PNG
                            }
                            else -> {
                                selectedImageType = "jpg"
                                Bitmap.CompressFormat.JPEG
                            }
                        }

                        // Step 3: Convert the Bitmap to a Base64-encoded string
                        val outputStream = ByteArrayOutputStream()
                        bitmap!!.compress(format, 100, outputStream) // Note: Adjust the quality as necessary
                        val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)

                        // Step 4: Generate the data URL
                        val imageType = when(format) {
                            Bitmap.CompressFormat.JPEG -> "jpeg"
                            Bitmap.CompressFormat.PNG -> "png"
                            // Add more mappings as necessary
                            else -> ""
                        }

                        baseImageString = "data:image/$imageType;base64,$base64Image"
                    }
                }
            }
        }
    }

    private fun readFile(uri: Uri) : Bitmap? {
        return contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { _ ->
                BitmapFactory.decodeStream(inputStream)
            }
        }
    }

    private fun openFile(pickerInitialUri: Uri) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"

            putExtra(DocumentsContract.EXTRA_INITIAL_URI, pickerInitialUri)
        }

        fileIntentLauncher.launch(intent)
    }

    private fun initLogic() {
        btnMicro?.setOnClickListener {
            if (isAiCurrentlyBusy()) {
                cancelAllAiActivity("mic button tap on this screen")
                return@setOnClickListener
            }
            // The mic is single-turn transcription ONLY now. While a hands-free
            // conversation is running the conversation button owns everything, so
            // the mic is inert (a tap here must not start a second capture).
            if (isHandsFreeEngaged()) return@setOnClickListener
            when (preferences!!.getEffectiveAudioModel()) {
                "google" -> handleGoogleSpeechRecognition()
                "whisper-local" -> handleLocalWhisperSpeechRecognition()
                else -> handleWhisperSpeechRecognition()
            }
        }

        // Touch interceptor: lets a tap during AI generation cancel everything
        // even though the click handler is otherwise disabled by isEnabled=false
        // in the generation/TTS code paths. OnTouchListener fires regardless of
        // View.isEnabled, so a stop tap always lands. Excludes hands-free: during
        // a conversation the conversation button is the stop control, not the mic.
        btnMicro?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && isAiCurrentlyBusy() &&
                !isRecording && !isHandsFreeEngaged()
            ) {
                cancelAllAiActivity("mic button touch on this screen (mid-generation)")
                true
            } else {
                false
            }
        }

        attachedImage?.setOnClickListener { /* ignored */ }

        // (No long-press listener on btnMicro: View.performLongClick is gated
        // on isEnabled, which is exactly false during generation — the only
        // window where cancelAllAiActivity has anything to do — so this
        // listener could never fire when it would matter. The OnTouchListener
        // above already carries the "tap to cancel mid-generation" behaviour
        // because OnTouchListener fires before View.onTouchEvent regardless
        // of isEnabled.)

        messageInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                /* unused */
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Mic and the conversation/send button now sit side by side and
                // both stay in the bar; only the conversation button's glyph
                // flips: waveform when empty (start hands-free), up-arrow when
                // there is text (send). No-op while a conversation is live.
                refreshConversationButton()
            }

            override fun afterTextChanged(s: Editable?) {
                /* unused */
            }
        })

        // btnSend is the dual conversation/send control (see onConversationButtonTapped).
        btnSend?.setOnClickListener {
            onConversationButtonTapped()
        }

        // Mirror of the mic's touch interceptor: while the button is disabled
        // (during generation/readback) a tap still lands here so the user can
        // stop a live conversation or cancel a busy turn. When enabled, returns
        // false so the click listener above handles the normal tap.
        btnSend?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && btnSend?.isEnabled == false) {
                onConversationButtonTapped()
                true
            } else {
                false
            }
        }

        btnAttachFile?.setOnClickListener {
            visionActions?.visibility = if (visionActions?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        btnVisionActionGallery?.setOnClickListener {
            visionActions?.visibility = View.GONE
            openFile("/storage/emulated/0/image.png".toUri())
        }

        btnVisionActionCamera?.setOnClickListener {
            visionActions?.visibility = View.GONE
            val intent = Intent(this, CameraPermissionActivity::class.java).setAction(Intent.ACTION_VIEW)
            permissionResultLauncherCamera.launch(intent)
        }

        btnRemoveImage?.setOnClickListener {
            attachedImage?.visibility = View.GONE
            imageIsSelected = false
            bitmap = null
        }

        messageInput?.setOnKeyListener { v, keyCode, event -> run {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (keyCode == KeyEvent.KEYCODE_ENTER && event.isShiftPressed && isHardKB() && preferences!!.getDesktopMode()) {
                        (v as EditText).append("\n")
                        return@run true
                    }

                    if (keyCode == KeyEvent.KEYCODE_ENTER && isHardKB() && preferences!!.getDesktopMode()) {
                        parseMessage((v as EditText).text.toString())
                        return@run true
                    }

                    if (((keyCode == KeyEvent.KEYCODE_ESCAPE && event.isShiftPressed) || keyCode == KeyEvent.KEYCODE_BACK) && preferences!!.getDesktopMode()) {
                        finishActivity()
                        return@run true
                    }

                    return@run false
                }
                else -> return@run false
            }
        }}

        if (preferences!!.getDesktopMode()) {
            messageInput?.requestFocus()
        }

        btnSettings?.setOnClickListener {
            val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                this,
                Pair.create(btnSettings, ViewCompat.getTransitionName(btnSettings!!))
            )
            settingsLauncher.launch(
                Intent(this, SettingsActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("chatId", chatId),
                options
            )
        }

        btnExport?.setOnClickListener {
            val gson = Gson()
            val json: String = gson.toJson(messages)

            fileContents = json.toByteArray()

            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "$chatId.json")
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, (Environment.getExternalStorageDirectory().path + "/SpeakGPT/$chatId.json").toUri())
            }
            fileSaveIntentLauncher.launch(intent)
        }

        btnDebugLog?.setOnClickListener {
            startActivity(
                Intent(this, LogsActivity::class.java)
                    .putExtra("type", "event")
                    .putExtra("chatId", chatId)
            )
        }
        updateDebugLogButtonVisibility()
    }

    /** The bug shortcut in the chat's top bar is a quick jump to the Event log,
     *  shown only while there's something worth reading there — i.e. when any
     *  voice diagnostics (the Energy/WebRTC/Silero VAD logging toggles) or Audio
     *  Health logging is on. Re-checked in onResume so toggling a switch in
     *  Settings and coming back updates it without reopening the chat. */
    private fun updateDebugLogButtonVisibility() {
        val on = voiceDiagnosticsEnabled() || preferences?.getAudioHealthLogging() == true
        btnDebugLog?.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun isHardKB(): Boolean {
        return resources.configuration.keyboard == KEYBOARD_QWERTY
    }

    private val fileSaveIntentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        run {
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.also { uri ->
                    writeToFile(uri)
                }
            }
        }
    }

    private fun writeToFile(uri: Uri) {
        try {
            contentResolver.openFileDescriptor(uri, "w")?.use {
                FileOutputStream(it.fileDescriptor).use { stream ->
                    stream.write(
                        fileContents
                    )
                }
            }
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        } catch (e: FileNotFoundException) {
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        } catch (e: IOException) {
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    @Suppress("DEPRECATION")
    private fun startWhisper() {
        if (openAIKey == null) {
            openAIMissing("whisper", "")
            return
        }
        // Arm-time permission check (the tap entry point checks too; this
        // covers arms that don't come through it). Without the permission
        // MediaRecorder just throws, which used to read as a generic failure.
        if (!hasRecordAudioPermission()) {
            logVoiceEventAlways("microphone permission is missing/revoked — cannot start cloud-Whisper capture")
            micIdle()
            isRecording = false
            permissionResultLauncherV2.launch(
                Intent(this, MicrophonePermissionActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
            )
            return
        }
        if (cancelState) {
            cancelState = false
            micIdle()
            isRecording = false
            return
        }
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(96000)
            r.setOutputFile("${externalCacheDir?.absolutePath}/tmp.m4a")
            r.prepare()
            // start() only runs when prepare() succeeded. It used to run
            // unconditionally AFTER the prepare-failure dialog was shown,
            // turning a handled setup failure into an IllegalStateException
            // crash. A start() failure is the same handled class.
            r.start()
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) { /* ignore */ }
            recorder = null
            logVoiceEventAlways("cloud-Whisper recorder setup failed: ${e.javaClass.simpleName}: ${e.message}")
            micIdle()
            isRecording = false
            MaterialAlertDialogBuilder(
                this@ChatActivity,
                R.style.App_MaterialAlertDialog
            )
                .setTitle(R.string.label_audio_error)
                .setMessage(R.string.msg_audio_error)
                .setPositiveButton(R.string.btn_close) { _, _ -> }
                .show()
            return
        }
        recorder = r
    }

    private fun stopWhisper() {
        recorder?.apply {
            stop()
            release()
        }
        recorder = null

        btnMicro?.isEnabled = false
        btnSend?.isEnabled = false
        progress?.visibility = View.VISIBLE

        if (!cancelState) {
            whisperScope = CoroutineScope(Dispatchers.Main)

            whisperScope?.launch {
                progress?.setOnClickListener {
                    cancel()
                    restoreUIState()
                }

                try {
                    processRecording()
                } catch (_: CancellationException) {
                    restoreUIState()
                }
            }
        } else {
            cancelState = false
            micIdle()
            isRecording = false
        }
    }

    private suspend fun processRecording() {
        try {
            val transcriptionRequest = TranscriptionRequest(
                audio = FileSource(
                    path = "${externalCacheDir?.absolutePath}/tmp.m4a".toPath(),
                    fileSystem = FileSystem.SYSTEM
                ),
                model = ModelId("whisper-1"),
            )
            val transcription = openAIAI?.transcription(transcriptionRequest)!!.text

            if (transcription.trim() == "") {
                isRecording = false
                btnMicro?.isEnabled = true
                btnSend?.isEnabled = true
                progress?.visibility = View.GONE
                micIdle()
            } else {
                playTranscriptionDoneSignal()
                // Sample the box BEFORE inserting (already-typed text never auto-sends).
                val boxWasEmpty = messageInput?.text.isNullOrEmpty()
                if (shouldAutoSendTranscription(boxWasEmpty)) {
                    putMessage(prefix + transcription + endSeparator, false)

                    chatMessages.add(
                        ChatMessage(
                            role = ChatRole.User,
                            content = prefix + transcription + endSeparator
                        )
                    )

                    saveSettings()

                    btnMicro?.isEnabled = false
                    btnSend?.isEnabled = false
                    progress?.visibility = View.VISIBLE

                    processRecordingScope = CoroutineScope(Dispatchers.Main)

                    processRecordingScope?.launch {
                        progress?.setOnClickListener {
                            cancel()
                            restoreUIState()
                        }

                        try {
                            generateResponse(prefix + transcription + endSeparator, true)
                        } catch (_: CancellationException) {
                            restoreUIState()
                        }
                    }
                } else {
                    restoreUIState()
                    insertTranscriptIntoBox(transcription)
                }
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Failed to record audio", Toast.LENGTH_SHORT).show()
            btnMicro?.isEnabled = true
            btnSend?.isEnabled = true
            progress?.visibility = View.GONE
        }
    }

    private fun handleWhisperSpeechRecognition() {
        if (isRecording) {
            micIdle()
            isRecording = false
            stopWhisper()
        } else {
            micRecording()
            isRecording = true

            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startWhisper()
            } else {
                permissionResultLauncherV2.launch(
                    Intent(
                        this,
                        MicrophonePermissionActivity::class.java
                    ).setAction(Intent.ACTION_VIEW)
                )
            }
        }
    }

    private fun handleLocalWhisperSpeechRecognition() {
        val handsFree = preferences?.getHandsFreeMode() == true

        if (isRecording) {
            if (handsFree) {
                // A tap during a hands-free listening turn ends the whole loop,
                // matching how a tap ends the Google hands-free conversation.
                logVadDiagnostics("manual-stop")
                stopHandsFreeLoop("mic button tapped while listening (whisper)")
                LocalWhisperEngine.get().cancel()
            } else {
                micIdle()
                isRecording = false
                stopLocalWhisper()
            }
            return
        }

        // Pre-A55/A75 arm64 CPUs can't run the shipped native lib (built
        // with armv8.2 dotprod+fp16) without SIGILL. Detect early and fall
        // back to cloud Whisper so unsupported devices get a transcript
        // instead of silently recording into a void (or in hands-free,
        // looping no-result turns forever).
        if (!org.teslasoft.assistant.stt.NativeCpuSupport.isSupported()) {
            Toast.makeText(this, R.string.local_whisper_no_model_snackbar, Toast.LENGTH_LONG).show()
            handleWhisperSpeechRecognition()
            return
        }

        val activeModel = preferences?.getActiveLocalWhisperModel().orEmpty()
        val installed = activeModel.isNotEmpty() &&
                LocalWhisperModels.byId(activeModel)?.let {
                    LocalWhisperStorage.isInstalled(this, it)
                } == true
        if (!installed) {
            // Selected on-device but no model on disk yet → fall back to
            // cloud Whisper for this utterance so the user still gets a
            // transcript. UI-level snackbar mirrors what the plan calls for.
            Toast.makeText(this, R.string.local_whisper_no_model_snackbar, Toast.LENGTH_LONG).show()
            handleWhisperSpeechRecognition()
            return
        }

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            if (handsFree) startLocalWhisperHandsFreeTurn(freshTurn = true)
            else startLocalWhisper()
        } else {
            permissionResultLauncherV2.launch(
                Intent(this, MicrophonePermissionActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
            )
        }
    }

    /** True iff RECORD_AUDIO is granted right now. Re-checked before every arm
     *  (not just at the tap entry points) and on returning to the screen,
     *  because a permission revoked mid-session used to surface as ordinary
     *  "heard nothing" instead of naming the real cause. */
    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun startLocalWhisper() {
        if (!hasRecordAudioPermission()) {
            // Distinct state, never "no speech": say exactly what is wrong in
            // the persistent Event log and send the user to the existing
            // microphone-permission screen (same one the tap entry points use).
            logVoiceEventAlways("microphone permission is missing/revoked — cannot start on-device capture")
            isRecording = false
            micIdle()
            permissionResultLauncherV2.launch(
                Intent(this, MicrophonePermissionActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
            )
            return
        }
        micRecording()
        isRecording = true
        val token = ++whisperTurnToken
        // applicationContext lets the engine route capture to a Bluetooth
        // headset when one is connected (else the built-in mic).
        val ok = LocalWhisperEngine.get().startRecording(
            context = applicationContext,
            onCaptureError = { reason, detail ->
                runOnUiThread { if (token == whisperTurnToken) onWhisperCaptureError(reason, detail) }
            }
        )
        if (!ok) {
            isRecording = false
            micIdle()
            Toast.makeText(this, R.string.local_whisper_capture_failed, Toast.LENGTH_LONG).show()
            return
        }
        preloadActiveLocalWhisperModel()
    }

    /**
     * Hands-free Whisper turn. Whisper has no end-of-speech detection, so we
     * hand the engine a VAD config built from the same silence/no-speech
     * timers the Google hands-free loop uses, and let it tell us when the turn
     * is over. End-of-turn transcribes and submits (which reads the reply
     * aloud and re-arms the next turn); no-speech ends the loop. This makes
     * on-device Whisper behave like Google hands-free — the only difference
     * being the local transcription step.
     */
    private fun startLocalWhisperHandsFreeTurn(freshTurn: Boolean) {
        Log.i("HandsFree", "startLocalWhisperHandsFreeTurn: freshTurn=$freshTurn " +
                "isRecording=$isRecording handsFreeStopped=$handsFreeStopped cancelState=$cancelState")
        // Permission is re-checked on EVERY arm, not just the tap entry point:
        // the auto re-arm after a readback used to open a doomed capture when
        // the permission had been revoked mid-session, and the failure then
        // masqueraded as "heard nothing". This is a distinct, always-logged
        // stop with the give-up cue, never a no-speech timeout.
        if (!hasRecordAudioPermission()) {
            logVoiceEventAlways("microphone permission is missing/revoked at " +
                    (if (freshTurn) "hands-free start" else "hands-free re-arm") +
                    " — stopping the loop (this is a permission problem, not silence)")
            isRecording = false
            stopHandsFreeLoop("microphone permission revoked", notify = true)
            return
        }
        if (freshTurn) {
            handsFreeStopped = false
            cancelState = false
            handsFreeTurnRetries = 0
            whisperCaptureErrorBudget.reset()
            // Same readback-interrupt reset as startRecognition(): a mic press
            // mid-readback must kill the readback's completion gate/watchdog and
            // silence the playback itself before the mic opens, or the VAD
            // listens to the assistant's own voice and stale loop state strands
            // the new turn (open mic that never registers anything).
            handsFreeReadbackExpected = false
            handsFreeReadbackToken++
            try {
                if (mediaPlayer?.isPlaying == true) {
                    mediaPlayer?.stop()
                    mediaPlayer?.reset()
                }
            } catch (_: Exception) { /* ignore */ }
            try { tts?.stop() } catch (_: Exception) { /* ignore */ }
            startHandsFreeService()
        }
        if (handsFreeStopped) return

        micHandsFreeActive(listening = true)
        isRecording = true

        val silenceMs = preferences!!.getHandsFreeSilenceSeconds().coerceAtLeast(1) * 1000L
        val noSpeechMs = preferences!!.getHandsFreeNoSpeechSeconds().coerceAtLeast(1) * 1000L
        val graceMs = if (freshTurn) 0L else 500L
        val vadMethod = preferences!!.getVadMethod()
        // Each detector has its own diagnostics toggle (Alert & Debug menu).
        // Silero used to piggyback on the WebRTC toggle, so its per-frame logs
        // couldn't be turned off independently of WebRTC.
        val vadLog = when (vadMethod) {
            org.teslasoft.assistant.stt.VadMethods.SILERO -> preferences!!.getVadLoggingSilero()
            org.teslasoft.assistant.stt.VadMethods.ENERGY -> preferences!!.getVadLoggingEnergy()
            else -> preferences!!.getVadLoggingWebrtc()
        }
        // The Silero session loads from assets and needs a Context; the
        // detector factory runs deeper down without one, so make sure the
        // runtime is resident before the turn starts. On failure the factory
        // falls back to Energy — say so, or the user tunes the wrong knobs.
        if (vadMethod == org.teslasoft.assistant.stt.VadMethods.SILERO &&
            !org.teslasoft.assistant.stt.SileroVadRuntime.ensureLoaded(applicationContext)
        ) {
            logVoiceEvent("Silero detector failed to load; this turn will use Energy detection")
        }
        // User-tuned energy gate (advanced voice settings): the field showed
        // the fixed gate discarding a quiet voice entirely, so the numbers are
        // theirs to adjust per device/mic.
        val tuning = org.teslasoft.assistant.stt.VadTuning(
            gateEnabled = preferences!!.getVadEnergyGateEnabled(),
            minSpeechRms = preferences!!.getVadMinSpeechRms().toDouble(),
            floorFactor = preferences!!.getVadFloorFactor().toDouble(),
            energyCeiling = preferences!!.getVadEnergyCeiling().toDouble(),
            hysteresisEnabled = preferences!!.getVadHysteresisEnabled(),
            hysteresisExitRatio = preferences!!.getVadHysteresisExitPercent() / 100.0,
            hangoverMs = preferences!!.getVadHangoverMs().toLong(),
            sileroThreshold = preferences!!.getVadSileroThreshold() / 100.0
        )
        // Every callback closure carries this turn's token; a late/duplicate
        // callback from an older turn (or from a session the engine already
        // cleaned up) compares stale and is dropped on the main thread.
        val token = ++whisperTurnToken
        val ok = LocalWhisperEngine.get().startRecording(
            // applicationContext lets the engine route capture to a Bluetooth
            // headset when one is connected (else the built-in mic), re-checked
            // every turn so a headset connecting mid-conversation is picked up.
            context = applicationContext,
            vad = LocalWhisperEngine.VadConfig(
                silenceMs, noSpeechMs, vadMethod, preferences!!.getVadWebRtcMode(), graceMs, vadLog,
                tuning = tuning,
                minSpeechMs = preferences!!.getVadMinSpeechMs().toLong(),
                audioHealth = preferences!!.getAudioHealthLogging()
            ),
            onEndOfTurn = { runOnUiThread { if (token == whisperTurnToken) onHandsFreeWhisperEndOfTurn() } },
            onNoSpeechTimeout = { runOnUiThread { if (token == whisperTurnToken) onHandsFreeWhisperNoSpeech() } },
            onCaptureError = { reason, detail ->
                runOnUiThread { if (token == whisperTurnToken) onWhisperCaptureError(reason, detail) }
            }
        )
        if (!ok) {
            isRecording = false
            micIdle()
            // The capture device can be briefly unavailable right after a
            // readback (audio routing hasn't released, or the device only just
            // woke with the screen off). Retry before tearing the whole
            // conversation down — giving up on the first failure is the
            // "mic never reopens after the reply" symptom.
            if (!freshTurn && handsFreeTurnRetries < 2) {
                handsFreeTurnRetries++
                logVoiceEvent("whisper capture failed to start; retry $handsFreeTurnRetries")
                handsFreeHandler.postDelayed({
                    if (!isFinishing && !isDestroyed && !handsFreeStopped && !cancelState && !isRecording) {
                        startLocalWhisperHandsFreeTurn(freshTurn = false)
                    }
                }, 600)
                return
            }
            // The toast below is suppressed by the OS when the chat isn't the
            // foreground screen (settings open over it, screen off) — the
            // event log line above via stopHandsFreeLoop is the durable record.
            stopHandsFreeLoop("whisper capture failed to start (after $handsFreeTurnRetries retries)", notify = true)
            Toast.makeText(this, R.string.local_whisper_capture_failed, Toast.LENGTH_LONG).show()
            return
        }
        handsFreeTurnRetries = 0
        logVoiceEvent(if (freshTurn) "listening turn started (mic button)" else "listening turn started (auto re-arm)")
        logMicRoute()
        preloadActiveLocalWhisperModel()
    }

    /** VAD said the user finished speaking — transcribe + submit this turn. */
    private fun onHandsFreeWhisperEndOfTurn() {
        Log.i("HandsFree", "onHandsFreeWhisperEndOfTurn: isRecording=$isRecording " +
                "handsFreeStopped=$handsFreeStopped cancelState=$cancelState")
        if (!isRecording || handsFreeStopped || cancelState) return
        isRecording = false
        // The turn made real progress — restore the mid-turn failure budget.
        whisperCaptureErrorBudget.reset()
        logVoiceEvent("end of turn detected; transcribing")
        logVadDiagnostics("end-of-turn", showToast = false)
        // stopLocalWhisper() transcribes the buffered audio and routes through
        // processLocalWhisperTranscript → generateResponse → speak; the
        // readback completion re-arms the next turn.
        stopLocalWhisper()
    }

    /** VAD saw no speech within the window — end the loop like Google does. */
    private fun onHandsFreeWhisperNoSpeech() {
        if (handsFreeStopped) return
        logVadDiagnostics("no-speech-timeout")
        stopHandsFreeLoop("no speech within the no-speech window", notify = true)
        LocalWhisperEngine.get().cancel()
    }

    /**
     * The engine's capture loop died on its own (AudioRecord read failure, a
     * crash inside the loop, or a wall-clock watchdog abort). This is the
     * explicit failure path — it is NEVER delivered as silence or an empty
     * transcript. The engine has already released the mic, the Bluetooth
     * route and the buffer; this side owns the UI state and the loop
     * decision: a bounded retry in hands-free (so one glitch doesn't end the
     * conversation), then a visible-and-audible stop through the same funnel
     * as every other loop ending. Manual push-to-talk resets to idle and
     * tells the user with the existing audio-error dialog. Late or duplicate
     * deliveries are filtered by the turn token before this runs.
     */
    private fun onWhisperCaptureError(reason: org.teslasoft.assistant.stt.CaptureErrorReason, detail: String) {
        logVoiceEventAlways("on-device capture failed ($reason): $detail")
        val wasHandsFreeTurn = preferences?.getHandsFreeMode() == true &&
                !handsFreeStopped && !cancelState && isRecording
        isRecording = false
        if (wasHandsFreeTurn) {
            if (whisperCaptureErrorBudget.tryConsume()) {
                logVoiceEvent("re-arming after capture error (attempt ${whisperCaptureErrorBudget.attemptsUsed()})")
                handsFreeHandler.postDelayed({
                    if (!isFinishing && !isDestroyed && !handsFreeStopped && !cancelState && !isRecording) {
                        startLocalWhisperHandsFreeTurn(freshTurn = false)
                    }
                }, 600)
            } else {
                stopHandsFreeLoop("on-device capture failed repeatedly ($reason)", notify = true)
            }
            return
        }
        // Manual push-to-talk (or the loop is already down): back to idle. The
        // dialog matches the house persistent-message rule and reuses the
        // existing audio-error wording; hands-free never shows it (the give-up
        // chime + Event log are the screen-off signals there).
        micIdle()
        btnMicro?.isEnabled = true
        btnSend?.isEnabled = true
        progress?.visibility = View.GONE
        if (preferences?.getHandsFreeMode() != true && !isFinishing && !isDestroyed) {
            MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                .setTitle(R.string.label_audio_error)
                .setMessage(R.string.local_whisper_capture_failed)
                .setPositiveButton(R.string.btn_close) { _, _ -> }
                .show()
        }
    }

    /** Surface the per-recording diagnostics when a hands-free turn ends. Two
     *  independent sources, each behind its own toggle: the active VAD detector
     *  ("was there speech?" — frames, RMS, gate, hysteresis) and Audio Health
     *  ("did the mic deliver usable audio?" — levels, clipping, route). When both
     *  are on they're written as two clearly-labelled lines in one entry so they
     *  read cleanly together. Toast for live feedback; Event log so the user can
     *  read it after the fact ("mic listens forever" / "never heard me").
     *
     *  [showToast] false for routine endings (every normal end-of-turn would
     *  otherwise toast over the conversation); the event log gets it either way. */
    private fun logVadDiagnostics(reason: String, showToast: Boolean = true) {
        val engine = LocalWhisperEngine.get()
        val vadDiag = engine.lastVadDiagnostics()
        val audioHealthOn = preferences?.getAudioHealthLogging() == true
        val audioDiag = if (audioHealthOn) engine.lastAudioHealthDiagnostics() else ""

        // Toast = live feedback. The VAD line shows on the no-speech toast as
        // before (so "I heard nothing, here's why" works without diagnostics
        // mode); Audio Health adds its line only when the user enabled it.
        if (showToast) {
            val toastMsg = listOf(vadDiag, audioDiag).filter { it.isNotEmpty() }.joinToString("\n\n")
            if (toastMsg.isNotEmpty()) Toast.makeText(this, toastMsg, Toast.LENGTH_LONG).show()
        }

        // Persistent write: each source follows its own toggle, so turning all
        // of them off means no diagnostics spam in the Event log.
        val parts = ArrayList<String>()
        if (voiceDiagnosticsEnabled() && vadDiag.isNotEmpty()) parts.add("VAD: $vadDiag")
        if (audioHealthOn && audioDiag.isNotEmpty()) parts.add(audioDiag)
        if (parts.isEmpty()) return
        try {
            org.teslasoft.assistant.preferences.Logger.logAsync(this, "event", "VoiceDiag", "debug", "$reason\n${parts.joinToString("\n")}")
        } catch (_: Throwable) { /* never let diagnostics crash the loop */ }
    }

    /** Write the microphone route chosen for this turn to the Event log: the
     *  requested device plus the actual active input before and after the mic
     *  opened. This is what lets the user confirm which mic is really in use —
     *  e.g. that a connected Bluetooth headset is being captured from, not the
     *  built-in mic. Always logged to logcat; written to the persistent Event
     *  log when Audio Health or any VAD logging is on, so it doesn't spam normal
     *  use but is there the moment the user turns diagnostics on to investigate. */
    private fun logMicRoute() {
        val diag = LocalWhisperEngine.get().lastMicRouteDiagnostics()
        if (diag.isEmpty()) return
        Log.i("VoiceLoop", "mic route: $diag")
        if (preferences?.getAudioHealthLogging() != true && !voiceDiagnosticsEnabled()) return
        try {
            org.teslasoft.assistant.preferences.Logger.logAsync(this, "event", "MicRoute", "info", diag)
        } catch (_: Throwable) { /* never let diagnostics crash the loop */ }
    }

    /** True when the user has switched on any VAD logging toggle (Energy,
     *  WebRTC or Silero) — treated as "voice diagnostics mode". */
    private fun voiceDiagnosticsEnabled(): Boolean {
        return preferences?.getVadLoggingWebrtc() == true ||
                preferences?.getVadLoggingEnergy() == true ||
                preferences?.getVadLoggingSilero() == true
    }

    /**
     * One timestamped line per meaningful voice-pipeline decision, written to
     * the persistent Event log (Settings -> Event log) whenever a VAD logging
     * toggle is on. The whole point of those toggles is letting the user report
     * voice failures intelligently — but the per-frame VAD output only goes to
     * logcat, which they can't see, and most loop decisions (re-arm, re-arm
     * skipped and why, capture failure, loop stop and why) used to log nowhere
     * persistent. This is the user-visible trail for "the mic never came back
     * and the event log was empty".
     */
    private fun logVoiceEvent(message: String) {
        Log.i("VoiceLoop", message)
        if (!voiceDiagnosticsEnabled()) return
        try {
            org.teslasoft.assistant.preferences.Logger.logAsync(this, "event", "VoiceLoop", "info", message)
        } catch (_: Throwable) { /* never let diagnostics crash the loop */ }
    }

    /** Like [logVoiceEvent] but always persists to the Event log, regardless of
     *  the VAD-logging toggles — for genuine failures (e.g. a TTS readback error)
     *  the user needs recorded even with per-turn diagnostics off. Callers must be
     *  bounded (e.g. the capped TTS retry path) so this can't spam the log. */
    private fun logVoiceEventAlways(message: String) {
        Log.w("VoiceLoop", message)
        try {
            org.teslasoft.assistant.preferences.Logger.logAsync(this, "event", "VoiceLoop", "warning", message)
        } catch (_: Throwable) { /* never let diagnostics crash the loop */ }
    }

    // Warm the model into RAM while the user is still talking so the
    // (multi-second, for the mid/large models) load overlaps with recording
    // instead of stalling on "Loading Whisper" after they stop. preload() is
    // idempotent and serialized internally, so it's a no-op once resident.
    private fun preloadActiveLocalWhisperModel() {
        val activeModel = preferences?.getActiveLocalWhisperModel().orEmpty()
        if (activeModel.isNotEmpty()) {
            val appCtx = applicationContext
            whisperPreloadScope = CoroutineScope(Dispatchers.IO)
            whisperPreloadScope?.launch {
                try { LocalWhisperEngine.get().preload(appCtx, activeModel) } catch (_: Exception) { /* ignore */ }
            }
        }
    }

    private fun stopLocalWhisper() {
        // This turn is being collected; any capture callback still in flight
        // (a duplicate end-of-turn, a racing error) belongs to the past.
        whisperTurnToken++
        btnMicro?.isEnabled = false
        btnSend?.isEnabled = false
        progress?.visibility = View.VISIBLE

        if (cancelState) {
            cancelState = false
            LocalWhisperEngine.get().cancel()
            micIdle()
            isRecording = false
            restoreUIState()
            return
        }

        whisperScope = CoroutineScope(Dispatchers.Main)
        whisperScope?.launch {
            progress?.setOnClickListener {
                cancel()
                LocalWhisperEngine.get().cancel()
                restoreUIState()
            }

            try {
                val activeModel = preferences?.getActiveLocalWhisperModel().orEmpty()
                val transcription = LocalWhisperEngine.get()
                    .stopAndTranscribe(this@ChatActivity, activeModel) { phase ->
                        // Surface progress in the input hint so the user can see
                        // whether they're waiting on the model load or the actual
                        // transcription (key for diagnosing the larger models).
                        messageInput?.hint = when (phase) {
                            LocalWhisperEngine.Phase.LOADING_MODEL -> getString(R.string.hint_loading_whisper)
                            LocalWhisperEngine.Phase.TRANSCRIBING -> getString(R.string.hint_transcribing)
                        }
                    }
                processLocalWhisperTranscript(transcription)
            } catch (_: CancellationException) {
                restoreUIState()
            } catch (e: Exception) {
                // Hands-free: a throwing transcription must not strand the
                // loop. restoreUIState() alone left HandsFreeService and any
                // Bluetooth mic route up while the mic never reopened — with
                // the screen off the user got no cue at all. Give up through
                // the one funnel that logs the reason, plays the give-up cue
                // and tears the loop down properly.
                if (preferences?.getHandsFreeMode() == true && !handsFreeStopped) {
                    logVoiceEventAlways("on-device transcription threw: ${e.message}")
                    stopHandsFreeLoop("on-device transcription threw", notify = true)
                } else {
                    Toast.makeText(this@ChatActivity, "Failed to transcribe on device", Toast.LENGTH_SHORT).show()
                }
                restoreUIState()
            }
        }
    }

    private fun processLocalWhisperTranscript(transcription: String?) {
        // Mirrors the downstream half of processRecording(): if auto-send
        // is on, push the transcript as a user turn and kick generation;
        // otherwise drop it into the message input box.
        // Transcription phase is over either way — drop the status hint.
        messageInput?.hint = getString(R.string.hint_message)
        if (transcription.isNullOrBlank()) {
            isRecording = false
            btnMicro?.isEnabled = true
            btnSend?.isEnabled = true
            progress?.visibility = View.GONE
            micIdle()
            // Hands-free: a blank result (background noise tripped the VAD, or
            // whisper produced nothing) shouldn't end the conversation — just
            // re-open the mic for another turn.
            if (preferences?.getHandsFreeMode() == true && !handsFreeStopped && !cancelState) {
                logVoiceEvent("transcription came back empty; reopening mic")
                startLocalWhisperHandsFreeTurn(freshTurn = false)
            }
            return
        }
        playTranscriptionDoneSignal()
        // Sample the box BEFORE inserting (already-typed text never auto-sends).
        val boxWasEmpty = messageInput?.text.isNullOrEmpty()
        if (shouldAutoSendTranscription(boxWasEmpty)) {
            putMessage(prefix + transcription + endSeparator, false)
            chatMessages.add(
                ChatMessage(
                    role = ChatRole.User,
                    content = prefix + transcription + endSeparator
                )
            )
            saveSettings()

            btnMicro?.isEnabled = false
            btnSend?.isEnabled = false
            progress?.visibility = View.VISIBLE

            processRecordingScope = CoroutineScope(Dispatchers.Main)
            processRecordingScope?.launch {
                progress?.setOnClickListener {
                    cancel()
                    restoreUIState()
                }
                try {
                    generateResponse(prefix + transcription + endSeparator, true)
                } catch (_: CancellationException) {
                    restoreUIState()
                }
            }
        } else {
            restoreUIState()
            insertTranscriptIntoBox(transcription)
        }
    }

    private fun handleGoogleSpeechRecognition() {
        if (isRecording) {
            try {
                if (mediaPlayer!!.isPlaying) {
                    mediaPlayer!!.stop()
                    mediaPlayer!!.reset()
                }
                tts!!.stop()
            } catch (_: java.lang.Exception) {/* unused */}
            if (preferences?.getHandsFreeMode() == true) {
                stopHandsFreeLoop("mic button tapped while listening (google)")
            } else {
                micIdle()
                recognizer?.stopListening()
                isRecording = false
            }
        } else {
            try {
                if (mediaPlayer!!.isPlaying) {
                    mediaPlayer!!.stop()
                    mediaPlayer!!.reset()
                }
                tts!!.stop()
            } catch (_: java.lang.Exception) {/* unused */}
            if (preferences?.getHandsFreeMode() == true) micHandsFreeActive(listening = true)
            else micRecording()
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startRecognition()
            } else {
                permissionResultLauncher.launch(
                    Intent(
                        this,
                        MicrophonePermissionActivity::class.java
                    ).setAction(Intent.ACTION_VIEW)
                )
            }

            isRecording = true
        }
    }

    private fun initSpeechListener() {
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(speechListener)
    }

    private fun initTTS() {
        tts = TextToSpeech(this, ttsListener)
    }

    /**
     * Builds the base URL handed to the OpenAI client. The client always appends
     * "chat/completions", so we compose the user's Base URL + Chat Endpoint and
     * strip a trailing chat/completions, letting the client re-append it to the
     * exact location the profile configured. Non-standard paths fall back to the
     * Base URL (the client still appends chat/completions).
     */
    private fun composeChatHost(rawBase: String?, rawEndpoint: String?): String {
        var base = (rawBase ?: "").trim()
        if (base.isBlank()) return base
        if (!base.endsWith("/")) base += "/"

        val endpoint = (rawEndpoint ?: ApiEndpointObject.DEFAULT_CHAT_ENDPOINT).trim().trimStart('/')
        val marker = "chat/completions"
        val full = base + endpoint
        return if (full.endsWith(marker)) full.removeSuffix(marker) else base
    }

    private fun initAI() {
        if (key == null) {
            startActivity(Intent(this, WelcomeActivity::class.java).setAction(Intent.ACTION_VIEW))
            finishActivity()
        } else {
            val isBearerAuth = apiEndpointObject?.authType == null ||
                    apiEndpointObject?.authType == ApiEndpointObject.AUTH_BEARER
            val extraHeaders: Map<String, String> = when (apiEndpointObject?.authType) {
                ApiEndpointObject.AUTH_X_API_KEY -> mapOf("x-api-key" to key!!)
                ApiEndpointObject.AUTH_API_KEY -> mapOf("api-key" to key!!)
                else -> emptyMap()
            }

            // Per-endpoint socket timeout: how long to wait for this server to
            // respond before an N2 "server did not respond in time". User-set on
            // the endpoint profile (default 30s); already clamped to 5..300 on
            // save, re-coerced here so a legacy/hand-edited value can't slip
            // through.
            val socketTimeout = ApiEndpointObject.coerceTimeoutSeconds(
                apiEndpointObject?.requestTimeoutSeconds ?: ApiEndpointObject.DEFAULT_TIMEOUT_SECONDS
            ).seconds

            val config = OpenAIConfig(
                // OpenAIConfig.token unconditionally generates an
                // "Authorization: Bearer <token>" header. When the user picks
                // x-api-key or api-key as their auth mode, the key already
                // goes through extraHeaders, and passing it as token here too
                // sends BOTH a Bearer header and the alternate-auth header —
                // which 4xx's at providers like Anthropic that reject the
                // extra Authorization header. Empty token suppresses the
                // Bearer line and lets the alternate header carry auth alone.
                token = if (isBearerAuth) key!! else "",
                logging = LoggingConfig(LogLevel.None, Logger.Simple),
                timeout = Timeout(socket = socketTimeout),
                organization = null,
                headers = extraHeaders,
                host = OpenAIHost(composeChatHost(apiEndpointObject?.host, apiEndpointObject?.chatEndpoint)),
                proxy = null,
                retry = RetryStrategy()
            )

            ai = OpenAI(config)
            // Auxiliary client for audio/image/function endpoints. Bound to
            // the active chat's endpoint (base host, same auth mode) so no
            // content is silently routed to api.openai.com.
            val configOpenAI = OpenAIConfig(
                token = if (isBearerAuth) openAIKey.toString() else "",
                logging = LoggingConfig(LogLevel.None, Logger.Simple),
                timeout = Timeout(socket = socketTimeout),
                organization = null,
                headers = extraHeaders,
                host = OpenAIHost(apiEndpointObject?.host!!),
                proxy = null,
                retry = RetryStrategy()
            )
            openAIAI = OpenAI(configOpenAI)
            loadModel()
            setup()
        }
    }

    /**
     * Decide which companion a brand-new chat opens with. Owner rules
     * (July 11 2026 — these SUPERSEDE the July 10 wording; there is no other
     * acceptable behavior):
     *   1. Default to the companion you last used.
     *   2. The ONLY exception is first-ever use — no last-used companion yet
     *      (or the last-used one was since deleted): open with whichever
     *      companion is at the top of the list.
     *   3. If no companion exists at all, a chat can't begin: prompt the owner
     *      to create one and take them straight to the creation screen.
     * "Last used" is recorded whenever a companion is chosen through ANY
     * selection surface (Quick Settings and the Companions list both write it),
     * so it always reflects the companion actually in use. One-shot per chat
     * (the persona_activation_seeded flag) and only for an empty chat (called
     * from [setup] inside its messages.isEmpty() guard), so existing
     * conversations are never retroactively changed.
     */
    private fun seedPersonaAndActivationDefaults() {
        if (preferences?.isPersonaActivationSeeded() == true) return

        if (preferences?.getPersonaId().isNullOrEmpty()) {
            val personaPrefs = PersonaPreferences.getPersonaPreferences(this)
            val personasList = personaPrefs.getPersonasList()

            if (personasList.isEmpty()) {
                // Rule 3: no companion exists. Ask the owner to create one and
                // open the creation screen. Do NOT mark seeding done — when
                // they return with a companion made, this runs again and seeds
                // it (rules 1/2).
                promptCreateFirstCompanion()
                return
            }

            val lastPersona = preferences?.getLastUsedPersonaId().orEmpty()
            if (lastPersona.isNotEmpty() && personaPrefs.getPersona(lastPersona).label.isNotEmpty()) {
                // Rule 1: continue with the companion you last used.
                preferences?.setPersonaId(lastPersona)
            } else {
                // Rule 2: first-ever use, or the last-used companion was since
                // deleted — open with the companion at the top of the list.
                preferences?.setPersonaId(Hash.hash(personasList.first().label))
            }
        }

        preferences?.setPersonaActivationSeeded(true)

        if (preferences?.getActivationPromptId().isNullOrEmpty()) {
            val lastActivation = preferences?.getLastUsedActivationPromptId().orEmpty()
            if (lastActivation.isNotEmpty()) {
                val activation = ActivationPromptPreferences
                    .getActivationPromptPreferences(this)
                    .getActivationPrompt(lastActivation)
                if (activation.label.isNotEmpty()) {
                    preferences?.setActivationPromptId(lastActivation)
                    // Mirror the QuickSettings selection flow: the prompt text is
                    // what setup() reads and sends as the first message.
                    preferences?.setPrompt(activation.prompt)
                }
            }
        }
    }

    /**
     * Rule 3 of the new-chat companion logic: no companion exists yet, so a
     * chat can't begin. Persistent dialog (never a toast) with the owner's
     * approved wording; the positive button opens the companion creation
     * screen. Declining leaves the chat companion-less until one is made.
     */
    private fun promptCreateFirstCompanion() {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setMessage(R.string.create_first_companion_message)
            .setCancelable(false)
            .setPositiveButton(R.string.create_first_companion_button) { _, _ ->
                createFirstCompanionLauncher.launch(
                    Intent(this, PersonasListActivity::class.java)
                        .putExtra("createOnStart", true)
                )
            }
            .setNegativeButton(R.string.cancel) { _, _ -> }
            .show()
    }

    /**
     * Seed a brand-new chat's checked additional lorebooks from the persona's
     * last-used set — but only when the persona has opted in via its
     * "auto-enable last-used lorebooks" toggle. One-shot per chat (same pattern
     * as [seedPersonaAndActivationDefaults]); afterwards the chat's own Quick
     * Settings selection always wins. Books that have since been deleted or
     * unlinked from the persona are skipped.
     */
    private fun seedLoreBooksForNewChat() {
        if (preferences?.isLoreBooksSeeded() == true) return
        preferences?.setLoreBooksSeeded(true)

        val personaId = preferences?.getPersonaId().orEmpty()
        if (personaId.isEmpty()) return

        val persona = PersonaPreferences.getPersonaPreferences(this).getPersona(personaId)
        if (!persona.autoLoadLastLoreBooks) return

        try {
            val linked = persona.additionalLoreBookIdList()
            val store = LoreBookStore.getInstance(this)
            val ids = persona.lastUsedLoreBookIdList().filter { linked.contains(it) && store.getBook(it) != null }
            if (ids.isNotEmpty()) {
                preferences?.setActiveLoreBookIds(ids)
            }
        } catch (e: Exception) {
            // Store unavailable (SQLCipher key problem): skip seeding, keep the chat usable.
            org.teslasoft.assistant.preferences.memory.MemoryLog.log(this, "LoreBook", "error", "Lorebook seeding skipped: ${e.message}")
        }
    }

    /*
    * Setup SpeakGPT with activation prompt.
    * */
    private fun setup() {
        if (messages.isEmpty()) {
            seedPersonaAndActivationDefaults()
            seedLoreBooksForNewChat()
            val prompt: String = preferences!!.getPrompt()

            if (prompt.toString() != "" && prompt.toString() != "null" && prompt != "") {
                putMessage(prompt, false)

                chatMessages.add(
                    ChatMessage(
                        role = ChatRole.User,
                        content = prompt
                    )
                )

                btnMicro?.isEnabled = false
                btnSend?.isEnabled = false
                progress?.visibility = View.VISIBLE

                setupScope = CoroutineScope(Dispatchers.Main)

                setupScope?.launch {
                    progress?.setOnClickListener {
                        cancel()
                        restoreUIState()
                        saveSettings()
                        syncChatProjection()
                        calculateCost()
                    }

                    try {
                        generateResponse(prompt, false)
                    } catch (_: CancellationException) {
                        restoreUIState()
                    }
                }
            }
        }
    }

    private fun loadModel() {
        model = preferences!!.getModel()
        endSeparator = preferences!!.getEndSeparator()
        prefix = preferences!!.getPrefix()
    }

    // Init image resolutions
    private fun loadResolution() {
        resolution = preferences!!.getResolution()
    }

    /** SYSTEM INITIALIZATION END **/

    private fun saveSettings() {
        // Guarded save (Round 4): ChatPreferences refuses the write when the
        // chat's storage is locked or its stored value is preserved-corrupt —
        // this screen's in-memory list came from that unreadable read, and
        // persisting it would overwrite the only copy. The refusal is logged
        // by the guard; the "Chat unavailable" state already blocks the UI.
        if (chatStorageUnavailable) return
        ChatPreferences.getChatPreferences().saveChatHistory(this, chatId, messages)
    }

    /**
     * A manual turn — typed send or regenerate — while hands-free mode is on
     * counts as the user deliberately continuing the conversation, so it resumes
     * a loop that an earlier error (or a Hang Up) had stopped. Without this the
     * reply still reads back (readback keys off the mode toggle) but the mic
     * never reopens, because the loop-stopped flag is otherwise only cleared by a
     * fresh mic press — leaving the user talking to a dead mic after a regenerate.
     * Safe against error-loop spirals: it only re-arms after a successful,
     * user-initiated turn; a turn that errors again re-stops the loop as before.
     * No-op when the loop is already live, so a typed message mid-conversation
     * doesn't log or change anything.
     */
    private fun resumeHandsFreeForManualTurn() {
        if (preferences?.getHandsFreeMode() != true) return
        if (!handsFreeStopped && !cancelState) return
        handsFreeStopped = false
        cancelState = false
        handsFreeTurnRetries = 0
        startHandsFreeService()
        logVoiceEvent("manual turn restored the hands-free loop and restarted its keep-alive service after a prior error/hang-up")
    }

    private fun parseMessage(message: String, shouldAdd: Boolean = true) {
        // No sends into a chat whose stored history is locked or preserved-
        // corrupt (Round 4) — the blocking dialog owns this screen.
        if (chatStorageUnavailable) return
        // Put timestamp to chat to sort chats by last message
        ChatPreferences.getChatPreferences().putTimestampToChatById(this, chatId)
        try {
            if (mediaPlayer!!.isPlaying) {
                mediaPlayer!!.stop()
                mediaPlayer!!.reset()
            }
            tts!!.stop()
        } catch (_: java.lang.Exception) {/* unused */}
        if (message != "") {
            messageInput?.setText("")

            keyboardMode = false

            // Re-engage hands-free if an earlier error/hang-up left the loop
            // stopped: this manual turn is the user's "keep going" signal, so the
            // mic re-arms once the reply finishes reading back.
            resumeHandsFreeForManualTurn()

            val m = prefix + message + endSeparator

            if (imageIsSelected) {
                val bytes = Base64.decode(baseImageString!!.split(",")[1], Base64.DEFAULT)
                writeImageToCache(bytes, selectedImageType!!)

                val encoded = java.util.Base64.getEncoder().encodeToString(bytes)

                val file = Hash.hash(encoded)

                if (shouldAdd) {
                    putMessage(m, false, file, selectedImageType!!)
                } else {
                    messages[messages.size - 1]["image"] = file
                    messages[messages.size - 1]["imageType"] = selectedImageType!!
                    messages[messages.size - 1]["message"] = m
                }
            } else {
                if (shouldAdd) putMessage(m, false)
            }
            saveSettings()

            btnMicro?.isEnabled = false
            btnSend?.isEnabled = false
            progress?.visibility = View.VISIBLE

            val imagineCommandEnabled: Boolean = preferences!!.getImagineCommand()

            if (m.lowercase().contains("/imagine") && m.length > 9 && imagineCommandEnabled) {
                val x: String = m.substring(9)

                if (openAIKey == null) {
                    openAIMissing("dalle", x)
                } else {
                    sendImageRequest(x)
                }
            } else if (m.lowercase().contains("/imagine") && m.length <= 9 && imagineCommandEnabled) {
                putMessage("Prompt can not be empty. Use /imagine &lt;PROMPT&gt;", true)

                saveSettings()

                btnMicro?.isEnabled = true
                btnSend?.isEnabled = true
                progress?.visibility = View.GONE
            } else {
                if (shouldAdd) {
                    chatMessages.add(
                        ChatMessage(
                            role = ChatRole.User,
                            content = m
                        )
                    )
                    syncChatProjection()
                }

                parseMessageScope = CoroutineScope(Dispatchers.Main)
                parseMessageScope?.launch {
                    progress?.setOnClickListener {
                        cancel()
                        restoreUIState()
                        saveSettings()
                        syncChatProjection()
                        calculateCost()
                    }

                    try {
                        generateResponse(m, false)
                    } catch (_: CancellationException) {
                        restoreUIState()
                    }
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun sendImageRequest(str: String) {
        imageRequestScope = CoroutineScope(Dispatchers.Main)
        imageRequestScope?.launch {
            runOnUiThread {
                btnMicro?.isEnabled = false
                btnSend?.isEnabled = false
                progress?.visibility = View.VISIBLE
            }

            progress?.setOnClickListener {
                cancel()
                restoreUIState()
                saveSettings()
                syncChatProjection()
                calculateCost()
            }

            try {
                generateImageR(str)
            } catch (_: CancellationException) {
                restoreUIState()
            }
        }
    }

    private fun startRecognition(freshTurn: Boolean = true) {
        // Re-checked on every arm (the tap entry point checks too, but the
        // hands-free restarts and re-arms come straight here): with the
        // permission revoked the recognizer just errors opaquely, and the
        // failure used to read as a recognizer problem instead of naming the
        // permission.
        if (!hasRecordAudioPermission()) {
            logVoiceEventAlways("microphone permission is missing/revoked at " +
                    (if (freshTurn) "recognition start" else "recognition re-arm") +
                    " — not opening the mic (this is a permission problem, not silence)")
            if (preferences?.getHandsFreeMode() == true && !handsFreeStopped) {
                stopHandsFreeLoop("microphone permission revoked", notify = true)
            } else {
                isRecording = false
                micIdle()
            }
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, LocaleParser.parse(preferences!!.getLanguage()))
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

        if (preferences?.getHandsFreeMode() == true) {
            if (freshTurn) {
                handsFreeUserSpoke = false
                handsFreeStopped = false
                cancelState = false
                handsFreeTurnRetries = 0
                // A fresh turn can start mid-readback (mic press interrupting
                // the assistant). The interrupted readback's completion gate and
                // watchdog must die here: left set, the no-barge-in gate in
                // onResults() silently drops every transcript of the new turn —
                // mic visibly open, nothing ever registered — and the watchdog
                // can never clear the flag because it bails out while
                // isRecording is true.
                handsFreeReadbackExpected = false
                handsFreeReadbackToken++
                handsFreeListenDeadline = System.currentTimeMillis() +
                        preferences!!.getHandsFreeNoSpeechSeconds().coerceAtLeast(1) * 1000L
                handsFreeBuffer = ""
                handsFreeSubmitRunnable?.let { handsFreeHandler.removeCallbacks(it) }
                handsFreeSubmitRunnable = null
                startHandsFreeService()
            }
            // Best-effort: ask the recognizer to tolerate longer pauses so the
            // user has time to think. Some engines ignore these; the restart
            // logic in the listener backs them up.
            val silenceMs = preferences!!.getHandsFreeSilenceSeconds().coerceAtLeast(1) * 1000L
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
        }

        recognizer?.startListening(intent)
    }

    private fun stopHandsFreeLoop(reason: String = "unspecified", notify: Boolean = false) {
        // The reason lands in the event log: "the mic never reopened" is only
        // diagnosable if every loop ending says why it ended.
        logVoiceEvent("hands-free loop stopped: $reason")
        // notify = the loop gave up on its own (heard nothing / couldn't capture),
        // not a user tap. Play an audible cue so a hands-free user with the screen
        // off knows it stopped listening and is waiting for them, rather than
        // sitting in silence assuming it's still listening.
        if (notify) playNoSpeechSignal()
        whisperTurnToken++ // invalidate any whisper turn callback still in flight
        handsFreeStopped = true
        handsFreeReadbackExpected = false
        handsFreeReadbackToken++ // invalidate any in-flight readback watchdog
        handsFreeHandler.removeCallbacksAndMessages(null)
        handsFreeSubmitRunnable = null
        handsFreeBuffer = ""
        try { recognizer?.stopListening() } catch (_: Exception) { /* ignore */ }
        // Release any Bluetooth SCO routing the Whisper engine took for capture
        // so the headset isn't left in call mode after the loop ends (no-op for
        // the Google STT path, which never routed). End-of-turn keeps the route
        // up between turns; only a real loop stop tears it down.
        try { LocalWhisperEngine.get().clearMicRouting() } catch (_: Exception) { /* ignore */ }
        isRecording = false
        micIdle()
        // The conversation is over: clear the engaged flag (there is no settings
        // toggle any more — the button is the only control) and return the
        // conversation/send button from its red "live" look to resting.
        preferences?.setHandsFreeMode(false)
        refreshConversationButton()
        stopHandsFreeService()
    }

    /** A failed turn must not silently re-arm the hands-free loop. Otherwise a
     *  single error (overloaded model, dropped connection, etc.) becomes an
     *  endless retry cycle that keeps erroring without the user touching the
     *  mic. Stopping the loop here means a fresh mic tap is required to resume. */
    private fun stopHandsFreeOnError() {
        if (preferences?.getHandsFreeMode() == true) {
            runOnUiThread { stopHandsFreeLoop("the response failed with an error") }
        }
    }

    /**
     * The conversation/send button (btnSend) was tapped. One button, three roles
     * decided by state:
     *   - a hands-free conversation is live  → STOP it (tap again ends it),
     *   - the input box has text             → SEND it (the up-arrow),
     *   - the AI is busy (generating/reading) → cancel everything,
     *   - otherwise (idle, empty box)        → START hands-free.
     * Reachable from both the click listener and the touch listener (the latter
     * catches taps while the button is disabled during generation/readback).
     */
    private fun onConversationButtonTapped() {
        when {
            isHandsFreeEngaged() -> stopHandsFreeByUser()
            !messageInput?.text.isNullOrEmpty() -> parseMessage(messageInput?.text.toString())
            isAiCurrentlyBusy() -> cancelAllAiActivity("conversation button tap on this screen")
            else -> startHandsFreeByUser()
        }
    }

    /** Engage hands-free from the conversation button. Flips the runtime flag the
     *  pipeline gates on and starts the loop through the engine's existing
     *  hands-free entry point (the handlers take their hands-free branch because
     *  the flag is now on). Only Google STT and on-device Whisper can detect
     *  end-of-speech and therefore loop; cloud Whisper cannot, so on that engine
     *  the button just runs a single transcription turn (never engaging a loop
     *  that could never re-arm and would strand the flag on). */
    private fun startHandsFreeByUser() {
        if (chatStorageUnavailable) return
        if (isAiCurrentlyBusy()) {
            cancelAllAiActivity("conversation button tap (busy) on this screen")
            return
        }
        val engine = preferences!!.getEffectiveAudioModel()
        if (engine != "google" && engine != "whisper-local") {
            // Cloud Whisper: no end-of-speech detection → no loop. Fall back to a
            // single capture, exactly like the mic button, without engaging
            // hands-free.
            handleWhisperSpeechRecognition()
            return
        }
        preferences?.setHandsFreeMode(true)
        handsFreeStopped = false
        logVoiceEvent("hands-free engaged (conversation button)")
        if (engine == "google") handleGoogleSpeechRecognition() else handleLocalWhisperSpeechRecognition()
    }

    /** Stop hands-free from the conversation button. cancelAllAiActivity is the
     *  same full teardown the mic-tap stop and the notification Hang Up use
     *  (cancels a still-streaming reply, silences readback, closes the mic, stops
     *  the service) and already clears the engaged flag and resets this button. */
    private fun stopHandsFreeByUser() {
        logVoiceEvent("hands-free stopped (conversation button)")
        cancelAllAiActivity("conversation button tap (stop hands-free)")
    }

    /** True while the AI is generating, speaking through TTS, playing back
     *  OpenAI TTS audio, or COMMITTED to speaking (readback decided but the
     *  audio hasn't started yet). Used so a single mic-button tap can cancel
     *  everything.
     *
     *  The pending-readback signals matter: the reply prints BEFORE any sound
     *  comes out (language detection, engine spin-up, cloud-voice fetch), and
     *  that gap is exactly when the user taps stop. Counting the gap as
     *  "idle" turned the stop tap into a mic-open — the loop started
     *  listening, the readback then spoke over the open mic, and in
     *  hands-free the app transcribed its own voice as the user's next turn.
     *  Same for the engines' documented habit of blipping isSpeaking=false
     *  mid-utterance: without these flags a stop tap during a blip opened
     *  the mic instead of stopping. */
    private fun isAiCurrentlyBusy(): Boolean {
        val ttsSpeaking = try { tts?.isSpeaking == true } catch (_: Exception) { false }
        val mediaPlaying = try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false }
        val progressVisible = progress?.visibility == View.VISIBLE
        val readbackPending = handsFreeReadbackExpected ||        // loop readback in flight
                readbackKeepAliveActive ||                        // plain read-aloud in flight
                pendingSpeak != null ||                           // utterance parked behind a TTS init
                (adapter?.getSpeakingPosition() ?: -1) != -1      // manual speaker-button readback
        return ttsSpeaking || mediaPlaying || progressVisible || readbackPending
    }

    /** Cancels generation, TTS, audio playback, recognizer, and the hands-free
     *  loop in one shot. Mirrors what long-press has always done; also reachable
     *  from a short tap when the AI is busy.
     *
     *  [source] names WHICH trigger fired, verbatim, in the log line. All three
     *  triggers used to log the identical "(stop tap)", so when a stop arrived
     *  with the screen off and the owner nowhere near the phone (July 11 2026
     *  report), the log claimed a tap that never happened and the real source
     *  was unprovable. Only three paths exist: the two mic-button touch paths
     *  (impossible with the screen off) and the notification Hang Up
     *  PendingIntent — which any paired device or app with notification access
     *  can fire without a human tap. */
    private fun cancelAllAiActivity(source: String) {
        logVoiceEvent("all AI activity cancelled ($source)")
        cancelState = true
        whisperTurnToken++ // invalidate any whisper turn callback still in flight
        handsFreeStopped = true
        handsFreeReadbackExpected = false
        handsFreeReadbackToken++ // invalidate any in-flight readback watchdog
        handsFreeHandler.removeCallbacksAndMessages(null)
        handsFreeSubmitRunnable = null
        handsFreeBuffer = ""
        // Stop must also cancel a reply that is still being GENERATED, not just
        // the audio. pronounce() runs unconditionally when the stream completes,
        // so an uncancelled generation meant: hit stop mid-generation, the
        // stream quietly finishes, and the full reply is read out loud anyway —
        // "I can't stop it from reading back to me." Cancelling the scopes takes
        // the same path as the progress-spinner cancel always has (each launch
        // site catches CancellationException and restores the UI; the
        // generateResponse finally releases the foreground service).
        killAllProcesses()
        stopReadback()
        try { recognizer?.stopListening() } catch (_: Exception) { /* ignore */ }
        // A whisper-local capture holds the device mic (and the OS privacy
        // indicator) independently of the Google recognizer — a stop tap must
        // release that too, or the mic stays open with nothing consuming it.
        try { LocalWhisperEngine.get().cancel() } catch (_: Exception) { /* ignore */ }
        isRecording = false
        micIdle()
        // Any stop (in-app, notification Hang Up, mid-generation tap) also ends a
        // hands-free conversation: clear the engaged flag and reset the
        // conversation/send button from its red "live" look to resting.
        preferences?.setHandsFreeMode(false)
        refreshConversationButton()
        stopHandsFreeService()
    }

    /**
     * Silence any readback, current or queued, and make sure nothing can start
     * one behind the user's back: bumps [readbackSession] (kills speak() calls
     * still in an async hop), drops [pendingSpeak] (an utterance parked behind
     * a TTS re-init used to survive a stop and play AFTER it), stops both audio
     * paths, cancels an in-flight cloud-voice fetch, and releases the
     * read-aloud keep-alive so the notification bar clears instead of a silent
     * service holding a wake lock.
     */
    private fun stopReadback() {
        readbackSession++
        pendingSpeak = null
        pendingSpeakSession = null
        ttsRemainingText = ""
        finalTtsUtteranceId = null
        ttsUtteranceText.clear()
        try { tts?.stop() } catch (_: Exception) { /* ignore */ }
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
                mediaPlayer?.reset()
            }
        } catch (_: Exception) { /* ignore */ }
        try { speakScope?.coroutineContext?.cancel(CancellationException("Readback stopped by user")) } catch (_: Exception) { /* ignore */ }
        adapter?.clearSpeakingPosition()
        releaseReadbackKeepAlive()
    }

    /**
     * Begin holding the process at foreground importance while the plain
     * read-aloud plays. Idempotent: a second call while already held is a no-op.
     * The poll watches actual playback (tts.isSpeaking / mediaPlayer.isPlaying)
     * and releases once audio has been heard and then stayed quiet, or once a
     * hard cap elapses with no audio (engine swallowed the utterance) — never
     * earlier, so a slow OpenAI-voice fetch can't drop the guard before playback
     * starts.
     */
    private fun acquireReadbackKeepAlive() {
        if (readbackKeepAliveActive) return
        readbackKeepAliveActive = true
        GenerationForegroundService.begin(this, chatId, chatName, reading = true)
        // A plain (non-hands-free) read-aloud is starting — the auto read-after-reply
        // or a manual speaker re-read. Turn the mic into a STOP control so a tap
        // stops the readback (during hands-free the mic is hidden instead, so skip).
        if (!isHandsFreeEngaged()) runOnUiThread { micReadbackStop() }
        val token = ++readbackKeepAliveToken
        val startedAt = System.currentTimeMillis()
        var everPlaying = false
        var quietPolls = 0
        lateinit var poll: Runnable
        poll = Runnable {
            if (token != readbackKeepAliveToken || !readbackKeepAliveActive) return@Runnable
            val playing = (try { tts?.isSpeaking == true } catch (_: Exception) { false }) ||
                          (try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false })
            if (playing) { everPlaying = true; quietPolls = 0 } else if (everPlaying) quietPolls++
            val elapsed = System.currentTimeMillis() - startedAt
            when {
                everPlaying && quietPolls >= HANDS_FREE_READBACK_STOP_POLLS -> releaseReadbackKeepAlive()
                // Audio never became audible within the hard cap: assume the
                // utterance was lost rather than hold the wake lock forever.
                !everPlaying && elapsed > HANDS_FREE_HARD_FALLBACK_MS -> releaseReadbackKeepAlive()
                else -> readbackKeepAliveHandler.postDelayed(poll, HANDS_FREE_READBACK_POLL_MS)
            }
        }
        readbackKeepAliveHandler.postDelayed(poll, HANDS_FREE_READBACK_POLL_MS)
    }

    /** Release the read-aloud keep-alive if held. Idempotent. */
    private fun releaseReadbackKeepAlive() {
        if (!readbackKeepAliveActive) return
        readbackKeepAliveActive = false
        readbackKeepAliveToken++ // stop any in-flight poll
        GenerationForegroundService.end(this)
        // The plain read-aloud is over — drop the mic's STOP look back to idle
        // (unless a hands-free conversation is running, where the mic stays hidden).
        if (!isHandsFreeEngaged()) runOnUiThread { micIdle() }
    }

    private val postNotificationsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored; service runs regardless */ }

    private fun ensurePostNotificationsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) return
        try {
            postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (_: Exception) { /* ignore */ }
    }

    private fun startHandsFreeService() {
        ensurePostNotificationsPermission()
        try {
            HandsFreeService.start(this, chatId, chatName)
        } catch (e: Exception) {
            // The loop still runs without the keep-alive, but the failure used
            // to leave no trace anywhere — screen-off protection was silently
            // missing when the session later died. One line per fresh loop
            // start, ungated. (On Android 14+, a revoked mic permission makes
            // this very start throw SecurityException — the line names it.)
            logVoiceEventAlways("HandsFreeService failed to start: ${e.javaClass.simpleName}: ${e.message} — " +
                    "hands-free continues WITHOUT the screen-off keep-alive")
        }
    }

    private fun stopHandsFreeService() {
        try {
            HandsFreeService.stop(this)
        } catch (_: Exception) { /* ignore */ }
    }

    private fun putMessage(message: String, isBot: Boolean, image: String = "", imageType: String = "") {
        val map: HashMap<String, Any> = HashMap()

        map["message"] = message
        map["isBot"] = isBot

        if (image != "") {
            map["image"] = image
            map["imageType"] = imageType
        }

        messages.add(map)
        adapter?.notifyItemInserted(messages.size - 1)

        updateMessagesSelectionProjection()

        scroll(true)
    }

    // ---- Streamed-reply completion state (Round 3) ------------------------
    // A streamed assistant reply is persisted incrementally, so a partial reply
    // on disk is otherwise indistinguishable from a finished one. These helpers
    // stamp a persisted completion marker (see MessageCompletionState) onto the
    // reply's own message map, so the marker travels atomically in the same JSON
    // blob as the text — there is never a window where the text is final but the
    // state is stale.

    /** Tag the just-added assistant placeholder as actively streaming. Not
     *  saved eagerly: the marker rides the first mid-stream save, so no
     *  fragment ever reaches disk without it. A death before that first save
     *  leaves nothing on disk to mislead. */
    private fun markLastAssistantStreaming() {
        val last = messages.lastOrNull() ?: return
        if (last["isBot"] == true) last[MessageCompletionState.KEY_STATE] = MessageCompletionState.STREAMING
    }

    /** Mark the last assistant reply as completed normally. The caller's
     *  existing completion saveSettings() persists it alongside the final text. */
    private fun markLastAssistantDone() {
        val last = messages.lastOrNull() ?: return
        if (last["isBot"] == true) {
            last[MessageCompletionState.KEY_STATE] = MessageCompletionState.DONE
            last.remove(MessageCompletionState.KEY_STATE_DETAIL)
        }
    }

    /** Stamp a terminal state onto the last assistant message ONLY if it is
     *  still marked streaming, and persist immediately. No-op otherwise, so it
     *  is safe from any terminal path and never downgrades an already-final
     *  state (e.g. a completion that raced ahead of a late cancellation). */
    private fun finalizeStreamingMessageState(state: String, detail: String?) {
        val last = messages.lastOrNull() ?: return
        if (last["isBot"] != true) return
        if (last[MessageCompletionState.KEY_STATE]?.toString() != MessageCompletionState.STREAMING) return
        last[MessageCompletionState.KEY_STATE] = state
        if (detail != null) last[MessageCompletionState.KEY_STATE_DETAIL] = detail
        saveSettings()
    }

    /** Content of an assistant message as the MODEL should see it. An
     *  unfinished reply gets an internal note appended so the model cannot
     *  mistake it for an intentionally completed reply — never shown to the
     *  user (this shapes the model projection only, not the visible text). */
    private fun modelFacingContent(message: HashMap<String, Any>): String {
        val content = message["message"].toString()
        if (message["isBot"] == true && content.isNotBlank() &&
            MessageCompletionState.isIncomplete(message[MessageCompletionState.KEY_STATE]?.toString())) {
            return content + "\n\n" + getString(R.string.message_incomplete_model_note)
        }
        return content
    }

    private fun scroll(mode: Boolean) {
        if (!disableAutoScroll) {
            val itemCount = adapter?.itemCount ?: 0

            if (mode) {
                chat?.post {
                    if (itemCount > 0) {
                        chat?.scrollToPosition(itemCount - 1)

                        scrollX(itemCount)
                    }
                }
            } else {
                scrollX(itemCount)
            }
        }
    }

    private fun scrollX(itemCount: Int) {
        chat?.post {
            val lastView = chat?.layoutManager?.findViewByPosition(itemCount - 1)
            lastView?.let {
                val scrollDistance = it.bottom - (chat?.height ?: 0)
                if (scrollDistance > 0) {
                    chat?.scrollBy(0, scrollDistance)
                }
            }
        }
    }

    private fun generateImages(prompt: String) {
        sendImageRequest(prompt)
    }

    private fun searchInternet(prompt: String) {
        putMessage("Searching at Google...", true)

        saveSettings()

        btnMicro?.isEnabled = true
        btnSend?.isEnabled = true
        progress?.visibility = View.GONE

        val q = prompt.replace(" ", "+")

        val intent = Intent()
        intent.action = Intent.ACTION_VIEW
        intent.data = "https://www.google.com/search?q=$q".toUri()
        startActivity(intent)
    }

    @Suppress("deprecation")
    private suspend fun generateResponse(request: String, shouldPronounce: Boolean) {
        // The single generation funnel is also the single guard point: no
        // generation into a chat whose stored history is locked or
        // preserved-corrupt (Round 4) — typed, voice and retry paths all
        // flow through here, and a reply that can't be saved must not be
        // produced over the blocking "Chat unavailable" state.
        if (chatStorageUnavailable) return

        disableAutoScroll = false

        // Capture the user's message here, the single point every input method flows
        // through (typing, voice recognition, and Whisper transcription), so the
        // lorebook matches triggers regardless of how the message was entered.
        lastUserMessageForLore = request

        // Keep the app at foreground importance for the whole generation so the
        // stream survives the screen turning off or the user switching apps
        // (otherwise the OS freezes the process / lets Wi-Fi power-save drop the
        // socket, and the request dies with "Software caused connection abort").
        GenerationForegroundService.begin(this, chatId, chatName)

        try {
            var response = ""

            if (imageIsSelected) {
                imageIsSelected = false

                attachedImage?.visibility = View.GONE

                putMessage("", true)
                markLastAssistantStreaming()

                val reqList: ArrayList<ContentPart> = arrayListOf()
                reqList.add(TextPart(request))
                reqList.add(ImagePart(baseImageString!!))
                val chatCompletionRequest = if (preferences?.getLogitBiasesConfigId() == null || preferences?.getLogitBiasesConfigId() == "null" || preferences?.getLogitBiasesConfigId() == "") {
                    ChatCompletionRequest(
                        model = ModelId("gpt-4o"),
                        temperature = if (preferences!!.getTemperature().toDouble() == 0.7) null else preferences!!.getTemperature().toDouble(),
                        topP = if (preferences!!.getTopP().toDouble() == 1.0) null else preferences!!.getTopP().toDouble(),
                        frequencyPenalty = if (preferences!!.getFrequencyPenalty().toDouble() == 0.0) null else preferences!!.getFrequencyPenalty().toDouble(),
                        presencePenalty = if (preferences!!.getPresencePenalty().toDouble() == 0.0) null else preferences!!.getPresencePenalty().toDouble(),
                        logitBias = logitBiasPreferences?.getLogitBiasesMap(),
                        seed = if (preferences!!.getSeed() != "") preferences!!.getSeed().toInt() else null,
                        messages = listOf(
                            ChatMessage(
                                role = ChatRole.System,
                                content = "You are a helpful assistant!"
                            ),
                            ChatMessage(
                                role = ChatRole.User,
                                content = reqList
                            )
                        )
                    )
                } else {
                    ChatCompletionRequest(
                        model = ModelId("gpt-4o"),
                        temperature = if (preferences!!.getTemperature().toDouble() == 0.7) null else preferences!!.getTemperature().toDouble(),
                        topP = if (preferences!!.getTopP().toDouble() == 1.0) null else preferences!!.getTopP().toDouble(),
                        frequencyPenalty = if (preferences!!.getFrequencyPenalty().toDouble() == 0.0) null else preferences!!.getFrequencyPenalty().toDouble(),
                        presencePenalty = if (preferences!!.getPresencePenalty().toDouble() == 0.0) null else preferences!!.getPresencePenalty().toDouble(),
                        seed = if (preferences!!.getSeed() != "") preferences!!.getSeed().toInt() else null,
                        messages = listOf(
                            ChatMessage(
                                role = ChatRole.System,
                                content = "You are a helpful assistant!"
                            ),
                            ChatMessage(
                                role = ChatRole.User,
                                content = reqList
                            )
                        )
                    )
                }

                val completions: Flow<ChatCompletionChunk> = ai!!.chatCompletions(chatCompletionRequest)

                scroll(true)

                completions.flowOn(Dispatchers.IO).collect { v ->
                    run {
                        if (!currentCoroutineContext().isActive) throw CancellationException()
                        else if (v.choices[0].delta != null && v.choices[0].delta?.content != null && v.choices[0].delta?.content.toString() != "null") {
                            response += v.choices[0].delta?.content
                            if (response != "null") {
                                messages[messages.size - 1]["message"] = response
                                if (messages.size > 2) {
                                    adapter?.notifyItemRangeChanged(messages.size - 3, messages.size - 1)
                                } else {
                                    adapter?.notifyItemChanged(messages.size - 1)
                                }
                                scroll(false)
                                saveSettings()
                            }
                        }
                    }
                }

                messages[messages.size - 1]["message"] = "${response}\n"
                markLastAssistantDone()

                if (messages.size > 2) {
                    adapter?.notifyItemRangeChanged(messages.size - 3, messages.size - 1)
                } else {
                    adapter?.notifyItemChanged(messages.size - 1)
                }

                syncChatProjection()

                pronounce(shouldPronounce, response)

                saveSettings()
                calculateCost()

                btnMicro?.isEnabled = true
                btnSend?.isEnabled = true
                progress?.visibility = View.GONE
                messageInput?.requestFocus()
            } else if (model.contains(":ft") || model.contains("ft:")) {
                putMessage("", true)
                markLastAssistantStreaming()
                val completionRequest = if (preferences?.getLogitBiasesConfigId() == null || preferences?.getLogitBiasesConfigId() == "null" || preferences?.getLogitBiasesConfigId() == "") {
                    CompletionRequest(
                        model = ModelId(model),
                        temperature = if (model.contains("gpt-5") || model.contains("o1") || model.contains("o3")) 1.0 else if (preferences!!.getTemperature().toDouble() == 0.7) null else preferences!!.getTemperature().toDouble(),
                        topP = if (preferences!!.getTopP().toDouble() == 1.0) null else preferences!!.getTopP().toDouble(),
                        frequencyPenalty = if (preferences!!.getFrequencyPenalty().toDouble() == 0.0) null else preferences!!.getFrequencyPenalty().toDouble(),
                        presencePenalty = if (preferences!!.getPresencePenalty().toDouble() == 0.0) null else preferences!!.getPresencePenalty().toDouble(),
                        prompt = request,
                        logitBias = if (model.contains("gpt-5") || model.contains("o1") || model.contains("o3")) null else logitBiasPreferences?.getLogitBiasesMap(),
                        echo = false
                    )
                } else {
                    CompletionRequest(
                        model = ModelId(model),
                        temperature = if (model.contains("gpt-5") || model.contains("o1") || model.contains("o3")) 1.0 else if (preferences!!.getTemperature().toDouble() == 0.7) null else preferences!!.getTemperature().toDouble(),
                        topP = if (preferences!!.getTopP().toDouble() == 1.0) null else preferences!!.getTopP().toDouble(),
                        frequencyPenalty = if (preferences!!.getFrequencyPenalty().toDouble() == 0.0) null else preferences!!.getFrequencyPenalty().toDouble(),
                        presencePenalty = if (preferences!!.getPresencePenalty().toDouble() == 0.0) null else preferences!!.getPresencePenalty().toDouble(),
                        prompt = request,
                        echo = false
                    )
                }

                val completions: Flow<TextCompletion> = ai!!.completions(completionRequest)

                completions.flowOn(Dispatchers.IO).collect { v ->
                    run {
                        if (!currentCoroutineContext().isActive) throw CancellationException()
                        else if (v.choices[0] != null && v.choices[0].text != null && v.choices[0].text.toString() != "null") {
                            response += v.choices[0].text
                            messages[messages.size - 1]["message"] = response
                            if (messages.size > 2) {
                                adapter?.notifyItemRangeChanged(messages.size - 3, messages.size - 1)
                            } else {
                                adapter?.notifyItemChanged(messages.size - 1)
                            }
                            saveSettings()
                        }
                    }
                }

                messages[messages.size - 1]["message"] = "$response\n"
                markLastAssistantDone()
                if (messages.size > 2) {
                    adapter?.notifyItemRangeChanged(messages.size - 3, messages.size - 1)
                } else {
                    adapter?.notifyItemChanged(messages.size - 1)
                }

                syncChatProjection()

                saveSettings()
                calculateCost()

                pronounce(shouldPronounce, response)

                btnMicro?.isEnabled = true
                btnSend?.isEnabled = true
                progress?.visibility = View.GONE
                messageInput?.requestFocus()
            } else {
                val functionCallingEnabled: Boolean = preferences!!.getFunctionCalling()

                if (functionCallingEnabled && openAIKey != null) {
                    val cm = mutableListOf(
                        ChatMessage(
                            role = ChatRole.User,
                            content = request
                        )
                    )

                    val functionRequest = chatCompletionRequest {
                        model = ModelId("gpt-4o")
                        messages = cm

                        tools {
                            function(
                                name = "generateImage",
                                description = "Generate an image based on the entered prompt"
                            ) {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("prompt") {
                                        put("type", "string")
                                        put("description", "The prompt for image generation")
                                    }
                                }
                                putJsonArray("required") {
                                    add("prompt")
                                }
                            }

                            function(
                                name = "searchAtInternet",
                                description = "Search the Internet",
                            ) {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("prompt") {
                                        put("type", "string")
                                        put("description", "Search query")
                                    }
                                }
                                putJsonArray("required") {
                                    add("prompt")
                                }
                            }
                        }

                        toolChoice = ToolChoice.Auto
                    }

                    val response1 = openAIAI?.chatCompletion(functionRequest)

                    val message = response1?.choices?.first()?.message

                    if (message?.toolCalls != null) {
                        val toolsCalls = message.toolCalls!!

                        if (toolsCalls.isEmpty()) {
                            regularGPTResponse(shouldPronounce)
                        } else {
                            for (toolCall in toolsCalls) {
                                require(toolCall is ToolCall.Function) { "Tool call is not a function" }
                                toolCall.execute()
                            }

                            // Put timestamp to chat to sort chats by last message
                            ChatPreferences.getChatPreferences().putTimestampToChatById(this, chatId)
                        }
                    } else {
                        regularGPTResponse(shouldPronounce)
                    }
                } else if (functionCallingEnabled) {
                    putMessage("Function calling requires OpenAI endpoint which is missing on your device. Please go to the settings and add OpenAI endpoint or disable Function Calling. OpenAI base url (host) is: https://api.openai.com/v1/ (don't forget to add slash at the end otherwise you will receive an error).", true)
                    saveSettings()
                    restoreUIState()
                    MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                        .setTitle("Unsupported feature")
                        .setMessage("Function calling feature is unavailable because it requires OpenAI endpoint. Would you like to disable this feature?")
                        .setPositiveButton("Disable") { _, _ -> run {
                            preferences?.setFunctionCalling(false)
                        }}
                        .setNegativeButton("Cancel") { _, _ -> }
                        .show()
                } else {
                    regularGPTResponse(shouldPronounce)
                }
            }
        } catch (_: CancellationException) {
            // The stream was cut short. Stamp a terminal state on the reply that
            // was streaming so it can't reopen looking finished, keeping whatever
            // partial text already streamed in. A user stop (mic/hang-up/progress
            // cancel) while the screen is alive is "stopped"; a cancellation from
            // the activity being torn down is "interrupted". This save also
            // closes the streaming marker so the load-time reconciler has nothing
            // to fix (they are belt-and-suspenders for a hard process kill, which
            // runs no code here at all). No suspension points below, so this runs
            // even though the coroutine is already cancelled.
            val destroying = isFinishing || isDestroyed
            finalizeStreamingMessageState(
                if (destroying) MessageCompletionState.INTERRUPTED else MessageCompletionState.STOPPED,
                if (destroying) MessageCompletionState.DETAIL_SCREEN_CLOSED else null
            )
            calculateCost()
            runOnUiThread {
                restoreUIState()
            }
        } catch (e: Exception) {
            playErrorSignal()
            stopHandsFreeOnError()
            // Single funnel: classify the failure to a stable code, always write
            // the diagnostic Error Log entry, and show the user the short coded
            // message (no profile/URL/model/trace — those live in the log). See
            // ERROR_CODES.md.
            val genError = GenerationErrorClassifier.classify(e)
            logGenerationError(genError, e, "message")
            val response = genError.chatMessage(this)

            if (messages.isEmpty() || messages[messages.size - 1]["isBot"] == false) {
                putMessage("", true)
            }

            // The reply failed before finishing. Mark it failed (keeping whatever
            // partial text streamed in) and stash the coded error SEPARATELY from
            // the reply text — the error prose is no longer appended into the
            // message body, so the model's own words are never contaminated. The
            // adapter renders the error next to the failure marker only when
            // "Show chat errors" is on.
            val failedIndex = messages.size - 1
            if (messages[failedIndex]["isBot"] == true) {
                messages[failedIndex][MessageCompletionState.KEY_STATE] = MessageCompletionState.FAILED
                messages[failedIndex][MessageCompletionState.KEY_STATE_DETAIL] = genError.code.code
                if (preferences?.showChatErrors() == true) {
                    messages[failedIndex][MessageCompletionState.KEY_ERROR_TEXT] = response
                } else {
                    messages[failedIndex].remove(MessageCompletionState.KEY_ERROR_TEXT)
                }
                if (messages.size > 2) {
                    adapter?.notifyItemRangeChanged(messages.size - 3, messages.size - 1)
                } else {
                    adapter?.notifyItemChanged(messages.size - 1)
                }
            }

            saveSettings()
            calculateCost()

            runOnUiThread {
                btnMicro?.isEnabled = true
                btnSend?.isEnabled = true
                progress?.visibility = View.GONE
                messageInput?.requestFocus()
            }
        } finally {
            GenerationForegroundService.end(this)
            // Memory system transcript capture: this finally is the one place
            // every turn (typed or voice, success or failure) passes exactly
            // once with the user's message still in scope — the same
            // single-funnel property the lorebook relies on.
            recordTranscriptTurn(request)
            calculateCost()
            runOnUiThread {
                restoreUIState()
            }
        }
    }

    /**
     * Queue this completed turn for the memory system's Archivist (Phase 2 of
     * memory-system-integration-plan.md). Reads the assistant's reply from the
     * live message list, snapshots the sampling settings (quick settings are
     * gospel — the Archivist wants to know which knobs served the turn), and
     * hands off to TranscriptRecorder on a worker thread. Best-effort in every
     * direction: no store, memory off, or any failure must never disturb the
     * conversation or the voice loop.
     */
    // One soft notification per process when the full memory system degrades
    // mid-conversation (enforcer spec: "user notified once, softly") — the
    // Event/Memory log carries the details, the toast just says it happened.
    // The flag lives on the companion object (memoryDegradedNotified below), so
    // it is genuinely once PER PROCESS: an instance field re-armed on every
    // ChatActivity recreation (e.g. rotation), turning "once, softly" into a
    // repeat toast for the same degraded session.
    private fun notifyMemoryDegradedOnce() {
        if (!memoryDegradedNotified.compareAndSet(false, true)) return
        runOnUiThread {
            Toast.makeText(this, getString(R.string.memory_degraded_notice), Toast.LENGTH_SHORT).show()
        }
    }

    /** Short rolling context for the librarian's retrieval query (enforcer
     *  spec: the message plus a summary of the last few turns, so
     *  mid-conversation topics keep retrieving — not just the latest line).
     *  The current user message is already the list's tail, so it's dropped. */
    private fun recentTurnsContext(): String {
        return try {
            chatMessages.dropLast(1)
                .takeLast(org.teslasoft.assistant.preferences.memory.enforcer.Enforcer.RECENT_CONTEXT_TURNS)
                .joinToString("\n") {
                    (it.content ?: "").take(
                        org.teslasoft.assistant.preferences.memory.enforcer.Enforcer.RECENT_CONTEXT_CHARS_PER_TURN
                    )
                }
        } catch (_: Exception) { "" }
    }

    private fun recordTranscriptTurn(request: String) {
        try {
            if (!MemoryStore.isProvisioned(this)) return
            val last = messages.lastOrNull() ?: return
            if (last["isBot"] != true) return
            val reply = last["message"].toString()
            if (reply.isBlank() || request.isBlank()) return

            // The reply is captured either way (nothing successfully received is
            // silently dropped), but a reply that did not finish streaming is
            // marked incomplete so the Archivist never treats a truncated
            // fragment as a reliable fact. Legacy/done -> complete.
            val replyComplete = MessageCompletionState.isComplete(
                last[MessageCompletionState.KEY_STATE]?.toString()
            )

            val appContext = applicationContext
            val turnChatId = chatId
            val turnPersonaId = preferences?.getPersonaId().orEmpty()
            val turnModel = model
            val memoryEnabled = preferences?.getChatMemoryEnabled() ?: true
            val excluded = preferences?.isChatExcludedFromMemory() ?: false
            val quickSettings = try {
                org.json.JSONObject()
                    .put("model", turnModel)
                    .put("temperature", preferences?.getTemperature()?.toDouble())
                    .put("top_p", preferences?.getTopP()?.toDouble())
                    .put("frequency_penalty", preferences?.getFrequencyPenalty()?.toDouble())
                    .put("presence_penalty", preferences?.getPresencePenalty()?.toDouble())
                    .put("max_tokens", preferences?.getMaxTokens())
                    .toString()
            } catch (_: Exception) { null }

            Thread {
                TranscriptRecorder.recordTurn(
                    appContext, turnChatId, turnPersonaId, request, reply,
                    turnModel, quickSettings, memoryEnabled, excluded, replyComplete
                )
            }.start()
        } catch (e: Exception) {
            // Capture must never break a turn — but a silently swallowed error
            // here is why a capture failure was invisible. Log it (best effort).
            try {
                org.teslasoft.assistant.preferences.memory.MemoryLog.log(
                    this, "Transcript", "error", "recordTranscriptTurn threw: ${e.message}"
                )
            } catch (_: Exception) { /* logging is best effort */ }
        }
    }

    /** True when this turn is part of voice interaction (hands-free mode, an
     *  active recording, or a pending readback). Used for the `Voice` flag on the
     *  Error Log entry (see ERROR_CODES.md). */
    private fun isVoiceLive(): Boolean =
        preferences?.getHandsFreeMode() == true || isRecording || handsFreeReadbackExpected

    /**
     * Write the always-on Error Log entry for a classified generation failure
     * (ERROR_CODES.md). Unlike the chat message, this carries the diagnostic
     * context the chat deliberately omits — profile, Base URL, model, voice flag,
     * HTTP status — plus the exception detail, or the full stack trace for the
     * ambiguous/unknown codes (S2/U0). Written on every error regardless of the
     * "Show chat errors" toggle, which controls only the chat display. Never logs
     * the API key, headers, or prompt text.
     *
     * The entry is written to the "crash" channel, which is the user-facing Error
     * Log. `trigger` is which generation path failed (e.g. "message",
     * "image-generation"); finer values (regenerate/continue) can be threaded
     * through the funnel later. When voice is live a compact snapshot is appended,
     * and the full last-known voice info is left in the Voice Debug Log too (see
     * logVoiceFailureSnapshot) so a clue is there even with per-turn logging off.
     */
    private fun logGenerationError(result: GenErrorResult, e: Throwable, trigger: String) {
        val voiceLive = isVoiceLive()
        try {
            val sb = StringBuilder()
            sb.append(result.chatMessage(this)).append('\n')
            sb.append("Profile: ${apiEndpointObject?.label ?: "unknown"}\n")
            sb.append("Base URL: ${apiEndpointObject?.host ?: "unknown"}\n")
            sb.append("Model: ${model.ifBlank { "unknown" }}\n")
            sb.append("Voice: ${if (voiceLive) "active" else "inactive"}\n")
            sb.append("Trigger: $trigger\n")
            sb.append("Screen: ${screenState()}\n")
            sb.append("Network: ${networkState()}\n")
            sb.append("Power save: ${powerSaveState()}")
            result.httpStatus?.let { sb.append("\nHTTP status: $it") }
            if (voiceLive) sb.append('\n').append(compactVoiceContext())
            if (result.code.includeStackTrace) {
                sb.append("\n\n").append(e.stackTraceToString())
            } else {
                e.message?.takeIf { it.isNotBlank() }?.let { sb.append("\nDetail: $it") }
            }
            org.teslasoft.assistant.preferences.Logger.log(this, "crash", "GenError", "error", sb.toString())
        } catch (_: Throwable) { /* never let diagnostics crash the error path */ }

        // Failure clue into the Voice Debug Log even when per-turn voice logging is
        // off (ERROR_CODES.md section 5). No-op when voice wasn't live.
        logVoiceFailureSnapshot(result.code.code)
    }

    /** "on"/"off"/"unknown" — whether the screen was interactive at failure time.
     *  Key signal for the screen-off/Wi-Fi-sleep hypothesis (ERROR_CODES.md E1). */
    private fun screenState(): String = try {
        if ((getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive) "on" else "off"
    } catch (_: Throwable) { "unknown" }

    /** "on"/"off"/"unknown" — battery power-save mode at failure time. */
    private fun powerSaveState(): String = try {
        if ((getSystemService(POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode) "on" else "off"
    } catch (_: Throwable) { "unknown" }

    /** Active transport: wifi/cellular/ethernet/other/none/unknown. Best-effort;
     *  any failure (e.g. missing permission) is reported as "unknown". */
    private fun networkState(): String = try {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val net = cm.activeNetwork
        if (net == null) "none" else {
            val caps = cm.getNetworkCapabilities(net)
            when {
                caps == null -> "unknown"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        }
    } catch (_: Throwable) { "unknown" }

    /** Compact voice snapshot appended to the Error Log entry when voice is live —
     *  a short state summary, not the per-turn firehose (ERROR_CODES.md section 5). */
    private fun compactVoiceContext(): String {
        val engine = try { LocalWhisperEngine.get() } catch (_: Throwable) { null }
        val loop = if (preferences?.getHandsFreeMode() == true) "hands-free" else "push-to-talk"
        val stt = if (preferences?.getActiveLocalWhisperModel().orEmpty().isNotEmpty()) "local-whisper" else "google"
        val mic = engine?.lastMicRouteDiagnostics().orEmpty().lineSequence().firstOrNull().orEmpty()
        val vad = engine?.lastVadDiagnostics().orEmpty().lineSequence().firstOrNull().orEmpty()
        val sb = StringBuilder("Voice context:\n  Loop: $loop\n  STT: $stt")
        if (mic.isNotEmpty()) sb.append("\n  Mic route: $mic")
        if (vad.isNotEmpty()) sb.append("\n  VAD: $vad")
        return sb.toString()
    }

    /** On a failure while voice was live, write the full last-known voice info to
     *  the Voice Debug Log ("event" channel) regardless of the VAD-logging
     *  toggles, so a failure always leaves a clue there (ERROR_CODES.md section 5). */
    private fun logVoiceFailureSnapshot(code: String) {
        if (!isVoiceLive()) return
        try {
            val engine = LocalWhisperEngine.get()
            val parts = ArrayList<String>()
            parts.add("Voice snapshot at failure [$code]")
            engine.lastMicRouteDiagnostics().takeIf { it.isNotEmpty() }?.let { parts.add("Mic route: $it") }
            engine.lastVadDiagnostics().takeIf { it.isNotEmpty() }?.let { parts.add("VAD: $it") }
            engine.lastAudioHealthDiagnostics().takeIf { it.isNotEmpty() }?.let { parts.add("Audio: $it") }
            org.teslasoft.assistant.preferences.Logger.log(this, "event", "GenError", "info", parts.joinToString("\n"))
        } catch (_: Throwable) { /* never let diagnostics crash the error path */ }
    }

    /**
     * Plays a short descending three-note tone when a response fails, so the user
     * knows a reply isn't coming even when they aren't looking at the screen.
     * Routed through the alarm stream so it is still audible when the phone's
     * ringer is set to silent / vibrate (alarms bypass ringer-silent, the same way
     * an alarm clock still sounds on a muted phone).
     */
    private fun playErrorSignal() {
        if (preferences?.getErrorSound() != true) return

        Thread {
            var track: AudioTrack? = null
            try {
                val sampleRate = 44100
                // A4 -> F4 -> D4: a descending, "disappointed" cadence.
                val notes = floatArrayOf(440.0f, 349.23f, 293.66f)
                val noteMs = 200
                val gapMs = 45
                val samplesPerNote = sampleRate * noteMs / 1000
                val samplesPerGap = sampleRate * gapMs / 1000
                val totalSamples = (samplesPerNote + samplesPerGap) * notes.size
                val buffer = ShortArray(totalSamples)

                var idx = 0
                for (freq in notes) {
                    for (i in 0 until samplesPerNote) {
                        val t = i.toDouble() / sampleRate
                        // Linear fade in/out to avoid clicks at note boundaries.
                        val envelope = when {
                            i < samplesPerNote * 0.1 -> i / (samplesPerNote * 0.1)
                            i > samplesPerNote * 0.8 -> (samplesPerNote - i) / (samplesPerNote * 0.2)
                            else -> 1.0
                        }
                        val sample = Math.sin(2.0 * Math.PI * freq * t) * envelope * 0.5 * Short.MAX_VALUE
                        buffer[idx++] = sample.toInt().toShort()
                    }
                    idx += samplesPerGap // leave silence (buffer is zero-initialized)
                }

                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val format = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()

                track = AudioTrack(
                    attributes,
                    format,
                    totalSamples * 2,
                    AudioTrack.MODE_STATIC,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
                track.write(buffer, 0, totalSamples)
                track.play()

                Thread.sleep(((noteMs + gapMs) * notes.size + 150).toLong())
                track.stop()
            } catch (_: Exception) {
                // Never let the alert sound interfere with surfacing the actual error.
            } finally {
                try { track?.release() } catch (_: Exception) { /* ignore */ }
            }
        }.start()
    }

    /**
     * Played when the hands-free loop gives up on its own — it heard nothing, or
     * couldn't capture audio, within the listening window — so a user with the
     * screen off knows it stopped listening rather than sitting in false silence.
     * Deliberately distinct from [playErrorSignal] (the model-error tone): two
     * low, slow descending notes (A3 -> E3) that read as "going quiet", not the
     * higher three-note "something failed" cadence. Same alarm-stream routing so
     * it stays audible on silent/vibrate. Respects the error-sound toggle so it
     * has an off switch alongside the other alert sound.
     */
    private fun playNoSpeechSignal() {
        if (preferences?.getErrorSound() != true) return

        Thread {
            var track: AudioTrack? = null
            try {
                val sampleRate = 44100
                // A3 -> E3: low, "settling down / went quiet" cadence.
                val notes = floatArrayOf(220.0f, 164.81f)
                val noteMs = 260
                val gapMs = 60
                val samplesPerNote = sampleRate * noteMs / 1000
                val samplesPerGap = sampleRate * gapMs / 1000
                val totalSamples = (samplesPerNote + samplesPerGap) * notes.size
                val buffer = ShortArray(totalSamples)

                var idx = 0
                for (freq in notes) {
                    for (i in 0 until samplesPerNote) {
                        val t = i.toDouble() / sampleRate
                        val envelope = when {
                            i < samplesPerNote * 0.1 -> i / (samplesPerNote * 0.1)
                            i > samplesPerNote * 0.8 -> (samplesPerNote - i) / (samplesPerNote * 0.2)
                            else -> 1.0
                        }
                        val sample = Math.sin(2.0 * Math.PI * freq * t) * envelope * 0.5 * Short.MAX_VALUE
                        buffer[idx++] = sample.toInt().toShort()
                    }
                    idx += samplesPerGap // silence (buffer is zero-initialized)
                }

                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val format = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()

                track = AudioTrack(
                    attributes,
                    format,
                    totalSamples * 2,
                    AudioTrack.MODE_STATIC,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
                track.write(buffer, 0, totalSamples)
                track.play()

                Thread.sleep(((noteMs + gapMs) * notes.size + 150).toLong())
                track.stop()
            } catch (_: Exception) {
                // A missing cue must never crash the loop teardown.
            } finally {
                try { track?.release() } catch (_: Exception) { /* ignore */ }
            }
        }.start()
    }

    /**
     * Plays a short ascending three-note tone once the user's speech has been
     * transcribed, so they know dictation finished without looking at the screen.
     * Deliberately the mirror image of [playErrorSignal]: same alarm-stream
     * routing (so it stays audible when the ringer is silent/vibrate), opposite
     * cadence — a rising D4 -> F4 -> A4 "ready" chime instead of the falling
     * "disappointed" one. Off unless the user opts in via the voice settings.
     */
    private fun playTranscriptionDoneSignal() {
        if (preferences?.getTranscriptionDoneSound() != true) return

        Thread {
            var track: AudioTrack? = null
            try {
                val sampleRate = 44100
                // Warm, soft two-note rising chime (G4 → C5, an ascending
                // fourth) in a comfortable mid register. Stays clear of the
                // error tone's descending A4→F4→D4 notes, but kept low and
                // gentle (longer notes, soft envelope, lower amplitude) so it
                // reads as a pleasant "got it" rather than a shrill beep.
                val notes = floatArrayOf(392.0f, 523.25f)
                val noteMs = 120
                val gapMs = 30
                val samplesPerNote = sampleRate * noteMs / 1000
                val samplesPerGap = sampleRate * gapMs / 1000
                val totalSamples = (samplesPerNote + samplesPerGap) * notes.size
                val buffer = ShortArray(totalSamples)

                var idx = 0
                for (freq in notes) {
                    for (i in 0 until samplesPerNote) {
                        val t = i.toDouble() / sampleRate
                        // Gentle attack and a long release so the note tapers
                        // off softly instead of clicking — keeps it un-jarring.
                        val envelope = when {
                            i < samplesPerNote * 0.1 -> i / (samplesPerNote * 0.1)
                            i > samplesPerNote * 0.5 -> (samplesPerNote - i) / (samplesPerNote * 0.5)
                            else -> 1.0
                        }
                        val sample = Math.sin(2.0 * Math.PI * freq * t) * envelope * 0.28 * Short.MAX_VALUE
                        buffer[idx++] = sample.toInt().toShort()
                    }
                    idx += samplesPerGap
                }

                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val format = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()

                track = AudioTrack(
                    attributes,
                    format,
                    totalSamples * 2,
                    AudioTrack.MODE_STATIC,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
                track.write(buffer, 0, totalSamples)
                track.play()

                Thread.sleep(((noteMs + gapMs) * notes.size + 150).toLong())
                track.stop()
            } catch (_: Exception) {
                // A missing confirmation chime must never break dictation.
            } finally {
                try { track?.release() } catch (_: Exception) { /* ignore */ }
            }
        }.start()
    }

    private val availableFunctions = mapOf("generateImage" to ::generateImage, "searchAtInternet" to ::searchAtInternet)

    private fun ToolCall.Function.execute() {
        val functionToCall = availableFunctions[function.name] ?: error("Function ${function.name} not found")
        val functionArgs = function.argumentsAsJson()
        functionToCall(functionArgs)
    }

    private fun generateImage(args: JsonObject) {
        val prompt = args.getValue("prompt").jsonPrimitive.content

        runOnUiThread {
            btnMicro?.isEnabled = false
            btnSend?.isEnabled = false
            progress?.visibility = View.VISIBLE
        }

        CoroutineScope(Dispatchers.Main).launch {
            generateImages(prompt)
        }
    }

    private fun searchAtInternet(args: JsonObject) {
        val prompt = args.getValue("prompt").jsonPrimitive.content

        runOnUiThread {
            btnMicro?.isEnabled = false
            btnSend?.isEnabled = false
            progress?.visibility = View.VISIBLE
        }

        CoroutineScope(Dispatchers.Main).launch {
            searchInternet(prompt)
        }
    }

    private suspend fun regularGPTResponse(shouldPronounce: Boolean) {
        disableAutoScroll = false

        var response = ""
        putMessage("", true)
        markLastAssistantStreaming()

        val msgs: ArrayList<ChatMessage> = arrayListOf()

        // Merge the selected persona prompt (first) with the always-on system message
        // into a single, stable System message. Keeping it identical and first on every
        // request is what lets providers' automatic prefix caching kick in.
        val systemMessage = preferences!!.getSystemMessage()
        val personaId = preferences!!.getPersonaId()
        val personaPrompt = if (personaId != "") {
            PersonaPreferences.getPersonaPreferences(this).getPersona(personaId).prompt
        } else {
            ""
        }
        val effectiveSystemMessage = listOf(personaPrompt, systemMessage)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        if (effectiveSystemMessage != "") {
            msgs.add(
                ChatMessage(
                    role = ChatRole.System,
                    content = effectiveSystemMessage
                )
            )
        }

        // Model rules (Stage 4, owner_approved_rules §11 Revision 5): every
        // ACTIVE rule whose model string matches this chat's model renders as
        // its OWN prompt-layer block after the stable prefix and before the
        // memory message (prompt-layer contract, block 2). Rules apply
        // automatically and are ON by default; the per-chat "Apply Model
        // Rules" toggle (which follows the global default) gates them. Never
        // appended to the stable first message — that would mutate the cached
        // prefix — and never placed inside the memory message. Deterministic
        // and byte-stable: matching rules only, fixed store order, same
        // wording every turn; with the toggle off or nothing matching the
        // block is absent entirely (zero bytes of the request change). Matches
        // are never truncated (§11). Independent of the memory-engine tier —
        // model rules are not memory content. Any failure degrades to "no
        // model rules this turn" and never blocks generation.
        if (preferences!!.getChatApplyModelRules() && MemoryStore.isProvisioned(this)) {
            val modelRulesBlock: String? = try {
                withContext(Dispatchers.IO) {
                    val rules = MemoryStore.getInstance(this@ChatActivity)
                        .getActiveModelRulesForModel(model)
                    if (rules.isEmpty()) null
                    else rules.joinToString(
                        separator = "\n",
                        prefix = getString(R.string.model_rules_injection_header) + "\n"
                    ) { "- " + it.text }
                }
            } catch (e: Exception) {
                org.teslasoft.assistant.preferences.memory.MemoryLog.log(
                    this, "ModelRules", "error", "Model rules unavailable this turn: ${e.message}"
                )
                null
            }
            if (modelRulesBlock != null) {
                msgs.add(
                    ChatMessage(
                        role = ChatRole.System,
                        content = modelRulesBlock
                    )
                )
            }
        }

        // QUICK SETTINGS IS AUTHORITATIVE (owner ruling, July 10 2026): the
        // per-chat "Use lore books" and "Use memory" switches decide, each on
        // its own, what this chat injects — any combination works. The global
        // Memory engine picker only supplies the DEFAULTS for chats that never
        // touched their switches (see the tri-state getters in Preferences).
        val loreBooksEnabled = preferences?.getChatLoreBooksEnabled() == true

        // Lorebook (memory system): match the user's latest message against the
        // persona's core lorebook (always active when the persona is used) plus
        // whichever additional lorebooks are checked for this chat, and inject the
        // matched memories as their own System message, placed after the base
        // prompt so prefix caching of the stable prompt holds.
        val allLoreMatches = ArrayList<LoreBookMatch>()
        // -1 = the store threw (unavailable); the debug view distinguishes that
        // from "searched N books and matched nothing" and "had no active books".
        var activeLoreBookCount = -1
        if (loreBooksEnabled) {
            try {
                val loreStore = LoreBookStore.getInstance(this)
                val activeBookIds = LinkedHashSet<String>()
                val checkedIds = preferences?.getActiveLoreBookIds() ?: arrayListOf()
                if (personaId != "") {
                    val loreBookPersona = PersonaPreferences.getPersonaPreferences(this).getPersona(personaId)
                    // Core book first: when the injection budget truncates, core memories win.
                    if (loreBookPersona.coreLoreBookId != "") activeBookIds.add(loreBookPersona.coreLoreBookId)
                    // Only books still linked to the persona count; a stale checked id
                    // left over from before an unlink must not keep injecting.
                    val linked = loreBookPersona.additionalLoreBookIdList()
                    activeBookIds.addAll(checkedIds.filter { linked.contains(it) })
                } else {
                    activeBookIds.addAll(checkedIds)
                }

                for (bookId in activeBookIds) {
                    allLoreMatches.addAll(loreStore.findMatches(lastUserMessageForLore, bookId))
                }
                activeLoreBookCount = activeBookIds.size
            } catch (e: Exception) {
                // The lorebook is now SQLCipher-backed; if its key/store is ever
                // unreadable the conversation must continue without lore rather
                // than crash mid-generation (never break the companion).
                org.teslasoft.assistant.preferences.memory.MemoryLog.log(this, "LoreBook", "error", "Lorebook unavailable this turn: ${e.message}")
            }
            // Every turn is recorded — zero-match and store-unavailable turns
            // included — so "lore didn't reach the model" is diagnosable from
            // the debug screen instead of invisible.
            LoreBookInjectionLog.record(lastUserMessageForLore, allLoreMatches, activeLoreBookCount)
        }

        // Full memory system (Phase 4 enforcer): assemble the per-turn memory
        // message — retrieved memories, lore notes, scene — as ONE separate
        // system message after the stable base prompt. Gated on the per-chat
        // "Use memory" switch alone (Quick Settings is God; the engine tier is
        // only its default). With lore books off for the chat, allLoreMatches
        // is empty, so the assembly contains no lore notes — the switches stay
        // independent. ANY failure degrades to the classic lore path below:
        // never block a turn.
        var memoryAssembly: String? = null
        if (preferences?.getChatMemoryEnabled() == true &&
            MemoryStore.isProvisioned(this)
        ) {
            memoryAssembly = try {
                withContext(Dispatchers.IO) {
                    org.teslasoft.assistant.preferences.memory.enforcer.Enforcer.getInstance(this@ChatActivity)
                        .assembleTurn(
                            org.teslasoft.assistant.preferences.memory.enforcer.Enforcer.TurnInput(
                                chatId = chatId,
                                personaId = personaId,
                                userMessage = lastUserMessageForLore,
                                recentContext = recentTurnsContext(),
                                modelTag = model,
                                loreMatches = allLoreMatches,
                                worldId = preferences?.getChatWorldId(),
                                campaignId = preferences?.getChatCampaignId(),
                                roleplayCharacterId = preferences?.getChatRoleplayCharacterId(),
                                userPersonaId = preferences?.getChatUserPersonaId(),
                                projectId = preferences?.getChatProjectId()
                            )
                        )
                }
            } catch (e: Exception) {
                org.teslasoft.assistant.preferences.memory.MemoryLog.log(
                    this, "Enforcer", "error", "Assembly failed, lore-books-only this turn: ${e.message}"
                )
                notifyMemoryDegradedOnce()
                null
            }
        }

        if (memoryAssembly != null) {
            msgs.add(
                ChatMessage(
                    role = ChatRole.System,
                    content = memoryAssembly
                )
            )
        } else if (allLoreMatches.isNotEmpty()) {
            // Safety budget: a message that trips many triggers at once must not
            // flood the context. Inject at most MAX_INJECTED_ENTRIES memories /
            // MAX_INJECTED_CHARS characters, in book order (core book first).
            val loreMatches = ArrayList<LoreBookMatch>()
            var loreChars = 0
            for (match in allLoreMatches) {
                if (loreMatches.size >= LoreBookStore.MAX_INJECTED_ENTRIES) break
                if (loreMatches.isNotEmpty() && loreChars + match.entry.content.length > LoreBookStore.MAX_INJECTED_CHARS) break
                loreChars += match.entry.content.length
                loreMatches.add(match)
            }

            val loreText = StringBuilder(getString(R.string.lorebook_injection_header))
            for (match in loreMatches) {
                loreText.append("\n- ").append(match.entry.content)
            }
            msgs.add(
                ChatMessage(
                    role = ChatRole.System,
                    content = loreText.toString()
                )
            )
        }

        msgs.addAll(chatMessages)

        val chatCompletionRequest = if (preferences?.getLogitBiasesConfigId() == null || preferences?.getLogitBiasesConfigId() == "null" || preferences?.getLogitBiasesConfigId() == "") {
            ChatCompletionRequest(
                model = ModelId(model),
                temperature = if (model.contains("gpt-5") || model.contains("o1") || model.contains("o3")) 1.0 else if (preferences!!.getTemperature().toDouble() == 0.7) null else preferences!!.getTemperature().toDouble(),
                topP = if (preferences!!.getTopP().toDouble() == 1.0) null else preferences!!.getTopP().toDouble(),
                frequencyPenalty = if (preferences!!.getFrequencyPenalty().toDouble() == 0.0) null else preferences!!.getFrequencyPenalty().toDouble(),
                presencePenalty = if (preferences!!.getPresencePenalty().toDouble() == 0.0) null else preferences!!.getPresencePenalty().toDouble(),
                seed = if (preferences!!.getSeed() != "") preferences!!.getSeed().toInt() else null,
                logitBias = if (model.contains("gpt-5") || model.contains("o1") || model.contains("o3")) null else logitBiasPreferences?.getLogitBiasesMap(),
                messages = msgs
            )
        } else {
            ChatCompletionRequest(
                model = ModelId(model),
                temperature = if (model.contains("gpt-5") || model.contains("o1") || model.contains("o3")) 1.0 else if (preferences!!.getTemperature().toDouble() == 0.7) null else preferences!!.getTemperature().toDouble(),
                topP = if (preferences!!.getTopP().toDouble() == 1.0) null else preferences!!.getTopP().toDouble(),
                frequencyPenalty = if (preferences!!.getFrequencyPenalty().toDouble() == 0.0) null else preferences!!.getFrequencyPenalty().toDouble(),
                presencePenalty = if (preferences!!.getPresencePenalty().toDouble() == 0.0) null else preferences!!.getPresencePenalty().toDouble(),
                seed = if (preferences!!.getSeed() != "") preferences!!.getSeed().toInt() else null,
                messages = msgs
            )
        }

        val completions: Flow<ChatCompletionChunk> =
            ai!!.chatCompletions(chatCompletionRequest)

        scroll(true)

        completions.flowOn(Dispatchers.IO).collect { v ->
            run {
                if (!currentCoroutineContext().isActive) throw CancellationException()
                else if (v.choices[0].delta != null && v.choices[0].delta?.content != null && v.choices[0].delta?.content.toString() != "null") {
                    response += v.choices[0].delta?.content
                    messages[messages.size - 1]["message"] = response
                    if (messages.size > 2) {
                        adapter?.notifyItemRangeChanged(messages.size - 3, messages.size - 1)
                    } else {
                        adapter?.notifyItemChanged(messages.size - 1)
                    }
                    scroll(false)
                    // Persist mid-stream so a killed process doesn't lose the
                    // partial reply — but NOT on every chunk: saveSettings()
                    // re-serializes and re-encrypts the WHOLE history on the
                    // main thread (flowOn only moves the upstream), so
                    // per-chunk saves made long conversations progressively
                    // slower with every turn. The completion save below still
                    // persists the full reply.
                    val nowUptime = android.os.SystemClock.uptimeMillis()
                    if (nowUptime - lastStreamSaveUptime >= STREAM_SAVE_INTERVAL_MS) {
                        lastStreamSaveUptime = nowUptime
                        saveSettings()
                    }
                }
            }
        }

        messages[messages.size - 1]["message"] = "$response\n"
        markLastAssistantDone()
        if (messages.size > 2) {
            adapter?.notifyItemRangeChanged(messages.size - 3, messages.size - 1)
        } else {
            adapter?.notifyItemChanged(messages.size - 1)
        }

        syncChatProjection()

        pronounce(shouldPronounce, response)

        saveSettings()
        calculateCost()

        btnMicro?.isEnabled = true
        btnSend?.isEnabled = true
        progress?.visibility = View.GONE
        messageInput?.requestFocus()

        // Put timestamp to chat to sort chats by last message
        ChatPreferences.getChatPreferences().putTimestampToChatById(this, chatId)

        if (autoNameAttempts < AUTO_NAME_MAX_ATTEMPTS && chatName.trim().contains("_autoname_")) {
            val placeholderName = ChatPreferences.getChatPreferences().getChatName(this, chatId)

            if (placeholderName.trim().contains("_autoname_")) {
                autoNameAttempts++
                btnMicro?.isEnabled = false
                btnSend?.isEnabled = false
                progress?.visibility = View.GONE
                messageInput?.requestFocus()

                val m = ArrayList(msgs.filter { it.role != ChatRole.System })

                m.add(
                    ChatMessage(
                        role = ChatRole.User,
                        content = "Create a short name for this chat according to the messages provided. Enter just short name and nothing else. Don't add word 'chat' or 'bot' to the name."
                    )
                )

                // Auto-naming used to be hardcoded to "gpt-4o". On any account or
                // custom API endpoint where that exact id isn't served, the request
                // threw and the (silent) catch left every chat stuck on its
                // "_autoname_" placeholder — so titles were never set. Use the chat's
                // own configured model instead, which the endpoint is known to serve.
                val titleModel = model.ifBlank { preferences?.getModel() ?: "gpt-4o" }
                val chatCompletionRequest2 = ChatCompletionRequest(
                    model = ModelId(titleModel),
                    maxTokens = 10,
                    messages = m
                )

                // The naming REQUEST and the local rename are separate failure
                // events and must not share a catch: a request error is
                // transient (a later turn retries), while a rename failure
                // means editChat aborted with the chat intact under its old
                // name. One catch-all used to swallow both — including a
                // half-applied settings copy.
                val newChatName: String? = try {
                    ai!!.chatCompletion(chatCompletionRequest2).choices.firstOrNull()?.message?.content?.trim()
                } catch (e: Exception) {
                    logVoiceEvent("auto-name request failed (attempt $autoNameAttempts of $AUTO_NAME_MAX_ATTEMPTS); a later turn retries")
                    null
                }

                if (!newChatName.isNullOrBlank() && !renameInProgress) {
                    // Storage work goes OFF the main thread: editChat does
                    // encrypted reads, verified encrypted writes, several
                    // synchronous commits and a SQLCipher re-point — none of
                    // which may run on the UI thread. The guard prevents a
                    // second turn from launching an overlapping rename while
                    // this one's IO is still in flight (the flag is set/checked
                    // only on the main dispatcher, so it holds across the
                    // withContext suspension).
                    renameInProgress = true
                    val renamed = try {
                        // editChat is atomic on the prefs side (ChatRenameTransaction):
                        // it moves the history, copies the WHOLE per-chat settings
                        // file (nothing enumerated by hand or re-derived from the
                        // endpoint profile) and flips the chat-list pointer only
                        // after the copies verify; the memory re-point is journalled
                        // and recoverable. false = nothing changed anywhere — keep
                        // the old id; a later turn may retry with a fresh title.
                        withContext(Dispatchers.IO) {
                            ChatPreferences.getChatPreferences().editChat(this@ChatActivity, newChatName, placeholderName)
                        }
                    } catch (e: Exception) {
                        logVoiceEventAlways("auto-name rename threw (${e.message}); keeping the placeholder name and old chat id")
                        false
                    } finally {
                        renameInProgress = false
                    }

                    // Back on the main dispatcher (regularGPTResponse resumes on
                    // Main after the IO hop). Never touch views/intent from IO,
                    // and never apply the result to a destroyed screen.
                    if (renamed && !isFinishing && !isDestroyed) {
                        chatId = Hash.hash(newChatName)

                        // Adopt the renamed chat in place. This used to relaunch
                        // ChatActivity (startActivity + finish) to pick up the new
                        // chat id — but onDestroy of the old instance stops TTS,
                        // kills the hands-free loop and releases the mic, which cut
                        // off the first reply's readback almost immediately and
                        // ended the voice conversation with no visible error.
                        // Everything keyed by the chat id is re-pointed here
                        // instead; the data itself was already moved by editChat.
                        this.chatName = newChatName
                        this.preferences = Preferences.getPreferences(this, chatId)
                        // If the OS later recreates this screen (rotation, process
                        // restore), onCreate re-reads the intent extras — they must
                        // name the renamed chat, not the deleted placeholder.
                        intent.putExtra("chatId", chatId)
                        intent.putExtra("name", this.chatName)
                        activityTitle?.text = newChatName
                        logVoiceEvent("chat auto-named without restarting the screen (voice loop preserved)")
                    } else if (!renamed) {
                        logVoiceEventAlways("auto-name rename did not apply; the chat keeps its placeholder name (attempt $autoNameAttempts of $AUTO_NAME_MAX_ATTEMPTS)")
                    }
                }
            }
        }
    }

    private fun pronounce(st: Boolean, message: String) {
        val handsFree = preferences?.getHandsFreeMode() == true
        // Hands-free is a spoken conversation: it must read the reply back (that
        // completion is also what re-arms the mic) regardless of the Always-speak
        // setting — turning Always-speak off must never break hands-free (owner
        // requirement). Silent mode still wins (the two are mutually exclusive in
        // settings, so this only matters defensively). Ordinary turns are
        // unchanged: st (a voice turn) or Always-speak drive the readback.
        val willReadAloud = (st && !silenceMode) || preferences!!.getNotSilence() ||
                (handsFree && !silenceMode)

        // Stamp this readback: if the user stops while we're still inside an
        // async hop below (ML Kit language detection), the stale stamp keeps
        // speak() from firing after the stop. See readbackSession.
        val session = readbackSession

        if (willReadAloud) {
            ttsErrorRetries = 0
            ttsRemainingText = ""
            finalTtsUtteranceId = null
            ttsUtteranceText.clear()
        }

        if (handsFree && willReadAloud) {
            // Record WHICH mechanism is protecting the process for this
            // readback. "Keep the app alive in the background" has now failed
            // several separate ways, and every diagnosis had to be
            // reconstructed by reading code because the log never said what
            // was actually held. With this line, a future cut-off readback
            // pairs with the ProcessExit record to prove which state slipped
            // through instead of inviting another guess.
            val protection = if (HandsFreeService.isRunning) "hands-free service"
                             else "readback keep-alive (hands-free loop idle)"
            logVoiceEvent("reply ready; reading it back (${preferences?.getTtsEngine()}); protected by: $protection")
            // This is a loop readback: its completion is what re-arms the mic.
            // (Manual speaker-button re-reads never set this flag, so they
            // never reopen the mic.)
            handsFreeReadbackExpected = true
            // The reply is about to be read back; keep the deep-red hands-free
            // background so the user can end the loop mid-readback (a tap becomes
            // a full cancel via btnMicro's touch listener). listening=false: the
            // recognizer is closed during readback, so the user's voice can't
            // barge in and stop the assistant.
            runOnUiThread { micHandsFreeActive(listening = false) }
            // Hard fallback: if speak() silently fails (TTS not initialized,
            // language detection stalls, etc.), this long-timeout watchdog
            // ensures the loop eventually re-arms. speak() arms its own
            // short-timeout watchdog when playback actually starts, which
            // bumps the token and invalidates this one.
            beginHandsFreeReadbackWatch(startTimeoutMs = HANDS_FREE_HARD_FALLBACK_MS)
        } else if (handsFree) {
            // Silence mode (or this turn isn't spoken): there's no readback to
            // wait on, so continue straight to the next listening turn instead
            // of stranding the mic waiting for a callback that never comes.
            handsFreeReadbackExpected = true
            onHandsFreeReadbackFinished()
        }

        if (willReadAloud) {
            // Keep the process alive through TTS playback so leaving the app or
            // turning the screen off doesn't cut the reply off mid-sentence.
            // Hands-free with a LIVE loop is covered by HandsFreeService (no
            // second keep-alive, no second bar) — but the hands-free PREFERENCE
            // alone proves nothing: the service only runs while the mic loop is
            // armed. With the pref on and the loop idle (stopped by an error or
            // Hang Up, or the user typing/listening from another window) a
            // readback used to run with NO foreground service at all — ~20 s
            // after the app left the foreground the cached-apps freezer froze
            // the process mid-readback and the TTS engine's progress callbacks
            // overflowed its async binder buffer, so the system killed the app
            // ([FREEZER BINDER ASYNC FULL], owner Event log, July 17 2026). Key
            // the skip on the service actually running, never on the pref.
            if (!handsFree || !HandsFreeService.isRunning) acquireReadbackKeepAlive()
            if (autoLangDetect) {
                try {
                    // ML Kit clients hold native resources: re-creating one per
                    // readback without closing the old one leaked a client per
                    // spoken reply across a long hands-free session.
                    try { languageIdentifier?.close() } catch (_: Exception) { /* already closed */ }
                    languageIdentifier = LanguageIdentification.getClient()
                    languageIdentifier?.identifyLanguage(message)
                        ?.addOnSuccessListener { languageCode ->
                            if (languageCode == "und") {
                                Log.i("MLKit", "Can't identify language.")
                            } else {
                                Log.i("MLKit", "Language: $languageCode")
                                tts!!.language = Locale.forLanguageTag(
                                    languageCode
                                )
                            }

                            speak(message, session)
                        }?.addOnFailureListener {
                            // Ignore auto language detection if an error is occurred
                            autoLangDetect = false
                            ttsPostInit()

                            speak(message, session)
                        }
                } catch (_: NullPointerException) {
                    autoLangDetect = false
                    ttsPostInit()

                    speak(message, session)
                }
            } else {
                speak(message, session)
            }
        }
    }

    private fun speak(message: String, session: Int = readbackSession) {
        // The user stopped this readback while it was still in flight (see
        // readbackSession) — starting the audio now would speak over a stop.
        if (session != readbackSession) return
        if (preferences!!.getTtsEngine() == "google") {
            val engine = tts
            if (engine == null || !isTTSInitialized) {
                pendingSpeak = message
                pendingSpeakSession = session
                if (engine == null) {
                    Handler(Looper.getMainLooper()).post { initTTS() }
                }
                return
            }
            val runSpeak = runSpeak@{
                // Re-check on the main looper too: a stop can land between the
                // entry check above and this posted execution.
                if (session != readbackSession) return@runSpeak
                val maxLength = try {
                    TextToSpeech.getMaxSpeechInputLength()
                } catch (_: Throwable) {
                    4000
                }
                val chunks = splitTtsText(message, maxLength.coerceAtLeast(1))
                val chunk = chunks.first()
                ttsRemainingText = chunks.drop(1).joinToString("")
                ttsChunkSession = session
                ttsUtteranceCounter++
                val utteranceId = "speakgpt-$ttsUtteranceCounter"
                lastTtsUtteranceStarted = false
                // Fresh utterance, fresh progress marker (utterance ids are
                // monotonic, so a stale marker can never match a new failure).
                ttsRangeUtteranceId = utteranceId
                ttsSpokenRangeStart = 0
                ttsUtteranceText[utteranceId] = chunk
                finalTtsUtteranceId = if (ttsRemainingText.isEmpty()) utteranceId else null
                val result = engine.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                if (result == TextToSpeech.ERROR) {
                    Log.w("TTS", "speak() returned ERROR")
                    handleTtsReadbackError(utteranceId, TextToSpeech.ERROR)
                } else {
                    beginHandsFreeReadbackWatch()
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                runSpeak()
            } else {
                Handler(Looper.getMainLooper()).post { runSpeak() }
            }
        } else {
            if (openAIKey == null) {
                adapter?.clearSpeakingPosition()
                openAIMissing("tts", message)
            } else {
                speakScope = CoroutineScope(Dispatchers.Main)

                speakScope?.launch {
                    progress?.setOnClickListener {
                        cancel()
                        restoreUIState()
                    }

                    try {
                        val rawAudio = openAIAI!!.speech(
                            request = SpeechRequest(
                                model = ModelId("tts-1"),
                                input = message,
                                voice = com.aallam.openai.api.audio.Voice(preferences!!.getOpenAIVoice()),
                            )
                        )

                        runOnUiThread {
                            try {
                                // create temp file that will hold byte array
                                val tempMp3 = File.createTempFile("audio", "mp3", cacheDir)
                                tempMp3.deleteOnExit()
                                val fos = FileOutputStream(tempMp3)
                                fos.write(rawAudio)
                                fos.close()

                                // resetting media player instance to evade problems
                                mediaPlayer?.reset()

                                val fis = FileInputStream(tempMp3)
                                mediaPlayer?.setDataSource(fis.fd)
                                mediaPlayer?.prepare()
                                mediaPlayer?.setOnCompletionListener {
                                    adapter?.clearSpeakingPosition()
                                    // Mirror the device-TTS onDone path so a
                                    // cloud voice also keeps hands-free looping.
                                    onHandsFreeReadbackFinished()
                                }
                                mediaPlayer?.setOnErrorListener { _, _, _ ->
                                    adapter?.clearSpeakingPosition()
                                    // A playback error must not strand the loop
                                    // either — re-arm as if readback finished.
                                    onHandsFreeReadbackFinished()
                                    false
                                }
                                mediaPlayer?.start()
                                beginHandsFreeReadbackWatch()
                            } catch (ex: IOException) {
                                adapter?.clearSpeakingPosition()
                                MaterialAlertDialogBuilder(this@ChatActivity, R.style.App_MaterialAlertDialog)
                                    .setTitle(R.string.label_audio_error)
                                    .setPositiveButton(R.string.btn_close) { _, _ -> }
                                    .setMessage(ex.stackTraceToString())
                                    .show()
                            }
                        }
                    } catch (_: CancellationException) {
                        restoreUIState()
                    } catch (e: Exception) {
                        // A failed speech request (network drop, HTTP error)
                        // used to escape this coroutine uncaught and kill the
                        // whole process mid-readback — and with it any
                        // hands-free loop. Fail just the readback instead:
                        // log it and re-arm exactly like the playback-error
                        // listener above.
                        logVoiceEventAlways("cloud voice request failed: ${e.message}")
                        runOnUiThread {
                            adapter?.clearSpeakingPosition()
                            onHandsFreeReadbackFinished()
                        }
                        releaseReadbackKeepAlive()
                        restoreUIState()
                    }
                }
            }
        }
    }

    /**
     * Android device TTS rejects any single speak() input over its advertised
     * maximum. Split only oversized replies, preferring a nearby natural break,
     * while preserving every character of the original text.
     */
    private fun splitTtsText(text: String, maxLength: Int): List<String> {
        if (text.length <= maxLength) return listOf(text)

        val chunks = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + maxLength, text.length)
            if (end < text.length) {
                val earliestNaturalBreak = start + (maxLength / 2)
                for (index in end - 1 downTo earliestNaturalBreak) {
                    val char = text[index]
                    if (char.isWhitespace() || char == '.' || char == '!' || char == '?' || char == ';') {
                        end = index + 1
                        break
                    }
                }
                if (end < text.length &&
                    Character.isHighSurrogate(text[end - 1]) &&
                    Character.isLowSurrogate(text[end])
                ) {
                    end--
                }
            }
            chunks.add(text.substring(start, end))
            start = end
        }
        return chunks
    }

    private fun writeImageToCache(bytes: ByteArray, imageType: String = "png") {
        try {
            contentResolver.openFileDescriptor(Uri.fromFile(File(getExternalFilesDir("images")?.absolutePath + "/" + Hash.hash(java.util.Base64.getEncoder().encodeToString(bytes)) + "." + imageType)), "w")?.use { fileDescriptor ->
                FileOutputStream(fileDescriptor.fileDescriptor).use {
                    it.write(
                        bytes
                    )
                }
            }
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun generateImageAsync(
        client: OpenAIClient,
        params: ImageGenerateParams,
        onSuccess: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) : Job {
        return CoroutineScope(Dispatchers.IO).launch {
            progress?.setOnClickListener {
                cancel()
                restoreUIState()
            }

            try {
                var imageId: String
                val response = client.images().generate(params)
                val data: Optional<List<Image>> = response.data()
                val images = data.orElse(emptyList())

                val b64 = images.firstOrNull()?.b64Json()?.get()
                    ?: throw NullPointerException("Base64 string is null or empty, stopping...")

                val byteArray = Base64.decode(b64, Base64.DEFAULT)
                writeImageToCache(byteArray)
                imageId = Hash.hash(b64)

                withContext(Dispatchers.Main) {
                    onSuccess(imageId)
                }
            } catch (_: CancellationException) {
                withContext(Dispatchers.Main) {
                    onSuccess("cancelled")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private suspend fun generateImageR(p: String) {
        runOnUiThread {
            btnMicro?.isEnabled = false
            btnSend?.isEnabled = false
            progress?.visibility = View.VISIBLE
        }

        chat?.setOnTouchListener(null)
        disableAutoScroll = false
        // chat?.transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
        try {
            if (preferences!!.getImageModel().contains("gpt-image-")) {
                val client: OpenAIClient = OpenAIOkHttpClient
                    .builder()
                    .baseUrl(apiEndpointPreferences!!.getApiEndpoint(this, preferences!!.getApiEndpointId()).host)
                    .apiKey(apiEndpointPreferences!!.getApiEndpoint(this, preferences!!.getApiEndpointId()).apiKey)
                    .build()

                val params = ImageGenerateParams.builder()
                    .prompt(p)
                    .model(preferences!!.getImageModel())
                    .n(1L)
                    .quality(ImageGenerateParams.Quality.AUTO) // Settings param "quality" does not exists yet.
                    .size(ImageGenerateParams.Size._1024X1024) // Settings param "resolution" is ignored as this model supports only 1024x1024 resolution
                    .build()

                generateGptImageJob = generateImageAsync(
                    client,
                    params,
                    onSuccess = { file ->
                        if (file == "cancelled") {
                            runOnUiThread {
                                restoreUIState()
                            }
                            return@generateImageAsync
                        }

                        runOnUiThread {
                            putMessage("~file:$file", true)

                            chat?.setOnTouchListener { _, event ->
                                run {
                                    if (event.action == MotionEvent.ACTION_SCROLL || event.action == MotionEvent.ACTION_UP) {
                                        // chat?.transcriptMode = ListView.TRANSCRIPT_MODE_DISABLED
                                        disableAutoScroll = true
                                    }
                                    return@setOnTouchListener false
                                }
                            }

                            scroll(true)
                            scroll(false)

                            saveSettings()

                            btnMicro?.isEnabled = true
                            btnSend?.isEnabled = true
                            progress?.visibility = View.GONE

                            messageInput?.requestFocus()

                            // Put timestamp to chat to sort chats by last message
                            ChatPreferences.getChatPreferences().putTimestampToChatById(this@ChatActivity, chatId)
                            initSettings()
                        }
                    },
                    onError = { error ->
                        runOnUiThread {
                            if (preferences?.showChatErrors() == true) {
                                putMessage(
                                    when (error) {
                                        else -> error.stackTraceToString()
                                    }, true
                                )
                            }
                            btnMicro?.isEnabled = true
                            btnSend?.isEnabled = true
                            progress?.visibility = View.GONE
                            messageInput?.requestFocus()
                        }
                    }
                )
            } else {
                val images = openAIAI?.imageURL(
                    creation = ImageCreation(
                        prompt = p,
                        model = ModelId(preferences!!.getImageModel()),
                        n = 1,
                        size = ImageSize(resolution)
                    )
                )

                val imageUrl = images?.get(0)?.url!!

                val url = URL(imageUrl)

                val `is` = withContext(Dispatchers.IO) {
                    url.openStream()
                }
                var file = ""
                val th = Thread {
                    val bytes: ByteArray = org.apache.commons.io.IOUtils.toByteArray(`is`)

                    writeImageToCache(bytes)

                    val encoded = java.util.Base64.getEncoder().encodeToString(bytes)

                    file = Hash.hash(encoded)
                }

                th.start()
                withContext(Dispatchers.IO) {
                    th.join()
                    runOnUiThread {
                        putMessage("~file:$file", true)

                        chat?.setOnTouchListener { _, event ->
                            run {
                                if (event.action == MotionEvent.ACTION_SCROLL || event.action == MotionEvent.ACTION_UP) {
                                    // chat?.transcriptMode = ListView.TRANSCRIPT_MODE_DISABLED
                                    disableAutoScroll = true
                                }
                                return@setOnTouchListener false
                            }
                        }

                        scroll(true)
                        scroll(false)

                        saveSettings()

                        btnMicro?.isEnabled = true
                        btnSend?.isEnabled = true
                        progress?.visibility = View.GONE

                        messageInput?.requestFocus()

                        // Put timestamp to chat to sort chats by last message
                        ChatPreferences.getChatPreferences().putTimestampToChatById(this@ChatActivity, chatId)
                        initSettings()
                    }
                }
            }
        } catch (_: CancellationException) {
            runOnUiThread {
                restoreUIState()
            }
        } catch (e: Exception) {
            playErrorSignal()
            stopHandsFreeOnError()
            // Same funnel as the text path: classify, always log, show the coded
            // message only when the user has chat errors enabled. See ERROR_CODES.md.
            val genError = GenerationErrorClassifier.classify(e)
            logGenerationError(genError, e, "image-generation")
            if (preferences?.showChatErrors() == true) {
                putMessage(genError.chatMessage(this), true)
            }

            saveSettings()

            btnMicro?.isEnabled = true
            btnSend?.isEnabled = true
            progress?.visibility = View.GONE

            messageInput?.requestFocus()
        } finally {
            if (!preferences!!.getImageModel().contains("gpt-image-")) {
                runOnUiThread {
                    restoreUIState()
                }
            }
        }
    }

    private fun findLastUserMessage(): HashMap<String, Any> {
        var lastUserMessage = hashMapOf<String, Any>()

        for (i in messages.size - 1 downTo 0) {
            if (messages[i]["isBot"] == false) {
                lastUserMessage = messages[i]
                break
            }
        }

        return lastUserMessage
    }

    private fun removeLastAssistantMessageIfAvailable() {
        if (messages.isNotEmpty() && messages.size - 1 > 0 && messages[messages.size - 1]["isBot"] == true) {
            // messages.removeAt(messages.size - 1)
            adapter?.onDelete(messages.size - 1)
        }

        if (chatMessages.isNotEmpty() && chatMessages.size - 1 > 0 && chatMessages[chatMessages.size - 1].role == Role.Assistant) {
            chatMessages.removeAt(chatMessages.size - 1)
        }
    }

    override fun onSpeakClick(message: String, position: Int) {
        // Tapping the speaker on the message that is CURRENTLY being read means
        // stop, not restart. It used to re-read from the top, so the most
        // natural "be quiet" tap made the readback start over — one face of
        // "I can't stop it from reading back to me".
        if (position != -1 && adapter?.getSpeakingPosition() == position) {
            stopReadback()
            return
        }
        // Manual re-read of a single message via the existing TTS path. This is
        // user-initiated playback, not a hands-free loop readback: it must never
        // re-arm the mic afterwards, so drop the loop's completion gate and any
        // in-flight watchdog before starting.
        handsFreeReadbackExpected = false
        handsFreeReadbackToken++
        // If the loop is currently listening, the re-read would be transcribed
        // as the user's speech — end the loop; the mic button restarts it
        // explicitly when the user wants the conversation back.
        if (preferences?.getHandsFreeMode() == true && isRecording) {
            stopHandsFreeLoop("speak button pressed on a message while listening")
            if (preferences?.getEffectiveAudioModel() == "whisper-local") {
                LocalWhisperEngine.get().cancel()
            }
        }
        // Stop any current playback so taps don't pile up.
        try { tts?.stop() } catch (_: Exception) { /* ignore */ }
        try { if (mediaPlayer?.isPlaying == true) { mediaPlayer?.stop(); mediaPlayer?.reset() } } catch (_: Exception) { /* ignore */ }
        // Tint the tapped speaker button until playback finishes, so the press
        // is visibly registered even while the audio is still being prepared.
        adapter?.setSpeakingPosition(position)
        // Same backgrounding guard as the auto read-after-reply: a manual re-read
        // is user-initiated playback that should survive leaving the app / screen
        // off. Hands-free is already covered by HandsFreeService, so skip there to
        // avoid a second keep-alive bar.
        if (preferences?.getHandsFreeMode() != true) acquireReadbackKeepAlive()
        // Fresh manual readback → fresh TTS failure budget.
        ttsErrorRetries = 0
        ttsRemainingText = ""
        finalTtsUtteranceId = null
        ttsUtteranceText.clear()
        speak(message)
    }

    override fun onRetryClick() {
        removeLastAssistantMessageIfAvailable()
        saveSettings()

        val message = findLastUserMessage()

        if (message["image"] != null) {
            btnMicro?.isEnabled = false
            btnSend?.isEnabled = false
            progress?.visibility = View.VISIBLE

            val uri = Uri.fromFile(File(getExternalFilesDir("images")?.absolutePath + "/" + message["image"] + "." + message["imageType"]))
            imageIsSelected = true
            bitmap = readFile(uri)

            if (bitmap != null) {
                imageIsSelected = true

                val mimeType = contentResolver.getType(uri)
                val format = when {
                    mimeType.equals("image/png", ignoreCase = true) -> {
                        selectedImageType = "png"
                        Bitmap.CompressFormat.PNG
                    }
                    else -> {
                        selectedImageType = "jpg"
                        Bitmap.CompressFormat.JPEG
                    }
                }

                // Step 3: Convert the Bitmap to a Base64-encoded string
                val outputStream = ByteArrayOutputStream()
                bitmap!!.compress(format, 100, outputStream) // Note: Adjust the quality as necessary
                val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)

                // Step 4: Generate the data URL
                val imageType = when(format) {
                    Bitmap.CompressFormat.JPEG -> "jpeg"
                    Bitmap.CompressFormat.PNG -> "png"
                    // Add more mappings as necessary
                    else -> ""
                }

                baseImageString = "data:image/$imageType;base64,$base64Image"
            }
        }

        parseMessage(message["message"].toString(), false)
    }

    private fun syncChatProjection() {
        if (chatMessages == null) chatMessages = arrayListOf()

        if (chatMessages.isNotEmpty()) chatMessages.clear()

        if (chatMessages == null) chatMessages = arrayListOf()

        for (message: HashMap<String, Any> in messages) {
            val content = message["message"].toString()
            // Skip blank-content turns. An empty user/assistant message (e.g. an
            // error placeholder, or a turn the user blanked out while editing)
            // makes OpenAI-compatible servers reject the whole request with HTTP
            // 400. With streaming on (Accept: text/event-stream) the Ktor client
            // then can't parse the non-SSE error body and throws the opaque
            // NoTransformationFoundException instead of a real error.
            if (!content.contains("data:image") && content.isNotBlank()) {
                if (message["isBot"] == true) {
                    chatMessages.add(
                        ChatMessage(
                            role = ChatRole.Assistant,
                            // An unfinished reply carries an internal, model-only
                            // note so the model treats it as truncated (not shown
                            // to the user; see modelFacingContent).
                            content = modelFacingContent(message)
                        )
                    )
                } else {
                    chatMessages.add(
                        ChatMessage(
                            role = ChatRole.User,
                            content = content
                        )
                    )
                }
            }
        }

        updateMessagesSelectionProjection()
        calculateCost()
    }

    override fun onMessageEdited() {
        syncChatProjection()
    }

    override fun onMessageDeleted() {
        syncChatProjection()
    }

    @SuppressLint("SetTextI18n")
    override fun onBulkSelectionChanged(position: Int, selected: Boolean) {
        messagesSelectionProjection[position]["selected"] = selected
        selectedCount?.text = messagesSelectionProjection.count { it["selected"] == true }.toString()
    }

    @Suppress("deprecation")
    override fun onChangeBulkActionMode(mode: Boolean) {
        bulkSelectionMode = mode

        if (mode) {
            if (Build.VERSION.SDK_INT < 30) {
                window.statusBarColor = ResourcesCompat.getColor(resources, R.color.accent_250, theme)
            }
            bulkContainer?.visibility = View.VISIBLE
        } else {
            reloadAmoled()
            bulkContainer?.visibility = View.GONE
        }
    }

    private fun openAIMissing(feature: String, prompt: String) {
        restoreUIState()

        val message = when(feature) {
            "dalle" -> "Image generation"
            "tts" -> "OpenAI text-to-speech"
            "whisper" -> "Whisper speech recognition"
            else -> "this OpenAI"
        }

        MaterialAlertDialogBuilder(
            this,
            R.style.App_MaterialAlertDialog
        )
            .setTitle("OpenAI API endpoint missing")
            .setMessage("To use $message, you need to add OpenAI API endpoint first. Would you like to add OpenAI endpoint now?")
            .setPositiveButton(R.string.yes) { _, _ -> requestAddApiEndpoint(feature, prompt) }
            .setNegativeButton(R.string.no) { _, _ -> onCancelOpenAIAction(feature) }
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (imageIsSelected) {
            outState.putString("image", baseImageString)
            outState.putString("imageType", selectedImageType)
        }
        super.onSaveInstanceState(outState)
    }

    private fun base64ToBitmap(base64Str: String): Bitmap? {
        return try {
            // Decode Base64 string to bytes
            val decodedBytes = Base64.decode(base64Str, Base64.DEFAULT)
            // Decode byte array to Bitmap
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (_: IllegalArgumentException) {
            // Handle the case where the Base64 string was not correctly formatted
            null
        }
    }

    private fun onRestoredState(savedInstanceState: Bundle?) {
        val image = savedInstanceState?.getString("image")

        if (image != null) {
            baseImageString = image
            imageIsSelected = true
            selectedImageType = savedInstanceState.getString("imageType")

            bitmap = base64ToBitmap(baseImageString!!.split(",")[1])

            if (bitmap != null) {
                attachedImage?.visibility = View.VISIBLE

                val resizedBitmap = resizeBitmapToMaxHeight(bitmap!!, 100)
                selectedImage?.setImageBitmap(roundCorners(resizedBitmap))
            }
        }
    }

    private fun requestAddApiEndpoint(feature: String, prompt: String) {
        val apiEndpointDialog: EditApiEndpointDialogFragment = EditApiEndpointDialogFragment.newInstance(
            "OpenAI",
            "https://api.openai.com/v1/",
            "",
            ApiEndpointObject.DEFAULT_CHAT_ENDPOINT,
            ApiEndpointObject.AUTH_BEARER,
            ApiEndpointObject.DEFAULT_MODEL,
            ApiEndpointObject.DEFAULT_TEMPERATURE,
            ApiEndpointObject.DEFAULT_TOP_P,
            ApiEndpointObject.DEFAULT_FREQUENCY_PENALTY,
            ApiEndpointObject.DEFAULT_PRESENCE_PENALTY,
            ApiEndpointObject.DEFAULT_MAX_TOKENS,
            "",
            "",
            -1
        )
        apiEndpointDialog.setListener(object : EditApiEndpointDialogFragment.StateChangesListener {
            override fun onAdd(apiEndpoint: ApiEndpointObject) {
                apiEndpointPreferences?.setApiEndpoint(this@ChatActivity, apiEndpoint)
                openAIKey = apiEndpoint.apiKey

                val configOpenAI = OpenAIConfig(
                    token = openAIKey.toString(),
                    logging = LoggingConfig(LogLevel.None, Logger.Simple),
                    timeout = Timeout(socket = 30.seconds),
                    organization = null,
                    headers = emptyMap(),
                    host = OpenAIHost(apiEndpoint.host),
                    proxy = null,
                    retry = RetryStrategy()
                )
                openAIAI = OpenAI(configOpenAI)
                onOpenAIAction(feature, prompt)
            }

            override fun onError(message: String, position: Int) {
                apiEndpointDialog.show(supportFragmentManager, "EditApiEndpointDialogFragment")
            }

            override fun onCancel(position: Int) {
                onCancelOpenAIAction(feature)
            }
        })
        apiEndpointDialog.show(supportFragmentManager, "EditApiEndpointDialogFragment")
    }

    private fun onCancelOpenAIAction(feature: String) {
        if (feature == "dalle") {
            putMessage("DALL-E image generation is disabled. Please add OpenAI API endpoint to enable this feature.", true)
            saveSettings()
        }
    }

    private fun onOpenAIAction(feature: String, prompt: String) {
        when (feature) {
            "dalle" -> {
                btnMicro?.isEnabled = false
                btnSend?.isEnabled = false
                progress?.visibility = View.VISIBLE

                CoroutineScope(Dispatchers.Main).launch {
                    progress?.setOnClickListener {
                        cancel()
                        restoreUIState()
                    }

                    generateImageR(prompt)
                }
            }
            "tts" -> speak(prompt)
            "whisper" -> handleWhisperSpeechRecognition()
        }
    }

    private fun updateMessagesSelectionProjection() {
        bulkSelectionMode = false
        adapter?.setBulkActionMode(false)

        messagesSelectionProjection.clear()

        for (m in messages) {
            messagesSelectionProjection.add(
                java.util.HashMap(
                    mapOf(
                        "message" to m["message"],
                        "isBot" to m["isBot"],
                        "image" to m["image"],
                        "imageType" to m["imageType"],
                        "selected" to false
                    )
                )
            )
        }
    }

    @SuppressLint("SetTextI18n", "NotifyDataSetChanged")
    private fun selectAll() {
        adapter?.selectAll()

        for (i in messagesSelectionProjection.indices) {
            messagesSelectionProjection[i]["selected"] = true
        }

        selectedCount?.text = messagesSelectionProjection.size.toString()
        bulkSelectionMode = true
        bulkContainer?.visibility = View.VISIBLE
        adapter?.notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun deselectAll() {
        adapter?.unselectAll()

        for (i in messagesSelectionProjection.indices) {
            messagesSelectionProjection[i]["selected"] = false
        }

        selectedCount?.text = "0"
        bulkSelectionMode = false
        bulkContainer?.visibility = View.GONE
        adapter?.notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun deleteSelectedMessages() {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle("Delete selected messages")
            .setMessage("Are you sure you want to delete selected messages?")
            .setPositiveButton("Delete") { _, _ ->
                var pos = 0
                var p = 0
                while (pos < messagesSelectionProjection.size) {
                    if (messagesSelectionProjection[pos]["selected"].toString() == "true") {
                        messages.removeAt(pos - p)
                        p++
                    }

                    pos++
                }

                syncChatProjection()
                saveSettings()
                adapter?.notifyDataSetChanged()
                updateMessagesSelectionProjection()
                deselectAll()
                calculateCost()
            }
            .setNegativeButton("Cancel") { _, _ -> }
            .show()
    }

    private fun copySelectedMessages() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Copied messages", conversationToString())
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Messages copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun shareSelectedMessages() {
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, conversationToString())
        startActivity(Intent.createChooser(intent, "Share messages"))
    }

    private fun conversationToString() : String {
        val stringBuilder = StringBuilder()

        for (m in messagesSelectionProjection) {
            if (m["selected"].toString() == "true") {
                if (m["isBot"] == true) {
                    stringBuilder.append("[Bot] >\n")
                } else {
                    stringBuilder.append("[User] >\n")
                }
                stringBuilder.append(m["message"])
                stringBuilder.append("\n\n")
            }
        }

        return stringBuilder.toString()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Async startup can attach the window before activity_chat is set.
        // initializeChatUi applies the insets after inflating the layout.
        if (chatStartupComplete) adjustPaddings()
    }

    private fun adjustPaddings() {
        WindowInsetsUtil.adjustPaddings(this, R.id.action_bar, EnumSet.of(WindowInsetsUtil.Companion.Flags.STATUS_BAR))
        WindowInsetsUtil.adjustPaddings(this, R.id.bulk_container, EnumSet.of(WindowInsetsUtil.Companion.Flags.STATUS_BAR))
        WindowInsetsUtil.adjustPaddings(this, R.id.keyboard_frame, EnumSet.of(WindowInsetsUtil.Companion.Flags.NAVIGATION_BAR))
        WindowInsetsUtil.adjustPaddings(this, R.id.messages, EnumSet.of(WindowInsetsUtil.Companion.Flags.NAVIGATION_BAR))

        val messages = findViewById<RecyclerView>(R.id.messages) ?: return
        val layoutParams = messages.layoutParams as ViewGroup.MarginLayoutParams

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // rootWindowInsets is nullable — Pixel 8 / API 36 returned null here
            // and crashed the app. Fall back to a zero status-bar inset rather
            // than tearing down the activity; the layout settles correctly the
            // next time insets dispatch.
            val statusTop = window.decorView.rootWindowInsets
                ?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
            layoutParams.topMargin = dpToPx(64) + statusTop
        } else {
            val view = findViewById<View>(android.R.id.content) ?: return
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
                layoutParams.topMargin = dpToPx(64) + insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                insets
            }
        }

        messages.layoutParams = layoutParams
    }

    private fun finishActivity() {
        val root: View = findViewById(R.id.root)
        root.animate().alpha(0f).setDuration(200)
        supportFinishAfterTransition()
    }
}
