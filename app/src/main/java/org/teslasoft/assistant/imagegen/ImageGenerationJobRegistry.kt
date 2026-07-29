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

package org.teslasoft.assistant.imagegen

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.util.GeneratedImageStorage
import org.teslasoft.assistant.util.Hash
import java.io.File

/** The chat message resource for each §13 failure cause. Every cause has
 *  its own message — an umbrella code must never conceal the specific
 *  cause from the user. */
fun imageFailureMessageRes(cause: ImageErrorCause): Int = when (cause) {
    ImageErrorCause.NO_GENERATOR_CONFIGURED -> R.string.image_gen_error_not_configured
    ImageErrorCause.GENERATOR_MODEL_REJECTED -> R.string.image_gen_error_model_rejected
    ImageErrorCause.ENDPOINT_UNREACHABLE -> R.string.image_gen_error_unreachable
    ImageErrorCause.AUTHENTICATION_FAILED -> R.string.image_gen_error_auth
    ImageErrorCause.PROMPT_REFUSED -> R.string.image_gen_error_prompt_refused
    ImageErrorCause.TIMED_OUT -> R.string.image_gen_error_timeout
    ImageErrorCause.NO_USABLE_IMAGE -> R.string.image_gen_error_no_usable_image
    ImageErrorCause.DOWNLOAD_INVALID -> R.string.image_gen_error_download_invalid
    ImageErrorCause.UNSUPPORTED_OPTION -> R.string.image_gen_error_unsupported_option
    ImageErrorCause.PROVIDER_ERROR -> R.string.image_gen_error_provider
    ImageErrorCause.CANCELLED -> R.string.image_gen_error_cancelled
}

/**
 * Process-level holder for in-flight image generations
 * (image-generation-rebuild-plan.md §5 progress experience). The job runs
 * in a registry-owned scope — NOT an activity scope — so leaving the chat,
 * rotation, or activity recreation cannot kill it or start a second one.
 * The chat screen attaches as a listener to render the Creating Image row
 * and receive the single terminal state; when no screen is attached at the
 * end, the result is written straight into the chat's stored history so
 * nothing the user asked for is lost.
 *
 * Every generation ends in exactly one of Complete, Failed, or Cancelled,
 * delivered exactly once. All registry methods must be called from the
 * main thread; the generation itself runs on IO inside the coordinator.
 */
object ImageGenerationJobRegistry {

    /** Who started the generation — the two paths end a turn differently:
     *  `/imagine` owns the whole turn (busy state, failure dialog), while a
     *  model-initiated tool call is mid-turn and the surrounding tool flow
     *  owns the turn state. */
    enum class Origin { IMAGINE, TOOL }

    /** The §5 rule: exactly one terminal state per generation. */
    sealed class Terminal {
        class Complete(val marker: String) : Terminal()
        class Failed(val cause: ImageErrorCause) : Terminal()
        data object Cancelled : Terminal()
    }

    class ActiveJob internal constructor(
        chatId: String,
        val request: ImageGenerationRequest,
        val origin: Origin,
        internal val terminal: CompletableDeferred<Terminal>
    ) {
        /** Mutable because a placeholder chat can be auto-renamed (and
         *  re-keyed) while its first turn is still generating. */
        var chatId: String = chatId
            internal set

        internal var job: Job? = null

        /** Awaited by the tool path to build its tool result. Cancelling
         *  the awaiting turn does NOT cancel the generation. */
        suspend fun await(): Terminal = terminal.await()
    }

    interface Listener {
        /** Called on the main thread with the job's single terminal state.
         *  Only fires while the listener is attached; with no listener the
         *  registry persists the result directly instead. */
        fun onImageJobFinished(job: ActiveJob, terminal: Terminal)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jobs = HashMap<String, ActiveJob>()
    private val listeners = HashMap<String, Listener>()

    /** The chat's in-flight generation, or null — the screen's restore
     *  check on (re)open. */
    fun activeJob(chatId: String): ActiveJob? = jobs[chatId]

    fun attach(chatId: String, listener: Listener) {
        listeners[chatId] = listener
    }

    /** Identity-guarded so a destroyed screen can never detach its
     *  replacement. */
    fun detach(chatId: String, listener: Listener) {
        if (listeners[chatId] === listener) listeners.remove(chatId)
    }

    /** The visible Cancel action (and the Stop control). Ends the job in
     *  the Cancelled terminal state. */
    fun cancel(chatId: String) {
        jobs[chatId]?.job?.cancel()
    }

