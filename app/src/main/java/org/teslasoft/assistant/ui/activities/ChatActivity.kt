Warning: truncated output (original token count: 134032)
Total output lines: 11035

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

        /** Marks the primary/tool-generation request when lifecycle logging is
         * off, so final outbound-field diagnostics still exclude auxiliary
         * completed requests such as auto-naming. */
        private val generationRequestAttribute =
            AttributeKey<Boolean>("GenerationRequest")

        /** Records whether the generation request was serialized with
         * `stream=true`; the response observer cannot inspect the outgoing
         * body through Ktor's response-side request object. */
        private val streamingRequestAttribute =
            AttributeKey<Boolean>("StreamingRequest")

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
        tts = TextToSpeech(this, ttsLis…84032 tokens truncated…. */
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
        val streamingEnabled = preferences?.getStreaming() ?: true

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
                rebuildRequestWithoutTools(
                    preparedTurn.request,
                    preparedTurn.request.messages,
                    streamingEnabled
                )
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
                streamOptions = if (streamingEnabled) StreamOptions(includeUsage = true) else null
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
                streamOptions = if (streamingEnabled) StreamOptions(includeUsage = true) else null
            )
        }
        }

        // §8 retry support: remembered so a failure of THIS request can be
        // judged as a tools rejection by the wrapper in generateResponse.
        lastRegularRequestCarriedImageTools = chatCompletionRequest.tools != null

        // §7.1: tool-call fragments accumulate until the name and JSON
        // arguments are complete. Providers stream them differently — many
        // fragments or one complete chunk. The same assembler also accepts a
        // complete response's tool calls, so the tool flow is identical after
        // either transport path returns.
        val toolCallAssembler = StreamedToolCallAssembler()

        scroll(true)
        if (streamingEnabled) {
            generationRequestActive = true
            try {
                val completions: Flow<ChatCompletionChunk> =
                    ai!!.chatCompletions(chatCompletionRequest)

            // The provider request begins dispatch here; from this point a
            // failure is a genuine provider/network/stream end, not a
            // pre-dispatch one.
            startGenerationNetworkDiagnostics()
            providerRequestDispatched = true
            completions.flowOn(Dispatchers.IO).collect { v ->
                run {
                    if (!currentCoroutineContext().isActive) throw CancellationException()
                    val choice = v.choices.firstOrNull()
                    // A usage-only final chunk (requested via streamOptions)
                    // carries an EMPTY choices list, so every choice access here
                    // must be null-safe — the old v.choices[0] would throw on
                    // that chunk.
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
                        // Persist mid-stream so a killed process doesn't lose
                        // the partial reply — but NOT on every chunk:
                        // saveSettings() re-serializes and re-encrypts the WHOLE
                        // history on the main thread. The completion save below
                        // still persists the full reply.
                        val nowUptime = android.os.SystemClock.uptimeMillis()
                        if (nowUptime - lastStreamSaveUptime >= STREAM_SAVE_INTERVAL_MS) {
                            lastStreamSaveUptime = nowUptime
                            saveSettings()
                        }
                    }
                }
            }
            } finally {
                generationRequestActive = false
            }
        } else {
            // Use Aallam's completed-response API directly. This path does not
            // request a streamed body and does not carry stream_options; the
            // full assistant message is only rendered after the call returns.
            startGenerationNetworkDiagnostics()
            providerRequestDispatched = true
            val completion = try {
                generationRequestActive = true
                ai!!.chatCompletion(chatCompletionRequest)
            } finally {
                generationRequestActive = false
            }
            val choice = completion.choices.firstOrNull()
            val content = choice?.message?.content?.takeIf { it != "null" }
            response = content.orEmpty()
            noteLifecycleChunk(
                choice?.finishReason?.value, completion.id, response.length,
                completion.usage?.promptTokens, completion.usage?.completionTokens,
                completion.usage?.totalTokens
            )
            completion.usage?.totalTokens?.let { pendingResponseTokens = it }
            choice?.message?.toolCalls?.forEachIndexed { index, call ->
                if (call is ToolCall.Function) {
                    toolCallAssembler.accept(
                        index,
                        call.id.id,
                        call.function.nameOrNull,
                        call.function.argumentsOrNull
                    )
                }
            }
            messages[messages.size - 1]["message"] = response
            currentLifecycle?.markNonStreamingResponse()
            adapter?.notifyItemChanged(messages.size - 1)
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
                shouldPronounce,
                streamingEnabled
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
