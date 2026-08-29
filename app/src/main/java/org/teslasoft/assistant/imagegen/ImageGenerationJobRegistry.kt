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
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogRecord
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStorageState
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStore
import org.teslasoft.assistant.service.ImageGenerationForegroundService
import org.teslasoft.assistant.util.AtomicFileWriter
import org.teslasoft.assistant.util.GeneratedImageStorage
import org.teslasoft.assistant.util.Hash
import java.io.File
import java.util.UUID

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

/** The same provider-detail formula used by failed text replies: the app's
 * cause-specific explanation remains the message body, while this block
 * reveals the provider's own sanitized error and request identity beneath it. */
fun imageFailureProviderDetailBlock(
    context: Context,
    failure: ImageGenerationJobRegistry.Terminal.Failed
): String {
    val diagnostics = failure.diagnostics
    val noProviderResponse = failure.cause == ImageErrorCause.ENDPOINT_UNREACHABLE ||
        failure.cause == ImageErrorCause.TIMED_OUT
    val detail = if (noProviderResponse) {
        context.getString(R.string.provider_error_no_response)
    } else {
        val providerMessage = failure.providerDetail?.trim()?.ifBlank { null }
        when {
            diagnostics?.httpStatus != null && providerMessage != null ->
                "${diagnostics.httpStatus} $providerMessage"
            providerMessage != null -> providerMessage
            diagnostics?.httpStatus != null -> diagnostics.httpStatus.toString()
            else -> context.getString(R.string.provider_error_none)
        }
    }
    val notReported = context.getString(R.string.provider_value_not_reported)
    val endpointFallback = try {
        ApiEndpointPreferences.getApiEndpointPreferences(context)
            .getApiEndpoint(context, failure.metadata.endpointId)
            .let { endpoint -> endpoint.label.ifBlank { endpoint.host } }
    } catch (_: Exception) {
        ""
    }
    val apiProvider = diagnostics?.endpointLabel?.trim()?.ifBlank { null }
        ?: endpointFallback.trim().ifBlank { null }
        ?: notReported
    val modelService = failure.reportedProvider?.trim()?.ifBlank { null } ?: notReported
    val model = failure.metadata.modelId.trim().ifBlank { notReported }
    return context.getString(R.string.provider_error_line, detail) +
        "\n" + context.getString(R.string.provider_api_provider_line, apiProvider) +
        "\n" + context.getString(R.string.provider_model_service_line, modelService) +
        "\n" + context.getString(R.string.provider_model_line, model) +
        "\n" + context.getString(
            R.string.provider_function_line,
            context.getString(R.string.provider_function_image_generation)
        )
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

    /** The §5 rule: exactly one terminal state per generation. Every
     *  terminal carries the §12 structured record for the message that
     *  ends the turn. */
    sealed class Terminal {
        abstract val metadata: GeneratedImageMetadata

        class Complete(
            val marker: String,
            override val metadata: GeneratedImageMetadata
        ) : Terminal()

        class Failed(
            val cause: ImageErrorCause,
            override val metadata: GeneratedImageMetadata,
            val providerDetail: String? = null,
            val reportedProvider: String? = null,
            val diagnostics: ImageRequestDiagnostics? = null
        ) : Terminal()

        class Cancelled(override val metadata: GeneratedImageMetadata) : Terminal()
    }

    class ActiveJob internal constructor(
        chatId: String,
        /** Immutable ownership identity. Chat titles never participate in it. */
        val originChatId: String,
        originChatName: String?,
        /** Allocated once and never recalculated from a label, chat name, file
         * hash, extension, or future image title. */
        val imageId: String,
        val request: ImageGenerationRequest,
        val origin: Origin,
        internal val terminal: CompletableDeferred<Terminal>
    ) {
        /** Mutable because a placeholder chat can be auto-renamed (and
         *  re-keyed) while its first turn is still generating. */
        var chatId: String = chatId
            internal set

        var originChatName: String? = originChatName
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

    /** A successful title-only chat rename changes only the catalog label. */
    fun updateOriginChatName(chatId: String, newName: String) {
        jobs[chatId]?.takeIf { it.originChatId == chatId }?.originChatName = newName
    }

    /** Starts (or returns the already-running) generation for this chat —
     *  idempotent per chat, which is what makes recreation unable to start
     *  a second image. */
    fun start(
        context: Context,
        chatId: String,
        originChatName: String?,
        request: ImageGenerationRequest,
        origin: Origin
    ): ActiveJob {
        jobs[chatId]?.let { return it }
        val app = context.applicationContext
        val record = ActiveJob(
            chatId = chatId,
            originChatId = chatId,
            originChatName = originChatName,
            imageId = UUID.randomUUID().toString(),
            request = request,
            origin = origin,
            terminal = CompletableDeferred()
        )
        // LAZY so the record is registered before the first suspension can run.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // Direct /imagine requests do not pass through ChatActivity's
            // text-generation funnel, so the image registry owns its own
            // screen-off/app-switch keep-alive. The existing service is
            // reference-counted, making this safe for tool generations that
            // overlap a regular model turn.
            val keepAliveStarted = ImageGenerationForegroundService.begin(app, chatId)
            val terminal = try {
                try {
                    runGeneration(app, record)
                } catch (_: CancellationException) {
                    Terminal.Cancelled(
                        metadataFor(record, GeneratedImageMetadata.STATUS_CANCELLED)
                    )
                } catch (_: Exception) {
                    // Without this catch, a local file-write/decode failure
                    // could leave the Creating Image row stuck forever.
                    Terminal.Failed(
                        ImageErrorCause.PROVIDER_ERROR,
                        metadataFor(
                            record,
                            GeneratedImageMetadata.STATUS_FAILED,
                            failureCode = ImageErrorCause.PROVIDER_ERROR.name
                        )
                    )
                }
            } finally {
                if (keepAliveStarted) ImageGenerationForegroundService.end(app)
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
        record: ActiveJob
    ): Terminal = when (val outcome = ImageGeneratorCoordinator.generate(app, record.request)) {
        is ImageGeneratorCoordinator.Outcome.Success -> {
            val bytes = outcome.image.bytes
            val marker = Hash.hash(java.util.Base64.getEncoder().encodeToString(bytes))
            val assetFileName = GeneratedImageStorage.catalogFileName(
                record.imageId,
                outcome.image.fileExtension
            ) ?: return Terminal.Failed(
                ImageErrorCause.PROVIDER_ERROR,
                metadataFor(
                    record,
                    GeneratedImageMetadata.STATUS_FAILED,
                    failureCode = ImageErrorCause.PROVIDER_ERROR.name
                )
            )
            // §12 width and height, read from the actual bytes without
            // decoding the full bitmap.
            val bounds = android.graphics.BitmapFactory.Options()
                .apply { inJustDecodeBounds = true }
            try {
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            } catch (_: Exception) { /* dimensions stay absent */ }
            val metadata = metadataFor(
                    record,
                    GeneratedImageMetadata.STATUS_COMPLETE,
                    fileHash = marker,
                    mimeType = outcome.image.mimeType,
                    width = bounds.outWidth.takeIf { it > 0 },
                    height = bounds.outHeight.takeIf { it > 0 },
                    assetFileName = assetFileName
                )
            val registration = withContext(Dispatchers.IO) {
                val imagesDir = app.getExternalFilesDir("images")
                    ?: return@withContext false
                val target = File(imagesDir, assetFileName)
                val fileResult = AtomicFileWriter.writeBytesAndVerify(target, bytes)
                if (fileResult == AtomicFileWriter.ByteWriteResult.FAILED) {
                    return@withContext false
                }
                val catalogResult = GeneratedImageCatalogStore.register(
                    app,
                    GeneratedImageCatalogRecord(
                        imageId = record.imageId,
                        fileHash = marker,
                        assetFileName = assetFileName,
                        mimeType = outcome.image.mimeType,
                        width = bounds.outWidth.takeIf { it > 0 },
                        height = bounds.outHeight.takeIf { it > 0 },
                        createdAt = metadata.createdAt,
                        originChatId = record.originChatId.takeIf { it.isNotBlank() },
                        originChatName = record.originChatName?.takeIf { it.isNotBlank() },
                        // imageId is also the stable exact-image locator until
                        // messages gain a separate durable message UUID.
                        originMessageId = record.imageId,
                        locked = false,
                        source = GeneratedImageCatalogRecord.Source.GENERATED
                    )
                )
                if (catalogResult.success) return@withContext true

                // A commit can report an exception after becoming durable.
                // Re-read before cleaning our new file; never remove a file an
                // active row may already own.
                val lookup = GeneratedImageCatalogStore.lookup(app, record.imageId)
                if (lookup.state == GeneratedImageCatalogStorageState.AVAILABLE &&
                    lookup.record?.assetFileName == assetFileName
                ) {
                    return@withContext true
                }
                if (fileResult == AtomicFileWriter.ByteWriteResult.WRITTEN &&
                    lookup.state == GeneratedImageCatalogStorageState.AVAILABLE &&
                    lookup.record == null
                ) {
                    target.delete()
                }
                false
            }
            if (!registration) {
                return Terminal.Failed(
                    ImageErrorCause.PROVIDER_ERROR,
                    metadataFor(
                        record,
                        GeneratedImageMetadata.STATUS_FAILED,
                        failureCode = ImageErrorCause.PROVIDER_ERROR.name
                    )
                )
            }
            ImageGenerationEventLog.recordSuccess(app, outcome.diagnostics)
            Terminal.Complete(marker, metadata)
        }
        is ImageGeneratorCoordinator.Outcome.Failure -> {
            ImageGenerationEventLog.recordFailure(
                app, outcome.diagnostics, outcome.errorCause, outcome.sanitizedDetail
            )
            Terminal.Failed(
                outcome.errorCause,
                metadataFor(
                    record,
                    GeneratedImageMetadata.STATUS_FAILED,
                    failureCode = outcome.errorCause.name
                ),
                providerDetail = outcome.sanitizedDetail,
                reportedProvider = outcome.reportedProvider,
                diagnostics = outcome.diagnostics
            )
        }
    }

    /** One §12 record per terminal message. Deliberately built only from
     *  the request and the detected result — no credential or signed URL
     *  can enter it. */
    private fun metadataFor(
        record: ActiveJob,
        status: String,
        fileHash: String? = null,
        mimeType: String? = null,
        width: Int? = null,
        height: Int? = null,
        failureCode: String? = null,
        assetFileName: String? = null
    ) = GeneratedImageMetadata(
        imageId = record.imageId,
        fileHash = fileHash,
        mimeType = mimeType,
        width = width,
        height = height,
        endpointId = record.request.endpointId,
        modelId = record.request.modelId,
        prompt = record.request.prompt,
        description = record.request.description,
        createdAt = System.currentTimeMillis(),
        status = status,
        failureCode = failureCode,
        assetFileName = assetFileName
    )

    private suspend fun finish(app: Context, record: ActiveJob, terminal: Terminal) {
        jobs.remove(record.chatId)
        val listener = listeners[record.chatId]
        try {
            if (listener != null) {
                try {
                    listener.onImageJobFinished(record, terminal)
                } catch (_: Exception) {
                    persistTerminalWithoutScreen(app, record, terminal)
                }
            } else {
                persistTerminalWithoutScreen(app, record, terminal)
            }
        } finally {
            // Release a model tool continuation only after the image/failure
            // has been inserted into the visible chat or durable history.
            // Completing first caused the assistant follow-up to appear while
            // the image row was still missing.
            record.terminal.complete(terminal)
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
                // §12: the structured record travels with the message; the
                // `~file:` text above is only the rendering projection.
                map[GeneratedImageMetadata.KEY] = terminal.metadata.toJson()
                if (terminal is Terminal.Failed) {
                    map[MessageCompletionState.KEY_STATE] = MessageCompletionState.FAILED
                    map[MessageCompletionState.KEY_STATE_DETAIL] = terminal.cause.name
                    map[MessageCompletionState.KEY_ERROR_TEXT] =
                        imageFailureProviderDetailBlock(app, terminal)
                }
                history.add(map)
                chatPreferences.saveChatHistory(app, record.chatId, history)
                if (terminal is Terminal.Complete) {
                    chatPreferences.putTimestampToChatById(app, record.chatId)
                }
            }
        } catch (_: Exception) { /* delivery must never crash the app */ }
    }
}
