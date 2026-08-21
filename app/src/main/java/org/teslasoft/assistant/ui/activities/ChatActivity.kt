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
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
import androidx.lifecycle.lifecycleScope
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
import com.aallam.openai.api.chat.StreamOptions
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ContentPart
import com.aallam.openai.api.chat.FunctionCall
import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.chat.TextPart
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.chat.ToolChoice
import com.aallam.openai.api.chat.ToolId
import com.aallam.openai.api.chat.chatCompletionRequest
import com.aallam.openai.api.completion.CompletionRequest
import com.aallam.openai.api.completion.TextCompletion
import com.aallam.openai.api.core.Role
import com.aallam.openai.api.file.FileSource
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.logging.Logger
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.RetryStrategy
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import eightbitlab.com.blurview.BlurView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
import org.teslasoft.assistant.preferences.ResponseLifecycle
import org.teslasoft.assistant.preferences.ResponseLifecycleRecorder
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.providers.NewChatProviderRestore
import org.teslasoft.assistant.providers.ProviderRoutingBlockedException
import org.teslasoft.assistant.providers.ProviderRoutingDiagnostics
import org.teslasoft.assistant.providers.ProviderRoutingResolver
import org.teslasoft.assistant.providers.ProviderRoutingSerializer
import org.teslasoft.assistant.providers.ReportedProviderParser
import org.teslasoft.assistant.providers.RoutingBlock
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.includes.ChatInclude
import org.teslasoft.assistant.preferences.includes.DocumentImporter
import org.teslasoft.assistant.preferences.includes.ImageCapability
import org.teslasoft.assistant.preferences.includes.ImageCapabilityStore
import org.teslasoft.assistant.preferences.includes.ImageImporter
import org.teslasoft.assistant.preferences.includes.IncludeAuxiliaryRequestPolicy
import org.teslasoft.assistant.preferences.includes.IncludeForm
import org.teslasoft.assistant.preferences.includes.IncludeKind
import org.teslasoft.assistant.preferences.includes.IncludeMessageProjection
import org.teslasoft.assistant.preferences.includes.ProjectedUserMessage
import org.teslasoft.assistant.preferences.includes.IncludeNotice
import org.teslasoft.assistant.preferences.includes.IncludeTextPolicy
import org.teslasoft.assistant.preferences.includes.PersistentIncludeContext
import org.teslasoft.assistant.ui.util.EditChatTitleDialog
import org.teslasoft.assistant.ui.util.IncludeEditDialog
import org.teslasoft.assistant.ui.util.IncludeStripController
import org.teslasoft.assistant.ui.util.IncludesPopupController
import org.teslasoft.assistant.util.AvatarRefreshCoordinator
import org.teslasoft.assistant.util.ProfileImageResolver
import org.teslasoft.assistant.preferences.LogitBiasPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.TranscriptRecorder
import org.teslasoft.assistant.preferences.lorebook.LoreBookBudget
import org.teslasoft.assistant.preferences.lorebook.LoreBookInjectionLog
import org.teslasoft.assistant.preferences.lorebook.LoreBookMatch
import org.teslasoft.assistant.preferences.lorebook.LoreBookStore
import org.teslasoft.assistant.preferences.lorebook.LoreDedup
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.stt.LocalWhisperEngine
import org.teslasoft.assistant.stt.SpeechTextFormatter
import org.teslasoft.assistant.stt.LocalWhisperModels
import org.teslasoft.assistant.stt.LocalWhisperStorage
import org.teslasoft.assistant.service.GenerationForegroundService
import org.teslasoft.assistant.service.HandsFreeService
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.adapters.chat.ChatAdapter
import org.teslasoft.assistant.ui.chat.ChatComposerLayout
import org.teslasoft.assistant.ui.chat.ChatImeInsetLayout
import org.teslasoft.assistant.ui.chat.ChatNameStyle
import org.teslasoft.assistant.ui.fragments.dialogs.EditApiEndpointDialogFragment
import org.teslasoft.assistant.ui.fragments.dialogs.QuickSettingsBottomSheetDialogFragment
import org.teslasoft.assistant.ui.onboarding.WelcomeActivity
import org.teslasoft.assistant.ui.permission.CameraPermissionActivity
import org.teslasoft.assistant.ui.permission.MicrophonePermissionActivity
import org.teslasoft.assistant.util.Hash
import org.teslasoft.assistant.util.GenErrorResult
import org.teslasoft.assistant.util.FrozenChatPayload
import org.teslasoft.assistant.util.GenErrorCode
import org.teslasoft.assistant.util.GenerationErrorClassifier
import org.teslasoft.assistant.imagegen.CreateImageTool
import org.teslasoft.assistant.imagegen.ImageConfirmationSpeech
import org.teslasoft.assistant.imagegen.ImageErrorCause
import org.teslasoft.assistant.imagegen.ImageErrorSanitizer
import org.teslasoft.assistant.imagegen.ImageGenerationEventLog
import org.teslasoft.assistant.imagegen.ImageFailureAction
import org.teslasoft.assistant.imagegen.GeneratedImageFiles
import org.teslasoft.assistant.imagegen.GeneratedImageMetadata
import org.teslasoft.assistant.imagegen.ImageGenerationJobRegistry
import org.teslasoft.assistant.imagegen.ImageGenerationRequest
import org.teslasoft.assistant.imagegen.imageFailureMessageRes
import org.teslasoft.assistant.imagegen.imageFailureProviderDetailBlock
import org.teslasoft.assistant.imagegen.ImageProviderAdapters
import org.teslasoft.assistant.imagegen.ImagineCommand
import org.teslasoft.assistant.imagegen.StreamedToolCallAssembler
import org.teslasoft.assistant.imagegen.ToolCapability
import org.teslasoft.assistant.imagegen.ToolCapabilityScope
import org.teslasoft.assistant.imagegen.ToolCapabilityStore
import org.teslasoft.assistant.imagegen.ToolSupportClassifier
import org.teslasoft.assistant.imagegen.failureActionFor
import org.teslasoft.assistant.util.LocaleParser
import org.teslasoft.assistant.util.ModelContextCapacity
import org.teslasoft.assistant.util.ModelContextDecision
import org.teslasoft.assistant.util.RequestCapacity
import org.teslasoft.assistant.util.RequestMessageSnapshot
import org.teslasoft.assistant.util.RequestHeapState
import org.teslasoft.assistant.util.WindowInsetsUtil
import org.teslasoft.assistant.util.chatMessage
import org.teslasoft.assistant.util.providerDetailBlock
import org.teslasoft.assistant.util.providerLimitMessage
import org.teslasoft.assistant.util.reachedServer
import org.teslasoft.assistant.util.ProviderErrorInfo
import io.ktor.client.plugins.observer.ResponseObserver
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.util.AttributeKey
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
import java.text.NumberFormat
import java.util.Optional
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds
import androidx.core.content.edit
import kotlinx.coroutines.flow.flowOn
import okio.FileSystem
import okio.Path.Companion.toPath

