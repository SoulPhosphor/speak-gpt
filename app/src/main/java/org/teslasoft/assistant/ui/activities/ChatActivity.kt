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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import android.os.StrictMode
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
import com.aallam.openai.api.chat.ChatCompletion
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
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.LogitBiasPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.stt.LocalWhisperEngine
import org.teslasoft.assistant.stt.LocalWhisperModels
import org.teslasoft.assistant.stt.LocalWhisperStorage
import org.teslasoft.assistant.service.HandsFreeService
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.adapters.chat.ChatAdapter
import org.teslasoft.assistant.ui.fragments.dialogs.EditApiEndpointDialogFragment
import org.teslasoft.assistant.ui.fragments.dialogs.QuickSettingsBottomSheetDialogFragment
import org.teslasoft.assistant.ui.onboarding.WelcomeActivity
import org.teslasoft.assistant.ui.permission.CameraPermissionActivity
import org.teslasoft.assistant.ui.permission.MicrophonePermissionActivity
import org.teslasoft.assistant.util.Hash
import org.teslasoft.assistant.util.LocaleParser
import org.teslasoft.assistant.util.WindowInsetsUtil
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
    private var messagesSelectionProjection: ArrayList<HashMap<String, Any>> = arrayListOf()
    private var messagesUsageProjection: ArrayList<HashMap<String, Any>> = arrayListOf()
    private var adapter: ChatAdapter? = null
    private var chatMessages: ArrayList<ChatMessage> = arrayListOf()
    private var chatId = ""
    private var chatName = ""
    private var languageIdentifier: LanguageIdentifier? = null

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

    // Init DALL-e
    private var resolution = "512x152"

    private var messageCounter = 0

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

    // Media player for OpenAI TTS
    private var mediaPlayer: MediaPlayer? = null

    // Init preferences
    private var preferences: Preferences? = null

    private var onSpeechResultsScope: CoroutineScope? = null
    private var whisperScope: CoroutineScope? = null
    private var whisperPreloadScope: CoroutineScope? = null
    private var processRecordingScope: CoroutineScope? = null
    private var setupScope: CoroutineScope? = null
    private var imageRequestScope: CoroutineScope? = null
    private var speakScope: CoroutineScope? = null
    private var generateGptImageJob: Job? = null

    private fun killAllProcesses() {
        onSpeechResultsScope?.coroutineContext?.cancel(CancellationException("Killed"))
        whisperScope?.coroutineContext?.cancel(CancellationException("Killed"))
        whisperPreloadScope?.coroutineContext?.cancel(CancellationException("Killed"))
        processRecordingScope?.coroutineContext?.cancel(CancellationException("Killed"))
        setupScope?.coroutineContext?.cancel(CancellationException("Killed"))
        imageRequestScope?.coroutineContext?.cancel(CancellationException("Killed"))
        speakScope?.coroutineContext?.cancel(CancellationException("Killed"))
        generateGptImageJob?.cancel(CancellationException("Killed"))
        generateGptImageJob = null
        handsFreeStopped = true
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
            micIdle()
            cancelState = false
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
        }
        messageInput?.hint = getString(R.string.hint_message)
    }

    private fun micRecording() {
        btnMicro?.apply {
            setImageResource(R.drawable.ic_stop_recording)
            setColorFilter(ResourcesCompat.getColor(resources, R.color.mic_listening_green, theme))
        }
        messageInput?.hint = getString(R.string.hint_listening)
    }

    private fun micHandsFreeStop() {
        btnMicro?.apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(ResourcesCompat.getColor(resources, R.color.light_red, theme))
        }
    }

    private suspend fun tokenizeArray() {
        messagesUsageProjection = arrayListOf()
        messagesUsageProjection.clear()

        if (chatMessages == null) chatMessages = arrayListOf()

        for (m in chatMessages) {
            val tokenizer = Tokenizer.of(encoding = Encoding.CL100K_BASE)
            val tokens = tokenizer.encode(m.content.toString()).size

            messagesUsageProjection.add(
                hashMapOf(
                    "isBot" to (m.role == Role.Assistant),
                    "tokens" to if (m.content.toString().trim().startsWith("~file:")) 0 else tokens
                )
            )
        }
    }

    private fun calculateCost() {
        CoroutineScope(Dispatchers.Main).launch {
            tokenizeArray()

            usageIn = 0
            usageOut = 0
            inCost = 0.0f
            outCost = 0.0f

            var i = messagesUsageProjection.size - 1

            while (i > 0) {
                var j = 0
                var c = 0

                while (j < i) {
                    c += messagesUsageProjection[j]["tokens"] as Int
                    j++
                }

                usageIn += c
                i--
            }

            for (m in messagesUsageProjection) {
                val msgUsage = if (m["isBot"] == true) m["tokens"] as Int else 0

                usageOut += msgUsage
            }

            inCost = usageIn * priceIn
            outCost = usageOut * priceOut
        }
    }

    private val speechListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { /* unused */ }
        override fun onBeginningOfSpeech() {
            handsFreeUserSpoke = true
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
                stopHandsFreeLoop()
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

    private fun submitRecognizedText(recognizedText: String) {
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

        if (preferences?.autoSend() == true) {
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
        } else {
            restoreUIState()
            messageInput?.setText(recognizedText)
        }
    }

    override fun onResume() {
        super.onResume()

        preloadAmoled()
        reloadAmoled()

        if (chatId != "") {
            preferences = Preferences.getPreferences(this, chatId)
            apiEndpointPreferences = ApiEndpointPreferences.getApiEndpointPreferences(this)
            logitBiasPreferences = LogitBiasPreferences(this, preferences?.getLogitBiasesConfigId()!!)
            apiEndpointObject = apiEndpointPreferences?.getApiEndpoint(this, preferences?.getApiEndpointId()!!)
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
    }

    /** Force the chat's top action bar and its buttons back to fully visible. */
    private fun restoreTopBarVisibility() {
        for (v in listOf(actionBar, btnBack, activityTitle, btnExport, btnSettings)) {
            v?.visibility = View.VISIBLE
            v?.alpha = 1f
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
    private var ttsUtteranceCounter: Long = 0
    private val ttsListener: TextToSpeech.OnInitListener =
        TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsPostInit()
                tts?.setOnUtteranceProgressListener(ttsProgressListener)
                isTTSInitialized = true
                pendingSpeak?.let {
                    pendingSpeak = null
                    Handler(Looper.getMainLooper()).post { speak(it) }
                }
            } else {
                isTTSInitialized = false
                Log.w("TTS", "TextToSpeech init failed with status $status")
            }
        }

    private val ttsProgressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { /* no-op */ }
        override fun onDone(utteranceId: String?) {
            maybeRestartHandsFreeAfterReadback()
        }
        @Suppress("OverridingDeprecatedMember")
        override fun onError(utteranceId: String?) {
            Log.w("TTS", "TTS utterance error: $utteranceId; re-initialising engine")
            Handler(Looper.getMainLooper()).post { reinitTTS() }
        }
        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.w("TTS", "TTS utterance error code $errorCode: $utteranceId; re-initialising engine")
            Handler(Looper.getMainLooper()).post { reinitTTS() }
        }
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
        if (handsFree && sttSupported && auto && !cancelState && !handsFreeStopped && !isRecording) {
            Handler(Looper.getMainLooper()).post {
                if (!isFinishing && !isDestroyed) {
                    if (effModel == "whisper-local") {
                        // Re-arm an on-device Whisper turn; the service and
                        // loop are already running so this is not a fresh turn.
                        startLocalWhisperHandsFreeTurn(freshTurn = false)
                    } else {
                        isRecording = true
                        micRecording()
                        startRecognition(true)
                    }
                }
            }
        } else if (handsFree) {
            Log.i("HandsFree", "No restart after readback: effModel=$effModel auto=$auto " +
                    "cancelState=$cancelState handsFreeStopped=$handsFreeStopped isRecording=$isRecording")
        }
    }

    private fun reinitTTS() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) { /* ignore */ }
        isTTSInitialized = false
        tts = TextToSpeech(this, ttsListener)
    }

    private fun ttsPostInit() {
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

        preloadAmoled()
        reloadAmoled()

        mediaPlayer = MediaPlayer()
        threadLoader = findViewById(R.id.thread_loader)
        threadLoader?.visibility = View.VISIBLE

        Thread {
            val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
            StrictMode.setThreadPolicy(policy)

            runOnUiThread {
                val chatActivityTitle: TextView = findViewById(R.id.chat_activity_title)
                val keyboardInput: LinearLayout = findViewById(R.id.keyboard_input)

                chatActivityTitle.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
                keyboardInput.setBackgroundColor(SurfaceColors.SURFACE_5.getColor(this))

                initChatId()
                initSettings()

                if (savedInstanceState != null) {
                    if (Build.VERSION.SDK_INT != Build.VERSION_CODES.R) {
                        adjustPaddings()
                    }
                    onRestoredState(savedInstanceState)
                }
            }
        }.start()
    }

    public override fun onDestroy() {
        if (tts != null) {
            tts!!.stop()
            tts!!.shutdown()
        }
        if (mediaPlayer!!.isPlaying) {
            mediaPlayer!!.stop()
            mediaPlayer!!.reset()
        }

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
    @Suppress("unchecked")
    private fun initSettings() {
        key = apiEndpointObject?.apiKey!!
        openAIKey = apiEndpointPreferences?.findOpenAIKeyIfAvailable(this)

        endSeparator = preferences!!.getEndSeparator()
        prefix = preferences!!.getPrefix()

        loadResolution()

        if (key == null) {
            startActivity(Intent(this, WelcomeActivity::class.java).setAction(Intent.ACTION_VIEW))
            finishActivity()
        } else {
            silenceMode = preferences!!.getSilence()
            autoLangDetect = preferences!!.getAutoLangDetect()
            messages = ChatPreferences.getChatPreferences().getChatById(this, chatId)

            // R8 fix
            if (messages == null) messages = arrayListOf()
            if (chatMessages == null) chatMessages = arrayListOf()

            for (message: HashMap<String, Any> in messages) {
                if (!message["message"].toString().contains("data:image")) {
                    if (message["isBot"] == true) {
                        chatMessages.add(
                            ChatMessage(
                                role = ChatRole.Assistant,
                                content = message["message"].toString()
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
                    /* for future */
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
                cancelAllAiActivity()
                return@setOnClickListener
            }
            when (preferences!!.getEffectiveAudioModel()) {
                "google" -> handleGoogleSpeechRecognition()
                "whisper-local" -> handleLocalWhisperSpeechRecognition()
                else -> handleWhisperSpeechRecognition()
            }
        }

        // Touch interceptor: lets a tap during AI generation cancel everything
        // even though the click handler is otherwise disabled by isEnabled=false
        // in the generation/TTS code paths. OnTouchListener fires regardless of
        // View.isEnabled, so a stop tap always lands.
        btnMicro?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && isAiCurrentlyBusy() && !isRecording) {
                cancelAllAiActivity()
                true
            } else {
                false
            }
        }

        attachedImage?.setOnClickListener { /* ignored */ }

        btnMicro?.setOnLongClickListener {
            cancelAllAiActivity()
            return@setOnLongClickListener true
        }

        messageInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                /* unused */
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s.toString() == "") {
                    btnSend?.visibility = View.GONE
                    btnMicro?.visibility = View.VISIBLE
                } else {
                    btnSend?.visibility = View.VISIBLE
                    btnMicro?.visibility = View.GONE
                }
            }

            override fun afterTextChanged(s: Editable?) {
                /* unused */
            }
        })

        btnSend?.setOnClickListener {
            parseMessage(messageInput?.text.toString())
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

    private fun startWhisper() {
        if (openAIKey == null) {
            openAIMissing("whisper", "")
        } else if (Build.VERSION.SDK_INT >= 31) {
            recorder = MediaRecorder(this).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC)
                setAudioChannels(1)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                setOutputFile("${externalCacheDir?.absolutePath}/tmp.m4a")

                if (!cancelState) {
                    try {
                        prepare()
                    } catch (_: IOException) {
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
                    }

                    start()
                } else {
                    cancelState = false
                    micIdle()
                    isRecording = false
                }
            }
        } else {
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC)
                setAudioChannels(1)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                setOutputFile("${externalCacheDir?.absolutePath}/tmp.m4a")

                if (!cancelState) {
                    try {
                        prepare()
                    } catch (_: IOException) {
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
                    }

                    start()
                } else {
                    cancelState = false
                    micIdle()
                    isRecording = false
                }
            }
        }
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
                if (preferences?.autoSend() == true) {
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
                    messageInput?.setText(transcription)
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
                stopHandsFreeLoop()
                LocalWhisperEngine.get().cancel()
            } else {
                micIdle()
                isRecording = false
                stopLocalWhisper()
            }
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

    private fun startLocalWhisper() {
        micRecording()
        isRecording = true
        val ok = LocalWhisperEngine.get().startRecording()
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
        if (freshTurn) {
            handsFreeStopped = false
            cancelState = false
            startHandsFreeService()
        }
        if (handsFreeStopped) return

        micRecording()
        isRecording = true

        val silenceMs = preferences!!.getHandsFreeSilenceSeconds().coerceAtLeast(1) * 1000L
        val noSpeechMs = preferences!!.getHandsFreeNoSpeechSeconds().coerceAtLeast(1) * 1000L
        val ok = LocalWhisperEngine.get().startRecording(
            vad = LocalWhisperEngine.VadConfig(silenceMs, noSpeechMs, preferences!!.getVadMethod(), preferences!!.getVadWebRtcMode()),
            onEndOfTurn = { runOnUiThread { onHandsFreeWhisperEndOfTurn() } },
            onNoSpeechTimeout = { runOnUiThread { onHandsFreeWhisperNoSpeech() } }
        )
        if (!ok) {
            isRecording = false
            micIdle()
            stopHandsFreeLoop()
            Toast.makeText(this, R.string.local_whisper_capture_failed, Toast.LENGTH_LONG).show()
            return
        }
        preloadActiveLocalWhisperModel()
    }

    /** VAD said the user finished speaking — transcribe + submit this turn. */
    private fun onHandsFreeWhisperEndOfTurn() {
        if (!isRecording || handsFreeStopped || cancelState) return
        isRecording = false
        // stopLocalWhisper() transcribes the buffered audio and routes through
        // processLocalWhisperTranscript → generateResponse → speak; the
        // readback completion re-arms the next turn.
        stopLocalWhisper()
    }

    /** VAD saw no speech within the window — end the loop like Google does. */
    private fun onHandsFreeWhisperNoSpeech() {
        if (handsFreeStopped) return
        // Diagnostic: when WebRTC times out hearing nothing, show what libfvad
        // actually saw (voiced-frame count + peak input level) so "it never hears
        // me" can be pinned to either a dead/quiet mic or fvad rejecting speech.
        if (preferences?.getVadMethod() == org.teslasoft.assistant.stt.VadMethods.WEBRTC) {
            val diag = LocalWhisperEngine.get().lastVadDiagnostics()
            if (diag.isNotEmpty()) Toast.makeText(this, diag, Toast.LENGTH_LONG).show()
        }
        stopHandsFreeLoop()
        LocalWhisperEngine.get().cancel()
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
            } catch (_: Exception) {
                Toast.makeText(this@ChatActivity, "Failed to transcribe on device", Toast.LENGTH_SHORT).show()
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
                startLocalWhisperHandsFreeTurn(freshTurn = false)
            }
            return
        }
        if (preferences?.autoSend() == true) {
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
            messageInput?.setText(transcription)
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
            micIdle()
            recognizer?.stopListening()
            isRecording = false
        } else {
            try {
                if (mediaPlayer!!.isPlaying) {
                    mediaPlayer!!.stop()
                    mediaPlayer!!.reset()
                }
                tts!!.stop()
            } catch (_: java.lang.Exception) {/* unused */}
            micRecording()
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
            val extraHeaders: Map<String, String> = when (apiEndpointObject?.authType) {
                ApiEndpointObject.AUTH_X_API_KEY -> mapOf("x-api-key" to key!!)
                ApiEndpointObject.AUTH_API_KEY -> mapOf("api-key" to key!!)
                else -> emptyMap()
            }

            val config = OpenAIConfig(
                token = key!!,
                logging = LoggingConfig(LogLevel.None, Logger.Simple),
                timeout = Timeout(socket = 30.seconds),
                organization = null,
                headers = extraHeaders,
                host = OpenAIHost(composeChatHost(apiEndpointObject?.host, apiEndpointObject?.chatEndpoint)),
                proxy = null,
                retry = RetryStrategy()
            )

            ai = OpenAI(config)
            val configOpenAI = OpenAIConfig(
                token = openAIKey.toString(),
                logging = LoggingConfig(LogLevel.None, Logger.Simple),
                timeout = Timeout(socket = 30.seconds),
                organization = null,
                headers = emptyMap(),
                host = OpenAIHost("https://api.openai.com/v1/"),
                proxy = null,
                retry = RetryStrategy()
            )
            openAIAI = OpenAI(configOpenAI)
            loadModel()
            setup()
        }
    }

    private fun initChatId() {
        val extras: Bundle? = intent.extras

        if (extras != null) {
            chatId = extras.getString("chatId", "")
            chatName = extras.getString("name", "")

            this.title = chatName
        }

        preferences = Preferences.getPreferences(this, chatId)
        apiEndpointPreferences = ApiEndpointPreferences.getApiEndpointPreferences(this)
        logitBiasPreferences = LogitBiasPreferences(this, preferences?.getLogitBiasesConfigId()!!)
        apiEndpointObject = apiEndpointPreferences?.getApiEndpoint(this, preferences?.getApiEndpointId()!!)

        preloadAmoled()
        reloadAmoled()
    }

    /*
    * Setup SpeakGPT with activation prompt.
    * */
    private fun setup() {
        if (messages.isEmpty()) {
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
        val chat = getSharedPreferences("chat_$chatId", MODE_PRIVATE)
        chat.edit {
            val gson = Gson()
            val json: String = gson.toJson(messages)

            putString("chat", json)
        }
    }

    private fun parseMessage(message: String, shouldAdd: Boolean = true) {
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

                CoroutineScope(Dispatchers.Main).launch {
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
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, LocaleParser.parse(preferences!!.getLanguage()))
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

        if (preferences?.getHandsFreeMode() == true) {
            if (freshTurn) {
                handsFreeUserSpoke = false
                handsFreeStopped = false
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

    private fun stopHandsFreeLoop() {
        handsFreeStopped = true
        handsFreeHandler.removeCallbacksAndMessages(null)
        handsFreeSubmitRunnable = null
        handsFreeBuffer = ""
        try { recognizer?.stopListening() } catch (_: Exception) { /* ignore */ }
        isRecording = false
        micIdle()
        stopHandsFreeService()
    }

    /** A failed turn must not silently re-arm the hands-free loop. Otherwise a
     *  single error (overloaded model, dropped connection, etc.) becomes an
     *  endless retry cycle that keeps erroring without the user touching the
     *  mic. Stopping the loop here means a fresh mic tap is required to resume. */
    private fun stopHandsFreeOnError() {
        if (preferences?.getHandsFreeMode() == true) {
            runOnUiThread { stopHandsFreeLoop() }
        }
    }

    /** True while the AI is generating, speaking through TTS, or playing back
     *  OpenAI TTS audio. Used so a single mic-button tap can cancel everything. */
    private fun isAiCurrentlyBusy(): Boolean {
        val ttsSpeaking = try { tts?.isSpeaking == true } catch (_: Exception) { false }
        val mediaPlaying = try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false }
        val progressVisible = progress?.visibility == View.VISIBLE
        return ttsSpeaking || mediaPlaying || progressVisible
    }

    /** Cancels generation, TTS, audio playback, recognizer, and the hands-free
     *  loop in one shot. Mirrors what long-press has always done; also reachable
     *  from a short tap when the AI is busy. */
    private fun cancelAllAiActivity() {
        cancelState = true
        handsFreeStopped = true
        handsFreeHandler.removeCallbacksAndMessages(null)
        handsFreeSubmitRunnable = null
        handsFreeBuffer = ""
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
                mediaPlayer?.reset()
            }
        } catch (_: Exception) { /* ignore */ }
        try { tts?.stop() } catch (_: Exception) { /* ignore */ }
        try { recognizer?.stopListening() } catch (_: Exception) { /* ignore */ }
        try { speakScope?.coroutineContext?.cancel(CancellationException("Cancelled by user")) } catch (_: Exception) { /* ignore */ }
        isRecording = false
        micIdle()
        stopHandsFreeService()
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
        } catch (_: Exception) { /* ignore — service will be retried on next turn */ }
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
        disableAutoScroll = false
        try {
            var response = ""

            if (imageIsSelected) {
                imageIsSelected = false

                attachedImage?.visibility = View.GONE

                putMessage("", true)

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
            calculateCost()
            runOnUiThread {
                restoreUIState()
            }
        } catch (e: Exception) {
            playErrorSignal()
            stopHandsFreeOnError()
            val response = when {
                e.stackTraceToString().contains("invalid model") -> {
                    getString(R.string.prompt_no_model_provided)
                }
                e.stackTraceToString().contains("does not exist") -> {
                    String.format(getString(R.string.prompt_model_not_available), model)
                }
                e.stackTraceToString().contains("Connect timeout has expired") || e.stackTraceToString().contains("SocketTimeoutException") -> {
                    getString(R.string.prompt_timed_out)
                }
                e.stackTraceToString().contains("This model's maximum") -> {
                    getString(R.string.prompt_max_tokens_error)
                }
                e.stackTraceToString().contains("No address associated with hostname") -> {
                    getString(R.string.prompt_offline)
                }
                e.stackTraceToString().contains("Incorrect API key") -> {
                    getString(R.string.prompt_key_invalid)
                }
                e.stackTraceToString().contains("you must provide a model") -> {
                    getString(R.string.prompt_no_model)
                }
                e.stackTraceToString().contains("Software caused connection abort") -> {
                    getString(R.string.prompt_error_unknown)
                }
                e.stackTraceToString().contains("You exceeded your current quota") -> {
                    getString(R.string.prompt_quota_reached)
                }
                e.stackTraceToString().contains("404") || e.stackTraceToString().contains("Not Found") -> {
                    "Endpoint not found (HTTP 404).\n\nProfile: ${apiEndpointObject?.label}\nBase URL: ${apiEndpointObject?.host}\n\nThe server returned 404 for this address. Check that this profile's Base URL includes the full path, and that this chat is set to the intended profile."
                }
                else -> {
                    "Profile: ${apiEndpointObject?.label}\nBase URL: ${apiEndpointObject?.host}\n\n" + e.stackTraceToString() + "\n\n" + e.message
                }
            }

            if (messages[messages.size - 1]["isBot"] == false) {
                putMessage("", true)
            }

            if (preferences?.showChatErrors() == true) {
                messages[messages.size - 1]["message"] = "${messages[messages.size - 1]["message"]}\n\n${getString(R.string.prompt_show_error)}\n\n$response"
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
            calculateCost()
            runOnUiThread {
                restoreUIState()
            }
        }
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
                    saveSettings()
                }
            }
        }

        messages[messages.size - 1]["message"] = "$response\n"
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

        if (messageCounter == 0) {
            val chatName = ChatPreferences.getChatPreferences().getChatName(this, chatId)

            if (chatName.trim().contains("_autoname_")) {
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

                val chatCompletionRequest2 = ChatCompletionRequest(
                    model = ModelId("gpt-4o"),
                    maxTokens = 10,
                    messages = m
                )

                try {
                    val completion: ChatCompletion = ai!!.chatCompletion(chatCompletionRequest2)

                    val newChatName = completion.choices[0].message.content

                    ChatPreferences.getChatPreferences().editChat(this, newChatName.toString(), chatName)
                    chatId = Hash.hash(newChatName.toString())

                    val preferences = Preferences.getPreferences(this, Hash.hash(chatName))

                    // Write settings
                    val resolution = preferences.getResolution()
                    val speech = preferences.getAudioModel()
                    val model = preferences.getModel()
                    val maxTokens = preferences.getMaxTokens()
                    val prefix = preferences.getPrefix()
                    val endSeparator = preferences.getEndSeparator()
                    val activationPrompt = preferences.getPrompt()
                    val layout = preferences.getLayout()
                    val silent = preferences.getSilence()
                    val systemMessage1 = preferences.getSystemMessage()
                    val alwaysSpeak = preferences.getNotSilence()
                    val autoLanguageDetect = preferences.getAutoLangDetect()
                    val functionCalling = preferences.getFunctionCalling()
                    val slashCommands = preferences.getImagineCommand()
                    val apiEndpointId = preferences.getApiEndpointId()
                    val logitBiasConfigId = preferences.getLogitBiasesConfigId()
                    val temperature = preferences.getTemperature()
                    val topP = preferences.getTopP()
                    val frequencyPenalty = preferences.getFrequencyPenalty()
                    val presencePenalty = preferences.getPresencePenalty()
                    val avatarType = preferences.getAvatarType()
                    val avatarId = preferences.getAvatarId()
                    val assistantName = preferences.getAssistantName()

                    preferences.setPreferences(Hash.hash(newChatName.toString()), this)
                    preferences.setResolution(resolution)
                    preferences.setAudioModel(speech)
                    preferences.setModel(model)
                    preferences.setMaxTokens(maxTokens)
                    preferences.setPrefix(prefix)
                    preferences.setEndSeparator(endSeparator)
                    preferences.setPrompt(activationPrompt)
                    preferences.setLayout(layout)
                    preferences.setSilence(silent)
                    preferences.setSystemMessage(systemMessage1)
                    preferences.setNotSilence(alwaysSpeak)
                    preferences.setAutoLangDetect(autoLanguageDetect)
                    preferences.setFunctionCalling(functionCalling)
                    preferences.setImagineCommand(slashCommands)
                    preferences.setApiEndpointId(apiEndpointId)
                    preferences.setLogitBiasesConfigId(logitBiasConfigId)
                    preferences.setTemperature(temperature)
                    preferences.setTopP(topP)
                    preferences.setFrequencyPenalty(frequencyPenalty)
                    preferences.setPresencePenalty(presencePenalty)
                    preferences.setAvatarType(avatarType)
                    preferences.setAvatarId(avatarId)
                    preferences.setAssistantName(assistantName)

                    activityTitle?.text = newChatName.toString()

                    val i = Intent(this, ChatActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("chatId", Hash.hash(newChatName.toString())).putExtra("name", newChatName.toString())
                    startActivity(i)
                    finishActivity()
                } catch (_: Exception) { /* model might not be available */ }
            }
        }

        messageCounter++
    }

    private fun pronounce(st: Boolean, message: String) {
        // In hands-free mode the reply is about to be read back; show a red ✕
        // on the mic so the user can end the loop mid-readback. A tap is turned
        // into a full cancel by btnMicro's touch listener (works while busy).
        if (preferences?.getHandsFreeMode() == true) runOnUiThread { micHandsFreeStop() }
        if ((st && !silenceMode) || preferences!!.getNotSilence()) {
            if (autoLangDetect) {
                try {
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

                            speak(message)
                        }?.addOnFailureListener {
                            // Ignore auto language detection if an error is occurred
                            autoLangDetect = false
                            ttsPostInit()

                            speak(message)
                        }
                } catch (_: NullPointerException) {
                    autoLangDetect = false
                    ttsPostInit()

                    speak(message)
                }
            } else {
                speak(message)
            }
        }
    }

    private fun speak(message: String) {
        if (preferences!!.getTtsEngine() == "google") {
            val engine = tts
            if (engine == null || !isTTSInitialized) {
                pendingSpeak = message
                if (engine == null) {
                    Handler(Looper.getMainLooper()).post { initTTS() }
                }
                return
            }
            val runSpeak = {
                ttsUtteranceCounter++
                val utteranceId = "speakgpt-$ttsUtteranceCounter"
                val result = engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                if (result == TextToSpeech.ERROR) {
                    Log.w("TTS", "speak() returned ERROR; re-initialising engine and queueing message")
                    pendingSpeak = message
                    reinitTTS()
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                runSpeak()
            } else {
                Handler(Looper.getMainLooper()).post { runSpeak() }
            }
        } else {
            if (openAIKey == null) {
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
                                    // Mirror the device-TTS onDone path so a
                                    // cloud voice also keeps hands-free looping.
                                    maybeRestartHandsFreeAfterReadback()
                                }
                                mediaPlayer?.start()
                            } catch (ex: IOException) {
                                MaterialAlertDialogBuilder(this@ChatActivity, R.style.App_MaterialAlertDialog)
                                    .setTitle(R.string.label_audio_error)
                                    .setPositiveButton(R.string.btn_close) { _, _ -> }
                                    .setMessage(ex.stackTraceToString())
                                    .show()
                            }
                        }
                    } catch (_: CancellationException) {
                        restoreUIState()
                    }
                }
            }
        }
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
            if (preferences?.showChatErrors() == true) {
                putMessage(
                    when {
                        e.stackTraceToString().contains("invalid model") -> {
                            getString(R.string.prompt_no_model_provided)
                        }
                        e.stackTraceToString().contains("Your request was rejected") -> {
                            getString(R.string.prompt_rejected)
                        }

                        e.stackTraceToString().contains("No address associated with hostname") -> {
                            getString(R.string.prompt_offline)
                        }

                        e.stackTraceToString().contains("Incorrect API key") -> {
                            getString(R.string.prompt_key_invalid)
                        }

                        e.stackTraceToString().contains("Software caused connection abort") -> {
                            getString(R.string.prompt_error_unknown)
                        }

                        e.stackTraceToString().contains("You exceeded your current quota") -> {
                            getString(R.string.prompt_quota_reached)
                        }

                        else -> {
                            e.stackTraceToString()
                        }
                    }, true
                )
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

    override fun onSpeakClick(message: String) {
        // Manual re-read of a single message via the existing TTS path. Stop any
        // current playback first so taps don't pile up.
        try { tts?.stop() } catch (_: Exception) { /* ignore */ }
        try { if (mediaPlayer?.isPlaying == true) { mediaPlayer?.stop(); mediaPlayer?.reset() } } catch (_: Exception) { /* ignore */ }
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
            if (!message["message"].toString().contains("data:image")) {
                if (message["isBot"] == true) {
                    chatMessages.add(
                        ChatMessage(
                            role = ChatRole.Assistant,
                            content = message["message"].toString()
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
        adjustPaddings()
    }

    private fun adjustPaddings() {
        WindowInsetsUtil.adjustPaddings(this, R.id.action_bar, EnumSet.of(WindowInsetsUtil.Companion.Flags.STATUS_BAR))
        WindowInsetsUtil.adjustPaddings(this, R.id.bulk_container, EnumSet.of(WindowInsetsUtil.Companion.Flags.STATUS_BAR))
        WindowInsetsUtil.adjustPaddings(this, R.id.keyboard_frame, EnumSet.of(WindowInsetsUtil.Companion.Flags.NAVIGATION_BAR))
        WindowInsetsUtil.adjustPaddings(this, R.id.messages, EnumSet.of(WindowInsetsUtil.Companion.Flags.NAVIGATION_BAR))

        val messages = findViewById<RecyclerView>(R.id.messages)

        // Keep the message list clear of the status bar by re-applying the top
        // margin on every insets dispatch. The previous Android-12+ path read
        // rootWindowInsets once; if that read landed before insets arrived it
        // saw a zero status-bar inset and the first messages slid up under the
        // status bar. Drive it off a listener (here on the content view, so we
        // don't replace the navigation-bar padding listener WindowInsetsUtil
        // installs on the messages view itself) so it self-corrects.
        val content = findViewById<View>(android.R.id.content) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            val lp = messages.layoutParams as ViewGroup.MarginLayoutParams
            lp.topMargin = dpToPx(64) + insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            messages.layoutParams = lp
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }

    private fun finishActivity() {
        val root: View = findViewById(R.id.root)
        root.animate().alpha(0f).setDuration(200)
        supportFinishAfterTransition()
    }
}
