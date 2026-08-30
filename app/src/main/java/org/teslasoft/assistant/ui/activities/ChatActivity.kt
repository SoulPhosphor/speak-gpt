Warning: truncated output (original token count: 155708)
Total output lines: 12728

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

import org.teslasoft.assistant.tts.api.*
import org.teslasoft.assistant.preferences.tts.TtsVoiceSelection
import org.teslasoft.assistant.preferences.tts.TtsVoiceKind
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
import android.view.Menu
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
import android.widget.PopupMenu
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
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import eightbitlab.com.blurview.BlurView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
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
import org.teslasoft.assistant.conversation.ConversationMode
import org.teslasoft.assistant.conversation.NewConversationCoordinator
import org.teslasoft.assistant.conversation.PendingConversationState
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.ActivationPromptPreferences
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.reasoning.LegacyReasoningRepair
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
import org.teslasoft.assistant.providers.ProviderDiagnosticSnapshot
import org.teslasoft.assistant.providers.RoutingBlock
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.includes.ChatInclude
import org.teslasoft.assistant.preferences.includes.CanonicalConversationMessage
import org.teslasoft.assistant.preferences.includes.DocumentImporter
import org.teslasoft.assistant.preferences.includes.ImageCapability
import org.teslasoft.assistant.preferences.includes.ImageCapabilityStore
import org.teslasoft.assistant.preferences.includes.ImageImporter
import org.teslasoft.assistant.preferences.includes.IncludeAuxiliaryRequestPolicy
import org.teslasoft.assistant.preferences.includes.IncludeForm
import org.teslasoft.assistant.preferences.includes.IncludeKind
import org.teslasoft.assistant.preferences.includes.IncludeMessageProjection
import org.teslasoft.assistant.preferences.includes.IncludeRenderer
import org.teslasoft.assistant.preferences.includes.ProjectedUserMessage
import org.teslasoft.assistant.preferences.includes.IncludeNotice
import org.teslasoft.assistant.preferences.includes.IncludeTextPolicy
import org.teslasoft.assistant.preferences.includes.PersistentIncludeContext
import org.teslasoft.assistant.preferences.includes.SummarizerSafeIncludeProjectionBuilder
import org.teslasoft.assistant.preferences.includes.StableAttachmentReference
import org.teslasoft.assistant.preferences.backup.readable.ReadableChatFormats
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationRepository
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationResult
import org.teslasoft.assistant.ui.util.ChatDeletionRequestCoordinator
import org.teslasoft.assistant.ui.util.ChatExportDialog
import org.teslasoft.assistant.ui.util.EditChatTitleDialog
import org.teslasoft.assistant.ui.util.IncludeEditDialog
import org.teslasoft.assistant.ui.util.IncludeStripController
import org.teslasoft.assistant.ui.util.IncludesPopupController
import org.teslasoft.assistant.util.AvatarRefreshCoordinator
import org.teslasoft.assistant.util.ProfileImageResolver
import org.teslasoft.assistant.preferences.LogitBiasPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.preferences.memory.ActiveMemoryAttribution
import org.teslasoft.assistant.preferences.memory.ActiveMemoryReference
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
import org.teslasoft.assistant.usage.ConversationUsageSummary
import org.teslasoft.assistant.usage.ProviderUsageAttempt
import org.teslasoft.assistant.usage.GenerationAttemptFailureException
import org.teslasoft.assistant.usage.ProviderUsageSnapshot
import org.teslasoft.assistant.usage.TokenCounts
import org.teslasoft.assistant.usage.TokenPricingCatalog
import org.teslasoft.assistant.usage.TokenPricingCatalogClient
import org.teslasoft.assistant.usage.TokenPricingSnapshot
import org.teslasoft.assistant.usage.TokenUsageAccounting
import org.teslasoft.assistant.usage.TurnUsageRecord
import org.teslasoft.assistant.ui.chat.ChatComposerLayout
import org.teslasoft.assistant.ui.chat.ChatExportFormat
import org.teslasoft.assistant.ui.chat.ChatExportFormatter
import org.teslasoft.assistant.ui.chat.ChatExportMessage
import org.teslasoft.assistant.ui.chat.ChatExportOptions
import org.teslasoft.assistant.ui.chat.ChatExportPdfWriter
import org.teslasoft.assistant.ui.chat.ChatImeInsetLayout
import org.teslasoft.assistant.ui.chat.StreamingBubbleScrollPolicy
import org.teslasoft.assistant.ui.chat.ChatNameStyle
import org.teslasoft.assistant.ui.chat.ChatSpeakerNames
import org.teslasoft.assistant.ui.chat.ConversationModeSelector
import org.teslasoft.assistant.ui.fragments.dialogs.EditApiEndpointDialogFragment
import org.teslasoft.assistant.ui.fragments.dialogs.QuickSettingsBottomSheetDialogFragment
import org.teslasoft.assistant.ui.fragments.tabs.PlaygroundFragment
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
import org.teslasoft.assistant.util.summarizer.CondensedRegenerationLock
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
import kotlinx.coroutines.asContextElement
import okio.FileSystem
import okio.Path.Companion.toPath