class ChatActivity : FragmentActivity(), ChatAdapter.OnUpdateListener,
    ImageGenerationJobRegistry.Listener {

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

        /**
         * Key under which a user message stores the attachments it carried,
         * as the JSON produced by `ChatInclude.listToJson`. Absent = none, so
         * every message written by an older build reads correctly.
         *
         * It lives in the SAME map as the message text, which means
         * saveSettings() persists text and attachments together — there is no
         * window where one is written and the other is not.
         */
        const val INCLUDES_KEY = "includes"

        /** How much of a document the bookmark-writing request sees. Enough
         *  to say what the file IS, without paying to send it all again. */
        private const val ARTIFACT_EXCERPT_CHARS = 2000

        /** Pins a split raw response to the lifecycle recorder for that exact request. */
        private val responseLifecycleRecorderAttribute =
            AttributeKey<ResponseLifecycleRecorder>("ResponseLifecycleRecorder")

        /** Pins the current turn's reasoning accumulator to a request whose
         *  resolved settings want provider reasoning displayed (§7.2). Its
         *  presence is what tells the response observer to split this stream for
         *  reasoning even when lifecycle logging is off. */
        private val reasoningObservationAttribute =
            AttributeKey<org.teslasoft.assistant.reasoning.ReasoningStreamAccumulator>("ReasoningObservation")

    }

    // Init UI
    private var messageInput: EditText? = null
    private var btnSend: ImageButton? = null
    private var btnMicro: ImageButton? = null
    private var btnSettings: ImageButton? = null
    private var progress: CircularProgressIndicator? = null
    private var chat: RecyclerView? = null
    private var activityTitle: TextView? = null
    private var btnQuickSettings: ImageButton? = null
    private var fileContents: ByteArray? = null
    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var btnDebugLog: ImageButton? = null

    // Conversation summarizer (conversation-summary-plan.md decisions 11 +
    // 16): data_alert first in the icon row (with the 1–5 count badge),
    // then the subject summary icon. The controller runs the background
    // fold-ins; it is cancelled deliberately (never an error) when this
    // screen goes away.
    private var btnSummary: ImageButton? = null
    private var btnSummarizerErrors: ImageButton? = null
    private var summarizerErrorBadge: TextView? = null
    private var summarizerController: org.teslasoft.assistant.util.summarizer.SummarizerController? = null

    // Database Health A2 banner (§15.2a): persistent + dismissible per chat
    // screen — OK hides it for THIS instance only, so each new chat re-shows
    // it while a database stays disabled (the owner's re-acknowledge rule).
    private var healthBanner: LinearLayout? = null
    private var healthBannerText: TextView? = null
    private var healthBannerRepair: com.google.android.material.button.MaterialButton? = null
    private var healthBannerOk: com.google.android.material.button.MaterialButton? = null
    private var healthBannerDismissed = false

    // Which databases were degraded at the LAST banner refresh — a fresh flag
    // appearing mid-session (§15.2c) plays the distinct audio warning once,
    // in hands-free sessions only.
    private var knownDegradedTypes: Set<org.teslasoft.assistant.preferences.backup.BackupType> = emptySet()
    private var degradedBaselineTaken = false
    private var keyboardFrame: ConstraintLayout? = null
    private var keyboardInput: ChatImeInsetLayout? = null
    private var composerSurface: ChatComposerLayout? = null
    private var composerResizePosted = false
    private var root: ConstraintLayout? = null
    private var threadLoader: LinearLayout? = null
    private var btnAttachFile: ImageButton? = null
    private var btnPersistentIncludes: ImageButton? = null
    private var btnExpandContent: ImageButton? = null
    private var btnCollapseContent: ImageButton? = null
    private var visionActions: LinearLayout? = null
    // Each paperclip-menu action is a labeled row, not an icon-only button.
    private var btnVisionActionCamera: View? = null
    private var btnVisionActionGallery: View? = null
    private var btnVisionActionDocument: View? = null

    // ---- Document includes (document-includes-plan.md) --------------------
    // Attachments the user has picked but not yet sent. Once a message goes
    // out these move into that message's own record (INCLUDES_KEY), so the
    // document text is saved atomically with the text it belongs to.
    private var includeStrip: LinearLayout? = null
    private var includeStripController: IncludeStripController? = null
    private var pendingIncludes: ArrayList<ChatInclude> = arrayListOf()
    private val pendingDocumentImports: MutableSet<String> = HashSet()
    private val pendingImageImports: MutableSet<String> = HashSet()
    // Import coroutines are scoped here so they can be cancelled when the
    // screen goes away; a job that finished decoding but never persisted its
    // include is a source of orphaned image files otherwise.
    private val imageImportScopes: MutableList<CoroutineScope> = mutableListOf()
    private var condenseJob: Job? = null
    private var condenseDialog: AlertDialog? = null
    private var reduceJob: Job? = null
    private var reduceDialog: AlertDialog? = null
    private val artifactJobs: MutableMap<String, Job> = HashMap()
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
    private var chatMessageIncludes: ArrayList<String?> = arrayListOf()

    // Avatar refresh coordinators, one per side (Profile Images refresh fix,
    // July 21 2026). They keep a refresh requested before the adapter exists
    // (onResume runs before the async chat load attaches it) so it is replayed
    // instead of dropped, and stamp each async resolve with a token so an
    // older resolve can never overwrite a newer picture selection.
    private val companionAvatarRefresh = AvatarRefreshCoordinator()
    private val userAvatarRefresh = AvatarRefreshCoordinator()

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
    private var autoLangDetect = false
    private var cancelState = false
    // True only when the CURRENT generation was cancelled by a deliberate user
    // action (Stop / Hang Up / mic or conversation-button cancel), so the
    // cancellation funnel can tell a real user stop from an app/lifecycle or
    // unknown cancellation. A cancelled coroutine alone never proves the user
    // caused it. Reset at the start of every generation.
    private var userRequestedStop = false
    // For a brand-new chat: the decision about restoring the last successful
    // provider/model/routing, resolved during initSettings and acted on once the
    // chat UI exists (a dialog + Summoning Circle, or the API Endpoints screen).
    private var providerRestoreOutcome: NewChatProviderRestore.Outcome? = null
    private var disableAutoScroll = false
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

    /** One normal chat request, frozen before the visible turn is committed. */
    private data class PreparedRegularTurn(
        val rawMessage: String,
        val storedMessage: String,
        val modelFacingMessage: String,
        val pendingIncludes: List<ChatInclude>,
        val historyBeforeSend: List<ChatMessage>,
        val selectedModel: String,
        val selectedEndpointId: String,
        val request: ChatCompletionRequest,
        val payload: FrozenChatPayload,
        val contextDecision: ModelContextDecision
    )

    private data class FrozenRegularRequest(
        val request: ChatCompletionRequest,
        val payload: FrozenChatPayload
    )

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

    // Diagnostic-only observer of audio output-route changes (Bluetooth / wired
    // headset connect & disconnect). READ-ONLY: it never changes routing — mic
    // route selection stays in the STT layer (MicRouteSelector / the whisper
    // engine) and is untouched here. Registered for the life of the Activity so a
    // connect that happens while the screen is off is still recorded. Its entries
    // are kept in the "AudioRoute" log family, deliberately separate from the
    // ChatActivity lifecycle lines, so a reproduction can tell whether a
    // Bluetooth handoff and an Activity destruction are related or coincidental.
    private var audioRouteCallback: android.media.AudioDeviceCallback? = null
    private val audioRouteHandler = Handler(Looper.getMainLooper())

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
    private var speakScope: CoroutineScope? = null
    // Typed-send / regenerate generation (parseMessage). Was an anonymous
    // CoroutineScope, which meant NOTHING could cancel it — the stop control
    // could not reach a typed turn's generation at all. Stored so
    // killAllProcesses()/cancelAllAiActivity() can cancel it like every other
    // generation path.
    private var parseMessageScope: CoroutineScope? = null
    private var requestPreparationInProgress = false

    private fun killAllProcesses() {
        onSpeechResultsScope?.coroutineContext?.cancel(CancellationException("Killed"))
        whisperScope?.coroutineContext?.cancel(CancellationException("Killed"))
        whisperPreloadScope?.coroutineContext?.cancel(CancellationException("Killed"))
        processRecordingScope?.coroutineContext?.cancel(CancellationException("Killed"))
        setupScope?.coroutineContext?.cancel(CancellationException("Killed"))
        speakScope?.coroutineContext?.cancel(CancellationException("Killed"))
        parseMessageScope?.coroutineContext?.cancel(CancellationException("Killed"))
        condenseJob?.cancel(CancellationException("Killed"))
        condenseJob = null
        condenseDialog?.dismiss()
        condenseDialog = null
        reduceJob?.cancel(CancellationException("Killed"))
        reduceJob = null
        reduceDialog?.dismiss()
        reduceDialog = null
        artifactJobs.values.toList().forEach { it.cancel(CancellationException("Killed")) }
        artifactJobs.clear()
        requestPreparationInProgress = false
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
            // §15.2c mid-session detection: a corruption caught during this
            // turn set the degraded flag at the store layer; this end-of-turn
            // refresh is where the banner appears and (hands-free only) the
            // distinct audio warning plays. Cheap prefs read; runs even while
            // the screen is off, which is exactly the case the cue exists for.
            updateHealthBanner(allowAudioCue = true)
        }
    }

    /**
     * A2 (§15.2a): show/refresh the persistent degraded-database banner. The
     * banner names Memory, Lorebooks, or both (a user-image problem gets the
     * same banner shape without a feature-disable behind it, §15.16), offers
     * Repair | OK, stays until dismissed, and returns on each new chat screen
     * while a database remains disabled. [allowAudioCue] is true on the
     * per-turn refresh path: a NEWLY degraded database there plays the
     * distinct §15.2c warning, in hands-free sessions only.
     */
    private fun updateHealthBanner(allowAudioCue: Boolean) {
        val degraded = try {
            org.teslasoft.assistant.preferences.backup.DatabaseHealthState.degradedTypes(this).toSet()
        } catch (_: Exception) { emptySet() }
        if (degradedBaselineTaken) {
            val fresh = degraded - knownDegradedTypes
            if (fresh.isNotEmpty()) {
                // A new problem re-arms a previously dismissed banner and, on
                // the mid-session path, plays the audio warning once.
                healthBannerDismissed = false
                if (allowAudioCue && isHandsFreeEngaged()) playDatabaseWarningSignal()
            }
        }
        knownDegradedTypes = degraded
        degradedBaselineTaken = true
        if (degraded.isEmpty() || healthBannerDismissed) {
            healthBanner?.visibility = View.GONE
            return
        }
        val memory = org.teslasoft.assistant.preferences.backup.BackupType.MEMORY in degraded
        val lore = org.teslasoft.assistant.preferences.backup.BackupType.LOREBOOK in degraded
        healthBannerText?.text = when {
            memory && lore -> getString(R.string.health_banner_both)
            memory -> getString(R.string.health_banner_memory)
            lore -> getString(R.string.health_banner_lorebook)
            else -> getString(R.string.health_banner_user_image)
        }
        healthBanner?.visibility = View.VISIBLE
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

    /** §5 spoken approval, the deny-and-continue case: the utterance waits
     *  for the declined turn to finish (tool result plus final text), then
     *  submits as an ordinary message. Gives up silently after ~60s so a
     *  wedged turn cannot queue ghost messages forever. */
    private fun submitRecognizedTextWhenIdle(recognizedText: String, attempt: Int = 0) {
        if (attempt > 120 || isFinishing || isDestroyed) return
        handsFreeHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (isAiCurrentlyBusy()) {
                submitRecognizedTextWhenIdle(recognizedText, attempt + 1)
            } else {
                submitRecognizedText(recognizedText)
            }
        }, 500)
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
        // §5 spoken approval: while an image confirmation is pending, the
        // next recognized utterance answers it — "create it" approves,
        // "cancel" denies, and anything else denies the image and then
        // continues as a normal message once the declined turn finishes.
        // The on-screen card keeps working the whole time.
        if (pendingImageConfirmation != null) {
            when (ImageConfirmationSpeech.interpret(recognizedText)) {
                ImageConfirmationSpeech.Answer.APPROVE -> {
                    playTranscriptionDoneSignal()
                    pendingImageConfirmation?.complete(true)
                    return
                }
                ImageConfirmationSpeech.Answer.DENY -> {
                    playTranscriptionDoneSignal()
                    pendingImageConfirmation?.complete(false)
                    return
                }
                ImageConfirmationSpeech.Answer.DENY_AND_CONTINUE -> {
                    pendingImageConfirmation?.complete(false)
                    // The declined turn still returns its tool result and
                    // final text; these words follow as the next normal
                    // message the moment the turn is over.
                    submitRecognizedTextWhenIdle(recognizedText)
                    return
                }
            }
        }

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

        // Summarizer Settings may have changed while we were away (endpoint
        // removed, defaults changed) — re-resolve the icons and badge.
        if (chatStartupComplete && chatId != "") refreshSummarizerIcons()

        // Catch images that finished while the screen was detached, and retry
        // any summary that failed earlier — silently, without touching chat.
        if (chatStartupComplete && chatId != "") ensureImageSummaries()

        // A2 banner refresh: a repair finished (banner clears) or a database
        // was flagged while we were away (banner appears). No audio cue here —
        // the screen is visible on resume; the cue belongs to the mid-session
        // hands-free path (§15.2c).
        updateHealthBanner(allowAudioCue = false)

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

        // A Companion / persona picture or a default may have changed while away
        // (an editor, Profile Image settings). Re-resolve both sides, display-only.
        refreshCompanionAvatar()
        refreshUserAvatar()
    }

    /** Force the chat's top action bar and its buttons back to fully visible. */
    private fun restoreTopBarVisibility() {
        for (v in listOf(actionBar, btnBack, activityTitle, btnQuickSettings, btnSettings)) {
            v?.visibility = View.VISIBLE
            v?.alpha = 1f
        }
    }

    /**
     * Resolves the assistant-side picture off the main thread and hands it
     * (with the current Default Shape) to ChatAdapter. AI-side cascade (owner
     * ruling, July 21 2026): the active Companion's own picture, else the
     * Default AI Avatar; null only when neither is set, and the adapter then
     * falls through to the built-in glyph. Display-only and best-effort - it
     * never touches generation or a turn. Called on resume and whenever the
     * chat's companion or a default picture / the Default Shape may have
     * changed, so a picture edited elsewhere shows up in this (and past) chats.
     */
    private fun refreshCompanionAvatar() {
        // Always register the request first so one made before the adapter is
        // attached is retained (see AvatarRefreshCoordinator / onAvatarTargetReady)
        // rather than silently dropped — dropping it was the restart-only-refresh
        // bug. The token drops a stale resolve that finishes after a newer one.
        val token = companionAvatarRefresh.newRequest()
        if (adapter == null) return
        val chatPreferences = preferences ?: return
        val personaId = chatPreferences.getPersonaId()
        val shape = GlobalPreferences.getPreferences(this).getProfileImageShape()
        CoroutineScope(Dispatchers.Main).launch {
            val resolved: Triple<File?, String, ChatNameStyle.Resolved> = withContext(Dispatchers.IO) {
                try {
                    val persona = if (personaId.isEmpty()) null
                        else PersonaPreferences.getPersonaPreferences(this@ChatActivity).getPersona(personaId)
                    val file = ProfileImageResolver.resolveAiImageFile(this@ChatActivity, persona?.avatarRef ?: "")
                    Triple(
                        file,
                        persona?.label ?: "",
                        ChatNameStyle.ai(chatPreferences, persona)
                    )
                } catch (_: Exception) {
                    Triple(null, "", ChatNameStyle.ai(chatPreferences))
                }
            }
            if (isFinishing || isDestroyed) return@launch
            // Drop this result if a newer refresh has since been requested.
            if (!companionAvatarRefresh.isCurrent(token)) return@launch
            adapter?.setCompanionPresentation(
                resolved.first,
                shape,
                resolved.second,
                resolved.third
            )
        }
    }

    /**
     * Resolves the user-side picture off the main thread and hands it to
     * ChatAdapter for the user's own bubbles. User-side cascade (owner ruling,
     * July 21 2026): the active Roleplay Character's picture, else the active My
     * Persona's, else the Default Personal Avatar; null only when none is set,
     * and the bubble then shows the generic person icon. Most chats use no
     * persona, so this is what puts the Default Personal Avatar on the user
     * bubble instead of a bare person icon. Display-only and best-effort.
     */
    private fun refreshUserAvatar() {
        val token = userAvatarRefresh.newRequest()
        if (adapter == null) return
        val rpCharId = preferences?.getChatRoleplayCharacterId().orEmpty()
        val userPersonaId = preferences?.getChatUserPersonaId().orEmpty()
        val shape = GlobalPreferences.getPreferences(this).getProfileImageShape()
        CoroutineScope(Dispatchers.Main).launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    val ref = activeUserIdentityImageRef(rpCharId, userPersonaId)
                    ProfileImageResolver.resolveUserImageFile(this@ChatActivity, ref)
                } catch (_: Exception) {
                    null
                }
            }
            if (isFinishing || isDestroyed) return@launch
            // Drop this result if a newer refresh has since been requested.
            if (!userAvatarRefresh.isCurrent(token)) return@launch
            adapter?.setUserAvatar(file, shape)
        }
    }

    /**
     * Called right after the chat adapter is created and attached (initUI).
     * Marks both avatar sides' targets ready and replays any refresh that was
     * requested before the adapter existed (onResume runs before this async
     * setup finishes), then paints the first frame. Because the retained
     * request is replayed here — not discarded — a Companion / persona picture
     * or a Default changed while the chat was being (re)created still shows
     * without an app restart.
     */
    private fun onAvatarTargetReady() {
        companionAvatarRefresh.markTargetReady()
        userAvatarRefresh.markTargetReady()
        refreshCompanionAvatar()
        refreshUserAvatar()
    }

    /** The active user identity's own image hash for [refreshUserAvatar]: the
     *  Roleplay Character's picture wins, else the My Persona's, else "" (so the
     *  resolver falls through to the Default Personal Avatar). Runs off-main;
     *  never provisions the store. */
    private fun activeUserIdentityImageRef(rpCharId: String, userPersonaId: String): String {
        if (!MemoryStore.isProvisioned(this)) return ""
        val store = MemoryStore.getInstance(this)
        if (rpCharId.isNotEmpty()) {
            store.getRoleplayCharacter(rpCharId)?.imageRef?.let { if (it.isNotEmpty()) return it }
        }
        if (userPersonaId.isNotEmpty()) {
            store.getUserPersona(userPersonaId)?.imageRef?.let { if (it.isNotEmpty()) return it }
        }
        return ""
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

    /**
     * Camera capture landing point. The system camera has written the JPEG
     * bytes into a fixed tmp.jpg under the app's own pictures dir; the flow
     * now hands that URI to [ImageImporter] on an IO thread so the image is
     * decoded, orientation-corrected, downsampled to the 2048-longest-edge
     * cap and copied into the chat's own images directory before it appears
     * as a pending include in the Includes strip. The tmp.jpg is overwritten
     * on the next capture; nothing here keeps it around.
     */
    private var cameraIntentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val imageFile = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "tmp.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", imageFile)
        importPendingImage(uri, displayNameOverride = cameraCaptureDisplayName())
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

            btnQuickSettings?.background = getAmoledAccentDrawable(
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
            btnPersistentIncludes?.background = getAmoledAccentDrawableV2(
                AppCompatResources.getDrawable(
                    this,
                    R.drawable.btn_accent_tonal_v5_amoled
                )!!, this
            )
            btnExpandContent?.background = getAmoledAccentDrawableV2(
                AppCompatResources.getDrawable(
                    this,
                    R.drawable.btn_accent_tonal_v5_amoled
                )!!, this
            )
            btnCollapseContent?.background = getAmoledAccentDrawableV2(
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

            btnQuickSettings?.background = getDarkAccentDrawable(
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
            btnPersistentIncludes?.background = getDarkAccentDrawable(
                AppCompatResources.getDrawable(
                    this,
                    R.drawable.btn_accent_tonal_v5
                )!!, this
            )
            btnExpandContent?.background = getDarkAccentDrawable(
                AppCompatResources.getDrawable(
                    this,
                    R.drawable.btn_accent_tonal_v5
                )!!, this
            )
            btnCollapseContent?.background = getDarkAccentDrawable(
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
        override fun onStart(utteranceId: String?) {
            lastTtsUtteranceStarted = true
            logTtsLifecycle("TTS onStart engine=google utteranceId=$utteranceId")
        }
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
            val isFinal = utteranceId == finalTtsUtteranceId
            logTtsLifecycle("TTS onDone engine=google utteranceId=$utteranceId final=$isFinal")
            if (!isFinal) {
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
            logTtsLifecycle("TTS onError engine=google utteranceId=$utteranceId code=null")
            handleTtsReadbackError(utteranceId, null)
        }
        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.w("TTS", "TTS utterance error code $errorCode: $utteranceId")
            logTtsLifecycle("TTS onError engine=google utteranceId=$utteranceId code=$errorCode")
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
        // Auto-send governs only the manual mic button (owner ruling, July
        // 2026); the hands-free loop always keeps listening after a readback.
        if (handsFree && sttSupported && !cancelState && !handsFreeStopped && !isRecording &&
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
            logVoiceEvent("mic NOT reopened after readback: engine=$effModel " +
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
    // list returns it; adopt it for this chat and mark seeding done. It becomes
    // the default for later chats only after an assistant response succeeds.
    private val createFirstCompanionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val personaId = result.data?.getStringExtra("personaId")
            if (!personaId.isNullOrEmpty()) {
                preferences?.setPersonaId(personaId)
                preferences?.setPersonaActivationSeeded(true)
                // onResume painted before this result assigned the new
                // Companion, so resolve its picture now instead of leaving
                // the default avatar visible until another resume.
                refreshCompanionAvatar()
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
                if (includeStripController?.collapseIfExpanded() == true) {
                    // The expanded Includes overlay consumes Back first.
                } else if (bulkSelectionMode) {
                    deselectAll()
                } else {
                    finishActivity()
                }
            }
        } else {
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (includeStripController?.collapseIfExpanded() == true) {
                        // The expanded Includes overlay consumes Back first.
                    } else if (bulkSelectionMode) {
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

        // Read-only audio output-route observer (Bluetooth / wired headset
        // connect & disconnect). Diagnostics only; it never changes routing.
        registerAudioRouteDiagnostics()

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

        // §5 recovery: reattach to a generation that is still running for
        // this chat and re-show its Creating Image row. Skipped while the
        // blocking storage-unavailable state owns the screen.
        if (!chatStorageUnavailable && adapter != null) {
            restoreImageGenerationJobState()
        }

        chatStartupComplete = true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (includeStripController?.isExpanded() == true) {
                val bounds = Rect()
                val touchedInsideStrip =
                    includeStrip?.getGlobalVisibleRect(bounds) == true &&
                        bounds.contains(event.rawX.toInt(), event.rawY.toInt())
                if (!touchedInsideStrip) includeStripController?.collapseIfExpanded()
            }

            if (visionActions?.visibility == View.VISIBLE) {
                val bounds = Rect()
                val touchedInsideMenu =
                    visionActions?.getGlobalVisibleRect(bounds) == true &&
                        bounds.contains(event.rawX.toInt(), event.rawY.toInt())
                val touchedPaperclip =
                    btnAttachFile?.getGlobalVisibleRect(bounds) == true &&
                        bounds.contains(event.rawX.toInt(), event.rawY.toInt())

                // Leave paperclip taps for its click listener so it can still
                // toggle the menu closed. Every other outside tap dismisses
                // the menu before the tapped control handles its own action.
                if (!touchedInsideMenu && !touchedPaperclip) {
                    visionActions?.visibility = View.GONE
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Reached only for configuration changes listed in the manifest's
        // configChanges — those are absorbed here WITHOUT recreating the Activity,
        // so the live conversation, generation, and mic loop survive untouched.
        // Logged so a screen-off reproduction can show a night-mode/orientation
        // flip landing here (conversation preserved) rather than in onDestroy
        // (conversation torn down). Gated on voice diagnostics: config changes
        // (e.g. rotations) can be frequent, so this must not spam the Event log.
        val night = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val orientation = when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            else -> "undefined"
        }
        logVoiceEvent(
            "ChatActivity configuration change absorbed (no recreation):" +
                    " night=$night orientation=$orientation" +
                    " handsFreeService=${HandsFreeService.isRunning}"
        )
        scheduleComposerHeightUpdate()
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
        // Decisive lifecycle record for the screen-off / route-change interruption
        // investigation. onDestroy currently always runs the full session teardown
        // below (killAllProcesses + stopHandsFreeService), so changingConfig=true
        // here means a mere Android configuration recreation is being treated as a
        // genuine conversation end — the exact condition that produced an
        // app_cancel "screen was closed" on a turn the user never abandoned. With
        // uiMode now handled in the manifest, a night-mode flip should no longer
        // reach this path; if this line still reports changingConfig=true after
        // the fix, a different configuration change is recreating the Activity and
        // the teardown must learn to distinguish the two. Always persisted (bounded
        // to once per destroy) so the next reproduction is conclusive.
        val teardownAction = if (isChangingConfigurations)
            "full teardown (configuration recreation — session state will be lost)"
        else "full teardown (genuine destroy)"
        logVoiceEventAlways(
            "ChatActivity destroy: finishing=$isFinishing" +
                    " changingConfig=$isChangingConfigurations" +
                    " generationActive=${requestPreparationInProgress || providerRequestDispatched}" +
                    " providerDispatched=$providerRequestDispatched" +
                    " readbackActive=$voiceWasLive" +
                    " readbackExpected=$handsFreeReadbackExpected" +
                    " handsFreePref=${preferences?.getHandsFreeMode() == true}" +
                    " handsFreeService=${HandsFreeService.isRunning}" +
                    " turn=${currentLifecycleTurnId.ifBlank { "none" }}" +
                    " action=$teardownAction"
        )
        // Route snapshot at destruction, in the separate AudioRoute family. Pairs
        // with the "readback start" snapshot and any device add/remove lines so a
        // destroy at the TTS/readback boundary can be matched against whatever the
        // audio route was doing at that moment.
        logVoiceEventAlways("AudioRoute [chat destroy]: ${describeAudioOutputRoute()}")
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

        // Deliberate cancellation of any in-flight fold-in: leaving the chat
        // is never a Summarizer Error, and the bookmark only ever advances on
        // a completed save (errors doc §4).
        summarizerController?.cancel()

        // Detach from the image job registry WITHOUT cancelling the job:
        // the generation deliberately survives leaving the chat and
        // recreation (§5); with no screen attached its result is written
        // straight into the stored history.
        ImageGenerationJobRegistry.detach(chatId, this)

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

        // Release the read-only audio-route observer with this Activity instance.
        unregisterAudioRouteDiagnostics()

        // Cancel any in-flight image import. Its own completion handler sees
        // isDestroyed and deletes freshly written bytes that never became a
        // persisted include, so cancelling here just stops the work promptly.
        for (scope in imageImportScopes.toList()) {
            try { scope.cancel() } catch (_: Exception) { /* ignore */ }
        }
        imageImportScopes.clear()

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
        // Brand-new chat: adopt the provider/model/routing the last conversation
        // successfully used, before the endpoint/key below are read. Updates
        // apiEndpointObject when it restores; otherwise records what the UI must
        // do once it exists (a dialog, or the API Endpoints screen).
        maybeRestoreProviderForNewChat(historyResult)

        key = apiEndpointObject?.apiKey!!
        // The auxiliary client (cloud Whisper, TTS, image generation,
        // function calling) must follow the active chat's endpoint. It used
        // to grab a key from any saved api.openai.com endpoint, which leaked
        // audio and message content to OpenAI while chatting with a
        // local/custom endpoint.
        openAIKey = apiEndpointObject?.apiKey

        endSeparator = preferences!!.getEndSeparator()
        prefix = preferences!!.getPrefix()


        if (key == null) {
            startActivity(Intent(this, WelcomeActivity::class.java).setAction(Intent.ACTION_VIEW))
            finishActivity()
        } else {
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
                            content = modelFacingContent(message)
                        )
                    )
                }
            }

            loadPendingIncludes()
            reconcileChatImages()

            updateMessagesSelectionProjection()


            adapter = ChatAdapter(messages, messagesSelectionProjection, this, preferences!!, chatId)
            adapter?.setOnUpdateListener(this)

            initUI()
            reloadAmoled()
            initSpeechListener()
            initTTS()
            initLogic()
            initAI()

            // The chat UI now exists, so a missing/absent configuration can be
            // surfaced (a dialog + Summoning Circle, or the API Endpoints screen).
            handleProviderRestoreOutcome()
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
        btnQuickSettings = findViewById(R.id.btn_quick_settings)
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        btnDebugLog = findViewById(R.id.btn_debug_log)
        btnSummary = findViewById(R.id.btn_summary)
        btnSummarizerErrors = findViewById(R.id.btn_summarizer_errors)
        summarizerErrorBadge = findViewById(R.id.summarizer_error_badge)
        keyboardFrame = findViewById(R.id.keyboard_frame)
        keyboardInput = findViewById(R.id.keyboard_input)
        composerSurface = findViewById(R.id.composer_surface)
        root = findViewById(R.id.root)
        btnAttachFile = findViewById(R.id.btn_attach)
        btnPersistentIncludes = findViewById(R.id.btn_persistent_includes)
        btnExpandContent = findViewById(R.id.btn_expand_content)
        btnCollapseContent = findViewById(R.id.btn_collapse_content)
        visionActions = findViewById(R.id.vision_action_selector)
        btnVisionActionCamera = findViewById(R.id.action_camera)
        btnVisionActionGallery = findViewById(R.id.action_gallery)
        btnVisionActionDocument = findViewById(R.id.action_document)
        includeStrip = findViewById(R.id.include_strip)
        initIncludeStrip()

        composerSurface?.setExpansionListener { expanded ->
            setComposerContainerExpanded(expanded)
        }
        root?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scheduleComposerHeightUpdate()
        }
        keyboardInput?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scheduleComposerHeightUpdate()
        }
        chat?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scheduleComposerHeightUpdate()
        }
        composerSurface?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scheduleComposerHeightUpdate()
        }
        includeStrip?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scheduleComposerHeightUpdate()
        }

        btnPersistentIncludes?.setOnClickListener { anchor ->
            val ids = persistentIncludeIds()
            IncludesPopupController.show(
                anchor = anchor,
                includeIds = ids,
                resolveCurrent = ::resolvePersistentIncludes,
                callbacks = object : IncludesPopupController.Callbacks {
                    override fun onIncludeEdit(includeId: String) {
                        findIncludeById(includeId)?.let(::editInclude)
                    }

                    override fun onIncludeRemove(includeId: String) {
                        findIncludeById(includeId)?.let(::removeInclude)
                    }

                    override fun onIncludeCondense(includeId: String) {
                        findIncludeById(includeId)?.let(::condenseInclude)
                    }
                }
            )
        }
        refreshPersistentIncludeControls()
        bulkContainer = findViewById(R.id.bulk_container)
        btnSelectAll = findViewById(R.id.btn_select_all)
        btnDeselectAll = findViewById(R.id.btn_deselect_all)
        btnDeleteSelected = findViewById(R.id.btn_delete_selected)
        btnCopySelected = findViewById(R.id.btn_copy_selected)
        btnShareSelected = findViewById(R.id.btn_share_selected)
        selectedCount = findViewById(R.id.text_selected_count)
        expandableWindowRoot = findViewById(R.id.expandable_window_root)
        blurSelectorView = findViewById(R.id.attach_bg)

        healthBanner = findViewById(R.id.health_banner)
        healthBannerText = findViewById(R.id.health_banner_text)
        healthBannerRepair = findViewById(R.id.health_banner_repair)
        healthBannerOk = findViewById(R.id.health_banner_ok)
        healthBannerRepair?.setOnClickListener {
            // Repair routes to the one home of the repair flow — the Backup &
            // Restore screen — with the affected database's A1 dialog opening
            // immediately.
            val degraded = org.teslasoft.assistant.preferences.backup.DatabaseHealthState.degradedTypes(this)
            val intent = Intent(this, MemoryBackupRestoreActivity::class.java)
            degraded.firstOrNull()?.let {
                intent.putExtra(MemoryBackupRestoreActivity.EXTRA_START_REPAIR_FOR, it.key)
            }
            startActivity(intent)
        }
        healthBannerOk?.setOnClickListener {
            // Acknowledged for this chat screen only; the banner returns on
            // the next chat while the problem persists (§15.2a).
            healthBannerDismissed = true
            healthBanner?.visibility = View.GONE
        }

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

        btnQuickSettings?.setImageResource(R.drawable.ic_history_edu)
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

        btnQuickSettings?.background = getDarkAccentDrawable(
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
            // While a chat is still waiting on its AI-generated name, chatName
            // holds the internal "_autoname_N" placeholder — show the same
            // "Untitled chat" fallback the header itself displays rather than
            // leaking the placeholder into the editable field.
            val currentTitle = if (chatName.trim().contains("_autoname_")) {
                getString(R.string.label_untitled_chat)
            } else {
                chatName
            }
            EditChatTitleDialog.show(this, currentTitle) { newTitle ->
                renameChatTitle(newTitle)
            }
        }

        val linearLayoutManager = LinearLayoutManager(this)
        // linearLayoutManager.stackFromEnd = true

        chat?.setLayoutManager(linearLayoutManager)

        val itemTouchHelper = ItemTouchHelper(itemTouchCallback)
        itemTouchHelper.attachToRecyclerView(chat)

        chat?.adapter = adapter

        adapter?.notifyDataSetChanged()

        // First paint of both avatars (resolved off-main), and the replay of
        // any refresh requested before this adapter was attached.
        onAvatarTargetReady()

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

    /**
     * Gallery image picker landing point. Hands the URI off to
     * [ImageImporter] which handles JPEG/PNG/HEIC conversion, EXIF
     * orientation, downsampling to the 2048 longest-edge cap and the copy
     * into this chat's own images directory. Failure surfaces through the
     * approved image-attach dialogs, never as a preview above the chat.
     */
    private val imageIntentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        importPendingImage(uri, displayNameOverride = null)
    }

    // ==== Image includes ====================================================
    // Camera and Image both flow through here so the pending Includes strip
    // is the ONE surface that ever holds an attached picture. The image bytes
    // live under the chat's private images dir; the ChatInclude carries just
    // the hash reference plus dimensions and mime for the model estimate.

    /** Turns a picked-or-captured image URI into a pending image include, or
     *  raises the approved failure dialog when the file cannot be prepared.
     *  The old preview above the chat is gone; a failure never shows a
     *  half-attached row.
     *
     *  Duplicate-source protection mirrors documents: the same picked source
     *  cannot be attached twice to one pending message, and a second tap while
     *  the first import is still running is ignored. The import scope is
     *  tracked so that if the screen goes away between the file write and the
     *  include being persisted, the freshly written bytes are deleted instead
     *  of orphaned. */
    private fun importPendingImage(uri: Uri, displayNameOverride: String?) {
        val fingerprint = ImageImporter.sourceFingerprint(uri.toString())
        if (pendingIncludes.any { it.sourceFingerprint == fingerprint } ||
            !pendingImageImports.add(fingerprint)
        ) {
            showImageAlreadyAttached()
            return
        }

        val scope = CoroutineScope(Dispatchers.Main)
        imageImportScopes.add(scope)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    ImageImporter.import(
                        this@ChatActivity, uri, chatId, displayNameOverride
                    )
                } catch (_: Exception) {
                    ImageImporter.Result.Unknown(displayNameOverride ?: "image")
                }
            }
            pendingImageImports.remove(fingerprint)
            imageImportScopes.remove(scope)
            if (isFinishing || isDestroyed) {
                // The screen is gone before the include could be persisted:
                // drop the bytes we just wrote so they never orphan.
                if (result is ImageImporter.Result.Success) {
                    withContext(Dispatchers.IO) {
                        ImageImporter.deleteOrphanFile(result.onDiskFile)
                    }
                }
                return@launch
            }

            when (result) {
                is ImageImporter.Result.Success -> {
                    pendingIncludes.add(result.include)
                    savePendingIncludes()
                    refreshIncludeStrip()
                }
                is ImageImporter.Result.Unsupported ->
                    showImageAttachDialog(
                        R.string.image_attach_unsupported_title,
                        R.string.image_attach_unsupported_body
                    )
                is ImageImporter.Result.HeicConversionFailed ->
                    showImageAttachDialog(
                        R.string.image_attach_conversion_failed_title,
                        R.string.image_attach_conversion_failed_body
                    )
                is ImageImporter.Result.ReadFailed ->
                    showImageAttachDialog(
                        R.string.image_attach_read_failed_title,
                        R.string.image_attach_read_failed_body
                    )
                is ImageImporter.Result.TooLarge ->
                    showImageAttachDialog(
                        R.string.image_attach_too_large_title,
                        R.string.image_attach_too_large_body
                    )
                is ImageImporter.Result.Unknown ->
                    showImageAttachDialog(
                        R.string.image_attach_read_failed_title,
                        R.string.image_attach_read_failed_body
                    )
            }
        }
    }

    private fun showImageAttachDialog(titleRes: Int, bodyRes: Int) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(titleRes)
            .setMessage(bodyRes)
            .setPositiveButton(R.string.okay, null)
            .show()
    }

    /** Camera captures name themselves after the chat plus a timestamp so a
     *  transcript's summary rows stay readable ("Trip planning 07-27-26
     *  14-32.jpg") instead of showing the always-`tmp.jpg` placeholder. */
    private fun cameraCaptureDisplayName(): String {
        val safeName = (chatName.ifBlank { "Untitled" })
            .replace(Regex("[/\\\\:*?\"<>|]"), " ")
            .trim()
            .ifBlank { "Untitled" }
        val stamp = java.text.SimpleDateFormat(
            "MM-dd-yy HH-mm", java.util.Locale.getDefault()
        ).format(java.util.Date())
        return "$safeName $stamp.jpg"
    }

    // ==== Document includes ================================================
    // See document-includes-plan.md for the current design. The short
    // version: an attached document is extracted to text on THIS device and
    // rides inside the user message it was attached to, so it works
    // identically on every OpenAI-compatible endpoint (GLM, DeepSeek,
    // OpenRouter) with no provider-specific upload anywhere, and it sits at a
    // fixed point in history that the provider's prefix cache can cover on
    // every later turn.

    private fun initIncludeStrip() {
        val strip = includeStrip ?: return
        val collapsed = findViewById<View>(R.id.include_collapsed_row) ?: return
        val scroll = findViewById<ScrollView>(R.id.include_list_scroll) ?: return
        val list = findViewById<LinearLayout>(R.id.include_list) ?: return

        includeStripController = IncludeStripController(
            this, strip, collapsed, scroll, list,
            object : IncludeStripController.Callbacks {
                override fun onRemoveInclude(include: ChatInclude) = removeInclude(include)
            }
        )
        refreshIncludeStrip()
    }

    private fun refreshIncludeStrip() {
        // Sent documents belong to the transcript row under the user name.
        // The composer only shows attachments waiting for the next Send.
        includeStripController?.bind(pendingIncludes)
        scheduleComposerHeightUpdate()
    }

    /** The composer paperclip is visible only while sent Includes remain in
     * history. Pending unsent Includes intentionally do not activate it. */
    private fun persistentIncludeIds(): List<String> =
        PersistentIncludeContext
            .allSent(messages, INCLUDES_KEY)
            .map { it.id }

    /** Resolve the requested ids from their original user-message records.
     * This is read-only; the popup never becomes an Include owner. */
    private fun resolvePersistentIncludes(ids: Set<String>): List<ChatInclude> {
        if (ids.isEmpty()) return emptyList()
        val result = ArrayList<ChatInclude>()
        val seen = HashSet<String>()
        for (message in messages) {
            if (message["isBot"] == true) continue
            for (include in includesOf(message)) {
                if (include.id in ids && seen.add(include.id)) result.add(include)
            }
        }
        return result
    }

    private fun refreshPersistentIncludeControls() {
        val button = btnPersistentIncludes ?: return
        val visible = persistentIncludeIds().isNotEmpty()
        button.visibility = if (visible) View.VISIBLE else View.GONE
        button.isEnabled = visible
    }

    /**
     * Expanded mode uses the existing keyboard_input/ChatImeInsetLayout
     * container. Its height is measured from the current chat viewport rather
     * than by installing another keyboard or inset listener.
     */
    private fun setComposerContainerExpanded(expanded: Boolean) {
        val surface = composerSurface ?: return
        val params = surface.layoutParams ?: return
        if (!expanded) {
            if (params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                surface.layoutParams = params
            }
            return
        }
        scheduleComposerHeightUpdate()
    }

    private fun scheduleComposerHeightUpdate() {
        if (composerSurface?.isExpanded() != true || composerResizePosted) return
        composerResizePosted = true
        composerSurface?.post {
            composerResizePosted = false
            updateExpandedComposerHeight()
        }
    }

    private fun updateExpandedComposerHeight() {
        val surface = composerSurface ?: return
        val chatView = chat ?: return
        val rootView = root ?: return
        val keyboard = keyboardInput ?: return
        if (!surface.isExpanded() || rootView.height <= 0) return

        val surfaceParams = surface.layoutParams ?: return
        val bottomMargin = (surfaceParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        val pendingStrip = includeStrip
        val pendingHeight = if (pendingStrip?.visibility == View.VISIBLE) {
            val pendingMargins = pendingStrip.layoutParams as? ViewGroup.MarginLayoutParams
            pendingStrip.height + (pendingMargins?.bottomMargin ?: 0)
        } else {
            0
        }
        val targetHeight = rootView.height - chatView.top - keyboard.paddingBottom -
            bottomMargin - pendingHeight
        if (targetHeight <= 0 || surfaceParams.height == targetHeight) return

        surfaceParams.height = targetHeight
        surface.layoutParams = surfaceParams
    }

    private fun includesOf(message: HashMap<String, Any>): List<ChatInclude> =
        ChatInclude.listFromJson(message[INCLUDES_KEY]?.toString())

    private fun savePendingIncludes(synchronous: Boolean = false) {
        preferences?.setPendingIncludes(
            if (pendingIncludes.isEmpty()) "" else ChatInclude.listToJson(pendingIncludes),
            synchronous = synchronous
        )
    }

    private fun loadPendingIncludes() {
        val loaded = ChatInclude.listFromJson(preferences?.getPendingIncludes())
        val sentIds = messages
            .flatMap(::includesOf)
            .mapTo(HashSet()) { it.id }
        pendingIncludes = ArrayList(
            loaded.filter { it.form != IncludeForm.ARTIFACT && it.id !in sentIds }
        )
        if (pendingIncludes.size != loaded.size) {
            // Recover safely if a process stopped after the chat-history side
            // of a pending-to-sent transfer was committed.
            savePendingIncludes(synchronous = true)
        }
    }

    /**
     * Replaces one include wherever it lives — still pending, or already
     * carried by a sent message — and re-renders everything that depends on
     * it. Changing an include changes what the model sees for that turn, so
     * the model projection is rebuilt too; leaving the old projection in place
     * would keep sending a document the user just removed.
     */
    private fun updateInclude(updated: ChatInclude) {
        var changed = false

        val pendingIndex = pendingIncludes.indexOfFirst { it.id == updated.id }
        if (pendingIndex >= 0) {
            pendingIncludes[pendingIndex] = updated
            savePendingIncludes(synchronous = true)
            changed = true
        }

        for (message in messages) {
            val existing = includesOf(message)
            if (existing.none { it.id == updated.id }) continue
            val merged = existing.map { if (it.id == updated.id) updated else it }
            message[INCLUDES_KEY] = ChatInclude.listToJson(merged)
            changed = true
        }

        if (!changed) return
        saveSettings()
        rebuildModelProjection()
        refreshIncludeStrip()
        refreshPersistentIncludeControls()
        adapter?.notifyDataSetChanged()
    }

    /**
     * Rebuilds the model-facing projection of the whole conversation from the
     * stored messages. Cheap (no encryption, no tokenizer) and the only way to
     * guarantee the projection and the stored includes cannot drift apart.
     */
    private fun rebuildModelProjection() {
        chatMessages = arrayListOf()
        chatMessageIncludes = arrayListOf()
        for (message in messages) {
            val content = modelFacingContent(message)
            if (content.isBlank()) continue
            chatMessages.add(
                ChatMessage(
                    role = if (message["isBot"] == true) {
                        ChatRole.Assistant
                    } else {
                        ChatRole.User
                    },
                    content = content
                )
            )
            chatMessageIncludes.add(
                if (message["isBot"] != true) message[INCLUDES_KEY]?.toString() else null
            )
        }
    }

    private fun openDocumentPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            // CATEGORY_OPENABLE is deliberately NOT set. It restricts the
            // picker to files that can be opened byte-for-byte, which hides
            // Google Docs and Sheets entirely — those have no bytes of their
            // own and are converted on request instead. Anything that turns
            // up as a result and cannot be converted is refused by the
            // importer with a specific reason.
            //
            // "*/*" with an EXTRA_MIME_TYPES filter, because some providers
            // hand back documents typed as octet-stream and a strict type
            // filter would make real .md/.csv files unpickable.
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, DocumentImporter.PICKER_MIME_TYPES)
        }
        documentIntentLauncher.launch(intent)
    }

    private val documentIntentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val fingerprint = DocumentImporter.sourceFingerprint(uri.toString())
        if (pendingIncludes.any { it.sourceFingerprint == fingerprint } ||
            !pendingDocumentImports.add(fingerprint)
        ) {
            showDocumentAlreadyAttached()
            return@registerForActivityResult
        }
        importDocument(uri, fingerprint)
    }

    /**
     * Reads the picked file off the main thread (a large document is real I/O)
     * and either attaches it or explains why it could not be. A failure is
     * always stated — never a silently ignored tap.
     */
    private fun importDocument(uri: Uri, sourceFingerprint: String) {
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    DocumentImporter.import(this@ChatActivity, uri)
                } catch (_: Exception) {
                    DocumentImporter.Result.Unknown("document")
                }
            }
            pendingDocumentImports.remove(sourceFingerprint)
            if (isFinishing || isDestroyed) return@launch

            when (result) {
                is DocumentImporter.Result.Success -> {
                    pendingIncludes.add(result.include)
                    savePendingIncludes()
                    refreshIncludeStrip()
                }
                is DocumentImporter.Result.Unsupported ->
                    showIncludeProblem(R.string.include_error_unsupported, result.fileName)
                is DocumentImporter.Result.PermissionDenied ->
                    showIncludeProblem(R.string.include_error_permission_denied, result.fileName)
                is DocumentImporter.Result.SourceUnavailable ->
                    showIncludeProblem(R.string.include_error_source_unavailable, result.fileName)
                is DocumentImporter.Result.FileGone ->
                    showIncludeProblem(R.string.include_error_file_gone, result.fileName)
                is DocumentImporter.Result.InterruptedRead ->
                    showIncludeCapacityProblem(
                        R.string.document_attach_failed_title,
                        R.string.document_attach_interrupted_body
                    )
                is DocumentImporter.Result.PasswordProtected ->
                    showIncludeProblem(R.string.include_error_password_protected, result.fileName)
                is DocumentImporter.Result.ContentMismatch ->
                    showIncludeProblem(R.string.include_error_content_mismatch, result.fileName)
                is DocumentImporter.Result.Corrupted ->
                    showIncludeProblem(R.string.include_error_corrupted, result.fileName)
                is DocumentImporter.Result.Empty ->
                    showIncludeProblem(R.string.include_error_empty, result.fileName)
                is DocumentImporter.Result.ExportUnavailable ->
                    showIncludeProblem(R.string.include_error_export_unavailable, result.fileName)
                is DocumentImporter.Result.ExportFailed ->
                    showIncludeCapacityProblem(
                        R.string.document_export_failed_title,
                        R.string.document_export_incomplete_body
                    )
                is DocumentImporter.Result.DeviceMemoryLimit ->
                    showIncludeCapacityProblem(
                        R.string.document_attach_failed_title,
                        R.string.document_attach_memory_body
                    )
                is DocumentImporter.Result.ArchiveExpansionLimit ->
                    showIncludeCapacityProblem(
                        R.string.document_attach_failed_title,
                        R.string.document_attach_expansion_body
                    )
                is DocumentImporter.Result.StorageLimit ->
                    showIncludeCapacityProblem(
                        R.string.document_storage_failed_title,
                        R.string.document_attach_storage_body
                    )
                is DocumentImporter.Result.Unknown ->
                    showIncludeProblem(R.string.include_error_unknown, result.fileName)
            }
        }
    }

    /** A dialog, never a toast (house rule) — the user must be able to read
     *  why their file did not attach at their own pace. */
    private fun showIncludeProblem(messageRes: Int, fileName: String) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(fileName)
            .setMessage(messageRes)
            .setPositiveButton(R.string.btn_close, null)
            .show()
    }

    private fun showIncludeCapacityProblem(titleRes: Int, messageRes: Int) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(R.string.okay, null)
            .show()
    }

    private fun showDocumentAlreadyAttached() {
        showAlreadyAttached(R.string.include_error_duplicate)
    }

    private fun showImageAlreadyAttached() {
        showAlreadyAttached(R.string.include_error_duplicate_image)
    }

    private fun showAlreadyAttached(titleRes: Int) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(titleRes)
            .setPositiveButton(R.string.okay, null)
            .show()
    }

    /**
     * Remove drops an include to its ARTIFACT form — a tiny bookmark —
     * rather than erasing it. Deleting outright would leave the conversation
     * full of replies about something the model can no longer see, which is
     * how a model starts inventing what the document said.
     *
     * The bookmark is written by the chat's own model; if that cannot be
     * reached the file-name fallback stands in immediately. Removal must never
     * block or fail on a network problem, and the line stays editable either
     * way.
     */
    private fun removeInclude(include: ChatInclude) {
        val pendingIndex = pendingIncludes.indexOfFirst { it.id == include.id }
        if (pendingIndex >= 0) {
            // It was never sent, so detaching it must leave no model-facing
            // history or artifact claiming that the user shared it.
            val removed = pendingIncludes.removeAt(pendingIndex)
            // Persist the removal BEFORE touching bytes, so a crash mid-delete
            // never leaves a saved include pointing at bytes that are gone.
            savePendingIncludes(synchronous = true)
            refreshIncludeStrip()
            if (removed.kind.isImage()) maybeDeleteImageBytes(removed)
            return
        }

        val fallback = IncludeTextPolicy.fallbackArtifactLine(include.fileName)

        if (include.kind.isImage()) {
            updateInclude(
                include.copy(
                    form = IncludeForm.ARTIFACT,
                    artifactLine = fallback,
                    notice = IncludeNotice.None
                ).withoutImageBytes()
            )
            artifactJobs.remove(include.id)?.cancel()
            val imageInclude = include
            val job = CoroutineScope(Dispatchers.Main).launch {
                val written = requestImageArtifactLine(imageInclude)
                maybeDeleteImageBytes(imageInclude)
                if (isFinishing || isDestroyed || written == null) return@launch
                val latest = findIncludeById(imageInclude.id) ?: return@launch
                if (latest.form == IncludeForm.ARTIFACT && latest.artifactLine == fallback) {
                    updateInclude(latest.copy(artifactLine = written))
                }
            }
            artifactJobs[include.id] = job
            job.invokeOnCompletion {
                if (artifactJobs[include.id] === job) artifactJobs.remove(include.id)
            }
            return
        }

        // Show the cheap form at once so the transcript responds to the tap;
        // the model-written reminder replaces the fallback when/if it arrives.
        updateInclude(
            include.copy(
                form = IncludeForm.ARTIFACT,
                artifactLine = fallback,
                notice = IncludeNotice.None
            )
        )

        artifactJobs.remove(include.id)?.cancel()
        val job = CoroutineScope(Dispatchers.Main).launch {
            val written = requestArtifactLine(include)
            if (isFinishing || isDestroyed || written == null) return@launch
            val latest = findIncludeById(include.id) ?: return@launch
            // Only replace the placeholder — never overwrite text the user
            // has since edited by hand.
            if (latest.form == IncludeForm.ARTIFACT && latest.artifactLine == fallback) {
                updateInclude(latest.copy(artifactLine = written))
            }
        }
        artifactJobs[include.id] = job
        job.invokeOnCompletion {
            if (artifactJobs[include.id] === job) artifactJobs.remove(include.id)
        }
    }

    /**
     * Deletes an image include's on-disk bytes, but only when no OTHER live
     * FULL image include (pending or in any saved message) still points at the
     * same content hash. Images dedupe by hash, so the same file can back more
     * than one include; deleting it out from under a surviving include would
     * break that include's send. The reference check runs on the main thread
     * (reads in-memory lists), the delete on IO.
     */
    private fun maybeDeleteImageBytes(include: ChatInclude) {
        val hash = include.imageFileHash?.takeIf { it.isNotEmpty() } ?: return
        val referenced = imageBytesStillReferenced(hash, excludingId = include.id)
        val chat = chatId
        CoroutineScope(Dispatchers.IO).launch {
            ImageImporter.deleteImageFileIfUnreferenced(
                this@ChatActivity, chat, include, referenced
            )
        }
    }

    private fun imageBytesStillReferenced(hash: String, excludingId: String): Boolean {
        if (pendingIncludes.any {
                it.id != excludingId && it.imageFileHash == hash && it.hasLiveImageBytes()
            }
        ) return true
        for (message in messages) {
            if (includesOf(message).any {
                    it.id != excludingId && it.imageFileHash == hash && it.hasLiveImageBytes()
                }
            ) return true
        }
        return false
    }

    /**
     * One-shot sweep on chat load: delete any file in this chat's image
     * directory that no live FULL image include references. Covers an import
     * that wrote its file but never persisted its include (the screen died in
     * the gap) and any bytes a prior rename move could not carry over.
     */
    private fun reconcileChatImages() {
        val referenced = HashSet<String>()
        for (include in pendingIncludes) {
            if (include.hasLiveImageBytes()) include.imageFileHash?.let(referenced::add)
        }
        for (message in messages) {
            for (include in includesOf(message)) {
                if (include.hasLiveImageBytes()) include.imageFileHash?.let(referenced::add)
            }
        }
        val chat = chatId
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ImageImporter.reconcileChatImages(this@ChatActivity, chat, referenced)
            } catch (_: Exception) { /* best-effort cleanup */ }
        }
    }

    private fun findIncludeById(id: String): ChatInclude? {
        pendingIncludes.firstOrNull { it.id == id }?.let { return it }
        for (message in messages) {
            includesOf(message).firstOrNull { it.id == id }?.let { return it }
        }
        return null
    }

    /**
     * Asks the selected endpoint/model for the short bookmark that stands in
     * for a removed attachment. The caller has already applied a usable
     * filename fallback, so a failed request remains silent.
     */
    private suspend fun requestArtifactLine(include: ChatInclude): String? {
        val client = ai ?: return null
        val lineModel = model.ifBlank { preferences?.getModel() ?: "" }
        if (lineModel.isBlank()) return null

        return try {
            val raw = withContext(Dispatchers.IO) {
                val spec = IncludeAuxiliaryRequestPolicy.artifact(
                    include = include,
                    selectedModel = lineModel,
                    excerptCharacters = ARTIFACT_EXCERPT_CHARS
                )
                val request = ChatCompletionRequest(
                    model = ModelId(spec.model),
                    maxTokens = spec.maxTokens,
                    messages = listOf(
                        ChatMessage(
                            role = ChatRole.User,
                            content = spec.prompt
                        )
                    )
                )
                client.chatCompletion(request).choices.firstOrNull()?.message?.content
            }
            IncludeTextPolicy.sanitizeArtifactLine(raw, include.fileName)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun requestImageArtifactLine(include: ChatInclude): String? {
        val client = ai ?: return null
        val lineModel = model.ifBlank { preferences?.getModel() ?: "" }
        if (lineModel.isBlank()) return null

        return try {
            withContext(Dispatchers.IO) {
                val file = ImageImporter.imageFile(this@ChatActivity, chatId, include)
                if (file == null || !file.exists()) return@withContext null

                val bytes = file.readBytes()
                val mime = include.imageMimeType ?: "image/jpeg"
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)

                val parts = ArrayList<ContentPart>()
                parts.add(TextPart(
                    "Create a very short reminder of this image for future AI requests " +
                    "after the image is removed. State what the image showed and its " +
                    "general subject or purpose. Include at most one or two especially " +
                    "important details. Use no more than three short sentences. " +
                    "Reply with the reminder only.\n\nFile name: ${include.fileName}"
                ))
                parts.add(ImagePart("data:$mime;base64,$encoded"))

                val request = ChatCompletionRequest(
                    model = ModelId(lineModel),
                    maxTokens = IncludeAuxiliaryRequestPolicy.ARTIFACT_MAX_TOKENS,
                    messages = listOf(
                        ChatMessage(role = ChatRole.User, content = parts)
                    )
                )
                val raw = client.chatCompletion(request)
                    .choices.firstOrNull()?.message?.content
                IncludeTextPolicy.sanitizeArtifactLine(raw, include.fileName)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun editInclude(include: ChatInclude) {
        val text = when (include.form) {
            IncludeForm.ARTIFACT -> include.modelText()
            IncludeForm.CONDENSED -> include.condensedText ?: include.fullText
            IncludeForm.FULL -> include.fullText
        }
        IncludeEditDialog.show(this, include.fileName, text) { edited ->
            val latest = findIncludeById(include.id) ?: return@show
            updateInclude(
                when (latest.form) {
                    IncludeForm.ARTIFACT -> latest.copy(artifactLine = edited)
                    else -> latest.withCondensedText(edited)
                }
            )
        }
    }

    private fun ChatInclude.withCondensedText(text: String): ChatInclude {
        return copy(
            form = IncludeForm.CONDENSED,
            condensedText = text
        )
    }

    /** Condense automatically replaces the model-facing full text with Cliff Notes. */
    private fun condenseInclude(include: ChatInclude) {
        val latest = findIncludeById(include.id) ?: return
        if (latest.form != IncludeForm.FULL) return
        if (latest.kind.isImage()) {
            reduceInclude(latest)
            return
        }
        if (condenseJob?.isActive == true) return

        if (preferences?.getNeverShowCondenseHint() == true) {
            startCondensing(latest)
        } else {
            showCondenseHint(latest)
        }
    }

    private fun showCondenseHint(include: ChatInclude) {
        val view = layoutInflater.inflate(R.layout.dialog_include_condense_hint, null)
        val condense = view.findViewById<MaterialButton>(R.id.btn_dialog_primary_action)
        val cancel = view.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action)
        val neverShow = view.findViewById<MaterialCheckBox>(R.id.include_condense_never_show)
        condense?.setText(R.string.include_action_condense)
        cancel?.setText(R.string.include_edit_cancel)

        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.include_condense_title)
            .setView(view)
            .setCancelable(true)
            .create()

        neverShow?.setOnCheckedChangeListener { _, checked ->
            preferences?.setNeverShowCondenseHint(checked)
        }
        cancel?.setOnClickListener { dialog.dismiss() }
        condense?.setOnClickListener {
            dialog.dismiss()
            val latest = findIncludeById(include.id) ?: return@setOnClickListener
            if (latest.form == IncludeForm.FULL) startCondensing(latest)
        }
        dialog.show()
    }

    private fun startCondensing(include: ChatInclude) {
        if (condenseJob?.isActive == true) return

        val view = layoutInflater.inflate(R.layout.dialog_include_condense_progress, null)
        val spinner = view.findViewById<CircularProgressIndicator>(R.id.include_condense_progress)
        val status = view.findViewById<TextView>(R.id.include_condense_status)
        val okay = view.findViewById<MaterialButton>(R.id.btn_dialog_action)
        okay?.setText(R.string.okay)
        okay?.visibility = View.GONE

        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(include.fileName)
            .setView(view)
            .setCancelable(false)
            .create()
        condenseDialog = dialog
        okay?.setOnClickListener {
            dialog.dismiss()
            if (condenseDialog === dialog) condenseDialog = null
        }
        dialog.show()

        val sourceText = include.fullText
        condenseJob = CoroutineScope(Dispatchers.Main).launch {
            val result = requestCondensedText(include)
            if (isFinishing || isDestroyed || condenseDialog !== dialog) return@launch

            val condensed = result.getOrNull()?.trim().orEmpty()
            val sourceTokens = IncludeTextPolicy.estimateTokens(sourceText)
            val condensedTokens = IncludeTextPolicy.estimateTokens(condensed)
            val latest = findIncludeById(include.id)
            val stillCurrent = latest?.form == IncludeForm.FULL &&
                    latest.fullText == sourceText

            val completionMessage = when {
                result.isFailure -> {
                    result.exceptionOrNull()?.let { error ->
                        val classified = GenerationErrorClassifier.classify(error)
                        logGenerationError(classified, error, "document condense")
                    }
                    getString(R.string.include_condense_failed)
                }
                condensed.isBlank() || condensedTokens >= sourceTokens ->
                    getString(R.string.include_condense_not_shorter)
                !stillCurrent ->
                    getString(R.string.include_condense_failed)
                else -> {
                    updateInclude(latest!!.withCondensedText(condensed))
                    getString(R.string.include_condense_complete)
                }
            }

            spinner?.visibility = View.GONE
            status?.text = completionMessage
            okay?.visibility = View.VISIBLE
            condenseJob = null
        }
    }

    /**
     * Asks the selected endpoint/model to make the Cliff Notes. The request
     * includes the configured output ceiling but does not invent a percentage
     * target based on the source length.
     */
    private suspend fun requestCondensedText(include: ChatInclude): Result<String> {
        val client = ai
            ?: return Result.failure(IllegalStateException("No selected AI endpoint"))
        val condenseModel = model.ifBlank { preferences?.getModel() ?: "" }
        if (condenseModel.isBlank()) {
            return Result.failure(IllegalStateException("No selected model"))
        }
        val outputLimit = preferences?.getMaxTokens() ?: 1500

        return try {
            val text = withContext(Dispatchers.IO) {
                val spec = IncludeAuxiliaryRequestPolicy.condense(
                    include = include,
                    selectedModel = condenseModel,
                    configuredMaxTokens = outputLimit
                )
                val request = ChatCompletionRequest(
                    model = ModelId(spec.model),
                    maxTokens = spec.maxTokens,
                    messages = listOf(
                        ChatMessage(
                            role = ChatRole.User,
                            content = spec.prompt
                        )
                    )
                )
                client.chatCompletion(request).choices.firstOrNull()?.message?.content?.trim()
            }
            if (text.isNullOrBlank()) {
                Result.failure(IllegalStateException("Condense returned no text"))
            } else {
                Result.success(text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun reduceInclude(include: ChatInclude) {
        if (reduceJob?.isActive == true) return

        if (preferences?.getNeverShowReduceHint() == true) {
            startReducing(include)
        } else {
            showReduceHint(include)
        }
    }

    private fun showReduceHint(include: ChatInclude) {
        val view = layoutInflater.inflate(R.layout.dialog_include_reduce_hint, null)
        val reduce = view.findViewById<MaterialButton>(R.id.btn_dialog_primary_action)
        val cancel = view.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action)
        val neverShow = view.findViewById<MaterialCheckBox>(R.id.include_reduce_never_show)
        reduce?.setText(R.string.include_action_reduce)
        cancel?.setText(R.string.include_edit_cancel)

        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.include_reduce_title)
            .setView(view)
            .setCancelable(true)
            .create()

        neverShow?.setOnCheckedChangeListener { _, checked ->
            preferences?.setNeverShowReduceHint(checked)
        }
        cancel?.setOnClickListener { dialog.dismiss() }
        reduce?.setOnClickListener {
            dialog.dismiss()
            val latest = findIncludeById(include.id) ?: return@setOnClickListener
            if (latest.form == IncludeForm.FULL && latest.kind.isImage()) startReducing(latest)
        }
        dialog.show()
    }

    private fun startReducing(include: ChatInclude) {
        if (reduceJob?.isActive == true) return

        val view = layoutInflater.inflate(R.layout.dialog_include_condense_progress, null)
        val spinner = view.findViewById<CircularProgressIndicator>(R.id.include_condense_progress)
        val status = view.findViewById<TextView>(R.id.include_condense_status)
        val okay = view.findViewById<MaterialButton>(R.id.btn_dialog_action)
        okay?.setText(R.string.okay)
        okay?.visibility = View.GONE

        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(include.fileName)
            .setView(view)
            .setCancelable(false)
            .create()
        reduceDialog = dialog
        okay?.setOnClickListener {
            dialog.dismiss()
            if (reduceDialog === dialog) reduceDialog = null
        }
        status?.setText(R.string.include_reduce_working)
        dialog.show()

        reduceJob = CoroutineScope(Dispatchers.Main).launch {
            val result = requestReducedText(include)
            if (isFinishing || isDestroyed || reduceDialog !== dialog) return@launch

            val reduced = result.getOrNull()?.trim().orEmpty()
            val latest = findIncludeById(include.id)
            val stillCurrent = latest?.form == IncludeForm.FULL &&
                    latest.kind.isImage() && latest.imageFileHash == include.imageFileHash

            val completionMessage = when {
                result.isFailure -> {
                    result.exceptionOrNull()?.let { error ->
                        val classified = GenerationErrorClassifier.classify(error)
                        logGenerationError(classified, error, "image reduce")
                    }
                    getString(R.string.include_reduce_failed)
                }
                reduced.isBlank() ->
                    getString(R.string.include_reduce_failed)
                !stillCurrent ->
                    getString(R.string.include_reduce_failed)
                else -> {
                    updateInclude(
                        latest!!.withCondensedText(reduced).withoutImageBytes()
                    )
                    maybeDeleteImageBytes(include)
                    getString(R.string.include_condense_complete)
                }
            }

            spinner?.visibility = View.GONE
            status?.text = completionMessage
            okay?.visibility = View.VISIBLE
            reduceJob = null
        }
    }

    private fun accompanyingUserMessage(includeId: String): String {
        for (message in messages) {
            if (message["isBot"] == true) continue
            val includes = includesOf(message)
            if (includes.any { it.id == includeId }) {
                return message["message"]?.toString().orEmpty()
            }
        }
        return ""
    }

    private suspend fun requestReducedText(include: ChatInclude): Result<String> {
        val client = ai
            ?: return Result.failure(IllegalStateException("No selected AI endpoint"))
        val reduceModel = model.ifBlank { preferences?.getModel() ?: "" }
        if (reduceModel.isBlank()) {
            return Result.failure(IllegalStateException("No selected model"))
        }
        val outputLimit = preferences?.getMaxTokens() ?: 1500
        val userText = accompanyingUserMessage(include.id)

        return try {
            val text = withContext(Dispatchers.IO) {
                val spec = IncludeAuxiliaryRequestPolicy.reduceImage(
                    include = include,
                    accompanyingUserMessage = userText,
                    selectedModel = reduceModel,
                    configuredMaxTokens = outputLimit
                )
                val file = ImageImporter.imageFile(this@ChatActivity, chatId, include)
                if (file == null || !file.exists()) {
                    error("Image file missing for include ${include.id}")
                }
                val parts = ArrayList<ContentPart>()
                parts.add(TextPart(spec.prompt))
                val bytes = file.readBytes()
                val mime = include.imageMimeType ?: "image/jpeg"
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                parts.add(ImagePart("data:$mime;base64,$encoded"))

                val request = ChatCompletionRequest(
                    model = ModelId(spec.model),
                    maxTokens = spec.maxTokens,
                    messages = listOf(
                        ChatMessage(role = ChatRole.User, content = parts)
                    )
                )
                client.chatCompletion(request).choices.firstOrNull()?.message?.content?.trim()
            }
            if (text.isNullOrBlank()) {
                Result.failure(IllegalStateException("Reduce returned no text"))
            } else {
                Result.success(text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun consumePendingIncludesForSend(): List<ChatInclude> {
        if (pendingIncludes.isEmpty()) return emptyList()
        val sent = pendingIncludes.map { it.forSentMessage() }
        pendingIncludes = arrayListOf()
        return sent
    }

    /** Opens the system image picker, filtered to JPEG, PNG and HEIC. HEIC
     *  is converted to JPEG at import time. Any other file the user
     *  navigates to is refused by [ImageImporter] with the approved dialog. */
    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, ImageImporter.PICKER_MIME_TYPES)
        }
        imageIntentLauncher.launch(intent)
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
            openImagePicker()
        }

        btnVisionActionCamera?.setOnClickListener {
            visionActions?.visibility = View.GONE
            val intent = Intent(this, CameraPermissionActivity::class.java).setAction(Intent.ACTION_VIEW)
            permissionResultLauncherCamera.launch(intent)
        }

        btnVisionActionDocument?.setOnClickListener {
            visionActions?.visibility = View.GONE
            openDocumentPicker()
        }

        messageInput?.setOnKeyListener { v, keyCode, event -> run {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (keyCode == KeyEvent.KEYCODE_ENTER && event.isShiftPressed && isHardKB() && preferences!!.getDesktopMode()) {
                        (v as EditText).append("\n")
                        return@run true
                    }

                    if (keyCode == KeyEvent.KEYCODE_ENTER && isHardKB() && preferences!!.getDesktopMode()) {
                        prepareTypedTurn((v as EditText).text.toString())
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

        btnQuickSettings?.setOnClickListener {
            openSummoningCircle()
        }

        btnDebugLog?.setOnClickListener {
            startActivity(
                Intent(this, LogsActivity::class.java)
                    .putExtra("type", "event")
                    .putExtra("chatId", chatId)
            )
        }
        updateDebugLogButtonVisibility()
        initSummarizer()
    }

    /* ==================== Conversation summarizer ====================
     * conversation-summary-plan.md §5 + conversation-summary-errors.md.
     * Transmission is bookmark-based (decision 15): each regular request
     * sends the summary as its own system message plus every message after
     * the fold-in bookmark, so a failing summarizer only ever makes requests
     * temporarily larger — never blocks or drops content. Scope is regular
     * chat requests only (decision 12): the Playground, image generation,
     * and the function-calling / fine-tuned-model paths keep full history.
     */

    private fun initSummarizer() {
        seedSummarizerToggle()
        if (summarizerController == null) {
            summarizerController = org.teslasoft.assistant.util.summarizer.SummarizerController(
                applicationContext
            ) { chatId }.also { controller ->
                controller.listener = object :
                    org.teslasoft.assistant.util.summarizer.SummarizerController.Listener {
                    override fun onSummarizerStateChanged() {
                        runOnUiThread { refreshSummarizerIcons() }
                    }

                    override fun onSummarizerErrorEpisode() {
                        playSummarizerErrorSignal()
                    }
                }
            }
        }
        btnSummary?.setOnClickListener { showSummaryView() }
        btnSummarizerErrors?.setOnClickListener { showSummarizerErrorsDialog() }
        refreshSummarizerIcons()
        // The next eligible cycle (errors doc §3): opening the chat retries
        // pending fold-ins and runs catch-up after a re-enable.
        summarizerCycle()
    }

    /**
     * Stamps the per-chat Use Summarizer value once, so flipping the global
     * "Use Summarizer for New Chats" default later never silently changes
     * what an EXISTING chat sends (decision 2 + §4.6). A chat is "new" here
     * while it has no messages yet.
     */
    private fun seedSummarizerToggle() {
        if (chatId.isEmpty() || chatStorageUnavailable) return
        if (preferences?.getChatUseSummarizerRaw().orEmpty().isNotEmpty()) return
        val enable = messages.isEmpty() &&
            preferences?.getSummarizerOnForNewChats() == true &&
            org.teslasoft.assistant.util.summarizer.SummarizerController.isConfigured(this)
        preferences?.setChatUseSummarizerRaw(if (enable) "true" else "false")
    }

    /** data_alert (with count badge) while the error log has entries;
     *  subject while the summarizer is on for this chat (decisions 11/16). */
    private fun refreshSummarizerIcons() {
        val summarizerOn = preferences?.getChatUseSummarizer() == true
        btnSummary?.visibility = if (summarizerOn) View.VISIBLE else View.GONE

        val errors = org.teslasoft.assistant.util.summarizer.SummarizerErrorLog
            .fromJson(preferences?.getSummarizerErrors())
        if (errors.isEmpty()) {
            btnSummarizerErrors?.visibility = View.GONE
            summarizerErrorBadge?.visibility = View.GONE
        } else {
            btnSummarizerErrors?.visibility = View.VISIBLE
            summarizerErrorBadge?.visibility = View.VISIBLE
            summarizerErrorBadge?.text = errors.size.toString()
        }
    }

    /** True when this chat's requests use summarizer transmission at all —
     *  the excluded paths (decision 12) always send full history. The old
     *  Function Calling exclusion is gone with the feature
     *  (image-generation-rebuild-plan.md §15): those chats now follow the
     *  normal summarizer rules like any other chat. */
    private fun summarizerTransmissionActive(): Boolean =
        preferences?.getChatUseSummarizer() == true &&
            !model.contains(":ft") && !model.contains("ft:")

    /** The summary's own system message (decision 14), or null when nothing
     *  is injected. Sent as the very last injected item before the oldest
     *  full message; the user's stored words are never mixed with it. */
    private fun summarizerInjectionText(): String? {
        if (!summarizerTransmissionActive()) return null
        val summary = preferences?.getSummarizerSummary().orEmpty()
        if (summary.isBlank()) return null
        return getString(R.string.summarizer_injection_header) + "\n\n" + summary
    }

    /**
     * The model-facing history AFTER the fold-in bookmark, built from the
     * stored messages with the same projection rules as
     * [rebuildModelProjection] (blank-content messages skipped). Null when
     * summarizer transmission is off or nothing is folded yet — callers then
     * use the full projection unchanged.
     */
    private fun summarizerTrimmedHistory(): Pair<List<ChatMessage>, List<String?>>? {
        if (!summarizerTransmissionActive()) return null
        val folded = (preferences?.getSummarizerFoldedCount() ?: 0).coerceAtMost(messages.size)
        if (folded <= 0) return null
        val msgs = ArrayList<ChatMessage>()
        val includes = ArrayList<String?>()
        for (i in folded until messages.size) {
            val message = messages[i]
            val content = modelFacingContent(message)
            if (content.isBlank()) continue
            msgs.add(
                ChatMessage(
                    role = if (message["isBot"] == true) ChatRole.Assistant else ChatRole.User,
                    content = content
                )
            )
            includes.add(if (message["isBot"] != true) message[INCLUDES_KEY]?.toString() else null)
        }
        return Pair(msgs, includes)
    }

    /** One snapshot entry per stored message so indexes stay aligned with
     *  the fold-in bookmark; blank entries advance it without being sent. */
    private fun summarizerSnapshot(): org.teslasoft.assistant.util.summarizer.SummarizerController.Snapshot? {
        if (isFinishing || isDestroyed || chatStorageUnavailable || chatId.isEmpty()) return null
        val entries = messages.map {
            org.teslasoft.assistant.util.summarizer.SummarizerController.Entry(
                isBot = it["isBot"] == true,
                text = modelFacingContent(it)
            )
        }
        return org.teslasoft.assistant.util.summarizer.SummarizerController.Snapshot(
            entries,
            preferences?.getChatSummarizerWindow() ?: 20
        )
    }

    /** Image ids whose summary call is in flight, so a repeat pass (each
     *  turn, each resume) never starts a second call for the same image. */
    private val imageSummaryInFlight = HashSet<String>()

    /**
     * Give every completed generated image a token-saving summary, silently.
     * Independent of the conversation-summarizer toggle: whenever a Summary
     * Model is configured, each image whose prompt has no summary and no user
     * edit yet is summarized once. A failure is quiet — the image keeps
     * sending its full prompt and the next turn or resume tries again (owner
     * request, Aug 16 2026).
     */
    private fun ensureImageSummaries() {
        val controller = summarizerController ?: return
        if (!org.teslasoft.assistant.util.summarizer.SummarizerController.isConfigured(this)) return
        val targets = messages.mapNotNull { msg ->
            if (msg["isBot"] != true) return@mapNotNull null
            val meta = GeneratedImageMetadata.fromJson(msg[GeneratedImageMetadata.KEY]?.toString())
                ?: return@mapNotNull null
            if (meta.status != GeneratedImageMetadata.STATUS_COMPLETE) return@mapNotNull null
            if (meta.prompt.isBlank()) return@mapNotNull null
            if (meta.effectiveSummary() != null) return@mapNotNull null
            if (meta.imageId in imageSummaryInFlight) return@mapNotNull null
            meta
        }
        for (meta in targets) {
            imageSummaryInFlight.add(meta.imageId)
            lifecycleScope.launch {
                val summary = controller.summarizeImagePrompt(meta.prompt)
                imageSummaryInFlight.remove(meta.imageId)
                if (!summary.isNullOrBlank()) applyImageSummary(meta.imageId, summary)
            }
        }
    }

    /** Store a freshly made image summary on its message, unless a user edit
     *  or another summary landed meanwhile, then refresh the row and the
     *  model projection. */
    private fun applyImageSummary(imageId: String, summary: String) {
        val index = messages.indexOfFirst {
            GeneratedImageMetadata.fromJson(it[GeneratedImageMetadata.KEY]?.toString())?.imageId == imageId
        }
        if (index < 0) return
        val meta = GeneratedImageMetadata.fromJson(messages[index][GeneratedImageMetadata.KEY]?.toString())
            ?: return
        if (meta.effectiveSummary() != null) return
        messages[index][GeneratedImageMetadata.KEY] = meta.withImageSummary(summary).toJson()
        saveSettings()
        rebuildModelProjection()
        adapter?.notifyItemChanged(index)
    }

    /** Runs a fold-in cycle when the summarizer is on for this chat. [force]
     *  (Update Now) also folds the final partial batch; automatic cycles
     *  wait for a full batch so provider prompt caching keeps applying. */
    private fun summarizerCycle(force: Boolean = false) {
        if (preferences?.getChatUseSummarizer() != true) return
        summarizerController?.runCycle(force) { summarizerSnapshot() }
    }

    /** Summary view (decision 11): the editable summary and Update Now.
     *  Edits save automatically when the view closes; Update Now saves them
     *  first, then folds everything up to the current window edge. */
    private fun showSummaryView() {
        val view = layoutInflater.inflate(R.layout.dialog_summary_view, null)
        val field = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.field_summary_text)
        val update = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_dialog_action)
        update?.setText(R.string.summarizer_update_now)
        field?.setText(preferences?.getSummarizerSummary().orEmpty())

        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.summarizer_summary_title)
            .setView(view)
            .create()

        fun saveEditsIfChanged() {
            val edited = field?.text?.toString().orEmpty()
            if (edited != preferences?.getSummarizerSummary().orEmpty()) {
                preferences?.commitSummarizerSummaryEdit(edited)
            }
        }
        dialog.setOnDismissListener { saveEditsIfChanged() }
        update?.setOnClickListener {
            saveEditsIfChanged()
            dialog.dismiss()
            summarizerCycle(force = true)
        }
        dialog.show()
    }

    /** Summarizer Errors dialog (decision 16 + errors doc §1): one status
     *  paragraph, the stored entries newest first, then Copy and Delete. */
    private fun showSummarizerErrorsDialog() {
        val entries = org.teslasoft.assistant.util.summarizer.SummarizerErrorLog
            .fromJson(preferences?.getSummarizerErrors())
        if (entries.isEmpty()) {
            refreshSummarizerIcons()
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_summarizer_errors, null)
        val status = view.findViewById<TextView>(R.id.summarizer_errors_status)
        val container = view.findViewById<LinearLayout>(R.id.summarizer_errors_container)
        val copy = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_dialog_primary_action)
        val delete = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_dialog_destructive_action)
        copy?.setText(R.string.summarizer_errors_copy)
        delete?.setText(R.string.summarizer_errors_delete)

        val statusText = when {
            preferences?.getChatUseSummarizer() != true ->
                getString(R.string.summarizer_errors_status_off)
            preferences?.getSummarizerEpisode().orEmpty().isNotEmpty() ->
                getString(R.string.summarizer_errors_status_behind)
            else ->
                getString(R.string.summarizer_errors_status_caught_up)
        }
        status?.text = statusText

        for (entry in entries) {
            val row = layoutInflater.inflate(R.layout.view_summarizer_error_entry, container, false)
            row.findViewById<TextView>(R.id.summarizer_error_entry_text).text =
                org.teslasoft.assistant.util.summarizer.SummarizerErrorMessages.renderEntry(this, entry)
            container?.addView(row)
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.summarizer_errors_title)
            .setView(view)
            .create()

        copy?.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val text = org.teslasoft.assistant.util.summarizer.SummarizerErrorMessages
                .renderLog(this, statusText, entries)
            clipboard.setPrimaryClip(ClipData.newPlainText("Summarizer Errors", text))
        }
        delete?.setOnClickListener {
            preferences?.setSummarizerErrors("")
            preferences?.setSummarizerEpisode("")
            refreshSummarizerIcons()
            dialog.dismiss()
        }
        dialog.show()
    }

    /**
     * The dedicated summarizer error sound (decision 16): plays once at the
     * start of a failure episode, so the user notices without looking.
     * Distinct from every other app cue — two short mid pulses then one
     * longer low note (C5, C5, F4) — where the database warning warbles
     * D5/Bb4, the generation-error cadence descends three notes, no-speech
     * is two low notes, and the done chime ascends. Same alarm-stream
     * routing so it stays audible on silent.
     */
    private fun playSummarizerErrorSignal() {
        Thread {
            var track: AudioTrack? = null
            try {
                val sampleRate = 44100
                val notes = floatArrayOf(523.25f, 523.25f, 349.23f)
                val noteDurationsMs = intArrayOf(110, 110, 260)
                val gapMs = 50
                var totalSamples = 0
                for (durationMs in noteDurationsMs) {
                    totalSamples += sampleRate * (durationMs + gapMs) / 1000
                }
                val buffer = ShortArray(totalSamples)

                var idx = 0
                for (n in notes.indices) {
                    val samplesPerNote = sampleRate * noteDurationsMs[n] / 1000
                    val samplesPerGap = sampleRate * gapMs / 1000
                    for (i in 0 until samplesPerNote) {
                        val t = i.toDouble() / sampleRate
                        val envelope = when {
                            i < samplesPerNote * 0.1 -> i / (samplesPerNote * 0.1)
                            i > samplesPerNote * 0.8 -> (samplesPerNote - i) / (samplesPerNote * 0.2)
                            else -> 1.0
                        }
                        val sample = Math.sin(2.0 * Math.PI * notes[n] * t) * envelope * 0.45 * Short.MAX_VALUE
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

                var totalMs = 150
                for (durationMs in noteDurationsMs) totalMs += durationMs + gapMs
                Thread.sleep(totalMs.toLong())
                track.stop()
            } catch (_: Exception) {
                // The cue must never interfere with the chat or the log entry.
            } finally {
                try { track?.release() } catch (_: Exception) { /* ignore */ }
            }
        }.start()
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

    override fun onGeneratedImageSaveClick(dataUrl: String, mimeType: String) {
        lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                try {
                    val encoded = dataUrl.substringAfter(";base64,", "")
                    if (encoded.isBlank()) return@withContext null
                    val normalizedMime =
                        mimeType.takeIf { it.startsWith("image/") } ?: "image/png"
                    val extension = when (normalizedMime.substringAfter('/')) {
                        "jpeg" -> "jpg"
                        "webp" -> "webp"
                        else -> "png"
                    }
                    Triple(
                        Base64.decode(encoded, Base64.DEFAULT),
                        normalizedMime,
                        extension
                    )
                } catch (_: Exception) {
                    null
                }
            }
            if (prepared == null) {
                Toast.makeText(
                    this@ChatActivity,
                    R.string.image_gen_save_failed,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            fileContents = prepared.first
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = prepared.second
                putExtra(Intent.EXTRA_TITLE, "generated-image.${prepared.third}")
                putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    (Environment.getExternalStorageDirectory().path +
                        "/Pictures/SpeakGPT/generated-image.${prepared.third}").toUri()
                )
            }
            fileSaveIntentLauncher.launch(intent)
        }
    }

    /** The user saved an edited image summary in the prompt box. Store it on
     *  the message's record so it becomes both what the box shows and what the
     *  model receives instead of the full prompt. */
    override fun onImageSummaryEdited(position: Int, editedSummary: String?) {
        if (position < 0 || position >= messages.size) return
        val meta = GeneratedImageMetadata.fromJson(
            messages[position][GeneratedImageMetadata.KEY]?.toString()
        ) ?: return
        messages[position][GeneratedImageMetadata.KEY] =
            meta.withSummaryEdited(editedSummary).toJson()
        saveSettings()
        rebuildModelProjection()
        adapter?.notifyItemChanged(position)
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

    /**
     * Text to Speech lifecycle diagnostic (owner instruction, Aug 16 2026):
     * one short line per TTS event — requested / onStart / onDone / onError /
     * skipped — for every turn where readback is expected, so a completed
     * reply that was never read aloud can be diagnosed: never reached
     * pronounce(), requested but never started, started then failed, or
     * completed normally (pointing at audio routing/output instead). Gated
     * only on its own recording toggle, independent of the VAD-logging
     * toggles [voiceDiagnosticsEnabled] gates — the point is to catch a rare,
     * unreproducible failure, so it must not depend on a separate diagnostics
     * setting also being on. Never includes the text being spoken.
     */
    private fun logTtsLifecycle(event: String) {
        if (preferences?.getTtsLifecycleLogging() != true) return
        val turn = currentLifecycleTurnId.ifBlank { "none" }
        try {
            org.teslasoft.assistant.preferences.Logger.logAsync(
                this, "tts_lifecycle", "TTS", "info", "$event turn=$turn"
            )
        } catch (_: Throwable) { /* diagnostics must never disturb readback */ }
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

    /** The saved favorite for [model] on the active endpoint, or null. */
    private fun favoriteForActiveEndpoint(model: String): org.teslasoft.assistant.preferences.dto.FavoriteModelObject? {
        val endpoint = apiEndpointObject ?: return null
        return FavoriteModelsPreferences.getPreferences(this).getFavorite(model, endpoint.id)
    }

    /**
     * Brand-new chat only: adopt the provider/model/routing that the last
     * conversation successfully used (owner spec, Aug 8 2026). Runs at most once
     * per chat and never touches a chat that already has messages, so a later
     * choice always wins. When the saved local setup is gone it records an
     * outcome for [handleProviderRestoreOutcome]; it never claims the model is
     * unavailable — that is decided only from the provider's own response on send.
     */
    private fun maybeRestoreProviderForNewChat(historyResult: ChatPreferences.ChatHistoryResult) {
        if (!historyResult.messages.isNullOrEmpty()) return
        if (preferences?.isProviderSeeded() == true) return
        preferences?.setProviderSeeded(true)

        val endpointId = preferences?.getLastSuccessfulEndpointId().orEmpty()
        val lastModel = preferences?.getLastSuccessfulModel().orEmpty()
        val routing = preferences?.getLastSuccessfulRouting() ?: FavoriteModelObject.ROUTING_AUTOMATIC

        // A deleted provider profile reads back with a blank host; a favorite is
        // present only while its (model, endpoint) star exists.
        val endpointExists = endpointId.isNotBlank() &&
            (apiEndpointPreferences?.getApiEndpoint(this, endpointId)?.host?.isNotBlank() == true)
        val favoriteExists = endpointId.isNotBlank() && lastModel.isNotBlank() &&
            FavoriteModelsPreferences.getPreferences(this).getFavorite(lastModel, endpointId) != null

        when (NewChatProviderRestore.decide(endpointId, lastModel, routing, endpointExists, favoriteExists)) {
            NewChatProviderRestore.Outcome.RESTORE -> {
                preferences?.setApiEndpointId(endpointId)
                preferences?.setModel(lastModel)
                apiEndpointObject = apiEndpointPreferences?.getApiEndpoint(this, endpointId)
            }
            // Surfaced once the UI exists (initSettings tail).
            NewChatProviderRestore.Outcome.MISSING_CONFIG ->
                providerRestoreOutcome = NewChatProviderRestore.Outcome.MISSING_CONFIG
            NewChatProviderRestore.Outcome.NO_CONFIG ->
                providerRestoreOutcome = NewChatProviderRestore.Outcome.NO_CONFIG
        }
    }

    /** Act on a brand-new chat's restore outcome now that the chat UI exists:
     *  a missing local setup gets the configuration dialog then the Summoning
     *  Circle; nothing ever recorded goes to the API Endpoints screen. */
    private fun handleProviderRestoreOutcome() {
        val outcome = providerRestoreOutcome ?: return
        providerRestoreOutcome = null
        when (outcome) {
            NewChatProviderRestore.Outcome.MISSING_CONFIG -> showConfigMissingDialog()
            NewChatProviderRestore.Outcome.NO_CONFIG -> openApiEndpointsScreen()
            NewChatProviderRestore.Outcome.RESTORE -> { /* applied already */ }
        }
    }

    /** Missing local configuration (provider profile deleted, or Only/Preferred
     *  routing whose favorite is gone). Describes it as missing configuration —
     *  never as the model being unavailable. Okay opens the Summoning Circle;
     *  there is nothing to cancel, so the dialog has no Cancel. */
    private fun showConfigMissingDialog() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setMessage(R.string.new_chat_config_missing)
            .setCancelable(false)
            .setPositiveButton(R.string.okay) { _, _ -> openSummoningCircle() }
            .show()
    }

    /** The provider itself returned a definite model-not-found on send. Okay
     *  opens the Summoning Circle. */
    private fun showModelUnavailableDialog() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setMessage(R.string.new_chat_model_unavailable)
            .setCancelable(false)
            .setPositiveButton(R.string.okay) { _, _ -> openSummoningCircle() }
            .show()
    }

    /** Open the Summoning Circle (Quick Settings) sheet for this chat. Shared by
     *  the header's Quick Settings icon and the recovery dialogs above. */
    private fun openSummoningCircle() {
        if (isFinishing || isDestroyed) return
        val sheet = QuickSettingsBottomSheetDialogFragment
            .newInstance(chatId, usageIn, usageOut, priceIn, priceOut)
        sheet.setOnUpdateListener(object : QuickSettingsBottomSheetDialogFragment.OnUpdateListener {
            override fun onUpdate() {
                refreshCompanionAvatar()
                refreshUserAvatar()
            }

            override fun onForceUpdate() {
                startActivity(Intent(this@ChatActivity, ChatActivity::class.java).putExtra("chatId", chatId).putExtra("name", chatName).setAction(Intent.ACTION_VIEW))
                finishActivity()
            }
        })
        sheet.show(supportFragmentManager, "QuickSettingsBottomSheetDialogFragment")
    }

    /** Applies a user-edited title from [EditChatTitleDialog]. Reuses the same
     *  atomic rename path as auto-naming (ChatPreferences.editChat /
     *  ChatRenameTransaction — moves history and copies every per-chat
     *  settings key, never re-derives them) and the same [renameInProgress]
     *  guard, so a manual rename and an in-flight auto-name rename can never
     *  overlap. */
    private fun renameChatTitle(newTitle: String) {
        if (newTitle == chatName || renameInProgress) return

        val chatPreferences = ChatPreferences.getChatPreferences()
        if (chatPreferences.checkDuplicate(this, newTitle)) {
            Toast.makeText(this, R.string.chat_error_unique, Toast.LENGTH_SHORT).show()
            return
        }

        val oldName = chatName
        renameInProgress = true
        lifecycleScope.launch {
            val renamed = try {
                withContext(Dispatchers.IO) {
                    chatPreferences.editChat(this@ChatActivity, newTitle, oldName)
                }
            } catch (e: Exception) {
                false
            } finally {
                renameInProgress = false
            }

            if (isFinishing || isDestroyed) return@launch

            if (renamed) {
                val previousChatId = chatId
                chatId = Hash.hash(newTitle)
                ImageGenerationJobRegistry.rename(previousChatId, chatId)
                chatName = newTitle
                preferences = Preferences.getPreferences(this@ChatActivity, chatId)
                intent.putExtra("chatId", chatId)
                intent.putExtra("name", chatName)
                activityTitle?.text = newTitle
            } else {
                MaterialAlertDialogBuilder(this@ChatActivity, R.style.App_MaterialAlertDialog)
                    .setTitle(R.string.title_rename_failed)
                    .setMessage(R.string.msg_rename_failed)
                    .setPositiveButton(R.string.btn_ok) { _, _ -> }
                    .show()
            }
        }
    }

    /** Open the API Endpoints screen so the user can set up a provider + model
     *  (used when nothing has ever produced a successful reply — never a
     *  hardcoded default model). */
    private fun openApiEndpointsScreen() {
        if (isFinishing || isDestroyed) return
        startActivity(Intent(this, ApiEndpointsListActivity::class.java).setAction(Intent.ACTION_VIEW))
    }

    /**
     * Just-before-send hook body. For a JSON Chat Completions request on an
     * OpenRouter-identity endpoint it either:
     *  - throws [ProviderRoutingBlockedException] when the saved routing cannot
     *    be satisfied (e.g. Only with no usable provider) — so the request is
     *    never silently sent unrestricted; or
     *  - sets the resolved `provider` object on the body, overwriting any
     *    existing one so a re-sent request (tool continuation / retry) never
     *    carries two.
     * While Response Lifecycle logging is active it also opts this OpenRouter
     * request into official response-side router metadata, which reports the
     * endpoint actually marked selected. Every other request BODY (generic
     * endpoint, non-chat body, parse issue, Automatic with no exclusions)
     * remains byte-for-byte unchanged.
     */
    /**
     * Bind lifecycle diagnostics to the exact Ktor request BEFORE dispatch.
     * Auxiliary non-stream requests share this client, so request identity is
     * attached only to streamed chat/legacy-completion bodies.
     */
    private fun bindLifecycleRecorderToGenerationRequest(request: HttpRequestBuilder) {
        if (request.attributes.contains(responseLifecycleRecorderAttribute)) return
        val recorder = currentLifecycle ?: return
        if (recorder.finalized) return
        val content = request.body as? TextContent ?: return
        if (content.contentType?.match(ContentType.Application.Json) != true) return

        val isStreamedGeneration = try {
            val root = com.google.gson.JsonParser.parseString(content.text).asJsonObject
            val streamed = root.get("stream")
                ?.takeUnless { it.isJsonNull }
                ?.asBoolean == true
            val hasGenerationInput = root.has("messages") || root.has("prompt")
            streamed && root.has("model") && hasGenerationInput
        } catch (_: Exception) {
            false
        }
        if (isStreamedGeneration) {
            request.attributes.put(responseLifecycleRecorderAttribute, recorder)
        }
    }

    private fun augmentRequestWithProviderRouting(request: HttpRequestBuilder) {
        if (apiEndpointObject?.isOpenRouterRouting() != true) return
        if (request.attributes.contains(responseLifecycleRecorderAttribute)) {
            // Official OpenRouter audit metadata, delivered on the final stream
            // chunk. This is the response authority for Automatic routing; the
            // app's requested provider settings are never substituted for it.
            request.headers.remove("X-OpenRouter-Metadata")
            request.headers.append("X-OpenRouter-Metadata", "enabled")
        }
        val content = request.body as? TextContent ?: return
        if (content.contentType?.match(ContentType.Application.Json) != true) return
        val text = content.text

        // Only a chat request carries a messages array; skip anything else, and
        // degrade to "do nothing" on any parse failure.
        val model = try {
            val root = com.google.gson.JsonParser.parseString(text).asJsonObject
            if (root.has("messages")) root.get("model")?.takeIf { !it.isJsonNull }?.asString else null
        } catch (_: Exception) {
            null
        } ?: return

        val resolution = ProviderRoutingResolver.resolve(true, favoriteForActiveEndpoint(model))

        // Block BEFORE dispatch — deliberately thrown so the send fails cleanly
        // through the existing error path rather than going out unrestricted.
        if (resolution.block != RoutingBlock.NONE) {
            lastRoutingAttachment = "BLOCKED (not sent)"
            throw ProviderRoutingBlockedException(providerBlockMessage(resolution.block))
        }

        val providerJson = resolution.providerJson
        if (providerJson == null) {
            // OpenRouter, but Automatic / no saved routing — nothing to attach.
            lastRoutingAttachment = "no provider object (Automatic / no saved routing)"
            return
        }
        try {
            val augmented = ProviderRoutingSerializer.augmentBody(text, providerJson)
            request.setBody(TextContent(augmented, content.contentType ?: ContentType.Application.Json))
            // Recorded ONLY after the body was actually replaced.
            lastRoutingAttachment = "provider object attached"
        } catch (_: Exception) {
            // Injection is best-effort; report honestly that it was not confirmed.
            lastRoutingAttachment = "attachment requested (mutation failed)"
        }
    }

    /**
     * The reasoning capability of the active endpoint for [model], layering the
     * §7.7 confidence ladder over this endpoint's persisted capability store.
     */
    private fun reasoningCapabilityForModel(model: String): org.teslasoft.assistant.reasoning.ReasoningCapability =
        org.teslasoft.assistant.reasoning.EndpointReasoningCapability.resolve(
            apiEndpointObject?.reasoningCapabilityByModel, model
        )

    /**
     * The effective reasoning settings for this turn (§7.5/§7.9): the
     * conversation's own override, else this model's favorite default, else
     * Auto — with the §7.8 clamp for an effort the active path no longer
     * supports. Read from the same favorite/conversation storage the UI writes,
     * so every send path (typed, voice, retry) resolves identically.
     */
    private fun resolveReasoningForModel(model: String): org.teslasoft.assistant.reasoning.ResolvedReasoning {
        val capability = reasoningCapabilityForModel(model)
        val favorite = favoriteForActiveEndpoint(model)
        val favoriteEffort = favorite?.let {
            org.teslasoft.assistant.reasoning.ReasoningEffort.fromSerialized(it.reasoningEffort)
        }
        val override = org.teslasoft.assistant.reasoning.ReasoningEffort.fromSerialized(
            preferences?.getReasoningEffortOverride()
        )
        return org.teslasoft.assistant.reasoning.ReasoningSettingsResolver.resolve(
            conversationOverride = override,
            favoriteEffort = favoriteEffort,
            favoriteShowReasoning = favorite?.showReasoning,
            capability = capability
        )
    }

    /**
     * Just-before-send hook that merges this turn's reasoning instruction into
     * the outgoing Chat Completions body (§7.9), using the same body-mutation
     * approach as provider routing so no separate request transport exists. It
     * applies to OpenRouter and generic OpenAI-compatible endpoints alike, in
     * each provider's own field shape. Fully fail-safe: a non-chat body, a
     * parse issue, a non-reasoning model, or Auto with reasoning returned leaves
     * the request byte-for-byte unchanged, so ordinary chat can never break.
     */
    private fun augmentRequestWithReasoning(request: HttpRequestBuilder) {
        val content = request.body as? TextContent ?: return
        if (content.contentType?.match(ContentType.Application.Json) != true) return
        val text = content.text

        var bodyHasTools = false
        val model = try {
            val root = com.google.gson.JsonParser.parseString(text).asJsonObject
            // Only streamed chat generation carries reasoning. Auxiliary
            // non-streamed calls on this same client (auto-naming, tool-name
            // resolution, summarizer) must not inherit a reasoning instruction.
            val streamed = root.get("stream")?.takeUnless { it.isJsonNull }?.asBoolean == true
            if (streamed && root.has("messages")) {
                bodyHasTools = (root.get("tools")?.takeUnless { it.isJsonNull }
                    ?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0) > 0
                root.get("model")?.takeIf { !it.isJsonNull }?.asString
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } ?: return

        val resolved = resolveReasoningForModel(model)
        val capability = reasoningCapabilityForModel(model)
        val isOpenRouter = apiEndpointObject?.isOpenRouterRouting() == true

        // Split THIS stream for reasoning when either is true:
        //  - display is wanted: the path can return visible reasoning and Show
        //    Reasoning is on (Auto still returns reasoning by default); or
        //  - continuation state may be needed: an OpenRouter reasoning path that
        //    is offering tools, so any reasoning_details produced alongside a
        //    tool call are captured to echo back on the follow-up (§7.2) — this
        //    happens even when Show Reasoning is Off.
        val wantDisplay = capability.isReasoningCapable && capability.canReturnVisibleReasoning && resolved.showReasoning
        val wantContinuationState = capability.isReasoningCapable && isOpenRouter && bodyHasTools
        currentTurnShowReasoning = wantDisplay
        if (wantDisplay || wantContinuationState) {
            currentTurnReasoning?.let { request.attributes.put(reasoningObservationAttribute, it) }
            currentTurnReasoningObservationActive = true
        }

        // 1) The reasoning request fields for this turn.
        val afterParam = org.teslasoft.assistant.reasoning.ReasoningRequestSerializer.augmentBody(
            text, resolved, isOpenRouter, capability.isReasoningCapable
        )
        val paramAttached = afterParam != text
        // 2) Reasoning-state continuation (§7.2): on an OpenRouter follow-up that
        //    carries an assistant tool-call message, echo back the reasoning
        //    details captured from the turn that produced the tool call. A no-op
        //    on the primary request (no tool-call assistant message yet) and off
        //    OpenRouter. Independent of Show Reasoning; never surfaced in the UI.
        var newBody = afterParam
        var continuationAttached = false
        if (isOpenRouter) {
            val withState = org.teslasoft.assistant.reasoning.ReasoningContinuationSerializer
                .attachToToolCallMessage(newBody, currentTurnReasoning?.reasoningDetails())
            if (withState != newBody) {
                newBody = withState
                continuationAttached = true
            }
        }

        if (newBody != text) {
            request.setBody(TextContent(newBody, content.contentType ?: ContentType.Application.Json))
            lastReasoningAttachment = buildString {
                append(
                    if (paramAttached) {
                        "reasoning attached (effort=${resolved.effort.serialized}, show=${resolved.showReasoning}, " +
                            "source=${resolved.source}, capability=${capability.source})"
                    } else {
                        "no reasoning param (Auto / provider default)"
                    }
                )
                if (continuationAttached) append("; reasoning_details echoed on tool-call continuation")
                resolved.clampedFrom?.let { append(", dropped unsupported=${it.serialized}") }
            }
        } else {
            lastReasoningAttachment = if (!capability.isReasoningCapable) {
                "no reasoning fields (model not known to reason)"
            } else {
                "no reasoning fields (Auto / provider default)"
            }
        }
    }

    /** Existing user-facing wording for a blocked routing configuration. */
    private fun providerBlockMessage(block: RoutingBlock): String = when (block) {
        RoutingBlock.ONLY_PROVIDER_NOT_SELECTED,
        RoutingBlock.ONLY_PROVIDER_UNAVAILABLE -> getString(R.string.provider_only_mode_error)
        RoutingBlock.NO_PREFERRED_AVAILABLE -> getString(R.string.provider_no_preferred_message)
        RoutingBlock.NONE -> ""
    }

    /**
     * The clearly labeled provider-routing request lines appended to a Response
     * Lifecycle entry. The
     * attachment status is the interceptor's ACTUAL result for this request
     * ([lastRoutingAttachment], set only after a confirmed body replacement),
     * never re-derived from the routing decision. If the send hook never ran,
     * it stays null and the line honestly reads "attachment requested…" rather
     * than claiming the provider object was attached.
     */
    private fun providerRoutingLogLine(model: String): String {
        val isOpenRouter = apiEndpointObject?.isOpenRouterRouting() == true
        val favorite = favoriteForActiveEndpoint(model)
        val status = lastRoutingAttachment ?: "attachment requested (send hook did not run)"
        return "\n" + ProviderRoutingDiagnostics.describe(isOpenRouter, favorite, status)
    }

    /**
     * The reasoning line appended to a Response Lifecycle entry (§7.8): what
     * reasoning instruction was actually written on the outgoing body and the
     * capability source it was resolved from. Reads the send hook's real
     * outcome ([lastReasoningAttachment]); if the hook never ran it says so
     * rather than implying anything was sent.
     */
    private fun reasoningLogLine(): String {
        val status = lastReasoningAttachment ?: "reasoning hook did not run"
        return "\nReasoning: $status"
    }

    /**
     * Attach the reasoning captured from the split stream copy to the reply it
     * belongs to (§7.1/§7.2). Runs from the response observer once the copy has
     * drained; it is the sole writer of the reasoning fields, so it never races
     * the typed stream's own completion/persistence for the answer text. It
     * mutates the reply's in-memory map, so whichever save runs last (this one
     * or the answer's completion save) keeps the reasoning. Reasoning is a
     * separate channel and is never merged into the answer text — so it never
     * reaches TTS and never signals completion. Fully best-effort.
     */
    private fun deliverObservedReasoning(acc: org.teslasoft.assistant.reasoning.ReasoningStreamAccumulator) {
        if (!acc.hasReasoning()) return
        val snapshot = acc.snapshot()
        if (snapshot.text.isBlank()) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val index = messages.indexOfLast { it["isBot"] == true }
            if (index < 0) return@runOnUiThread
            val msg = messages[index]
            msg[ChatAdapter.KEY_MESSAGE_REASONING] = snapshot.text
            msg[ChatAdapter.KEY_MESSAGE_REASONING_SUMMARY] = snapshot.isSummary.toString()
            snapshot.reasoningTokens?.let {
                msg[ChatAdapter.KEY_MESSAGE_REASONING_TOKENS] = it.toString()
            }
            adapter?.notifyItemChanged(index)
            saveSettings()
        }
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

            // Per-endpoint timeouts, user-set on the endpoint profile and already
            // clamped on save (re-coerced here so a legacy/hand-edited value can't
            // slip through). The CONNECT timeout bounds establishing the
            // connection (→ N2 "connection timed out"); the SOCKET timeout bounds
            // waiting for the model's response once connected (→ N4 "response
            // timed out"). They are separate so a slow model can be given minutes
            // to reply without also making an unreachable server hang that long.
            val connectTimeout = ApiEndpointObject.coerceConnectTimeoutSeconds(
                apiEndpointObject?.connectTimeoutSeconds ?: ApiEndpointObject.DEFAULT_CONNECT_TIMEOUT_SECONDS
            ).seconds
            val responseTimeout = ApiEndpointObject.coerceResponseTimeoutSeconds(
                apiEndpointObject?.responseTimeoutSeconds ?: ApiEndpointObject.DEFAULT_RESPONSE_TIMEOUT_SECONDS
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
                timeout = Timeout(connect = connectTimeout, socket = responseTimeout),
                organization = null,
                headers = extraHeaders,
                host = OpenAIHost(composeChatHost(apiEndpointObject?.host, apiEndpointObject?.chatEndpoint)),
                proxy = null,
                retry = RetryStrategy(maxRetries = 0),
                // Capture failed-response detail and, while Response Lifecycle
                // logging is active, tap a split COPY of successful generation
                // streams for official router metadata or an API-reported
                // top-level provider. Ktor's
                // ResponseObserver preserves streaming: the OpenAI-compatible
                // client receives the original channel unchanged and unbuffered.
                httpClientConfig = {
                    install(ResponseObserver) {
                        filter { call ->
                            if (!call.response.status.isSuccess()) {
                                true
                            } else {
                                // Observe a successful generation stream when
                                // lifecycle logging owns a recorder OR this turn
                                // wants provider reasoning displayed. The observer
                                // receives a split copy, so the normal typed
                                // stream is neither buffered nor consumed.
                                val recorder = if (call.request.attributes.contains(responseLifecycleRecorderAttribute)) {
                                    call.request.attributes[responseLifecycleRecorderAttribute]
                                } else {
                                    null
                                }
                                val wantsReasoning = call.request.attributes.contains(reasoningObservationAttribute)
                                when {
                                    recorder != null -> {
                                        recorder.beginProviderObservation()
                                        true
                                    }
                                    wantsReasoning -> true
                                    else -> false
                                }
                            }
                        }
                        onResponse { response ->
                            try {
                                if (!response.status.isSuccess()) {
                                    capturedProviderErrorBody = response.bodyAsText()
                                } else {
                                    val attrs = response.call.request.attributes
                                    val recorder = if (attrs.contains(responseLifecycleRecorderAttribute)) {
                                        attrs[responseLifecycleRecorderAttribute]
                                    } else {
                                        null
                                    }
                                    val reasoningAcc = if (attrs.contains(reasoningObservationAttribute)) {
                                        attrs[reasoningObservationAttribute]
                                    } else {
                                        null
                                    }
                                    if (recorder != null || reasoningAcc != null) {
                                        try {
                                            // Receive the observer's split copy ONCE (a second
                                            // bodyAsChannel() throws and cancels the origin,
                                            // killing the live stream) and drain it to end of
                                            // stream — stopping early stalls Ktor's splitter
                                            // and freezes the visible reply after ~4 KB; see
                                            // consumeObservedStream. One drain feeds both the
                                            // lifecycle inspector and, via lineObserver, the
                                            // reasoning accumulator.
                                            val observedChannel = response.bodyAsChannel()
                                            ReportedProviderParser.consumeObservedStream(
                                                observedChannel,
                                                onProvider = { reported ->
                                                    // Response-derived only. Never substitute the
                                                    // configured or requested provider here.
                                                    recorder?.noteActualModelProvider(reported)
                                                },
                                                lineObserver = reasoningAcc?.let { acc -> { line -> acc.acceptLine(line) } }
                                            )
                                        } finally {
                                            recorder?.finishProviderObservation()
                                            if (reasoningAcc != null) {
                                                // Display only when Show Reasoning wanted it; the
                                                // reasoning_details for a tool-call continuation are
                                                // held in the accumulator regardless.
                                                if (currentTurnShowReasoning) deliverObservedReasoning(reasoningAcc)
                                                // Signal capture complete so a tool continuation can
                                                // echo reasoning_details on its follow-up.
                                                currentTurnReasoningObserved?.complete(Unit)
                                            }
                                        }
                                    }
                                }
                            } catch (_: Exception) { /* best effort; never disturb the failure path */ }
                        }
                    }
                    // OpenRouter provider routing: structurally add the resolved
                    // `provider` object to the outgoing Chat Completions body just
                    // before it is sent. Applies to every request on THIS chat
                    // client — the primary turn, tool-result continuations, and
                    // no-tools retries — so routing carries through all of them.
                    // Fully fail-safe: any error, a non-OpenRouter endpoint, a
                    // non-chat body, or Automatic-with-no-exclusions leaves the
                    // request byte-for-byte unchanged, so normal chat can never be
                    // broken by this hook.
                    install(createClientPlugin("OpenRouterProviderRouting") {
                        on(Send) { request ->
                            bindLifecycleRecorderToGenerationRequest(request)
                            try {
                                augmentRequestWithProviderRouting(request)
                            } catch (blocked: ProviderRoutingBlockedException) {
                                // Deliberate: abort dispatch for an unsatisfiable
                                // config rather than send it unrestricted.
                                throw blocked
                            } catch (_: Exception) {
                                // Any other issue is non-fatal: send unmodified.
                            }
                            try {
                                // Reasoning rides the same just-before-send body
                                // mutation; independent of routing and fully
                                // fail-safe, so it never blocks or breaks a send.
                                augmentRequestWithReasoning(request)
                            } catch (_: Exception) {
                                // Non-fatal: send without a reasoning instruction.
                            }
                            proceed(request)
                        }
                    })
                }
            )

            ai = OpenAI(config)
            // Auxiliary client for audio/image/function endpoints. Bound to
            // the active chat's endpoint (base host, same auth mode) so no
            // content is silently routed to api.openai.com.
            val configOpenAI = OpenAIConfig(
                token = if (isBearerAuth) openAIKey.toString() else "",
                logging = LoggingConfig(LogLevel.None, Logger.Simple),
                timeout = Timeout(connect = connectTimeout, socket = responseTimeout),
                organization = null,
                headers = extraHeaders,
                host = OpenAIHost(apiEndpointObject?.host!!),
                proxy = null,
                retry = RetryStrategy(maxRetries = 0)
            )
            openAIAI = OpenAI(configOpenAI)
            loadModel()
            setup()
        }
    }

    /**
     * Decide which companion a brand-new chat opens with:
     *   1. Default to the companion from the most recent chat that received a
     *      successful assistant response.
     *   2. If no successful companion has been recorded yet, or it was deleted,
     *      use the companion at the top of the list.
     *   3. If no companion exists at all, prompt the owner to create one and
     *      open the creation screen.
     * One-shot per chat (the persona_activation_seeded flag) and only for an
     * empty chat, so existing conversations are never retroactively changed.
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

            val lastPersona = preferences?.getLastSuccessfulPersonaId().orEmpty()
            if (lastPersona.isNotEmpty() && personaPrefs.getPersona(lastPersona).label.isNotEmpty()) {
                // Rule 1: continue with the companion you last used.
                preferences?.setPersonaId(lastPersona)
            } else {
                // Rule 2: first-ever use, or the last-used companion was since
                // deleted — open with the companion at the top of the list. Use
                // its stable id, never a hash of its (mutable) label.
                preferences?.setPersonaId(personasList.first().id)
            }
        }

        preferences?.setPersonaActivationSeeded(true)

        // initUI performs the first avatar paint before initAI reaches this
        // new-chat seeding step. Re-resolve after assigning the Companion;
        // AvatarRefreshCoordinator drops the earlier fallback result if its
        // storage lookup finishes later.
        refreshCompanionAvatar()

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

    /** SYSTEM INITIALIZATION END **/

    private fun saveSettings(
        synchronous: Boolean = false
    ): ChatStorageHealth.WriteOutcome {
        // Guarded save (Round 4): ChatPreferences refuses the write when the
        // chat's storage is locked or its stored value is preserved-corrupt —
        // this screen's in-memory list came from that unreadable read, and
        // persisting it would overwrite the only copy. The refusal is logged
        // by the guard; the "Chat unavailable" state already blocks the UI.
        if (chatStorageUnavailable) return ChatStorageHealth.WriteOutcome.BLOCKED_CORRUPT
        // The inline image-confirmation card and the Creating Image row are
        // transient UI rows (image-generation-rebuild-plan.md §5) — never
        // persisted, so no unexplained empty assistant message is saved and
        // reopening cannot show a stale copy.
        val isTransientImageRow = { row: HashMap<String, Any> ->
            row[ChatAdapter.KEY_IMAGE_CONFIRMATION] == true ||
                row[ChatAdapter.KEY_IMAGE_PROGRESS] == true
        }
        val persistableMessages =
            if (messages.any(isTransientImageRow)) {
                ArrayList(messages.filterNot(isTransientImageRow))
            } else {
                messages
            }
        return ChatPreferences.getChatPreferences().saveChatHistory(
            this,
            chatId,
            persistableMessages,
            synchronous = synchronous
        )
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

    private fun parseMessage(
        message: String,
        shouldAdd: Boolean = true,
        preparedTurn: PreparedRegularTurn? = null
    ) {
        // No sends into a chat whose stored history is locked or preserved-
        // corrupt (Round 4) — the blocking dialog owns this screen.
        if (chatStorageUnavailable) return
        if (preparedTurn != null) {
            val stillExact = message == preparedTurn.rawMessage &&
                messageInput?.text?.toString() == preparedTurn.rawMessage &&
                pendingIncludes.toList() == preparedTurn.pendingIncludes &&
                chatMessages.toList() == preparedTurn.historyBeforeSend &&
                model == preparedTurn.selectedModel &&
                preferences?.getApiEndpointId().orEmpty() == preparedTurn.selectedEndpointId
            if (!stillExact) {
                restoreUIState()
                return
            }
        }
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

            val m = preparedTurn?.storedMessage ?: (prefix + message + endSeparator)

            if (shouldAdd) putMessage(m, false)

            // Attachments waiting in the strip belong to THIS message: they
            // move into its record so the document text is saved atomically
            // with the text it accompanies, and so it holds a fixed position
            // in history that the provider's prefix cache can cover on every
            // later turn. Only on shouldAdd — a retry re-sends an existing
            // message that already carries its own attachments.
            var transferredPendingIncludes = false
            if (shouldAdd) {
                val attached = if (preparedTurn != null) {
                    pendingIncludes = arrayListOf()
                    preparedTurn.pendingIncludes.map { it.forSentMessage() }
                } else {
                    consumePendingIncludesForSend()
                }
                if (attached.isNotEmpty() && messages.isNotEmpty()) {
                    messages[messages.size - 1][INCLUDES_KEY] = ChatInclude.listToJson(attached)
                    transferredPendingIncludes = true
                }
                refreshIncludeStrip()
                refreshPersistentIncludeControls()
            }
            val saveOutcome = saveSettings(synchronous = transferredPendingIncludes)
            if (transferredPendingIncludes &&
                saveOutcome == ChatStorageHealth.WriteOutcome.OK
            ) {
                // Commit the chat side first. If the process stops between
                // these writes, loadPendingIncludes de-duplicates by include
                // id; the document is never lost.
                savePendingIncludes(synchronous = true)
            }

            btnMicro?.isEnabled = false
            btnSend?.isEnabled = false
            progress?.visibility = View.VISIBLE

            // Rebuilt /imagine (image-generation-rebuild-plan.md §2.1): the
            // RAW typed text is parsed — not the stored prefix+separator
            // form — so the command triggers only at the start of the
            // message, and trailing --shape / --quality options override
            // the saved defaults for this request only (§11). The toggle is
            // the app-wide Enable /imagine setting (§5); the legacy
            // per-chat copy stops being read (§14).
            val imagineParse = if (preferences!!.getImagineCommandGlobal()) {
                ImagineCommand.parse(message)
            } else {
                ImagineCommand.Parse.NotImagine
            }

            if (imagineParse is ImagineCommand.Parse.Request) {
                handleImagineRequest(imagineParse)
            } else if (imagineParse is ImagineCommand.Parse.EmptyPrompt) {
                putMessage("Prompt can not be empty. Use /imagine &lt;PROMPT&gt;", true)

                saveSettings()

                btnMicro?.isEnabled = true
                btnSend?.isEnabled = true
                progress?.visibility = View.GONE
            } else if (imagineParse is ImagineCommand.Parse.InvalidOption) {
                // §11: an unknown or invalid trailing option is a clear
                // correctable error naming the supported options and
                // values, and no image is generated.
                putMessage(
                    getString(R.string.image_gen_invalid_option, imagineParse.optionText),
                    true
                )

                saveSettings()

                btnMicro?.isEnabled = true
                btnSend?.isEnabled = true
                progress?.visibility = View.GONE
            } else {
                if (shouldAdd) {
                    chatMessages.add(
                        ChatMessage(
                            role = ChatRole.User,
                            // Not plain `m`: if this turn carried attachments
                            // they are part of what the model receives.
                            content = preparedTurn?.modelFacingMessage
                                ?: messages.lastOrNull()?.let { modelFacingContent(it) }
                                ?: m
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
                        generateResponse(m, false, preparedTurn)
                    } catch (_: CancellationException) {
                        restoreUIState()
                    }
                }
            }
        }
    }

    /**
     * The rebuilt `/imagine` pipeline (image-generation-rebuild-plan.md
     * §2.1/§11/§13): pre-flight configuration check with the persistent
     * Configure dialog, §11 option resolution against the selected
     * generator's capabilities with the never-silent unsupported-option
     * notice, then one request through the shared generator coordinator.
     */
    private fun handleImagineRequest(parsed: ImagineCommand.Parse.Request) {
        val globalPreferences = Preferences.getPreferences(this, "")
        val endpointId = globalPreferences.getImageGeneratorEndpointId()
        val generatorModelId = globalPreferences.getImageGeneratorModel()
        if (endpointId.isBlank() || generatorModelId.isBlank()) {
            saveSettings()
            restoreUIState()
            MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                .setTitle(R.string.title_image_generation)
                .setMessage(R.string.image_gen_configure_message)
                .setPositiveButton(R.string.image_gen_action_configure) { _, _ ->
                    startActivity(Intent(this, ImageGenerationSettingsActivity::class.java))
                }
                .setNegativeButton(R.string.btn_cancel) { _, _ -> }
                .show()
            return
        }

        val endpoint = apiEndpointPreferences!!.getApiEndpoint(this, endpointId)
        val capabilities = ImageProviderAdapters.forEndpoint(endpoint).capabilities
        val resolved = ImagineCommand.resolveOptions(
            parsed.shapeOverride,
            parsed.qualityOverride,
            globalPreferences.getImageGeneratorShape(),
            globalPreferences.getImageGeneratorQuality(),
            capabilities
        )
        val request = ImageGenerationRequest(
            prompt = parsed.prompt,
            shape = resolved.shape,
            quality = resolved.quality,
            endpointId = endpointId,
            modelId = generatorModelId
        )

        if (resolved.unsupportedExplicit.isNotEmpty()) {
            // §11: an explicitly requested option the selected generator
            // cannot support is never silently ignored.
            val optionLabels = resolved.unsupportedExplicit.joinToString(", ") { option ->
                if (option == ImagineCommand.OPTION_SHAPE) {
                    getString(R.string.image_gen_row_shape)
                } else {
                    getString(R.string.image_gen_row_quality)
                }
            }
            saveSettings()
            restoreUIState()
            MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                .setTitle(R.string.title_image_generation)
                .setMessage(getString(R.string.image_gen_unsupported_option_notice, optionLabels))
                .setPositiveButton(R.string.image_gen_action_continue) { _, _ ->
                    sendCoordinatorImageRequest(request)
                }
                .setNegativeButton(R.string.btn_cancel) { _, _ -> }
                .show()
            return
        }

        sendCoordinatorImageRequest(request)
    }

    /** §5 progress experience: the generation runs in the process-level
     *  job registry — NOT an activity scope — so leaving the chat or
     *  recreating this screen cannot kill it or double it. This screen
     *  shows the inline Creating Image row (with its visible Cancel), and
     *  the busy state; the single terminal state arrives through
     *  [onImageJobFinished]. */
    private fun sendCoordinatorImageRequest(request: ImageGenerationRequest) {
        btnMicro?.isEnabled = false
        btnSend?.isEnabled = false
        progress?.visibility = View.VISIBLE
        progress?.setOnClickListener { ImageGenerationJobRegistry.cancel(chatId) }
        showImageProgressCard()
        // Attach (idempotent) before starting: a chat whose id was assigned
        // after onCreate must still receive the terminal state here.
        ImageGenerationJobRegistry.attach(chatId, this)
        ImageGenerationJobRegistry.start(
            this, chatId, request, ImageGenerationJobRegistry.Origin.IMAGINE
        )
    }

    /** The §5 single terminal state — Complete, Failed, or Cancelled —
     *  delivered exactly once by the job registry while this screen is
     *  attached. `/imagine` owns its whole turn, so it also restores the
     *  busy state; a tool-call generation is mid-turn and leaves the turn
     *  state to the surrounding tool flow. */
    override fun onImageJobFinished(
        job: ImageGenerationJobRegistry.ActiveJob,
        terminal: ImageGenerationJobRegistry.Terminal
    ) {
        if (job.chatId != chatId) return
        removeImageProgressCard()
        val fromImagine = job.origin == ImageGenerationJobRegistry.Origin.IMAGINE
        when (terminal) {
            is ImageGenerationJobRegistry.Terminal.Complete -> {
                putMessage("~file:" + terminal.marker, true)
                attachGeneratedImageRecord(terminal.metadata)
                scroll(true)
                scroll(false)
                saveSettings()
                // Make the token-saving summary right after the image finishes
                // (owner request, Aug 16 2026); silent, so chat is not disturbed.
                ensureImageSummaries()
                ChatPreferences.getChatPreferences()
                    .putTimestampToChatById(this, chatId)
                if (fromImagine) {
                    btnMicro?.isEnabled = true
                    btnSend?.isEnabled = true
                    progress?.visibility = View.GONE
                    messageInput?.requestFocus()
                }
            }
            is ImageGenerationJobRegistry.Terminal.Failed -> {
                if (fromImagine) {
                    presentImageGenerationFailure(job.request, terminal)
                } else {
                    // The image cause and the provider's own sanitized detail
                    // use the same failed-message formula as ordinary replies.
                    appendImageGenerationFailure(terminal)
                    saveSettings()
                }
            }
            is ImageGenerationJobRegistry.Terminal.Cancelled -> {
                putMessage(getString(R.string.image_gen_error_cancelled), true)
                attachGeneratedImageRecord(terminal.metadata)
                saveSettings()
                if (fromImagine) restoreUIState()
            }
        }
    }

    /** §12: stamp the just-added terminal message with its structured
     *  record. Rides the same persisted map as the message text, so the
     *  two can never separate. */
    private fun attachGeneratedImageRecord(metadata: GeneratedImageMetadata) {
        val last = messages.lastOrNull() ?: return
        if (last["isBot"] == true) {
            last[GeneratedImageMetadata.KEY] = metadata.toJson()
        }
    }

    /** The Creating Image row's visible Cancel action (plan §5). */
    override fun onImageProgressCancel() {
        ImageGenerationJobRegistry.cancel(chatId)
    }

    /** Transient §5 Creating Image row — never persisted; restored on
     *  reopen while the registry still holds the chat's job. */
    private fun showImageProgressCard() {
        if (messages.any { it[ChatAdapter.KEY_IMAGE_PROGRESS] == true }) return
        val card = HashMap<String, Any>()
        card["isBot"] = true
        card["message"] = ""
        card[ChatAdapter.KEY_IMAGE_PROGRESS] = true
        messages.add(card)
        adapter?.notifyItemInserted(messages.size - 1)
        updateMessagesSelectionProjection()
        scroll(true)
    }

    private fun removeImageProgressCard() {
        val index = messages.indexOfLast { it[ChatAdapter.KEY_IMAGE_PROGRESS] == true }
        if (index >= 0) {
            messages.removeAt(index)
            adapter?.notifyItemRemoved(index)
            updateMessagesSelectionProjection()
        }
    }

    /** §5 recovery: reopening (or recreating) the chat while its
     *  generation is still running re-shows the Creating Image row; an
     *  `/imagine` turn also re-enters its busy state. Never restarts the
     *  generation — the registry holds the one running job. */
    private fun restoreImageGenerationJobState() {
        if (chatId == "") return
        ImageGenerationJobRegistry.attach(chatId, this)
        val activeJob = ImageGenerationJobRegistry.activeJob(chatId) ?: return
        showImageProgressCard()
        if (activeJob.origin == ImageGenerationJobRegistry.Origin.IMAGINE) {
            btnMicro?.isEnabled = false
            btnSend?.isEnabled = false
            progress?.visibility = View.VISIBLE
            progress?.setOnClickListener { ImageGenerationJobRegistry.cancel(chatId) }
        }
    }

    /** §13 failure behavior (owner ruling, 2026-07-29): a concise
     *  cause-specific message in chat, plus the action matching the cause —
     *  Edit Prompt for a refused prompt, Change Settings for unsupported
     *  options and configuration or authentication failures, Retry only
     *  for failures that may succeed unchanged. The provider's sanitized
     *  message is also preserved beneath the app explanation, matching the
     *  ordinary failed-reply format. */
    private fun presentImageGenerationFailure(
        request: ImageGenerationRequest,
        failure: ImageGenerationJobRegistry.Terminal.Failed
    ) {
        playErrorSignal()
        stopHandsFreeOnError()

        val causeText = getString(imageFailureMessageRes(failure.cause))
        appendImageGenerationFailure(failure)
        saveSettings()
        btnMicro?.isEnabled = true
        btnSend?.isEnabled = true
        progress?.visibility = View.GONE
        messageInput?.requestFocus()

        val builder = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(causeText)
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
        when (failureActionFor(failure.cause)) {
            ImageFailureAction.EDIT_PROMPT -> {
                builder.setPositiveButton(R.string.image_gen_action_edit_prompt) { _, _ ->
                    // The exact prompt that was sent to the generator, back
                    // in the composer as a runnable command.
                    messageInput?.setText("/imagine " + request.prompt)
                    messageInput?.setSelection(messageInput?.text?.length ?: 0)
                    messageInput?.requestFocus()
                }
            }
            ImageFailureAction.CHANGE_SETTINGS,
            ImageFailureAction.OPEN_IMAGE_SETTINGS -> {
                builder.setPositiveButton(R.string.image_gen_action_change_settings) { _, _ ->
                    startActivity(Intent(this, ImageGenerationSettingsActivity::class.java))
                }
            }
            ImageFailureAction.RETRY -> {
                builder.setPositiveButton(R.string.btn_msg_retry) { _, _ ->
                    sendCoordinatorImageRequest(request)
                }
            }
            ImageFailureAction.NONE -> { /* cancellation shows no action */ }
        }
        builder.show()
    }

    /** App explanation in the message body; the provider's own sanitized
     *  response in the separate failed-message detail field. This is the
     *  established chat error formula, and keeping the fields separate also
     *  prevents either string from entering later model context as AI prose. */
    private fun appendImageGenerationFailure(
        failure: ImageGenerationJobRegistry.Terminal.Failed
    ) {
        putMessage(getString(imageFailureMessageRes(failure.cause)), true)
        attachGeneratedImageRecord(failure.metadata)
        val last = messages.lastOrNull() ?: return
        if (last["isBot"] == true) {
            last[MessageCompletionState.KEY_STATE] = MessageCompletionState.FAILED
            last[MessageCompletionState.KEY_STATE_DETAIL] = failure.cause.name
            last[MessageCompletionState.KEY_ERROR_TEXT] =
                imageFailureProviderDetailBlock(this, failure)
            adapter?.notifyItemChanged(messages.lastIndex)
        }
    }

    /* --------------- Model-initiated image creation (§6/§7/§8) --------------- */

    /** Whether the most recent regular request carried the create_image
     *  tool — the §8 retry wrapper's evidence that a failure could be a
     *  tools rejection at all. */
    private var lastRegularRequestCarriedImageTools = false

    /** §8: UNKNOWN tries the tool, SUPPORTED keeps sending it, UNSUPPORTED
     *  withholds it until the endpoint editor's reset forgets the record. */
    private fun chatToolCapabilityScopeKey(selectedModel: String): String {
        val endpoint = apiEndpointObject
        val favorite = favoriteForActiveEndpoint(selectedModel)
        return ToolCapabilityScope.key(
            selectedModel,
            openRouterRouting = endpoint?.isOpenRouterRouting() == true,
            routingType = favorite?.routingType ?: FavoriteModelObject.ROUTING_AUTOMATIC,
            selectedProvider = favorite?.selectedProvider.orEmpty(),
            allowFallbacks = favorite?.allowFallbacks != false,
            providerOrder = favorite?.providerOrder ?: emptyList(),
            ignoredProviders = favorite?.ignoredProviders ?: emptyList()
        )
    }

    private fun chatModelMayReceiveImageTool(selectedModel: String): Boolean {
        return try {
            val chatEndpointId = preferences?.getApiEndpointId().orEmpty()
            if (chatEndpointId.isEmpty()) return true
            val chatEndpoint =
                apiEndpointPreferences?.getApiEndpoint(this, chatEndpointId) ?: return true
            ToolCapabilityStore.get(
                chatEndpoint.toolCapabilityByModel,
                chatToolCapabilityScopeKey(selectedModel)
            ) !=
                ToolCapability.UNSUPPORTED
        } catch (_: Exception) {
            true
        }
    }

    /** Persist a learned tool capability for this chat's exact
     *  endpoint/model pair — same persistence shape as vision capability.
     *  Learning must never break a turn. */
    private fun recordChatToolCapability(selectedModel: String, capability: ToolCapability) {
        try {
            val chatEndpointId = preferences?.getApiEndpointId().orEmpty()
            if (chatEndpointId.isEmpty() || selectedModel.isBlank()) return
            val prefs = ApiEndpointPreferences.getApiEndpointPreferences(this)
            val endpoint = prefs.getApiEndpoint(this, chatEndpointId)
            val updated =
                ToolCapabilityStore.set(
                    endpoint.toolCapabilityByModel,
                    chatToolCapabilityScopeKey(selectedModel),
                    capability
                )
            if (updated != endpoint.toolCapabilityByModel) {
                endpoint.toolCapabilityByModel = updated
                prefs.setApiEndpoint(this, endpoint)
            }
        } catch (_: Exception) { /* capability learning must never break a turn */ }
    }

    /** The §8 UNSUPPORTED transition: record it, drop the dead streaming
     *  placeholder so the retry doesn't stack empty bubbles, and show the
     *  one-time notice — it appears exactly once because the learned state
     *  prevents any further tool-bearing request to this pair. Returns the
     *  state the pair had before, for the §13 capability-change entry. */
    private fun learnToolsUnsupportedAndNotify(): ToolCapability {
        val previousState = try {
            val chatEndpointId = preferences?.getApiEndpointId().orEmpty()
            if (chatEndpointId.isEmpty()) ToolCapability.UNKNOWN
            else ToolCapabilityStore.get(
                apiEndpointPreferences?.getApiEndpoint(this, chatEndpointId)?.toolCapabilityByModel,
                chatToolCapabilityScopeKey(model)
            )
        } catch (_: Exception) {
            ToolCapability.UNKNOWN
        }
        recordChatToolCapability(model, ToolCapability.UNSUPPORTED)
        runOnUiThread {
            if (messages.isNotEmpty() && messages.last()["isBot"] == true &&
                messages.last()["message"].toString().isEmpty()
            ) {
                messages.removeAt(messages.size - 1)
                adapter?.notifyItemRemoved(messages.size)
                updateMessagesSelectionProjection()
            }
            putMessage(getString(R.string.image_gen_tools_unsupported_notice), true)
            saveSettings()
        }
        return previousState
    }

    /** §13 automatic capability-change entry, written once the without-tools
     *  retry has finished either way. Logging must never break the retry. */
    private fun recordToolCapabilityChangeEntry(
        previousState: ToolCapability,
        rawError: String?,
        retrySucceeded: Boolean
    ) {
        try {
            val chatEndpointId = preferences?.getApiEndpointId().orEmpty()
            val endpoint = if (chatEndpointId.isEmpty()) null
            else apiEndpointPreferences?.getApiEndpoint(this, chatEndpointId)
            ImageGenerationEventLog.recordCapabilityChange(
                this,
                endpointLabel = endpoint?.label.orEmpty(),
                modelId = model,
                previousState = toolCapabilityLabel(previousState),
                newState = toolCapabilityLabel(ToolCapability.UNSUPPORTED),
                sanitizedError = ImageErrorSanitizer.sanitize(rawError, endpoint?.apiKey),
                retriedWithoutTools = true,
                retrySucceeded = retrySucceeded
            )
        } catch (_: Exception) { /* logging must never break the retry */ }
    }

    private fun toolCapabilityLabel(capability: ToolCapability): String = when (capability) {
        ToolCapability.UNKNOWN -> "Unknown"
        ToolCapability.SUPPORTED -> "Supported"
        ToolCapability.UNSUPPORTED -> "Unsupported"
    }

    /** Resolved by the inline confirmation card's Create/Cancel tap. */
    private var pendingImageConfirmation: CompletableDeferred<Boolean>? = null

    override fun onImageConfirmationDecision(approved: Boolean) {
        pendingImageConfirmation?.complete(approved)
    }

    private fun showImageConfirmationCard(prompt: String) {
        val card = HashMap<String, Any>()
        card["isBot"] = true
        card["message"] = ""
        card[ChatAdapter.KEY_IMAGE_CONFIRMATION] = true
        card[ChatAdapter.KEY_IMAGE_CONFIRMATION_PROMPT] = prompt
        card[ChatAdapter.KEY_IMAGE_CONFIRMATION_COMPANION] =
            currentCompanionLabel().ifBlank { getString(R.string.chat_role_assistant) }
        messages.add(card)
        adapter?.notifyItemInserted(messages.size - 1)
        updateMessagesSelectionProjection()
        scroll(true)
    }

    private fun removeImageConfirmationCard() {
        val index = messages.indexOfLast { it[ChatAdapter.KEY_IMAGE_CONFIRMATION] == true }
        if (index >= 0) {
            messages.removeAt(index)
            adapter?.notifyItemRemoved(index)
            updateMessagesSelectionProjection()
        }
    }

    /** §5 confirmation: the inline card naming the companion, prompt
     *  collapsed behind View Prompt, plus the spoken announcement over the
     *  same read-aloud gate as replies (owner-approved wording) — in a
     *  hands-free conversation its completed readback is what re-arms the
     *  mic so the next utterance can answer. Skipped when Ask Before
     *  Creating is off. Cancelling the turn (the progress tap) cancels the
     *  await and removes the card. */
    private suspend fun requestImageConfirmation(
        prompt: String,
        globalPreferences: Preferences,
        shouldPronounce: Boolean
    ): Boolean {
        if (!globalPreferences.getAskBeforeAiImages()) return true
        val decision = CompletableDeferred<Boolean>()
        pendingImageConfirmation = decision
        runOnUiThread { showImageConfirmationCard(prompt) }
        pronounce(
            shouldPronounce,
            getString(
                R.string.image_gen_spoken_announcement,
                currentCompanionLabel().ifBlank { getString(R.string.chat_role_assistant) }
            )
        )
        try {
            return decision.await()
        } finally {
            pendingImageConfirmation = null
            runOnUiThread { removeImageConfirmationCard() }
        }
    }

    /** §7 image tool flow: close the streamed text bubble (or drop an
     *  empty placeholder — §5 forbids saving an unexplained empty
     *  assistant message), execute at most one create_image call per turn
     *  (§6), return one tool result per call to the SAME conversation
     *  model, and stream its final text. Unknown tool names are never
     *  executed. Tools are not re-offered on the follow-up, so the turn
     *  cannot loop. */
    private suspend fun handleAssistantToolCalls(
        calls: List<StreamedToolCallAssembler.AssembledToolCall>,
        originalRequest: ChatCompletionRequest,
        streamedText: String,
        shouldPronounce: Boolean
    ) {
        if (streamedText.isEmpty()) {
            if (messages.isNotEmpty() && messages.last()["isBot"] == true) {
                messages.removeAt(messages.size - 1)
                adapter?.notifyItemRemoved(messages.size)
                updateMessagesSelectionProjection()
            }
        } else {
            markLastAssistantDone()
        }
        saveSettings()

        val globalPreferences = Preferences.getPreferences(this, "")
        var executedImageCall = false
        val results =
            ArrayList<kotlin.Pair<StreamedToolCallAssembler.AssembledToolCall, String>>()
        for (call in calls) {
            val result = when {
                call.name != CreateImageTool.NAME -> {
                    ImageGenerationEventLog.recordToolMistake(
                        this, "the model called unknown tool \"${call.name}\""
                    )
                    CreateImageTool.errorResult(
                        "unknown tool \"${call.name}\" — it was not executed"
                    )
                }
                executedImageCall -> {
                    ImageGenerationEventLog.recordToolMistake(
                        this, "the model attempted more than one image in a single turn"
                    )
                    CreateImageTool.errorResult("only one image may be created per user turn")
                }
                else -> {
                    executedImageCall = true
                    executeCreateImageCall(call, globalPreferences, shouldPronounce)
                }
            }
            results.add(kotlin.Pair(call, result))
        }

        // §7.2: before dispatching the follow-up, make sure this turn's
        // reasoning state has finished being captured from the split stream, so
        // the assistant tool-call message can echo its reasoning_details back.
        // Bounded so a stalled observer can never hang the turn; skipped
        // entirely when reasoning was never observed for this turn.
        if (currentTurnReasoningObservationActive) {
            kotlinx.coroutines.withTimeoutOrNull(1500L) {
                currentTurnReasoningObserved?.await()
            }
        }

        var assistantCallId = 0
        val assistantToolCallMessage = ChatMessage(
            role = ChatRole.Assistant,
            content = streamedText.ifEmpty { null },
            toolCalls = calls.map { call ->
                ToolCall.Function(
                    id = ToolId(call.id ?: "call_${assistantCallId++}"),
                    function = FunctionCall(call.name, call.arguments)
                )
            }
        )
        var resultCallId = 0
        val toolResultMessages = results.map { pair ->
            ChatMessage(
                role = ChatRole.Tool,
                content = pair.second,
                toolCallId = ToolId(pair.first.id ?: "call_${resultCallId++}")
            )
        }
        val followUpRequest = rebuildRequestWithoutTools(
            originalRequest,
            originalRequest.messages + assistantToolCallMessage + toolResultMessages
        )
        streamAssistantTextResponse(followUpRequest, shouldPronounce)
    }

    /** One §6-validated create_image execution: the user's saved quality
     *  default always applies (the tool has no quality field), §11 shape
     *  resolution reports model-side fallbacks in the tool result instead
     *  of interrupting the user, the §5 confirmation runs when enabled,
     *  and the shared coordinator generates the image. Failures put the
     *  §13 cause message in chat and return a clean tool error. */
    private suspend fun executeCreateImageCall(
        call: StreamedToolCallAssembler.AssembledToolCall,
        globalPreferences: Preferences,
        shouldPronounce: Boolean
    ): String {
        val validation = CreateImageTool.validate(call.arguments)
        if (validation is CreateImageTool.Validation.Invalid) {
            // §13: model mistakes are log entries, never chat errors.
            ImageGenerationEventLog.recordToolMistake(this, validation.toolError)
            return CreateImageTool.errorResult(validation.toolError)
        }
        val valid = validation as CreateImageTool.Validation.Valid

        val endpointId = globalPreferences.getImageGeneratorEndpointId()
        val generatorModelId = globalPreferences.getImageGeneratorModel()
        if (endpointId.isBlank() || generatorModelId.isBlank()) {
            return CreateImageTool.errorResult("no image generator is configured")
        }

        val endpoint = apiEndpointPreferences!!.getApiEndpoint(this, endpointId)
        val adapter = ImageProviderAdapters.forEndpoint(endpoint)
        val resolved = ImagineCommand.resolveOptions(
            valid.shapeOverride,
            null,
            globalPreferences.getImageGeneratorShape(),
            globalPreferences.getImageGeneratorQuality(),
            adapter.capabilities
        )
        if (resolved.unsupportedExplicit.isNotEmpty() || resolved.silentFallbacks.isNotEmpty()) {
            // §13: a model-initiated option that fell back to the provider
            // default — the case the user cannot otherwise see.
            ImageGenerationEventLog.recordSilentFallback(
                this,
                (resolved.unsupportedExplicit + resolved.silentFallbacks).joinToString(", "),
                endpoint.provider.ifBlank { adapter.providerName },
                generatorModelId
            )
        }
        val request = ImageGenerationRequest(
            prompt = valid.prompt,
            shape = resolved.shape,
            quality = resolved.quality,
            endpointId = endpointId,
            modelId = generatorModelId,
            description = valid.description
        )

        if (!requestImageConfirmation(valid.prompt, globalPreferences, shouldPronounce)) {
            return CreateImageTool.cancelledResult()
        }

        // §5: the generation itself runs in the process-level job registry
        // so recreating this screen mid-turn cannot kill it or double it.
        // The attached screen shows the Creating Image row and appends the
        // single terminal chat message (and the registry records the §13
        // entries); this flow only builds the tool result.
        val job = withContext(Dispatchers.Main) {
            showImageProgressCard()
            // Attach (idempotent) before starting: a chat whose id was
            // assigned after onCreate must still receive the terminal state.
            ImageGenerationJobRegistry.attach(chatId, this@ChatActivity)
            ImageGenerationJobRegistry.start(
                this@ChatActivity, chatId, request, ImageGenerationJobRegistry.Origin.TOOL
            )
        }
        return when (val terminal = job.await()) {
            is ImageGenerationJobRegistry.Terminal.Complete -> {
                // §11: a model-initiated unsupported option applies the
                // fallback and reports it in the tool result instead of
                // interrupting the user.
                val fallbackNote = if (resolved.unsupportedExplicit.isNotEmpty() ||
                    resolved.silentFallbacks.isNotEmpty()
                ) {
                    "the requested shape is not supported by the image service; " +
                        "the provider default was used"
                } else {
                    null
                }
                CreateImageTool.successResult(terminal.marker, valid.description, fallbackNote)
            }
            is ImageGenerationJobRegistry.Terminal.Failed ->
                CreateImageTool.errorResult(
                    "image generation failed: " + terminal.cause.name.lowercase()
                )
            is ImageGenerationJobRegistry.Terminal.Cancelled ->
                CreateImageTool.cancelledResult()
        }
    }

    /** ChatCompletionRequest is not a data class, so the §7 follow-up and
     *  the §8 without-tools retry rebuild it field-by-field, carrying the
     *  original sampling values and deliberately no tools. */
    @OptIn(com.aallam.openai.api.BetaOpenAI::class) // reading seed back is beta-gated
    private fun rebuildRequestWithoutTools(
        original: ChatCompletionRequest,
        requestMessages: List<ChatMessage>
    ): ChatCompletionRequest = ChatCompletionRequest(
        model = original.model,
        messages = requestMessages,
        maxTokens = original.maxTokens,
        temperature = original.temperature,
        topP = original.topP,
        frequencyPenalty = original.frequencyPenalty,
        presencePenalty = original.presencePenalty,
        seed = original.seed,
        logitBias = original.logitBias,
        // Ask supported providers to include token usage in the stream so the
        // Response Lifecycle Log can record provider-reported counts. Harmless
        // where unsupported: the field simply stays "not reported".
        streamOptions = StreamOptions(includeUsage = true)
    )

    /** The follow-up response after tool results (§7.7): plain streamed
     *  text into a fresh assistant bubble, then the normal turn
     *  completion. */
    private suspend fun streamAssistantTextResponse(
        request: ChatCompletionRequest,
        shouldPronounce: Boolean
    ) {
        var response = ""
        putMessage("", true)
        markLastAssistantStreaming()
        startLifecycle(ResponseLifecycle.PHASE_TOOL_CONTINUATION, request.maxTokens)
        val completions: Flow<ChatCompletionChunk> = ai!!.chatCompletions(request)
        scroll(true)
        // Dispatch begins at collection; a failure past this point is a real
        // provider/network end, not a pre-dispatch one.
        startGenerationNetworkDiagnostics()
        providerRequestDispatched = true
        completions.flowOn(Dispatchers.IO).collect { v ->
            if (!currentCoroutineContext().isActive) throw CancellationException()
            val choice = v.choices.firstOrNull()
            noteLifecycleChunk(
                choice?.finishReason?.value, v.id,
                (choice?.delta?.content?.takeIf { it != "null" }?.length ?: 0),
                v.usage?.promptTokens, v.usage?.completionTokens, v.usage?.totalTokens
            )
            v.usage?.totalTokens?.let { pendingResponseTokens = it }
            val delta = choice?.delta?.content
            if (delta != null && delta != "null") {
                response += delta
                messages[messages.size - 1]["message"] = response
                adapter?.notifyItemChanged(messages.size - 1)
                scroll(false)
            }
        }
        finalizeLifecycleSuccess()
        messages[messages.size - 1]["message"] = "$response\n"
        markLastAssistantDone()
        adapter?.notifyItemChanged(messages.size - 1)
        syncChatProjection()
        pronounce(shouldPronounce, response)
        saveSettings()
        calculateCost()
        summarizerCycle()
        btnMicro?.isEnabled = true
        btnSend?.isEnabled = true
        progress?.visibility = View.GONE
        messageInput?.requestFocus()
        ChatPreferences.getChatPreferences().putTimestampToChatById(this, chatId)
    }

    // ===== Response Lifecycle diagnostics (opt-in, off by default) =====
    // One record is written per streamed VISIBLE generation request — never one
    // combined record for a whole multi-step turn — so a completed primary
    // stream and an interrupted continuation are two comparable entries that
    // share [currentLifecycleTurnId]. Capture is entirely gated on the toggle:
    // when it is off, [currentLifecycle] stays null and these helpers no-op.
    @Volatile
    private var currentLifecycle: ResponseLifecycleRecorder? = null

    /** Network transport evidence for the currently dispatched provider request. */
    private var generationNetworkMonitor: org.teslasoft.assistant.util.GenerationNetworkMonitor? = null

    /** Whether the in-flight streamed request has actually begun dispatch/
     *  collection. False throughout request construction; set true immediately
     *  before the provider stream is collected, and reset per attempt in
     *  [startLifecycle]. This — never the mere existence of a visible assistant
     *  row — is the pre-dispatch boundary: a failure or a non-user, non-teardown
     *  cancellation while this is false ended before anything reached the
     *  provider, so it is recorded as request_not_sent and never written to the
     *  Provider Failure Log. */
    @Volatile
    private var providerRequestDispatched: Boolean = false

    /** The provider-routing send hook's ACTUAL result for the in-flight
     *  request, written on the send thread and read when the lifecycle entry is
     *  finalized. Reset to null when each streamed request begins, so a hook
     *  that never runs is reported as unconfirmed rather than "attached". */
    @Volatile
    private var lastRoutingAttachment: String? = null
    /** Diagnostic: the reasoning instruction actually written onto the outgoing
     *  body by [augmentRequestWithReasoning] for this streamed request, or a
     *  reason none was (§7.8). Written on the send thread, read when the
     *  lifecycle entry finalizes. Reset with [lastRoutingAttachment]. */
    @Volatile
    private var lastReasoningAttachment: String? = null
    private var currentLifecycleTurnId: String = ""
    private var lifecycleTurnCounter: Int = 0

    /** Mint the turn id shared by this turn's primary and continuation streams.
     *  Always cheap; the actual capture is still gated in [startLifecycle]. */
    private fun beginLifecycleTurn() {
        lifecycleTurnCounter++
        currentLifecycleTurnId = "T" + System.currentTimeMillis().toString() + "-" + lifecycleTurnCounter
    }

    /** Begin recording one streamed request when Response Lifecycle logging is
     *  on. A still-pending recorder (e.g. the §8 first attempt that is about to
     *  be retried) is closed first so its record is never dropped. */
    private suspend fun startLifecycle(phase: String, requestedMaxOutput: Int?) {
        currentLifecycle?.let {
            if (!it.finalized) finalizeLifecycleTerminal(
                ResponseLifecycle.Outcome.INCOMPLETE, "missing", true,
                ResponseLifecycle.Termination.STREAM_CLOSED, "superseded by a new request"
            )
        }
        // Reset for THIS request (after any superseded entry above is written
        // with its own result), so the send hook's actual outcome is recorded
        // fresh and a hook that never runs never reports a stale attachment.
        lastRoutingAttachment = null
        lastReasoningAttachment = null
        // Reset the dispatch boundary for THIS attempt regardless of whether
        // lifecycle logging is on, because the Provider Failure Log gate also
        // depends on it.
        providerRequestDispatched = false
        if (preferences?.getResponseLifecycleLogging() != true) {
            currentLifecycle = null
            return
        }
        if (currentLifecycleTurnId.isBlank()) beginLifecycleTurn()
        val endpoint = apiEndpointObject
        val apiProvider = endpoint?.provider?.trim()?.ifBlank { null }
            ?: endpoint?.label?.trim()?.ifBlank { null }
            ?: endpoint?.host?.trim().orEmpty()
        currentLifecycle = ResponseLifecycleRecorder(
            turnId = currentLifecycleTurnId,
            phase = phase,
            apiProvider = apiProvider,
            apiEndpoint = endpoint?.host ?: "",
            model = model,
            requestedMaxOutput = requestedMaxOutput,
            startUptimeMs = android.os.SystemClock.uptimeMillis()
        )
    }

    private fun noteLifecycleChunk(
        finishReason: String?, id: String?, contentLength: Int,
        promptTokens: Int?, completionTokens: Int?, totalTokens: Int?
    ) {
        val r = currentLifecycle ?: return
        if (r.finalized) return
        r.noteChunk(finishReason, id, contentLength, promptTokens, completionTokens, totalTokens)
    }

    /** Finalize a stream that ended on its own (the flow completed without
     *  throwing): the outcome is decided only from the finish reason actually
     *  seen — text having arrived is never treated as completion. */
    private suspend fun finalizeLifecycleSuccess() {
        val r = currentLifecycle ?: return
        if (r.finalized) return
        val n = ResponseLifecycle.classifyNormalCompletion(r.lastFinishReason, r.receivedCharacters)
        writeLifecycle(r, n.outcome, n.finishReasonDisplay, n.streamClosed, n.termination, null)
    }

    /** Finalize a stream cut short by an error, a user stop, or an app cancel —
     *  the terminal values are decided by the caller from what it caught. */
    private suspend fun finalizeLifecycleTerminal(
        outcome: ResponseLifecycle.Outcome, finishReasonDisplay: String,
        streamClosed: Boolean, termination: ResponseLifecycle.Termination, errorText: String?
    ) {
        val r = currentLifecycle ?: return
        if (r.finalized) return
        writeLifecycle(r, outcome, finishReasonDisplay, streamClosed, termination, errorText)
    }

    private suspend fun writeLifecycle(
        r: ResponseLifecycleRecorder, outcome: ResponseLifecycle.Outcome,
        finishReasonDisplay: String, streamClosed: Boolean,
        termination: ResponseLifecycle.Termination, errorText: String?
    ) {
        r.markFinalized()
        val durationMs = android.os.SystemClock.uptimeMillis() - r.startUptimeMs
        // The response observer usually records the provider from the first SSE
        // chunk. This bounded wait only closes a scheduling race on extremely
        // short replies, and applies solely while this opt-in log is active.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            r.awaitProviderObservation(250L)
        }
        val body = ResponseLifecycle.format(
            turnId = r.turnId, phase = r.phase, apiProvider = r.apiProvider,
            apiEndpoint = r.apiEndpoint, actualModelProvider = r.actualModelProvider,
            model = r.model,
            outcome = outcome, finishReasonDisplay = finishReasonDisplay,
            streamClosed = streamClosed, termination = termination,
            requestedMaxOutput = r.requestedMaxOutput, promptTokens = r.promptTokens,
            completionTokens = r.completionTokens, totalTokens = r.totalTokens,
            receivedCharacters = r.receivedCharacters, durationMs = durationMs,
            generationId = r.generationId, errorText = errorText,
            attemptId = r.attemptId
        ) + providerRoutingLogLine(r.model) + reasoningLogLine()
        currentLifecycle = null
        org.teslasoft.assistant.preferences.Logger.logResponseLifecycleAsync(this, body)
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
     * Prepares a normal typed turn without touching the composer, attachment
     * strip, or persisted history. Only [commitPreparedTurn] crosses that
     * boundary, after every capacity decision has completed.
     */
    private fun prepareTypedTurn(rawMessage: String) {
        if (chatStorageUnavailable || rawMessage.isEmpty() ||
            requestPreparationInProgress
        ) {
            return
        }

        // Preserve the existing non-chat command and fine-tuned pipelines.
        // They do not use the normal chat-completions request built below.
        // The old Function Calling diversion is gone with the feature (§15).
        val imagineAttempt = preferences?.getImagineCommandGlobal() == true &&
            ImagineCommand.isImagineAttempt(rawMessage)
        if (imagineAttempt ||
            model.contains(":ft") || model.contains("ft:")
        ) {
            parseMessage(rawMessage)
            return
        }

        requestPreparationInProgress = true
        val pendingSnapshot = pendingIncludes.toList()
        val historySnapshot = chatMessages.toList()
        val historyIncludesSnapshot = chatMessageIncludes.toList()
        val selectedModel = model
        val endpointId = preferences?.getApiEndpointId().orEmpty()
        val maximumResponseTokens = preferences?.getMaxTokens() ?: 0
        val storedMessage = prefix + rawMessage + endSeparator
        val sentIncludes = pendingSnapshot.map { it.forSentMessage() }
        val sentIncludesJson = ChatInclude.listToJson(sentIncludes)
        val modelFacingMessage = IncludeMessageProjection.userContent(
            storedMessage,
            sentIncludesJson
        )
        // Summarizer transmission (decision 15): the request carries the
        // summary plus only the messages after the fold-in bookmark. The
        // trimmed pair and the summary text are captured together here so a
        // fold-in landing mid-preparation can't split them; the staleness
        // check in parseMessage keeps comparing the FULL projection snapshot.
        val summarizerTrim = summarizerTrimmedHistory()
        val summaryInjection = summarizerInjectionText()
        val requestMessages = ArrayList(summarizerTrim?.first ?: historySnapshot)
        requestMessages.add(
            ChatMessage(role = ChatRole.User, content = modelFacingMessage)
        )
        val requestIncludes = ArrayList(summarizerTrim?.second ?: historyIncludesSnapshot)
        requestIncludes.add(sentIncludesJson)

        btnMicro?.isEnabled = false
        btnSend?.isEnabled = false
        progress?.visibility = View.VISIBLE

        parseMessageScope = CoroutineScope(Dispatchers.Main)
        parseMessageScope?.launch {
            try {
                val frozen = buildFrozenRegularRequest(
                    requestMessages = requestMessages,
                    requestIncludes = requestIncludes,
                    loreQuery = storedMessage,
                    selectedModel = selectedModel,
                    maximumResponseTokens = maximumResponseTokens,
                    summaryInjection = summaryInjection
                )
                val measurement = RequestCapacity.measure(frozen.payload)
                if (!RequestCapacity.canAssemble(
                        measurement,
                        RequestHeapState.current()
                    )
                ) {
                    requestPreparationInProgress = false
                    restoreUIState()
                    showRequestHardBlock(
                        R.string.request_prepare_failed_title,
                        getString(R.string.request_prepare_failed_body)
                    )
                    return@launch
                }

                val contextWindow = apiEndpointObject
                    ?.takeIf {
                        it.contextWindowModelId == selectedModel &&
                            preferences?.getApiEndpointId().orEmpty() == endpointId
                    }
                    ?.contextWindowTokens
                val decision = ModelContextCapacity.decide(
                    contextWindow,
                    RequestCapacity.approximateInputTokens(frozen.payload),
                    maximumResponseTokens
                )
                val prepared = PreparedRegularTurn(
                    rawMessage = rawMessage,
                    storedMessage = storedMessage,
                    modelFacingMessage = modelFacingMessage,
                    pendingIncludes = pendingSnapshot,
                    historyBeforeSend = historySnapshot,
                    selectedModel = selectedModel,
                    selectedEndpointId = endpointId,
                    request = frozen.request,
                    payload = frozen.payload,
                    contextDecision = decision
                )
                val hasFullImages = conversationHasFullImages(requestIncludes)

                when (decision) {
                    ModelContextDecision.Send -> visionCheckAndCommit(prepared, hasFullImages)
                    is ModelContextDecision.Block -> {
                        requestPreparationInProgress = false
                        restoreUIState()
                        showRequestHardBlock(
                            R.string.request_context_exceeded_title,
                            getString(
                                R.string.request_context_exceeded_body,
                                formatTokenCount(decision.requiredAtLeast),
                                formatTokenCount(decision.contextWindow)
                            )
                        )
                    }
                    is ModelContextDecision.WarnRange -> {
                        requestPreparationInProgress = false
                        restoreUIState()
                        showRequestWarning(
                            getString(
                                R.string.request_context_range_warning_body,
                                formatTokenCount(decision.minimumRequired),
                                formatTokenCount(decision.maximumRequired),
                                formatTokenCount(decision.contextWindow)
                            ),
                            prepared,
                            hasFullImages
                        )
                    }
                    is ModelContextDecision.WarnApproximate -> {
                        requestPreparationInProgress = false
                        restoreUIState()
                        showRequestWarning(
                            getString(
                                R.string.request_context_approximate_warning_body,
                                formatTokenCount(decision.approximateRequired),
                                formatTokenCount(decision.contextWindow)
                            ),
                            prepared,
                            hasFullImages
                        )
                    }
                }
            } catch (_: OutOfMemoryError) {
                requestPreparationInProgress = false
                restoreUIState()
                showRequestHardBlock(
                    R.string.request_prepare_failed_title,
                    getString(R.string.request_prepare_failed_body)
                )
            } catch (e: CancellationException) {
                requestPreparationInProgress = false
                restoreUIState()
                throw e
            } catch (e: Exception) {
                requestPreparationInProgress = false
                restoreUIState()
                val genError = GenerationErrorClassifier.classify(e)
                logGenerationError(genError, e, "request preparation")
                MaterialAlertDialogBuilder(
                    this@ChatActivity,
                    R.style.App_MaterialAlertDialog
                )
                    .setTitle(R.string.label_error)
                    .setMessage(genError.chatMessage(this@ChatActivity))
                    .setPositiveButton(R.string.okay, null)
                    .show()
            }
        }
    }

    private fun commitPreparedTurn(prepared: PreparedRegularTurn) {
        requestPreparationInProgress = false
        parseMessage(prepared.rawMessage, preparedTurn = prepared)
    }

    private fun formatTokenCount(value: Int): String =
        NumberFormat.getIntegerInstance().format(value)

    private fun showRequestHardBlock(titleRes: Int, body: String) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(titleRes)
            .setMessage(body)
            .setPositiveButton(R.string.okay, null)
            .show()
    }

    private fun showRequestWarning(
        body: String,
        prepared: PreparedRegularTurn,
        hasFullImages: Boolean = false
    ) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.request_context_warning_title)
            .setMessage(body)
            .setPositiveButton(R.string.send_anyway) { _, _ ->
                visionCheckAndCommit(prepared, hasFullImages)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun visionCheckAndCommit(
        prepared: PreparedRegularTurn,
        hasFullImages: Boolean
    ) {
        if (!hasFullImages) {
            commitPreparedTurn(prepared)
            return
        }
        val capJson = apiEndpointObject?.imageCapabilityByModel.orEmpty()
        when (ImageCapabilityStore.get(capJson, prepared.selectedModel)) {
            ImageCapability.SUPPORTED -> commitPreparedTurn(prepared)
            ImageCapability.UNSUPPORTED -> showRequestHardBlock(
                R.string.image_model_unsupported_title,
                getString(R.string.image_model_unsupported_body)
            )
            ImageCapability.UNKNOWN -> {
                MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                    .setTitle(R.string.image_model_unknown_title)
                    .setMessage(R.string.image_model_unknown_body)
                    .setPositiveButton(R.string.send_anyway) { _, _ ->
                        commitPreparedTurn(prepared)
                    }
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show()
            }
        }
    }

    private suspend fun awaitVisionCapabilityCheck(): Boolean {
        if (!conversationHasFullImages(chatMessageIncludes)) return true
        val capJson = apiEndpointObject?.imageCapabilityByModel.orEmpty()
        return when (ImageCapabilityStore.get(capJson, model)) {
            ImageCapability.SUPPORTED -> true
            ImageCapability.UNSUPPORTED -> {
                withContext(Dispatchers.Main) {
                    showRequestHardBlock(
                        R.string.image_model_unsupported_title,
                        getString(R.string.image_model_unsupported_body)
                    )
                }
                false
            }
            ImageCapability.UNKNOWN -> suspendCancellableCoroutine { cont ->
                MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                    .setTitle(R.string.image_model_unknown_title)
                    .setMessage(R.string.image_model_unknown_body)
                    .setPositiveButton(R.string.send_anyway) { _, _ -> cont.resume(true) }
                    .setNegativeButton(R.string.btn_cancel) { _, _ -> cont.resume(false) }
                    .setOnCancelListener { cont.resume(false) }
                    .show()
            }
        }
    }

    private fun recordVisionCapability(capability: ImageCapability) {
        val endpoint = apiEndpointObject ?: return
        val currentModel = model.ifBlank { preferences?.getModel() ?: "" }
        if (currentModel.isBlank()) return
        val updated = ImageCapabilityStore.set(
            endpoint.imageCapabilityByModel, currentModel, capability
        )
        endpoint.imageCapabilityByModel = updated
        val prefs = ApiEndpointPreferences.getApiEndpointPreferences(this)
        prefs.setApiEndpoint(this, endpoint)
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
            !messageInput?.text.isNullOrEmpty() ->
                prepareTypedTurn(messageInput?.text.toString())
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
        // This funnel is only ever reached from a deliberate user action (Stop
        // spinner, notification Hang Up, mic tap, conversation button). Mark the
        // current generation's cancellation as user-initiated so it is treated
        // as a benign stop, not an app/lifecycle or unknown interruption.
        userRequestedStop = true
        // Stop is a deliberate user cancel, so it DOES end a running image
        // generation (unlike leaving the screen, which lets it finish).
        if (chatId != "") ImageGenerationJobRegistry.cancel(chatId)
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

    /** The active companion's display name for this chat, or "" when none is
     *  set. A cheap prefs read; used to stamp each assistant reply so its label
     *  is locked to the companion that produced it. */
    private fun currentCompanionLabel(): String {
        val personaId = preferences?.getPersonaId().orEmpty()
        if (personaId.isEmpty()) return ""
        return try {
            PersonaPreferences.getPersonaPreferences(this).getPersona(personaId).label
        } catch (_: Exception) {
            ""
        }
    }

    private fun putMessage(message: String, isBot: Boolean) {
        val map: HashMap<String, Any> = HashMap()

        map["message"] = message
        map["isBot"] = isBot

        // When this message was created, for the Message Details popup. Stored
        // as a string so it round-trips through the generic Gson history map
        // like every other key. Both roles get one; nothing invents a time for
        // messages saved before this feature.
        map[ChatAdapter.KEY_MESSAGE_TIME] = System.currentTimeMillis().toString()

        // Lock this assistant reply's label to the companion active right now,
        // so a later companion switch never rewrites past labels.
        if (isBot) {
            val companion = currentCompanionLabel()
            if (companion.isNotBlank()) map[ChatAdapter.KEY_COMPANION_NAME] = companion
        }

        messages.add(map)
        adapter?.notifyItemInserted(messages.size - 1)
        refreshPersistentIncludeControls()

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
        if (last["isBot"] == true) {
            last[MessageCompletionState.KEY_STATE] = MessageCompletionState.STREAMING
            // Freeze the producing model onto the reply the moment it begins, so
            // a later model switch never relabels this turn (plan §4.1). Only a
            // genuine streamed reply reaches here — error/image placeholders do
            // not — so those correctly carry no model attribution.
            val usedModel = model.ifBlank { preferences?.getModel().orEmpty() }
            if (usedModel.isNotBlank()) last[ChatAdapter.KEY_MESSAGE_MODEL] = usedModel
            // Reset the provider token capture for this fresh reply so a turn
            // whose provider reports no usage does not inherit the previous
            // turn's count.
            pendingResponseTokens = null
            // Fresh reasoning accumulator for this turn. The send hook attaches
            // it to reasoning-wanted requests; the response observer feeds it
            // from the split stream copy. A turn that never wants reasoning
            // simply never has it observed.
            currentTurnReasoning = org.teslasoft.assistant.reasoning.ReasoningStreamAccumulator()
            currentTurnShowReasoning = false
            currentTurnReasoningObservationActive = false
            currentTurnReasoningObserved = kotlinx.coroutines.CompletableDeferred()
        }
    }

    /** The current turn's reasoning accumulator (§7.2). Created when the
     *  streamed reply begins; fed by the response observer on reasoning-wanted
     *  turns; read once the split stream drains to stamp the reply's Thinking
     *  content and to echo reasoning state on a tool-call continuation. Null
     *  between turns. */
    @Volatile
    private var currentTurnReasoning: org.teslasoft.assistant.reasoning.ReasoningStreamAccumulator? = null

    /** Whether this turn's reasoning should be DISPLAYED (Show Reasoning on and
     *  the path returns visible reasoning). Reasoning may still be observed for
     *  continuation state when this is false (§7.2). */
    @Volatile
    private var currentTurnShowReasoning: Boolean = false

    /** True once the response observer has been asked to split this turn's
     *  stream for reasoning (display and/or continuation state), so the tool
     *  continuation knows to wait for that capture before dispatching. */
    @Volatile
    private var currentTurnReasoningObservationActive: Boolean = false

    /** Completes when the observer finishes draining this turn's split stream,
     *  so a tool-call continuation can await reasoning_details capture before
     *  building the follow-up. Null between turns. */
    @Volatile
    private var currentTurnReasoningObserved: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** Provider-reported total tokens for the reply currently streaming, taken
     *  from the usage-bearing final chunk (streamOptions.includeUsage). Null
     *  until reported and null when the provider does not report usage, so the
     *  display omits tokens rather than inventing a value. */
    private var pendingResponseTokens: Int? = null

    /** While a Retry is in flight, the versions the regenerated reply will be
     *  folded into: the prior turn's existing version list, or its single
     *  current reply wrapped as version one. Null for an ordinary first-time
     *  reply, which is never versioned. */
    private var pendingRetryVariants: MutableList<HashMap<String, String>>? = null

    /**
     * Fold the just-finished regenerated reply into its turn's version list as
     * the newest version and make it the canonical one, preserving every prior
     * version for browsing. No-op unless a Retry set [pendingRetryVariants].
     * Runs on both a successful and a terminal (interrupted/failed) finish so a
     * failed regeneration never discards the versions that came before it.
     */
    private fun mergePendingRetryVariants() {
        val history = pendingRetryVariants ?: return
        pendingRetryVariants = null
        val last = messages.lastOrNull() ?: return
        if (last["isBot"] != true) return

        history.add(ChatAdapter.snapshotVariant(last))
        last[ChatAdapter.KEY_VARIANTS] = ChatAdapter.variantsToJson(history)
        last[ChatAdapter.KEY_CANONICAL_VARIANT] = (history.size - 1).toString()
        last[ChatAdapter.KEY_DISPLAY_VARIANT] = (history.size - 1).toString()
        adapter?.notifyItemChanged(messages.size - 1)
    }

    /** Mark the last assistant reply as completed normally. The caller's
     *  existing completion saveSettings() persists it alongside the final text. */
    private fun markLastAssistantDone() {
        val last = messages.lastOrNull() ?: return
        if (last["isBot"] == true) {
            last[MessageCompletionState.KEY_STATE] = MessageCompletionState.DONE
            last.remove(MessageCompletionState.KEY_STATE_DETAIL)
            // Stamp the provider-reported total tokens for this turn, when the
            // provider reported them. Stored as a string like every other
            // history key; absent when unreported.
            pendingResponseTokens?.let { last[ChatAdapter.KEY_MESSAGE_TOKENS] = it.toString() }
            // A regeneration folds this finished reply into the turn's version
            // list. Runs after the metadata above so the new version captures
            // the final model, tokens, and state.
            mergePendingRetryVariants()
            // This chat just produced a successful reply, so its complete
            // model-and-companion snapshot may qualify for the next new chat.
            recordLastSuccessfulConfig()
        }
    }

    /** Remember the provider, model, routing, and companion that just produced
     *  a successful reply. All values must come from this same chat; an empty or
     *  deleted companion leaves the prior complete snapshot unchanged. */
    private fun recordLastSuccessfulConfig() {
        val endpointId = apiEndpointObject?.id?.takeIf { it.isNotBlank() }
            ?: preferences?.getApiEndpointId().orEmpty()
        val usedModel = model.ifBlank { preferences?.getModel().orEmpty() }
        val personaId = preferences?.getPersonaId().orEmpty()
        val personaExists = personaId.isNotBlank() &&
            PersonaPreferences.getPersonaPreferences(this).getPersona(personaId).label.isNotBlank()
        if (endpointId.isBlank() || usedModel.isBlank() || !personaExists) return
        val routing = favoriteForActiveEndpoint(usedModel)?.routingType
            ?: FavoriteModelObject.ROUTING_AUTOMATIC
        preferences?.setLastSuccessfulConfig(endpointId, usedModel, routing, personaId)
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
        // A regeneration that ended in a terminal state still keeps the prior
        // versions: fold this attempt in as the newest version rather than
        // letting the earlier good replies vanish with the failed retry.
        mergePendingRetryVariants()
        saveSettings()
    }

    /**
     * Enforce the invariant that a saved user message never ends with no
     * explanation and no Regenerate target: guarantee the turn ends with a
     * visible, regenerate-able assistant bubble carrying a comprehensible cause,
     * and (for a real problem, never a user stop) record it to the Error Log so
     * a future occurrence self-identifies.
     *
     * If no assistant bubble was ever created (a failure before streaming
     * began), one is created here. An already-completed reply is never
     * downgraded — only a still-streaming reply or a freshly-created empty
     * bubble is stamped.
     */
    private fun showTerminalFailure(
        state: String,
        detail: String,
        reason: String?,
        logAsError: Boolean,
        errorTag: String,
        errorSummary: String
    ) {
        if (messages.isEmpty() || messages[messages.size - 1]["isBot"] == false) {
            putMessage("", true)
        }
        val idx = messages.size - 1
        if (idx >= 0 && messages[idx]["isBot"] == true) {
            val existing = messages[idx][MessageCompletionState.KEY_STATE]?.toString()
            val stampable = existing == MessageCompletionState.STREAMING ||
                (existing.isNullOrBlank() && messages[idx]["message"]?.toString().isNullOrEmpty())
            if (stampable) {
                messages[idx][MessageCompletionState.KEY_STATE] = state
                messages[idx][MessageCompletionState.KEY_STATE_DETAIL] = detail
                if (!reason.isNullOrBlank()) {
                    messages[idx][MessageCompletionState.KEY_ERROR_TEXT] = reason
                }
                if (messages.size > 2) {
                    adapter?.notifyItemRangeChanged(messages.size - 3, messages.size - 1)
                } else {
                    adapter?.notifyItemChanged(messages.size - 1)
                }
            }
        }
        saveSettings()
        if (logAsError) {
            try {
                org.teslasoft.assistant.preferences.Logger.log(
                    this, "crash", errorTag, "error",
                    errorSummary +
                        "\nModel: ${model.ifBlank { "unknown" }}" +
                        "\nScreen: ${screenState()}" +
                        "\nNetwork: ${networkState()}"
                )
            } catch (_: Throwable) { /* diagnostics must never crash the failure path */ }
        }
    }

    /** Content of a message as the MODEL should see it, which is not always
     *  what the user sees.
     *
     *  An unfinished assistant reply gets an internal note appended so the
     *  model cannot mistake it for an intentionally completed reply. A user
     *  message carrying attachments gets those attachments rendered into it,
     *  in whatever form they are in right now — full text, a condensed
     *  version, or a tiny bookmark once removed. Neither is ever shown in
     *  the chat; this shapes the model projection only. */
    private fun modelFacingContent(message: HashMap<String, Any>): String {
        val content = message["message"].toString()
        if (message["isBot"] == true) {
            // A completed generated image is stored to the model as a file
            // marker, which means nothing to the model. Replace it with a plain
            // sentence saying an image exists here and who made it, using the
            // token-saving summary when one exists (owner request, Aug 16 2026).
            val meta = GeneratedImageMetadata.fromJson(message[GeneratedImageMetadata.KEY]?.toString())
            if (meta != null && meta.status == GeneratedImageMetadata.STATUS_COMPLETE) {
                return imageInjectionSentence(meta)
            }
            if (meta == null && content.trimStart().startsWith("~file:")) {
                return getString(R.string.image_gen_injection_legacy)
            }
            if (content.isNotBlank() && MessageCompletionState.isIncomplete(
                    message[MessageCompletionState.KEY_STATE]?.toString())) {
                return content + "\n\n" + getString(R.string.message_incomplete_model_note)
            }
            return content
        }
        return IncludeMessageProjection.userContent(
            typedText = content,
            includesJson = message[INCLUDES_KEY]?.toString()
        )
    }

    /** The reminder a completed generated image contributes to the model each
     *  turn: the token-saving summary (user edit, else summarizer version) or
     *  the full prompt when no summary exists yet, prefixed by who made it. */
    private fun imageInjectionSentence(meta: GeneratedImageMetadata): String {
        val text = meta.effectiveSummary() ?: meta.prompt
        if (text.isBlank()) return getString(R.string.image_gen_injection_legacy)
        val res = if (meta.initiatedByUser()) {
            R.string.image_gen_injection_user
        } else {
            R.string.image_gen_injection_model
        }
        return getString(res, text)
    }

    private suspend fun resolveImagePartsForSend(
        textMessages: List<ChatMessage>,
        includesList: List<String?>
    ): List<ChatMessage> = withContext(Dispatchers.IO) {
        val cid = chatId
        textMessages.mapIndexed { i, msg ->
            if (msg.role != ChatRole.User) return@mapIndexed msg
            val json = includesList.getOrNull(i) ?: return@mapIndexed msg
            val projection = IncludeMessageProjection.userMessageParts(
                msg.content?.toString().orEmpty(), json
            )
            if (projection.isTextOnly()) msg
            else buildMultiPartUserMessage(projection, cid)
        }
    }

    private fun buildMultiPartUserMessage(
        projection: ProjectedUserMessage,
        cid: String
    ): ChatMessage {
        val parts = ArrayList<ContentPart>()
        if (projection.text.isNotBlank()) {
            parts.add(TextPart(projection.text))
        }
        for (ref in projection.imageParts) {
            val include = ChatInclude(
                id = ref.includeId,
                fileName = ref.fileName,
                kind = if (ref.imageMimeType == "image/png") IncludeKind.PNG else IncludeKind.JPEG,
                form = IncludeForm.FULL,
                fullText = "",
                imageFileHash = ref.imageFileHash,
                imageMimeType = ref.imageMimeType
            )
            val file = ImageImporter.imageFile(this, cid, include) ?: continue
            if (!file.exists()) continue
            val bytes = try { file.readBytes() } catch (_: Exception) { continue }
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            parts.add(ImagePart("data:${ref.imageMimeType};base64,$encoded"))
        }
        return if (parts.isEmpty()) {
            ChatMessage(role = ChatRole.User, content = "")
        } else if (parts.size == 1 && parts[0] is TextPart) {
            ChatMessage(role = ChatRole.User, content = projection.text)
        } else {
            ChatMessage(role = ChatRole.User, content = parts)
        }
    }

    private fun conversationHasFullImages(
        includesList: List<String?>
    ): Boolean = includesList.any { json ->
        json != null && ChatInclude.listFromJson(json).any { it.hasLiveImageBytes() }
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

    @Suppress("deprecation")
    // The raw error-response body captured by the chat client's ResponseObserver
    // for the current turn (null on success or a transport failure that never got
    // a response). Read in the failure handler to name the upstream provider.
    @Volatile private var capturedProviderErrorBody: String? = null

    private suspend fun generateResponse(
        request: String,
        shouldPronounce: Boolean,
        preparedTurn: PreparedRegularTurn? = null
    ) {
        // The single generation funnel is also the single guard point: no
        // generation into a chat whose stored history is locked or
        // preserved-corrupt (Round 4) — typed, voice and retry paths all
        // flow through here, and a reply that can't be saved must not be
        // produced over the blocking "Chat unavailable" state.
        if (chatStorageUnavailable) return

        // A fresh generation: any user-stop flag left from a previous turn is
        // cleared, so only a Stop during THIS generation counts as a user stop.
        userRequestedStop = false

        // Silent retry point: any completed image still missing its summary
        // gets one before this turn's history is projected to the model.
        ensureImageSummaries()

        // Clear any provider error captured on a previous turn before this one
        // makes its request, so a failure never shows a stale provider.
        capturedProviderErrorBody = null

        // Mint the turn id every streamed request in this visible turn (primary
        // plus any tool continuation) shares in the Response Lifecycle Log.
        beginLifecycleTurn()

        if (preparedTurn == null && !awaitVisionCapabilityCheck()) {
            restoreUIState()
            return
        }

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

            if (model.contains(":ft") || model.contains("ft:")) {
                putMessage("", true)
                markLastAssistantStreaming()
                startLifecycle(ResponseLifecycle.PHASE_PRIMARY, preferences?.getMaxTokens())
                val completionRequest = if (preferences?.getLogitBiasesConfigId() == null || preferences?.getLogitBiasesConfigId() == "null" || preferences?.getLogitBiasesConfigId() == "") {
                    CompletionRequest(
                        model = ModelId(model),
                        maxTokens = preferences!!.getMaxTokens(),
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
                        maxTokens = preferences!!.getMaxTokens(),
                        temperature = if (model.contains("gpt-5") || model.contains("o1") || model.contains("o3")) 1.0 else if (preferences!!.getTemperature().toDouble() == 0.7) null else preferences!!.getTemperature().toDouble(),
                        topP = if (preferences!!.getTopP().toDouble() == 1.0) null else preferences!!.getTopP().toDouble(),
                        frequencyPenalty = if (preferences!!.getFrequencyPenalty().toDouble() == 0.0) null else preferences!!.getFrequencyPenalty().toDouble(),
                        presencePenalty = if (preferences!!.getPresencePenalty().toDouble() == 0.0) null else preferences!!.getPresencePenalty().toDouble(),
                        prompt = request,
                        echo = false
                    )
                }

                val completions: Flow<TextCompletion> = ai!!.completions(completionRequest)

                // Dispatch begins at collection; a failure past this point is a
                // real provider/network end, not a pre-dispatch one.
                startGenerationNetworkDiagnostics()
                providerRequestDispatched = true
                completions.flowOn(Dispatchers.IO).collect { v ->
                    run {
                        if (!currentCoroutineContext().isActive) throw CancellationException()
                        val choice = v.choices.firstOrNull()
                        noteLifecycleChunk(
                            choice?.finishReason?.value, v.id,
                            (choice?.text?.takeIf { it != "null" }?.length ?: 0),
                            v.usage?.promptTokens, v.usage?.completionTokens, v.usage?.totalTokens
                        )
                        v.usage?.totalTokens?.let { pendingResponseTokens = it }
                        if (v.choices[0] != null && v.choices[0].text != null && v.choices[0].text.toString() != "null") {
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

                finalizeLifecycleSuccess()
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
                // The old Function Calling router — a hidden gpt-4o request
                // choosing between its image and web-search functions — is
                // removed entirely (image-generation-rebuild-plan.md §15).
                // Every normal chat goes straight to the regular request;
                // image creation lives in the create_image tool coordinator.
                run {
                    try {
                        regularGPTResponse(shouldPronounce, preparedTurn)
                    } catch (toolsError: Exception) {
                        // §8: ONLY a clear tools-not-supported rejection of a
                        // tool-bearing request learns the capability and
                        // retries once without tools — the user's message is
                        // neither lost nor duplicated. Anything else (or a
                        // failing retry) falls through to the normal error
                        // funnel below.
                        if (toolsError !is CancellationException &&
                            lastRegularRequestCarriedImageTools &&
                            ToolSupportClassifier.isToolsNotSupportedError(toolsError.message)
                        ) {
                            // The first attempt was a real visible streamed
                            // request that the provider rejected for carrying
                            // tools. Close its lifecycle record before the
                            // without-tools retry opens a fresh primary record.
                            finalizeLifecycleTerminal(
                                ResponseLifecycle.Outcome.INCOMPLETE, "missing", true,
                                ResponseLifecycle.Termination.PROVIDER_ERROR, toolsError.message
                            )
                            val previousState = learnToolsUnsupportedAndNotify()
                            var retrySucceeded = false
                            try {
                                regularGPTResponse(
                                    shouldPronounce,
                                    preparedTurn,
                                    suppressImageTools = true
                                )
                                retrySucceeded = true
                            } finally {
                                recordToolCapabilityChangeEntry(
                                    previousState, toolsError.message, retrySucceeded
                                )
                            }
                        } else {
                            throw toolsError
                        }
                    }
                }
            }
        } catch (_: CancellationException) {
            // The stream was cut short. A cancelled coroutine does NOT prove the
            // user stopped it — only an actual Stop / Hang Up / cancel tap does,
            // and that set userRequestedStop. Classify by the real cause so a
            // benign user stop, an app/lifecycle interruption, and a genuinely
            // unknown early end are never conflated (owner ruling, Aug 8 2026).
            // No suspension points below, so this runs even though the coroutine
            // is already cancelled.
            val destroying = isFinishing || isDestroyed
            val replyStarted = messages.isNotEmpty() &&
                messages[messages.size - 1]["isBot"] == true
            val (state, detail) = MessageCompletionState.classifyCancellation(
                userRequestedStop, destroying, replyStarted
            )
            when (detail) {
                MessageCompletionState.DETAIL_USER_STOP -> {
                    // Deliberate user Stop: not an error. No chat marker and no
                    // Error Log entry; the Response Lifecycle still records it
                    // (kept on for now, for debugging).
                    finalizeLifecycleTerminal(
                        ResponseLifecycle.Outcome.STOPPED, "missing", true,
                        ResponseLifecycle.Termination.USER_STOP, null
                    )
                    finalizeStreamingMessageState(state, detail)
                }
                MessageCompletionState.DETAIL_START_FAILED -> {
                    // The reply never started. Include the known reason (a
                    // teardown) when there is one.
                    val reason = if (destroying)
                        getString(R.string.gen_interrupt_reason_screen_closed) else null
                    val summary = "reply could not start" + (reason?.let { ": $it" } ?: "")
                    finalizeLifecycleTerminal(
                        if (destroying) ResponseLifecycle.Outcome.CANCELLED
                        else ResponseLifecycle.Outcome.INCOMPLETE,
                        "missing", true,
                        if (destroying) ResponseLifecycle.Termination.APP_CANCEL
                        else ResponseLifecycle.Termination.STREAM_CLOSED,
                        summary
                    )
                    showTerminalFailure(
                        state, detail, reason, logAsError = true,
                        errorTag = "GenStartFailed", errorSummary = summary
                    )
                }
                MessageCompletionState.DETAIL_SCREEN_CLOSED -> {
                    // The app's own lifecycle tore the screen down mid-reply.
                    val reason = getString(R.string.gen_interrupt_reason_screen_closed)
                    finalizeLifecycleTerminal(
                        ResponseLifecycle.Outcome.CANCELLED, "missing", true,
                        ResponseLifecycle.Termination.APP_CANCEL, "app interrupted: $reason"
                    )
                    showTerminalFailure(
                        state, detail, reason, logAsError = true,
                        errorTag = "GenInterrupted", errorSummary = "app interrupted the reply: $reason"
                    )
                }
                else -> {
                    // Cancelled with no user stop and no teardown. Split on the
                    // dispatch boundary: before the provider request was sent this
                    // is a pre-dispatch cancellation (request_not_sent), recorded
                    // as Cancelled; once dispatched it is the existing "ended
                    // early; cause unknown" diagnostic. The visible row's terminal
                    // handling is unchanged either way.
                    if (providerRequestDispatched) {
                        finalizeLifecycleTerminal(
                            ResponseLifecycle.Outcome.INCOMPLETE, "missing", true,
                            ResponseLifecycle.Termination.STREAM_CLOSED, "ended early; cause unknown"
                        )
                    } else {
                        finalizeLifecycleTerminal(
                            ResponseLifecycle.Outcome.CANCELLED, "missing", true,
                            ResponseLifecycle.Termination.REQUEST_NOT_SENT, null
                        )
                    }
                    showTerminalFailure(
                        state, detail, null, logAsError = true,
                        errorTag = "GenUnknownEnd", errorSummary = "reply ended early; cause unknown"
                    )
                }
            }
            calculateCost()
            runOnUiThread {
                restoreUIState()
            }
        } catch (e: Exception) {
            playErrorSignal()
            val failureDiagnostics = captureGenerationFailureDiagnostics()
            stopHandsFreeOnError()
            // Single funnel: classify the failure to a stable code, always write
            // the diagnostic Error Log entry, and show the user the short coded
            // message (no profile/URL/model/trace — those live in the log). See
            // ERROR_CODES.md.
            val genError = GenerationErrorClassifier.classify(e)
            logGenerationError(genError, e, "message", failureDiagnostics)

            if (genError.isVisionRejection && conversationHasFullImages(chatMessageIncludes)) {
                recordVisionCapability(ImageCapability.UNSUPPORTED)
            }

            // Owner ruling (July 31 2026): beneath the app's own explanation,
            // always show the raw provider detail — the server's error and the
            // provider name (or a truthful placeholder for each).
            val appExplanation = genError.providerLimitMessage(this)
                ?: genError.chatMessage(this)
            val providerInfo = ProviderErrorInfo.parse(capturedProviderErrorBody)
            // Error responses sometimes report the upstream provider even when
            // no successful SSE stream began. Preserve it as actual only because
            // it came from the response body parsed above.
            currentLifecycle?.noteActualModelProvider(providerInfo.providerName)
            // Expanded provider detail (owner ruling, Aug 1 2026): the connection
            // profile's name, the upstream model service the server reported (or
            // "Not Reported" for a direct provider that names none), the model,
            // and the function. Resolved once so the on-screen block and the
            // Provider Failure Log entry are identical.
            val notReported = getString(R.string.provider_value_not_reported)
            val apiProviderName = apiEndpointObject?.label?.trim()?.ifBlank { null }
                ?: apiEndpointObject?.host?.trim()?.ifBlank { null } ?: notReported
            val modelServiceProvider = providerInfo.providerName?.trim()?.ifBlank { null } ?: notReported
            val modelName = model.trim().ifBlank { null }
                ?: apiEndpointObject?.model?.trim()?.ifBlank { null } ?: notReported
            val functionLabel = getString(R.string.provider_function_chat)
            val response = appExplanation + "\n" +
                genError.providerDetailBlock(
                    this, e.message, apiProviderName, modelServiceProvider, modelName, functionLabel, providerInfo.message
                )

            // Lifecycle: an errored reply is Incomplete. The termination source
            // separates a provider error (the server answered with an error)
            // from a dropped connection, a timeout, or a parse failure. The
            // error text is the raw server error / exception, verbatim.
            val lifecycleTermination = if (!providerRequestDispatched) {
                // Nothing was dispatched to the provider — the attempt ended
                // during request construction. Record only that fact; never a
                // provider, network, parser, or timeout category.
                ResponseLifecycle.Termination.REQUEST_NOT_SENT
            } else when {
                genError.reachedServer() -> ResponseLifecycle.Termination.PROVIDER_ERROR
                (e::class.java.name + " " + (e.message ?: "")).contains("timeout", ignoreCase = true) ->
                    ResponseLifecycle.Termination.CLIENT_TIMEOUT
                (e::class.java.name).contains("Serialization", ignoreCase = true) ->
                    ResponseLifecycle.Termination.PARSER_ERROR
                else -> ResponseLifecycle.Termination.NETWORK_ERROR
            }
            val lifecycleError = listOfNotNull(
                genError.httpStatus?.toString(),
                (providerInfo.message ?: e.message)?.trim()?.ifBlank { null }
            ).joinToString(" ").ifBlank { e.message ?: "error" }
            finalizeLifecycleTerminal(
                ResponseLifecycle.Outcome.INCOMPLETE,
                currentLifecycle?.lastFinishReason ?: "missing",
                true,
                lifecycleTermination, lifecycleError
            )

            // Record to the Provider Failure Log when enabled AND the server
            // actually answered — a user stop or a request that never reached a
            // server is not a provider fault and is never logged. The same
            // expanded fields the user sees, plus the server's own error.
            // A pre-dispatch failure contacted no provider, so it is never a
            // provider fault: skip the Provider Failure Log even if a local
            // exception happened to classify as one that "reached" a server.
            if (preferences?.getLogChatFailures() == true && genError.reachedServer() && providerRequestDispatched) {
                val providerErrorRaw = listOfNotNull(
                    genError.httpStatus?.toString(),
                    (providerInfo.message ?: e.message)?.trim()?.ifBlank { null }
                ).joinToString(" ").ifBlank { "(no message)" }
                org.teslasoft.assistant.preferences.Logger
                    .logProviderFailure(this, apiProviderName, modelServiceProvider, modelName, functionLabel, providerErrorRaw)
            }

            if (messages.isEmpty() || messages[messages.size - 1]["isBot"] == false) {
                putMessage("", true)
            }

            // The reply failed before finishing. Mark it failed (keeping whatever
            // partial text streamed in) and stash the coded error SEPARATELY from
            // the reply text — the error prose is no longer appended into the
            // message body, so the model's own words are never contaminated. The
            // adapter always renders the error next to the failure marker: a
            // failed reply is never hidden (owner ruling, July 31 2026).
            val failedIndex = messages.size - 1
            if (messages[failedIndex]["isBot"] == true) {
                messages[failedIndex][MessageCompletionState.KEY_STATE] = MessageCompletionState.FAILED
                messages[failedIndex][MessageCompletionState.KEY_STATE_DETAIL] = genError.code.code
                messages[failedIndex][MessageCompletionState.KEY_ERROR_TEXT] = response
                if (messages.size > 2) {
                    adapter?.notifyItemRangeChanged(messages.size - 3, messages.size - 1)
                } else {
                    adapter?.notifyItemChanged(messages.size - 1)
                }
            }

            saveSettings()
            calculateCost()

            // The provider gave a definite model-not-found answer — the only
            // honest "model no longer available" signal (owner spec, Aug 8 2026,
            // no pre-send catalog check). Guide the user to pick another in the
            // Summoning Circle; the failure bubble above still records it.
            if (genError.code == GenErrorCode.M2) {
                runOnUiThread { showModelUnavailableDialog() }
            }

            runOnUiThread {
                btnMicro?.isEnabled = true
                btnSend?.isEnabled = true
                progress?.visibility = View.GONE
                messageInput?.requestFocus()
            }
        } finally {
            try { generationNetworkMonitor?.close() } catch (_: Throwable) {}
            generationNetworkMonitor = null
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
    private fun recentTurnsContext(
        requestMessages: List<ChatMessage> = chatMessages
    ): String {
        return try {
            requestMessages.dropLast(1)
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
            // The Archive pause is passed into capture so the turn and the
            // bookmark's durable pause bit are reconciled atomically. The
            // memory injection switch is independent and is not read here.
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
            // Typed scene context (counterplan §4(e)): the chat's current
            // selections, stamped on the transcript row at capture — scene
            // identity is not muddled into the sampling-settings JSON.
            val turnWorldId = preferences?.getChatWorldId()?.takeIf { it.isNotBlank() }
            val turnCampaignId = preferences?.getChatCampaignId()?.takeIf { it.isNotBlank() }
            val turnRpCharacterId = preferences?.getChatRoleplayCharacterId()?.takeIf { it.isNotBlank() }
            val turnUserPersonaId = preferences?.getChatUserPersonaId()?.takeIf { it.isNotBlank() }
            val turnProjectId = preferences?.getChatProjectId()?.takeIf { it.isNotBlank() }

            Thread {
                TranscriptRecorder.recordTurn(
                    appContext, turnChatId, turnPersonaId, request, reply,
                    turnModel, quickSettings, excluded, replyComplete,
                    worldId = turnWorldId,
                    campaignId = turnCampaignId,
                    roleplayCharacterId = turnRpCharacterId,
                    userPersonaId = turnUserPersonaId,
                    projectId = turnProjectId
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
    private fun logGenerationError(
        result: GenErrorResult,
        e: Throwable,
        trigger: String,
        failureSnapshot: org.teslasoft.assistant.util.GenerationFailureSnapshot? = null
    ) {
        val voiceLive = org.teslasoft.assistant.util.resolveFailureVoiceState(
            failureSnapshot,
            isVoiceLive()
        )
        try {
            val sb = StringBuilder()
            sb.append(result.providerLimitMessage(this) ?: result.chatMessage(this)).append('\n')
            sb.append("Profile: ${apiEndpointObject?.label ?: "unknown"}\n")
            sb.append("Base URL: ${apiEndpointObject?.host ?: "unknown"}\n")
            sb.append("Model: ${model.ifBlank { "unknown" }}\n")
            sb.append("Voice: ${if (voiceLive) "active" else "inactive"}\n")
            sb.append("Trigger: $trigger\n")
            sb.append("Screen: ${screenState()}\n")
            if (failureSnapshot != null) {
                failureSnapshot.asLogLines().forEach { line ->
                    sb.append(line).append('\n')
                }
            } else {
                sb.append("Network: ${networkState()}\n")
            }
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
        logVoiceFailureSnapshot(result.code.code, voiceLive)
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

    /** Short name for an AudioDeviceInfo type, for the AudioRoute diagnostics. */
    private fun audioDeviceTypeName(type: Int): String = when (type) {
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth_a2dp"
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
        android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
        android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "builtin_speaker"
        android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "builtin_earpiece"
        android.media.AudioDeviceInfo.TYPE_HEARING_AID -> "hearing_aid"
        else -> "type_$type"
    }

    /** Best-effort snapshot of the current audio OUTPUT route. Read-only; any
     *  failure is reported rather than thrown so diagnostics never break audio. */
    private fun describeAudioOutputRoute(): String = try {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map { audioDeviceTypeName(it.type) }
            .distinct()
            .joinToString(",")
            .ifEmpty { "none" }
        @Suppress("DEPRECATION")
        val sco = try { am.isBluetoothScoOn } catch (_: Throwable) { false }
        "outputs=$outputs btSco=$sco"
    } catch (e: Throwable) { "unavailable (${e.javaClass.simpleName})" }

    /** AudioRoute-family snapshot, gated on voice diagnostics (called per turn). */
    private fun logAudioRoute(context: String) {
        logVoiceEvent("AudioRoute [$context]: ${describeAudioOutputRoute()}")
    }

    /** Register the read-only output-route observer. Device add/remove are rare,
     *  decisive events (a Bluetooth headset connecting), so they are always
     *  persisted; the current route is included so the connect and the resulting
     *  route land in one line. Never alters routing. */
    private fun registerAudioRouteDiagnostics() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val cb = object : android.media.AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) {
                    val names = addedDevices?.map { audioDeviceTypeName(it.type) }?.distinct()
                        ?.joinToString(",")?.ifEmpty { "none" } ?: "none"
                    logVoiceEventAlways("AudioRoute device(s) added: $names; now ${describeAudioOutputRoute()}")
                }
                override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
                    val names = removedDevices?.map { audioDeviceTypeName(it.type) }?.distinct()
                        ?.joinToString(",")?.ifEmpty { "none" } ?: "none"
                    logVoiceEventAlways("AudioRoute device(s) removed: $names; now ${describeAudioOutputRoute()}")
                }
            }
            am.registerAudioDeviceCallback(cb, audioRouteHandler)
            audioRouteCallback = cb
        } catch (e: Throwable) {
            logVoiceEventAlways("AudioRoute diagnostics unavailable: ${e.javaClass.simpleName}")
        }
    }

    /** Detach the read-only output-route observer. Activity-owned, so it must be
     *  released with the Activity instance (called from onDestroy). */
    private fun unregisterAudioRouteDiagnostics() {
        val cb = audioRouteCallback ?: return
        try {
            (getSystemService(AUDIO_SERVICE) as AudioManager).unregisterAudioDeviceCallback(cb)
        } catch (_: Throwable) { /* already gone */ }
        audioRouteCallback = null
    }

    /** Start a fresh trace at the exact point this provider request begins collection. */
    private fun startGenerationNetworkDiagnostics() {
        try { generationNetworkMonitor?.close() } catch (_: Throwable) {}
        generationNetworkMonitor = try {
            org.teslasoft.assistant.util.GenerationNetworkMonitor(this)
        } catch (_: Throwable) {
            null
        }
    }

    /** Freeze failure facts before hands-free/error teardown mutates the evidence. */
    private fun captureGenerationFailureDiagnostics(): org.teslasoft.assistant.util.GenerationFailureSnapshot {
        val network = generationNetworkMonitor?.snapshot()
            ?: org.teslasoft.assistant.util.GenerationNetworkSnapshot(
                atDispatch = "not observed",
                atFailure = networkState(),
                transitions = emptyList(),
                transitionTrackingAvailable = false
            )
        return org.teslasoft.assistant.util.GenerationFailureSnapshot(
            voiceLive = isVoiceLive(),
            network = network,
            generationKeepAlive = GenerationForegroundService.diagnostics(),
            handsFreeKeepAlive = HandsFreeService.connectionDiagnostics()
        )
    }

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
    private fun logVoiceFailureSnapshot(code: String, voiceWasLive: Boolean = isVoiceLive()) {
        if (!voiceWasLive) return
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
     * The §15.2c mid-conversation database warning: played ONCE when a
     * database failure is confirmed mid-session, hands-free sessions only
     * (owner ruling July 16 2026 — a typed session with the screen visible
     * gets the banner alone). "Distinct" is a hard requirement: an
     * alternating two-pitch warble (D5/Bb4 x2) that cannot be confused with
     * the descending error cadence, the low no-speech tone, or the ascending
     * done chime. Deliberately NOT gated on the error-sound preference: its
     * whole purpose is reaching a screen-off hands-free user, and it fires at
     * most once per new failure.
     */
    private fun playDatabaseWarningSignal() {
        Thread {
            var track: AudioTrack? = null
            try {
                val sampleRate = 44100
                // D5 -> Bb4 -> D5 -> Bb4: an "attention" warble, not a cadence.
                val notes = floatArrayOf(587.33f, 466.16f, 587.33f, 466.16f)
                val noteMs = 140
                val gapMs = 35
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
                        val sample = Math.sin(2.0 * Math.PI * freq * t) * envelope * 0.45 * Short.MAX_VALUE
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
                // The warning sound must never interfere with the banner path.
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

    /**
     * Builds the provider request once. The returned Aallam request and the
     * provider-neutral measurement payload are made from the same immutable
     * snapshots; callers must send [FrozenRegularRequest.request] directly.
     */
    private suspend fun buildFrozenRegularRequest(
        requestMessages: List<ChatMessage>,
        requestIncludes: List<String?>,
        loreQuery: String,
        selectedModel: String,
        maximumResponseTokens: Int,
        summaryInjection: String? = null
    ): FrozenRegularRequest {
        val msgs = ArrayList<ChatMessage>()

        // Stable base prompt: companion persona first, then the chat's system
        // instructions, exactly as before.
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
        if (effectiveSystemMessage.isNotEmpty()) {
            msgs.add(ChatMessage(role = ChatRole.System, content = effectiveSystemMessage))
        }

        // Selected-model rules are a separate prompt layer after the stable
        // companion/system content.
        if (preferences!!.getChatApplyModelRules() && MemoryStore.isProvisioned(this)) {
            val modelRulesBlock: String? = try {
                withContext(Dispatchers.IO) {
                    val rules = MemoryStore.getInstance(this@ChatActivity)
                        .getActiveModelRulesForModel(
                            preferences!!.getApiEndpointId(),
                            selectedModel
                        )
                    if (rules.isEmpty()) null
                    else rules.joinToString(
                        separator = "\n",
                        prefix = getString(R.string.model_rules_injection_header) + "\n"
                    ) { "- " + it.text }
                }
            } catch (e: Exception) {
                org.teslasoft.assistant.preferences.memory.MemoryLog.log(
                    this,
                    "ModelRules",
                    "error",
                    "Model rules unavailable this turn: ${e.message}"
                )
                null
            }
            modelRulesBlock?.let {
                msgs.add(ChatMessage(role = ChatRole.System, content = it))
            }
        }

        val loreBooksEnabled = preferences?.getChatLoreBooksEnabled() == true
        val allLoreMatches = ArrayList<LoreBookMatch>()
        var activeLoreBookCount = -1
        var dedupedLoreMatches: List<LoreBookMatch> = emptyList()
        if (loreBooksEnabled) {
            try {
                val loreStore = LoreBookStore.getInstance(this)
                val activeBookIds = LinkedHashSet<String>()
                val checkedIds = preferences?.getActiveLoreBookIds() ?: arrayListOf()
                if (personaId != "") {
                    val loreBookPersona =
                        PersonaPreferences.getPersonaPreferences(this).getPersona(personaId)
                    if (loreBookPersona.coreLoreBookId != "") {
                        activeBookIds.add(loreBookPersona.coreLoreBookId)
                    }
                    val linked = loreBookPersona.additionalLoreBookIdList()
                    activeBookIds.addAll(checkedIds.filter { linked.contains(it) })
                } else {
                    activeBookIds.addAll(checkedIds)
                }
                // One batched call across every active book (counterplan Step
                // 1.6) rather than one query per book.
                allLoreMatches.addAll(loreStore.findMatches(loreQuery, activeBookIds.toList()))
                activeLoreBookCount = activeBookIds.size
            } catch (e: Exception) {
                org.teslasoft.assistant.preferences.memory.MemoryLog.log(
                    this,
                    "LoreBook",
                    "error",
                    "Lorebook unavailable this turn: ${e.message}"
                )
            }
            // Cross-book dedup (Step 1.6): the same lore content can live in
            // two active books; first occurrence (core book first) wins. This
            // path has no entry/character budget of its own, so dedup is the
            // only thing that can drop a match here.
            dedupedLoreMatches = LoreDedup.dedup(allLoreMatches)
            LoreBookInjectionLog.record(
                userMessage = loreQuery,
                matched = allLoreMatches,
                activeBooks = activeLoreBookCount,
                injected = dedupedLoreMatches,
                cut = LoreDedup.droppedDuplicates(allLoreMatches).map { (dup, _) ->
                    LoreBookInjectionLog.Cut(dup, "duplicate content")
                }
            )
        }

        var memoryAssembly: String? = null
        if (preferences?.getChatMemoryEnabled() == true && MemoryStore.isProvisioned(this)) {
            memoryAssembly = try {
                withContext(Dispatchers.IO) {
                    org.teslasoft.assistant.preferences.memory.enforcer.Enforcer
                        .getInstance(this@ChatActivity)
                        .assembleTurn(
                            org.teslasoft.assistant.preferences.memory.enforcer.Enforcer.TurnInput(
                                chatId = chatId,
                                personaId = personaId,
                                userMessage = loreQuery,
                                recentContext = recentTurnsContext(requestMessages),
                                modelTag = selectedModel,
                                // Lore is frozen as its own complete request
                                // layer immediately after memory below. Passing
                                // it into Enforcer would apply its legacy
                                // injection cap before request-capacity checks.
                                loreMatches = emptyList(),
                                worldId = preferences?.getChatWorldId(),
                                campaignId = preferences?.getChatCampaignId(),
                                roleplayCharacterId =
                                    preferences?.getChatRoleplayCharacterId(),
                                userPersonaId = preferences?.getChatUserPersonaId(),
                                projectId = preferences?.getChatProjectId()
                            )
                        )
                }
            } catch (e: Exception) {
                org.teslasoft.assistant.preferences.memory.MemoryLog.log(
                    this,
                    "Enforcer",
                    "error",
                    "Assembly failed, lore-books-only this turn: ${e.message}"
                )
                notifyMemoryDegradedOnce()
                null
            }
        }

        // Summary injection (decision 14): before the retained history, since
        // it stands in for earlier turns that were folded away and belongs in
        // chronological position ahead of them.
        if (summaryInjection != null) {
            msgs.add(ChatMessage(role = ChatRole.System, content = summaryInjection))
        }

        // Conversation history, all active attachments embedded in their user
        // turns, and the current input have already been frozen in this list.
        // Image bytes are loaded from disk and base64-encoded here (IO thread)
        // so they are never held in memory between turns. Resolved as one
        // ordered list, then split so memory/lore land right before only the
        // newest message: the retained history above them stays a stable,
        // cacheable prefix turn to turn instead of trailing content that's
        // regenerated every turn (owner ruling, Aug 15 2026 — memory and lore
        // used to sit ahead of the whole history and broke prefix caching for
        // it on every single turn).
        val resolvedHistory = resolveImagePartsForSend(requestMessages, requestIncludes)
        msgs.addAll(resolvedHistory.dropLast(1))

        if (memoryAssembly != null) {
            msgs.add(ChatMessage(role = ChatRole.System, content = memoryAssembly))
        }

        if (dedupedLoreMatches.isNotEmpty()) {
            val loreText = StringBuilder(getString(R.string.lorebook_injection_header))
            for (match in dedupedLoreMatches) {
                loreText.append("\n- ").append(match.entry.content)
            }
            msgs.add(ChatMessage(role = ChatRole.System, content = loreText.toString()))
        }

        resolvedHistory.lastOrNull()?.let { msgs.add(it) }

        val usesRestrictedSampling = selectedModel.contains("gpt-5") ||
            selectedModel.contains("o1") || selectedModel.contains("o3")
        val temperature = if (usesRestrictedSampling) {
            1.0
        } else {
            preferences!!.getTemperature().toDouble().takeUnless { it == 0.7 }
        }
        val topP = preferences!!.getTopP().toDouble().takeUnless { it == 1.0 }
        val frequencyPenalty =
            preferences!!.getFrequencyPenalty().toDouble().takeUnless { it == 0.0 }
        val presencePenalty =
            preferences!!.getPresencePenalty().toDouble().takeUnless { it == 0.0 }
        val seed = preferences!!.getSeed().takeIf { it.isNotEmpty() }?.toInt()
        val hasNoBiasConfig = preferences?.getLogitBiasesConfigId().isNullOrEmpty() ||
            preferences?.getLogitBiasesConfigId() == "null"
        val logitBias = if (hasNoBiasConfig && !usesRestrictedSampling) {
            logitBiasPreferences?.getLogitBiasesMap()?.toMap()
        } else {
            null
        }

        // §7: the create_image tool rides the SAME regular request when
        // offered — never a separate routing request. Both request builders
        // share this one availability decision, and §8's learned capability
        // withholds the tool only after a clear tools-not-supported error.
        val globalImagePreferences = Preferences.getPreferences(this, "")
        val imageTools = if (
            CreateImageTool.shouldOfferTool(
                globalImagePreferences.getAiCreateImagesEnabled(),
                globalImagePreferences.getImageGeneratorEndpointId(),
                globalImagePreferences.getImageGeneratorModel()
            ) &&
            chatModelMayReceiveImageTool(selectedModel)
        ) {
            listOf(CreateImageTool.definition())
        } else {
            null
        }

        val request = ChatCompletionRequest(
            model = ModelId(selectedModel),
            maxTokens = maximumResponseTokens,
            temperature = temperature,
            topP = topP,
            frequencyPenalty = frequencyPenalty,
            presencePenalty = presencePenalty,
            seed = seed,
            logitBias = logitBias,
            messages = msgs.toList(),
            tools = imageTools
        )
        val payloadMessages = msgs.map { message ->
            val role = when (message.role) {
                ChatRole.System -> "system"
                ChatRole.User -> "user"
                ChatRole.Assistant -> "assistant"
                ChatRole.Tool -> "tool"
                else -> message.role.toString().lowercase(Locale.ROOT)
            }
            RequestMessageSnapshot.freeze(role, message)
        }
        val payload = FrozenChatPayload(
            model = selectedModel,
            messages = payloadMessages,
            maximumResponseTokens = maximumResponseTokens,
            temperature = temperature,
            topP = topP,
            frequencyPenalty = frequencyPenalty,
            presencePenalty = presencePenalty,
            seed = seed,
            logitBias = logitBias
        )
        return FrozenRegularRequest(request, payload)
    }

    // streamOptions (include-usage) is beta-gated in the client library, like
    // the seed read in rebuildRequestWithoutTools; opt in for the primary
    // request builders below.
    @OptIn(com.aallam.openai.api.BetaOpenAI::class)
    private suspend fun regularGPTResponse(
        shouldPronounce: Boolean,
        preparedTurn: PreparedRegularTurn? = null,
        suppressImageTools: Boolean = false
    ) {
        disableAutoScroll = false

        var response = ""
        putMessage("", true)
        markLastAssistantStreaming()
        // Begin the lifecycle record the moment the visible assistant row
        // exists — BEFORE request construction — so a failure or cancellation
        // during construction still produces a record and is classified as a
        // pre-dispatch (request_not_sent) end, not a provider/network failure.
        // The requested-output value is read the same way the request below is
        // built, so the recorded figure is unchanged.
        startLifecycle(
            ResponseLifecycle.PHASE_PRIMARY,
            preparedTurn?.request?.maxTokens ?: preferences?.getMaxTokens()
        )

        val msgs: ArrayList<ChatMessage>
        val chatCompletionRequest: ChatCompletionRequest
        if (preparedTurn != null) {
            // This is the object that was measured before the composer was
            // committed. Do not rebuild any part of it here — the §8
            // tools-rejected retry only strips the tool definition.
            chatCompletionRequest = if (suppressImageTools) {
                rebuildRequestWithoutTools(preparedTurn.request, preparedTurn.request.messages)
            } else {
                preparedTurn.request
            }
            msgs = ArrayList(preparedTurn.request.messages)
        } else {
            msgs = arrayListOf()

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

        // Model rules (owner_approved_rules §11 Revision 6): every ACTIVE rule
        // whose endpoint/model identity matches this chat renders as
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
                        .getActiveModelRulesForModel(
                            preferences!!.getApiEndpointId(),
                            model
                        )
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
        // Cross-book dedup (Step 1.6) applied once here, before either
        // downstream consumer (the enforcer below or the classic fallback
        // further down) ever sees the matches — first occurrence (core book
        // first) wins, so a memory copied into two active books is never
        // counted, budgeted, or injected twice.
        var dedupedLoreMatches: List<LoreBookMatch> = emptyList()
        // The same budget selection the enforcer applies to loreNotes,
        // precomputed here with the shared, pure LoreBookBudget so the debug
        // log can report exactly what reached the prompt regardless of
        // whether the enforcer or the classic fallback below ends up
        // rendering it — both act on the identical deduped input.
        var loreBudget = LoreBookBudget.Selection(emptyList(), emptyList())
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

                // One batched call across every active book (counterplan Step
                // 1.6) rather than one query per book.
                allLoreMatches.addAll(loreStore.findMatches(lastUserMessageForLore, activeBookIds.toList()))
                activeLoreBookCount = activeBookIds.size
            } catch (e: Exception) {
                // The lorebook is now SQLCipher-backed; if its key/store is ever
                // unreadable the conversation must continue without lore rather
                // than crash mid-generation (never break the companion).
                org.teslasoft.assistant.preferences.memory.MemoryLog.log(this, "LoreBook", "error", "Lorebook unavailable this turn: ${e.message}")
            }
            dedupedLoreMatches = LoreDedup.dedup(allLoreMatches)
            loreBudget = LoreBookBudget.select(
                dedupedLoreMatches, LoreBookStore.MAX_INJECTED_ENTRIES, LoreBookStore.MAX_INJECTED_CHARS
            )
            // Every turn is recorded — zero-match and store-unavailable turns
            // included — so "lore didn't reach the model" is diagnosable from
            // the debug screen instead of invisible. injected/cut reflect what
            // actually reaches the prompt below, not the raw search results.
            LoreBookInjectionLog.record(
                userMessage = lastUserMessageForLore,
                matched = allLoreMatches,
                activeBooks = activeLoreBookCount,
                injected = loreBudget.kept,
                cut = LoreDedup.droppedDuplicates(allLoreMatches).map { (dup, _) ->
                    LoreBookInjectionLog.Cut(dup, "duplicate content")
                } + loreBudget.cut.map { LoreBookInjectionLog.Cut(it.match, it.reason) }
            )
        }

        // Full memory system (Phase 4 enforcer): assemble the per-turn memory
        // message — retrieved memories, lore notes, scene — as ONE separate
        // system message after the stable base prompt. Gated on the per-chat
        // "Use memory" switch alone (Quick Settings is God; the engine tier is
        // only its default). With lore books off for the chat, dedupedLoreMatches
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
                                loreMatches = dedupedLoreMatches,
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

        // Summarizer transmission on the legacy in-line path (retry/voice):
        // same summary injection (decision 14) and bookmark trim (decision
        // 15) as the frozen path. Both values are read back-to-back so a
        // fold-in commit can't split the summary/bookmark pair.
        val legacySummaryInjection = summarizerInjectionText()
        val legacySummarizerTrim = summarizerTrimmedHistory()
        if (legacySummaryInjection != null) {
            msgs.add(ChatMessage(role = ChatRole.System, content = legacySummaryInjection))
        }

        // Resolved as one ordered list, then split so memory/lore land right
        // before only the newest message: the retained history above them
        // stays a stable, cacheable prefix turn to turn instead of trailing
        // content that's regenerated every turn (owner ruling, Aug 15 2026 —
        // same fix as the frozen path above).
        val legacyResolvedHistory = resolveImagePartsForSend(
            legacySummarizerTrim?.first ?: chatMessages,
            legacySummarizerTrim?.second ?: chatMessageIncludes
        )
        msgs.addAll(legacyResolvedHistory.dropLast(1))

        if (memoryAssembly != null) {
            msgs.add(
                ChatMessage(
                    role = ChatRole.System,
                    content = memoryAssembly
                )
            )
        } else if (loreBudget.kept.isNotEmpty()) {
            // Safety budget: a message that trips many triggers at once must not
            // flood the context. Inject at most MAX_INJECTED_ENTRIES memories /
            // MAX_INJECTED_CHARS characters, in book order (core book first).
            // Same shared selection the enforcer would have used for loreNotes
            // had it run (counterplan Step 1.6) — precomputed above as
            // [loreBudget] so this path and the debug log agree with it.
            val loreText = StringBuilder(getString(R.string.lorebook_injection_header))
            for (match in loreBudget.kept) {
                loreText.append("\n- ").append(match.entry.content)
            }
            msgs.add(
                ChatMessage(
                    role = ChatRole.System,
                    content = loreText.toString()
                )
            )
        }

        legacyResolvedHistory.lastOrNull()?.let { msgs.add(it) }

        // §7: the same tool-availability decision as the frozen typed-send
        // builder — neither regular path may silently omit the image tool.
        val globalImagePreferences = Preferences.getPreferences(this, "")
        val legacyPathImageTools = if (
            !suppressImageTools &&
            CreateImageTool.shouldOfferTool(
                globalImagePreferences.getAiCreateImagesEnabled(),
                globalImagePreferences.getImageGeneratorEndpointId(),
                globalImagePreferences.getImageGeneratorModel()
            ) &&
            chatModelMayReceiveImageTool(model)
        ) {
            listOf(CreateImageTool.definition())
        } else {
            null
        }

        chatCompletionRequest = if (preferences?.getLogitBiasesConfigId() == null || preferences?.getLogitBiasesConfigId() == "null" || preferences?.getLogitBiasesConfigId() == "") {
            ChatCompletionRequest(
                model = ModelId(model),
                maxTokens = preferences!!.getMaxTokens(),
                temperature = if (model.contains("gpt-5") || model.contains("o1") || model.contains("o3")) 1.0 else if (preferences!!.getTemperature().toDouble() == 0.7) null else preferences!!.getTemperature().toDouble(),
                topP = if (preferences!!.getTopP().toDouble() == 1.0) null else preferences!!.getTopP().toDouble(),
                frequencyPenalty = if (preferences!!.getFrequencyPenalty().toDouble() == 0.0) null else preferences!!.getFrequencyPenalty().toDouble(),
                presencePenalty = if (preferences!!.getPresencePenalty().toDouble() == 0.0) null else preferences!!.getPresencePenalty().toDouble(),
                seed = if (preferences!!.getSeed() != "") preferences!!.getSeed().toInt() else null,
                logitBias = if (model.contains("gpt-5") || model.contains("o1") || model.contains("o3")) null else logitBiasPreferences?.getLogitBiasesMap(),
                messages = msgs,
                tools = legacyPathImageTools,
                // Ask supported providers to include token usage in the stream
                // for the Response Lifecycle Log; ignored where unsupported.
                streamOptions = StreamOptions(includeUsage = true)
            )
        } else {
            ChatCompletionRequest(
                model = ModelId(model),
                maxTokens = preferences!!.getMaxTokens(),
                temperature = if (model.contains("gpt-5") || model.contains("o1") || model.contains("o3")) 1.0 else if (preferences!!.getTemperature().toDouble() == 0.7) null else preferences!!.getTemperature().toDouble(),
                topP = if (preferences!!.getTopP().toDouble() == 1.0) null else preferences!!.getTopP().toDouble(),
                frequencyPenalty = if (preferences!!.getFrequencyPenalty().toDouble() == 0.0) null else preferences!!.getFrequencyPenalty().toDouble(),
                presencePenalty = if (preferences!!.getPresencePenalty().toDouble() == 0.0) null else preferences!!.getPresencePenalty().toDouble(),
                seed = if (preferences!!.getSeed() != "") preferences!!.getSeed().toInt() else null,
                messages = msgs,
                tools = legacyPathImageTools,
                // Ask supported providers to include token usage in the stream
                // for the Response Lifecycle Log; ignored where unsupported.
                streamOptions = StreamOptions(includeUsage = true)
            )
        }
        }

        // §8 retry support: remembered so a failure of THIS request can be
        // judged as a tools rejection by the wrapper in generateResponse.
        lastRegularRequestCarriedImageTools = chatCompletionRequest.tools != null

        val completions: Flow<ChatCompletionChunk> =
            ai!!.chatCompletions(chatCompletionRequest)

        // §7.1: tool-call fragments accumulate until the name and JSON
        // arguments are complete. Providers stream them differently — many
        // fragments or one complete chunk — and a stream that dies mid-call
        // fails cleanly at validation instead of hanging the turn.
        val toolCallAssembler = StreamedToolCallAssembler()

        scroll(true)

        // The provider request begins dispatch here; from this point a failure
        // is a genuine provider/network/stream end, not a pre-dispatch one.
        startGenerationNetworkDiagnostics()
        providerRequestDispatched = true
        completions.flowOn(Dispatchers.IO).collect { v ->
            run {
                if (!currentCoroutineContext().isActive) throw CancellationException()
                val choice = v.choices.firstOrNull()
                // A usage-only final chunk (requested via streamOptions) carries
                // an EMPTY choices list, so every choice access here must be
                // null-safe — the old v.choices[0] would throw on that chunk.
                noteLifecycleChunk(
                    choice?.finishReason?.value, v.id,
                    (choice?.delta?.content?.takeIf { it != "null" }?.length ?: 0),
                    v.usage?.promptTokens, v.usage?.completionTokens, v.usage?.totalTokens
                )
                v.usage?.totalTokens?.let { pendingResponseTokens = it }
                choice?.delta?.toolCalls?.forEach { fragment ->
                    toolCallAssembler.accept(
                        fragment.index,
                        fragment.id?.id,
                        fragment.function?.nameOrNull,
                        fragment.function?.argumentsOrNull
                    )
                }
                val deltaContent = choice?.delta?.content
                if (deltaContent != null && deltaContent != "null") {
                    response += deltaContent
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

        // The primary stream ended on its own. Finalize its lifecycle record
        // now — before any tool-call continuation opens its own record under
        // the same turn id — so a completed primary and an interrupted
        // continuation stay separate, comparable entries.
        finalizeLifecycleSuccess()

        // §8: a completed stream of a tool-bearing request proves the
        // endpoint ACCEPTED tools for this model — whether or not the model
        // chose to use them. Refusal to call the tool never marks anything.
        if (chatCompletionRequest.tools != null) {
            recordChatToolCapability(model, ToolCapability.SUPPORTED)
        }

        // §7: an actual tool call is the ONLY thing that triggers a second
        // conversation-model request. Ordinary text responses fall through
        // to the normal completion below with exactly one request made.
        val assembledToolCalls = toolCallAssembler.assembled()
        if (assembledToolCalls.isNotEmpty()) {
            handleAssistantToolCalls(
                assembledToolCalls,
                chatCompletionRequest,
                response,
                shouldPronounce
            )
            return
        }

        messages[messages.size - 1]["message"] = "$response\n"
        markLastAssistantDone()

        if (conversationHasFullImages(chatMessageIncludes)) {
            recordVisionCapability(ImageCapability.SUPPORTED)
        }

        if (messages.size > 2) {
            adapter?.notifyItemRangeChanged(messages.size - 3, messages.size - 1)
        } else {
            adapter?.notifyItemChanged(messages.size - 1)
        }

        syncChatProjection()

        pronounce(shouldPronounce, response)

        saveSettings()
        calculateCost()

        // The next eligible summarizer cycle (decision 15 + errors doc §3):
        // a completed turn may have pushed a full batch past the window, and
        // a failed fold-in retries here — never in a rapid background loop.
        summarizerCycle()

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

                // Preserve the normal leading System prefix byte-for-byte so providers
                // can reuse any prompt cache already built for the conversation. The
                // title-only instruction belongs at the boundary immediately before
                // the first conversation turn. Appending it as a User message caused
                // some models to title the naming instruction itself instead.
                val m = ArrayList(msgs)
                val conversationStart = m.indexOfFirst { it.role != ChatRole.System }
                    .let { if (it >= 0) it else m.size }

                m.add(
                    conversationStart,
                    ChatMessage(
                        role = ChatRole.System,
                        content = "Create a concise 2-6 word title for the conversation that follows. " +
                                "Return only the title text. Describe the conversation topic, not this naming instruction. " +
                                "Do not explain or describe what the user wants. Do not prefix the title with " +
                                "'Title', 'Name', 'Chat', or 'Bot'."
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
                    // Ten tokens was too small for a heterogeneous set of models:
                    // some spend part of the completion budget before emitting title
                    // text. The prompt still constrains the visible answer to 2-6 words.
                    maxTokens = 128,
                    messages = m
                )

                // The naming REQUEST and the local rename are separate failure
                // events and must not share a catch: a request error is
                // transient (a later turn retries), while a rename failure
                // means editChat aborted with the chat intact under its old
                // name. One catch-all used to swallow both — including a
                // half-applied settings copy.
                val newChatName: String? = try {
                    val rawName = ai!!.chatCompletion(chatCompletionRequest2)
                        .choices.firstOrNull()?.message?.content
                    rawName
                        ?.trim()
                        ?.lineSequence()
                        ?.firstOrNull { it.isNotBlank() }
                        ?.trim()
                        ?.replace(Regex("(?i)^(title|name)\\s*:\\s*"), "")
                        ?.trim()
                        ?.removeSurrounding("\"")
                        ?.removeSurrounding("'")
                        ?.removeSurrounding("`")
                        ?.removeSurrounding("**")
                        ?.trim()
                        ?.take(80)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
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
                        val previousChatId = chatId
                        chatId = Hash.hash(newChatName)
                        // Re-point any running image generation (and this
                        // screen's registry listener) at the renamed chat id
                        // so its terminal state cannot land in the deleted
                        // placeholder chat.
                        ImageGenerationJobRegistry.rename(previousChatId, chatId)

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
        // requirement). Ordinary turns are unchanged: st (a voice turn) or
        // Always-speak drive the readback.
        val willReadAloud = st || preferences!!.getNotSilence() || handsFree

        // TTS lifecycle: proves pronounce() was reached and a readback was
        // expected for this turn — the baseline every later TTS lifecycle
        // line (or its absence) is read against.
        if (willReadAloud) {
            logTtsLifecycle("TTS requested engine=${preferences?.getTtsEngine()} handsFree=$handsFree")
        }

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
            // TTS/readback boundary — the point the later reproduction reported the
            // chat being destroyed right at. Snapshot the output route here so a
            // Bluetooth handoff at this boundary can be correlated with (or ruled
            // out against) an Activity destruction recorded moments later.
            logAudioRoute("readback start")
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
            val spoken = toSpokenText(message)
            if (autoLangDetect) {
                try {
                    // ML Kit clients hold native resources: re-creating one per
                    // readback without closing the old one leaked a client per
                    // spoken reply across a long hands-free session.
                    try { languageIdentifier?.close() } catch (_: Exception) { /* already closed */ }
                    languageIdentifier = LanguageIdentification.getClient()
                    languageIdentifier?.identifyLanguage(spoken)
                        ?.addOnSuccessListener { languageCode ->
                            if (languageCode == "und") {
                                Log.i("MLKit", "Can't identify language.")
                            } else {
                                Log.i("MLKit", "Language: $languageCode")
                                tts!!.language = Locale.forLanguageTag(
                                    languageCode
                                )
                            }

                            speak(spoken, session)
                        }?.addOnFailureListener {
                            // Ignore auto language detection if an error is occurred
                            autoLangDetect = false
                            ttsPostInit()

                            speak(spoken, session)
                        }
                } catch (_: NullPointerException) {
                    autoLangDetect = false
                    ttsPostInit()

                    speak(spoken, session)
                }
            } else {
                speak(spoken, session)
            }
        }
    }

    /** What TTS should actually say. When "Read Formatting Language" is off
     *  (the default), Markdown formatting is not pronounced and code blocks
     *  become a short spoken note; when on, the reply is spoken verbatim. This
     *  only affects speech — the on-screen message is rendered from the
     *  original text elsewhere and is never changed here. Applied once at each
     *  top-level entry to the speech path, before the text is chunked. */
    private fun toSpokenText(raw: String): String {
        return if (GlobalPreferences.getPreferences(this).getReadFormattingLanguage()) {
            raw
        } else {
            SpeechTextFormatter.forSpeech(raw)
        }
    }

    private fun speak(message: String, session: Int = readbackSession) {
        // The user stopped this readback while it was still in flight (see
        // readbackSession) — starting the audio now would speak over a stop.
        if (session != readbackSession) {
            logTtsLifecycle("TTS skipped reason=stale_session (stopped or superseded before dispatch)")
            return
        }
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
                if (session != readbackSession) {
                    logTtsLifecycle("TTS skipped reason=stale_session (stopped between dispatch and main-looper run)")
                    return@runSpeak
                }
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
                    logTtsLifecycle("TTS onError utteranceId=$utteranceId code=dispatch_rejected (engine.speak() returned ERROR)")
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
                logTtsLifecycle("TTS skipped reason=openai_key_missing")
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
                                    logTtsLifecycle("TTS onDone engine=openai")
                                    adapter?.clearSpeakingPosition()
                                    // Mirror the device-TTS onDone path so a
                                    // cloud voice also keeps hands-free looping.
                                    onHandsFreeReadbackFinished()
                                }
                                mediaPlayer?.setOnErrorListener { _, what, extra ->
                                    logTtsLifecycle("TTS onError engine=openai code=$what/$extra (mediaPlayer playback error)")
                                    adapter?.clearSpeakingPosition()
                                    // A playback error must not strand the loop
                                    // either — re-arm as if readback finished.
                                    onHandsFreeReadbackFinished()
                                    false
                                }
                                mediaPlayer?.start()
                                logTtsLifecycle("TTS onStart engine=openai")
                                beginHandsFreeReadbackWatch()
                            } catch (ex: IOException) {
                                logTtsLifecycle("TTS onError engine=openai code=io_exception (preparing local playback)")
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
                        logTtsLifecycle("TTS onError engine=openai code=request_failed (speech request never returned audio)")
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
        speak(toSpokenText(message))
    }

    override fun onRetryClick() {
        // Keep the reply being regenerated as an alternate version instead of
        // discarding it (owner spec, Aug 16 2026). Snapshot the current last
        // assistant turn's existing version list (or wrap its single current
        // reply) BEFORE it is removed; the regenerated reply is folded in as the
        // newest version once it finishes.
        val last = messages.lastOrNull()
        pendingRetryVariants = if (last != null && last["isBot"] == true) {
            val existing = ChatAdapter.parseVariants(last[ChatAdapter.KEY_VARIANTS]?.toString())
            if (existing.isNotEmpty()) existing
            else mutableListOf(ChatAdapter.snapshotVariant(last))
        } else {
            null
        }

        removeLastAssistantMessageIfAvailable()
        saveSettings()

        val message = findLastUserMessage()
        // Image attachments now ride as structured includes on the user
        // message record; a retry re-sends the same message and includes
        // via the normal send path — no legacy [image]/[imageType] fields
        // to unpack here.
        parseMessage(message["message"].toString(), false)
    }

    override fun onRegenerate(position: Int) {
        if (position < 0 || position >= messages.size) return
        if (messages[position]["isBot"] != true) return

        // The latest turn just adds a version (Stage 1) — nothing follows it, so
        // there is nothing to discard and no warning is needed.
        if (position == messages.size - 1) {
            onRetryClick()
            return
        }

        // An earlier turn: regenerating here discards everything after it, so
        // confirm the branch first (owner spec, Aug 16 2026, exact wording).
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.branch_regenerate_title)
            .setMessage(R.string.branch_regenerate_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.branch_regenerate_confirm) { _, _ ->
                // Record-only truncation (files preserved), then the turn at
                // this position is the last one, so the normal regenerate path
                // applies and keeps this turn's version history.
                truncateAfter(position)
                onRetryClick()
            }
            .show()
    }

    /**
     * Remove every message after [index] from the visible thread and the
     * model-facing projection, and persist the shortened history. Record-only,
     * by owner spec (Aug 16 2026): a truncated message that referenced a
     * generated image or an uploaded attachment loses only that reference — the
     * stored file keeps its independent lifetime and is never deleted here.
     */
    @SuppressLint("NotifyDataSetChanged")
    private fun truncateAfter(index: Int) {
        if (index < 0 || index >= messages.size - 1) return
        while (messages.size > index + 1) {
            messages.removeAt(messages.size - 1)
        }
        // Rebuild the projections to the shortened thread before rebinding so
        // the adapter never reads a selection slot that no longer exists.
        syncChatProjection()
        adapter?.notifyDataSetChanged()
        saveSettings()
    }

    /**
     * Make the response version currently displayed on the turn at [position]
     * its canonical one (owner spec, Aug 16 2026). On the latest turn nothing
     * follows it, so the switch is silent. On an earlier turn this changes the
     * causal branch, so it confirms ("Make Current Response?") and then discards
     * every message after that turn.
     */
    override fun onMakeVersionCurrent(position: Int) {
        if (position < 0 || position >= messages.size) return
        val msg = messages[position]
        if (msg["isBot"] != true) return
        val variants = ChatAdapter.parseVariants(msg[ChatAdapter.KEY_VARIANTS]?.toString())
        if (variants.size < 2) return
        val canonical = msg[ChatAdapter.KEY_CANONICAL_VARIANT]?.toString()?.toIntOrNull()
            ?: (variants.size - 1)
        val display = msg[ChatAdapter.KEY_DISPLAY_VARIANT]?.toString()?.toIntOrNull() ?: canonical
        if (display == canonical || display !in variants.indices) return

        if (position == messages.size - 1) {
            // Newest turn: nothing after it to destroy, so switch silently.
            promoteVersionAt(position, display)
            return
        }

        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.make_current_title)
            .setMessage(R.string.make_current_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.make_current_confirm) { _, _ ->
                promoteVersionAt(position, display)
                truncateAfter(position)
            }
            .show()
    }

    /** Make version [index] of the turn at [position] canonical: mirror it into
     *  the message's top-level fields, rebuild the model projection so context
     *  uses it, and persist. The pager rebinds, so its icon flips to the
     *  check_circle placeholder. */
    private fun promoteVersionAt(position: Int, index: Int) {
        if (position < 0 || position >= messages.size) return
        val msg = messages[position]
        val variants = ChatAdapter.parseVariants(msg[ChatAdapter.KEY_VARIANTS]?.toString())
        if (index !in variants.indices) return
        msg[ChatAdapter.KEY_CANONICAL_VARIANT] = index.toString()
        msg[ChatAdapter.KEY_DISPLAY_VARIANT] = index.toString()
        ChatAdapter.applyVariant(msg, variants[index])
        rebuildModelProjection()
        adapter?.notifyItemChanged(position)
        saveSettings()
    }

    override fun onResponseVersionChanged() {
        // Browsing is display-only: persist the pager position so it survives a
        // reopen, but never truncate history or rebuild the model projection —
        // the canonical version the conversation uses is unchanged.
        saveSettings()
    }

    private fun syncChatProjection() {
        rebuildModelProjection()
        updateMessagesSelectionProjection()
        calculateCost()
        refreshPersistentIncludeControls()
    }

    override fun onMessageEdited() {
        syncChatProjection()
    }

    override fun onMessageDeleted() {
        syncChatProjection()
    }

    override fun onIncludeEdit(includeId: String) {
        findIncludeById(includeId)?.let(::editInclude)
    }

    override fun onIncludeRemove(includeId: String) {
        findIncludeById(includeId)?.let(::removeInclude)
    }

    override fun onIncludeCondense(includeId: String) {
        findIncludeById(includeId)?.let(::condenseInclude)
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
        // Pending image attachments survive a config change through the
        // per-chat pending_includes preference (loadPendingIncludes), not
        // through the instance-state bundle, so there is nothing image-side
        // to write here anymore.
        super.onSaveInstanceState(outState)
    }

    private fun onRestoredState(savedInstanceState: Bundle?) {
        // Left as a no-op after the vision path was retired; pending image
        // includes rehydrate from preferences on load, not from the instance
        // state bundle. Kept as a hook in case a future piece of state needs
        // the same lifecycle place.
    }

    private fun requestAddApiEndpoint(feature: String, prompt: String) {
        val apiEndpointDialog: EditApiEndpointDialogFragment = EditApiEndpointDialogFragment.newInstance(
            "",
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
                    retry = RetryStrategy(maxRetries = 0)
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
        // No cleanup is required for the remaining speech-only branches.
    }

    private fun onOpenAIAction(feature: String, prompt: String) {
        when (feature) {
            "tts" -> speak(toSpokenText(prompt))
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
                val foldedBefore = preferences?.getSummarizerFoldedCount() ?: 0
                // §12 cleanup: note the generated-image files the selected
                // messages reference before they are removed.
                val deletedImageHashes = GeneratedImageFiles.referencedHashes(
                    messages.filterIndexed { index, _ ->
                        index < messagesSelectionProjection.size &&
                            messagesSelectionProjection[index]["selected"].toString() == "true"
                    }
                )
                var removedBeforeBookmark = 0
                var pos = 0
                var p = 0
                while (pos < messagesSelectionProjection.size) {
                    if (messagesSelectionProjection[pos]["selected"].toString() == "true") {
                        // Bulk delete bypasses ChatPreferences.deleteMessage,
                        // so the fold-in bookmark is realigned here the same
                        // way: one step per removed already-folded message.
                        if (pos < foldedBefore) removedBeforeBookmark++
                        messages.removeAt(pos - p)
                        p++
                    }

                    pos++
                }
                if (removedBeforeBookmark > 0) {
                    preferences?.setSummarizerFoldedCount(foldedBefore - removedBeforeBookmark)
                }

                syncChatProjection()
                saveSettings()
                if (deletedImageHashes.isNotEmpty()) {
                    GeneratedImageFiles.deleteIfUnreferenced(this, deletedImageHashes)
                }
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

        // The list's top is now genuinely CONSTRAINED below the action bar /
        // A2 health banner (activity_chat.xml, Build Phase 3): the old
        // hand-set "64dp + status inset" margin existed only because
        // match_parent ignored those constraints, and keeping it here would
        // double-count the offset (the action bar already carries the
        // status-bar padding applied above) and open a dead gap at the top
        // of the chat. Constraints own the position; the margin stays zero.
        layoutParams.topMargin = 0

        messages.layoutParams = layoutParams
    }

    private fun finishActivity() {
        val root: View = findViewById(R.id.root)
        root.animate().alpha(0f).setDuration(200)
        supportFinishAfterTransition()
    }
}