    /** The chat auto-name flow re-keys a placeholder chat in place; any
     *  running generation (and the attached screen) must follow, or its
     *  terminal state would land in the deleted placeholder id. */
    fun rename(oldChatId: String, newChatId: String) {
        if (oldChatId == newChatId) return
        jobs.remove(oldChatId)?.let { record ->
            record.chatId = newChatId
            jobs[newChatId] = record
        }
        listeners.remove(oldChatId)?.let { listeners[newChatId] = it }
    }

    /** Starts (or returns the already-running) generation for this chat —
     *  idempotent per chat, which is what makes recreation unable to start
     *  a second image. */
    fun start(
        context: Context,
        chatId: String,
        request: ImageGenerationRequest,
        origin: Origin
    ): ActiveJob {
        jobs[chatId]?.let { return it }
        val app = context.applicationContext
        val record = ActiveJob(chatId, request, origin, CompletableDeferred())
        // LAZY so the record is registered before the first suspension can run.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val terminal = try {
                runGeneration(app, request)
            } catch (_: CancellationException) {
                Terminal.Cancelled
            }
            // NonCancellable: the terminal state must be delivered even when
            // the ending IS a cancellation.
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                finish(app, record, terminal)
            }
        }
        record.job = job
        jobs[chatId] = record
        job.start()
        return record
    }

    private suspend fun runGeneration(
        app: Context,
        request: ImageGenerationRequest
    ): Terminal = when (val outcome = ImageGeneratorCoordinator.generate(app, request)) {
        is ImageGeneratorCoordinator.Outcome.Success -> {
            // Marker id and stored file name derive from the same encoded
            // bytes (GeneratedImageStorage), so the saved message keeps
            // resolving to its file; the real detected type decides the
            // extension (§4.5).
            val marker = withContext(Dispatchers.IO) {
                File(
                    app.getExternalFilesDir("images"),
                    GeneratedImageStorage.cacheFileName(
                        outcome.image.bytes, outcome.image.fileExtension
                    )
                ).writeBytes(outcome.image.bytes)
                Hash.hash(java.util.Base64.getEncoder().encodeToString(outcome.image.bytes))
            }
            ImageGenerationEventLog.recordSuccess(app, outcome.diagnostics)
            Terminal.Complete(marker)
        }
        is ImageGeneratorCoordinator.Outcome.Failure -> {
            ImageGenerationEventLog.recordFailure(
                app, outcome.diagnostics, outcome.errorCause, outcome.sanitizedDetail
            )
            Terminal.Failed(outcome.errorCause)
        }
    }

    private suspend fun finish(app: Context, record: ActiveJob, terminal: Terminal) {
        jobs.remove(record.chatId)
        record.terminal.complete(terminal)
        val listener = listeners[record.chatId]
        if (listener != null) {
            listener.onImageJobFinished(record, terminal)
        } else {
            persistTerminalWithoutScreen(app, record, terminal)
        }
    }

    /** §5: leaving the chat must not lose the result. With no screen
     *  attached the terminal message is appended straight into the chat's
     *  stored history — same message shapes the screen itself would save. */
    private suspend fun persistTerminalWithoutScreen(
        app: Context,
        record: ActiveJob,
        terminal: Terminal
    ) {
        val message = when (terminal) {
            is Terminal.Complete -> "~file:" + terminal.marker
            is Terminal.Failed -> app.getString(imageFailureMessageRes(terminal.cause))
            is Terminal.Cancelled -> app.getString(R.string.image_gen_error_cancelled)
        }
        try {
            withContext(Dispatchers.IO) {
                val chatPreferences = ChatPreferences.getChatPreferences()
                val result = chatPreferences.getChatByIdResult(app, record.chatId)
                // A locked or preserved-corrupt history must never be
                // overwritten with a fresh list (Round 4 guard).
                if (!ChatStorageHealth.isAuthoritative(result.state)) return@withContext
                val history = result.messages
                val map = HashMap<String, Any>()
                map["message"] = message
                map["isBot"] = true
                history.add(map)
                chatPreferences.saveChatHistory(app, record.chatId, history)
                if (terminal is Terminal.Complete) {
                    chatPreferences.putTimestampToChatById(app, record.chatId)
                }
            }
        } catch (_: Exception) { /* delivery must never crash the app */ }
    }
}