class ChatActivity : FragmentActivity(), ChatAdapter.OnUpdateListener,
    ImageGenerationJobRegistry.Listener, PlaygroundFragment.PendingCommitHost {

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

        private const val STATE_EXPLICIT_IMAGINE_DRAFT =
            "state_explicit_imagine_draft"

        /** How much of a document the bookmark-writing request sees. Enough
         *  to say what the file IS, without paying to send it all again. */
        private const val ARTIFACT_EXCERPT_CHARS = 2000

        /** Pins a split raw response to the lifecycle recorder for that exact request. */
        private val responseLifecycleRecorderAttribute =
            AttributeKey<ResponseLifecycleRecorder>("ResponseLifecycleRecorder")

        /** Pins durable accounting to the exact HTTP request independently of
         * optional diagnostics. */
        private val providerUsageAttemptAttribute =
            AttributeKey<ProviderUsageAttempt>("ProviderUsageAttempt")

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
            AttributeKey<ReasoningObservationContext>("ReasoningObservation")

    }

    private data class ReasoningObservationContext(
        val accumulator: org.teslasoft.assistant.reasoning.ReasoningStreamAccumulator,
        val endpointId: String,
        val modelId: String,
        val showReasoning: Boolean
    )

    // Init UI
    private var messageInput: EditText? = null
    private var btnSend: ImageButton? = null
    private var btnMicro: ImageButton? = null
    private var btnSettings: ImageButton? = null
    private var btnChatMenu: ImageButton? = null
    private var progress: CircularProgressIndicator? = null
    private var chat: RecyclerView? = null
    private var activityTitle: TextView? = null
    private var btnQuickSettings: ImageButton? = null
    private var fileContents: ByteArray? = null
    private var pendingChatExportBytes: ByteArray? = null
    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var conversationModeSelector: ConversationModeSelector? = null
    private var playgroundPanel: View? = null
    private var conversationMode: ConversationMode = ConversationMode.CHAT
    private var pendingConversation = false

    // Conversation summarizer (conversation-summary-plan.md decisions 11 +
    // 16): data_alert first in the icon row (with the 1–5 count badge),
    // then the subject summary icon. The controller runs the background
    // fold-ins; it is cancelled deliberately (never an error) when this
    // screen goes away.
    private var btnSummary: ImageButton? = null
    private var btnSummarizerErrors: ImageButton? = null
    private var summarizerErrorBadge: TextView? = null
    private var summarizerController: org.teslasoft.assistant.util.summarizer.SummarizerController? = null
    private var summarizerOperationChip: com.google.android.material.card.MaterialCardView? = null
    private var summarizerOperationSpinner: CircularProgressIndicator? = null
    private var summarizerOperationSuccess: ImageView? = null
    private var summarizerOperationText: TextView? = null
    private var summarizerOperationCancel: com.google.android.material.button.MaterialButton? = null
    private val summarizerStatusHandler = Handler(Looper.getMainLooper())
    private var projectionStatusVisible = false
    private val hideSummarizerStatus = Runnable {
        projectionStatusVisible = false
        summarizerOperationChip?.visibility = View.GONE
    }
    private val summarizerListener = object :
        org.teslasoft.assistant.util.summarizer.SummarizerController.Listener {
        override fun onSummarizerStateChanged() {
            runOnUiThread {
                refreshSummarizerIcons()
                refreshManualCompactionMarker()
            }
        }

        override fun onSummarizerErrorEpisode() {
            playSummarizerErrorSignal()
        }

        override fun onSummarizerOperationChanged(
            state: org.teslasoft.assistant.util.summarizer.SummarizerController.OperationState
        ) {
            runOnUiThread { renderSummarizerOperation(state) }
        }
    }

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
    private var btnChatTools: ImageButton? = null
    private var btnPersistentIncludes: ImageButton? = null
    private var btnExpandContent: ImageButton? = null
    private var btnCollapseContent: ImageButton? = null
    private var visionActions: LinearLayout? = null
    private var toolActions: LinearLayout? = null
    // Each paperclip-menu action is a labeled row, not an icon-only button.
    private var btnVisionActionCamera: View? = null
    private var btnVisionActionGallery: View? = null
    private var btnVisionActionDocument: View? = null
    private var btnToolCompact: View? = null
    private var btnToolCreateImage: View? = null
    private var explicitImagineDraft = false

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
    private var blurToolView: BlurView? = null

    // Init chat
    private var messages: ArrayList<HashMap<String, Any>> = arrayListOf()

    /** True when this chat's stored history is LOCKED or CORRUPT (Round 4):
     *  the owner-approved "Chat unavailable" state is showing, and sending,
     *  saving and generation are refused so the preserved encrypted value
     *  can never be overwritten by this screen's (empty) in-memory view. */
    private var chatStorageUnavailable = false
    private var messagesSelectionProjection: ArrayList<HashMap<String, Any>> = arrayListOf()
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
    private var deletingChat = false
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

    // Set when a send closes the keyboard, consumed by the first keyboard
    // change that follows, so only that one close skips the position hold.
    private var imeClosingForSend = false
    private var inCost: Float = 0.0f
    private var outCost: Float = 0.0f
    private var usageIn: Int = 0
    private var usageOut: Int = 0
    private var conversationUsageSummary = ConversationUsageSummary(emptyList())
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
        val historyResult: ChatPreferences.ChatHistoryResult,
        val pendingConversation: Boolean,
        val conversationMode: ConversationMode
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
        val selectedModel: String,
        val selectedEndpointId: String,
        val request: ChatCompletionRequest,
        val payload: FrozenChatPayload,
        val activeMemoryReferences: List<ActiveMemoryReference>,
        val contextDecision: ModelContextDecision
    )

    private data class FrozenRegularRequest(
        val request: ChatCompletionRequest,
        val payload: FrozenChatPayload,
        val activeMemoryReferences: List<ActiveMemoryReference>
    )

    /** One fully resolved conversation snapshot shared by measurement/send. */
    private data class FrozenConversationProjection(
        val persistentIncludes: List<ChatMessage>,
        val conversation: List<ChatMessage>,
        val summaryInjection: String?,
        val hasFullImages: Boolean
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
    /** FULL-image files held until their current request has copied bytes into
     *  its immutable projection. Include edits still update canonical state;
     *  only physical deletion is deferred for snapshot consistency. */
    private val protectedRequestImageHashes = HashSet<String>()
    private val deferredRequestImageDeletes = LinkedHashMap<String, ChatInclude>()

    private fun killAllProcesses() {
        onSpeechResultsScope?.coroutineContext?.cancel(CancellationException("Killed"))
        whisperScope?.coroutineContext?.cancel(CancellationException("Killed"))
        whisperPreloadScope?.coroutineContext?.cancel(CancellationException("Killed"))
        processRecordingScope?.coroutineContext?.cancel(CancellationException("Killed"))
        setupScope?.coroutineContext?.cancel(CancellationException("Killed"))
        speechSelectionGate.cancel()
        apiReadback?.stop()
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
        releaseRequestImagePayloads()
        handsFreeStopped = true
        handsFreeReadbackExpected = false
        handsFreeHandler.removeCallbacksAndMessages(null)
        handsFreeSubmitRunnable = null
        handsFreeBuffer = ""
    }

    /**
     * The progress ring and cancel X fully replace the conversation/send glyph
     * while transcription or generation is busy. Keeping the underlying button
     * invisible prevents its waveform/square from showing through the ring.
     */
    private fun setGenerationProgressVisible(visible: Boolean) {
        progress?.visibility = if (visible) View.VISIBLE else View.GONE
        btnSend?.visibility = if (visible) View.INVISIBLE else View.VISIBLE
    }

    private fun restoreUIState() {
        runOnUiThread {
            setGenerationProgressVisible(false)
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
            background = null
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
            background = null
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
            background = null
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
            setBackgroundResource(R.drawable.btn_accent_tonal_v5)
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
            background = null
            clearColorFilter()
            backgroundTintList = null
            setImageResource(
                if (!messageInput?.text.isNullOrEmpty()) R.drawable.ic_arrow_up
                else R.drawable.ic_conversation
            )
        }
    }

    /** Build the card from frozen per-request records. CL100K runs only for
     * legacy assistant messages that have no durable record; a new
     * provider-reported turn never reaches the tokenizer. */
    private fun calculateCost() {
        lifecycleScope.launch { refreshConversationUsageSummary() }
    }

    private suspend fun refreshConversationUsageSummary(): ConversationUsageSummary {
        val snapshot = messages.map { HashMap(it) }
        val summary = withContext(Dispatchers.Default) {
                val legacyIndexes = snapshot.indices.filter { index ->
                    val message = snapshot[index]
                    message["isBot"] == true &&
                        MessageCompletionState.isComplete(
                            message[MessageCompletionState.KEY_STATE]?.toString()
                        ) &&
                        TokenUsageAccounting.decodeRecords(
                            message[ChatAdapter.KEY_TOKEN_USAGE_RECORDS]?.toString()
                        ).isEmpty()
                }.toSet()
                val legacyCounts = HashMap<Int, TokenCounts>()
                if (legacyIndexes.isNotEmpty()) {
                    val tokenizer = Tokenizer.of(Encoding.CL100K_BASE)
                    var prefixTokens = 0
                    snapshot.forEachIndexed { index, message ->
                        val content = message["message"]?.toString().orEmpty()
                        val count = if (content.trim().startsWith("~file:")) 0
                            else tokenizer.encode(content).size
                        if (index in legacyIndexes) {
                            legacyCounts[index] = TokenCounts(prefixTokens, count, prefixTokens + count)
                        }
                        prefixTokens += count
                    }
                }
                TokenUsageAccounting.summarizeMessages(
                    snapshot
                ) { index -> legacyCounts[index] ?: TokenCounts(null, null, null) }
        }
        conversationUsageSummary = summary
        usageIn = summary.totalInputTokens
        usageOut = summary.totalOutputTokens
        inCost = summary.groups.sumOf { it.inputCost }.toFloat()
        outCost = summary.groups.sumOf { it.outputCost }.toFloat()
        return summary
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
                setGenerationProgressVisible(false)
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

  …125708 tokens truncated…RIES memories /
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

        val lorebookIds = memoryAssemblyResult?.lorebookEntryIds
            ?: loreBudget.injectedEntryIds
        activeMemoryReferences = ActiveMemoryAttribution.fromFinalSelection(
            memoryAssemblyResult?.memoryIds.orEmpty(),
            lorebookIds
        )
        attachActiveMemoryAttribution(activeMemoryReferences)
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
                withGenerationRequestContext {
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
                withGenerationRequestContext { ai!!.chatCompletion(chatCompletionRequest) }
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
        val providerDiagnostics = finalizeLifecycleSuccess()
        applyProviderWarnings(providerDiagnostics)

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
                streamingEnabled,
                activeMemoryReferences
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
        setGenerationProgressVisible(false)

        // Put timestamp to chat to sort chats by last message
        ChatPreferences.getChatPreferences().putTimestampToChatById(this, chatId)

        if (autoNameAttempts < AUTO_NAME_MAX_ATTEMPTS && chatName.trim().contains("_autoname_")) {
            val placeholderName = ChatPreferences.getChatPreferences().getChatName(this, chatId)

            if (placeholderName.trim().contains("_autoname_")) {
                autoNameAttempts++
                btnMicro?.isEnabled = false
                btnSend?.isEnabled = false
                setGenerationProgressVisible(false)

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
                    // Persist the title off the main thread. Keep the rename
                    // guard across the IO suspension; the chat ID never changes.
                    renameInProgress = true
                    val renamed = try {
                        withContext(Dispatchers.IO) {
                            ChatPreferences.getChatPreferences().editChat(this@ChatActivity, newChatName, placeholderName, chatId)
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
                        // Update the display and restored title only. Existing
                        // preferences, voice playback and jobs keep the same ID.
                        this.chatName = newChatName
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

    private fun observeSpeechSelection() {
        val settings = org.teslasoft.assistant.preferences.SecurePrefs.get(this,
            org.teslasoft.assistant.preferences.tts.AppTtsVoicePreferences.STORE_NAME)
        if (speechSettings === settings) return
        speechSettings?.unregisterOnSharedPreferenceChangeListener(speechSelectionListener)
        speechSettings = settings
        settings.registerOnSharedPreferenceChangeListener(speechSelectionListener)
    }

    private fun speak(message: String, session: Int = readbackSession, selected: TtsVoiceSelection? = null) {
        observeSpeechSelection()
        // The user stopped this readback while it was still in flight (see
        // readbackSession) — starting the audio now would speak over a stop.
        if (session != readbackSession) {
            logTtsLifecycle("TTS skipped reason=stale_session (stopped or superseded before dispatch)")
            return
        }
        if (selected == null) {
            val token = speechSelectionGate.begin()
            lifecycleScope.launch {
                val prefs = preferences ?: return@launch
                val result = TtsSelectionService(this@ChatActivity, prefs).reconcile(token)
                token.deliver {
                    if (session != readbackSession || isDestroyed) return@deliver
                    result.fold(onSuccess = { speak(message, session, it) }, onFailure = {
                        finishApiReadback()
                        showSpeechFailure((it as? TtsException)?.failure ?: TtsFailure(
                            TtsOperation.SPEECH, TtsTarget(""), "", TtsFailureKind.UNKNOWN), message, session)
                    })
                }
            }
            return
        }
        if (preferences?.getSelectedTtsVoice() != selected) return
        if (selected.kind == TtsVoiceKind.DEVICE) {

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
                // A voice selected while Settings covered this existing engine must take
                // effect without requiring activity recreation. Preserve auto-language behavior.
                if (!autoLangDetect) {
                    val voice = runCatching { engine.voices?.firstOrNull { it.name == selected.voiceId } }.getOrNull()
                    if (voice != null && engine.voice?.name != selected.voiceId) engine.setVoice(voice)
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
            val playback = apiReadback ?: TtsPlayback(this).also { apiReadback = it }
            playback.play(selected.sourceId, selected.voiceId, message, TtsOperation.SPEECH,
                stillCurrent = { session == readbackSession && !isDestroyed && preferences?.getSelectedTtsVoice() == selected },
                onPlayer = { next ->
                    if (mediaPlayer !== next) runCatching { mediaPlayer?.release() }
                    mediaPlayer = next
                },
                onStart = {
                    logTtsLifecycle("TTS onStart engine=openai")
                    beginHandsFreeReadbackWatch()
                },
                onDone = {
                    logTtsLifecycle("TTS onDone engine=openai")
                    adapter?.clearSpeakingPosition()
                    // Mirror the device-TTS onDone path so a cloud voice keeps hands-free looping.
                    onHandsFreeReadbackFinished()
                    releaseReadbackKeepAlive()
                },
                onInvalidated = { adapter?.clearSpeakingPosition(); releaseReadbackKeepAlive() },
                onPlaybackError = { what, extra ->
                    logTtsLifecycle("TTS onError engine=openai code=$what/$extra (mediaPlayer playback error)")
                },
                onFailure = { failure ->
                    if (failure.kind == TtsFailureKind.KEY_MISSING) {
                        logTtsLifecycle("TTS skipped reason=openai_key_missing")
                    } else if (failure.kind == TtsFailureKind.PLAYBACK) {
                        logTtsLifecycle("TTS onError engine=openai code=io_exception (preparing local playback)")
                    } else {
                        val e = TtsException(failure)
                        logTtsLifecycle("TTS onError engine=openai code=request_failed (speech request never returned audio)")
                        logVoiceEventAlways("cloud voice request failed: ${e.message}")
                    }
                    finishApiReadback()
                    showSpeechFailure(failure, message, session)
                })
        }
    }

    private fun finishApiReadback() {
        adapter?.clearSpeakingPosition()
        onHandsFreeReadbackFinished()
        releaseReadbackKeepAlive()
        restoreUIState()
    }

    private fun showSpeechFailure(failure: TtsFailure, message: String, session: Int) {
        if (session != readbackSession || isFinishing || isDestroyed) return
        if (failure.kind in setOf(TtsFailureKind.VOICE_DELETED, TtsFailureKind.SOURCE_MISSING, TtsFailureKind.PROFILE_MISSING) &&
            preferences?.getSelectedTtsVoice()?.kind == TtsVoiceKind.API) {
            val token = speechRecoveryGate.begin()
            lifecycleScope.launch {
                val result = TtsSelectionService(this@ChatActivity, preferences!!).reconcile(token, failure)
                token.deliver {
                    result.exceptionOrNull()?.let { error ->
                        (error as? TtsException)?.failure?.let { showSpeechNotice(it, message, session) }
                    }
                }
            }
        } else showSpeechNotice(failure, message, session)
    }

    private fun showSpeechNotice(failure: TtsFailure, message: String, session: Int) {
        if (isFinishing || isDestroyed) return
        val selection = preferences?.getSelectedTtsVoice()
        speechNotice?.dismiss()
        speechNotice = TtsVoiceDialogs.show(this, chatId, failure) {
            if (session == readbackSession && preferences?.getSelectedTtsVoice() == selection) {
                // Retry is an explicit user action and resolves the same still-valid source again.
                if (message.isNotBlank()) {
                    // The existing manual-readback path stops listening before replaying audio.
                    onSpeakClick(message, -1)
                } else {
                    val token = speechRecoveryGate.begin()
                    lifecycleScope.launch {
                        val result = TtsSelectionService(this@ChatActivity, preferences!!).reconcile(token)
                        token.deliver { (result.exceptionOrNull() as? TtsException)?.failure?.let {
                            showSpeechNotice(it, "", readbackSession)
                        } }
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
        stopReadback()
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
        // Adapter rows explain this with an anchored popup. Keep this guard so
        // stale/recycled UI or any future caller can never bypass the history lock.
        if (condensedRegenerationLockKind(position) != null) return

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
        val manualBoundary = preferences?.getManualCompactionBoundary() ?: 0
        if (manualBoundary > messages.size) {
            preferences?.setManualCompactionBoundary(messages.size)
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
        if (condensedRegenerationLockKind(position) != null) return
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
        refreshManualCompactionMarker()
        refreshCondensedRegenerationLocks()
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
        outState.putBoolean(STATE_EXPLICIT_IMAGINE_DRAFT, explicitImagineDraft)
        super.onSaveInstanceState(outState)
    }

    private fun onRestoredState(savedInstanceState: Bundle?) {
        explicitImagineDraft =
            savedInstanceState?.getBoolean(STATE_EXPLICIT_IMAGINE_DRAFT, false) == true
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
                val manualBoundaryBefore =
                    preferences?.getManualCompactionBoundary() ?: 0
                val summaryLockBefore =
                    preferences?.getSummaryRegenerationLockBoundary() ?: 0
                val compactionLockBefore =
                    preferences?.getCompactionRegenerationLockBoundary() ?: 0
                // §12 cleanup: note the generated-image files the selected
                // messages reference before they are removed.
                val deletedImageHashes = GeneratedImageFiles.referencedHashes(
                    messages.filterIndexed { index, _ ->
                        index < messagesSelectionProjection.size &&
                            messagesSelectionProjection[index]["selected"].toString() == "true"
                    }
                )
                var removedBeforeBookmark = 0
                var removedBeforeManualBoundary = 0
                var removedBeforeSummaryLock = 0
                var removedBeforeCompactionLock = 0
                var pos = 0
                var p = 0
                while (pos < messagesSelectionProjection.size) {
                    if (messagesSelectionProjection[pos]["selected"].toString() == "true") {
                        // Bulk delete bypasses ChatPreferences.deleteMessage,
                        // so the fold-in bookmark is realigned here the same
                        // way: one step per removed already-folded message.
                        if (pos < foldedBefore) removedBeforeBookmark++
                        if (pos < manualBoundaryBefore) {
                            removedBeforeManualBoundary++
                        }
                        if (pos < summaryLockBefore) removedBeforeSummaryLock++
                        if (pos < compactionLockBefore) removedBeforeCompactionLock++
                        messages.removeAt(pos - p)
                        p++
                    }

                    pos++
                }
                if (removedBeforeBookmark > 0) {
                    preferences?.setSummarizerFoldedCount(foldedBefore - removedBeforeBookmark)
                }
                if (removedBeforeManualBoundary > 0) {
                    preferences?.setManualCompactionBoundary(
                        manualBoundaryBefore - removedBeforeManualBoundary
                    )
                }
                if (removedBeforeSummaryLock > 0) {
                    preferences?.setSummaryRegenerationLockBoundary(
                        summaryLockBefore - removedBeforeSummaryLock
                    )
                }
                if (removedBeforeCompactionLock > 0) {
                    preferences?.setCompactionRegenerationLockBoundary(
                        compactionLockBefore - removedBeforeCompactionLock
                    )
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
