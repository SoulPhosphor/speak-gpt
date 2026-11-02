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

package org.teslasoft.assistant.util.summarizer

import android.content.Context
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.RetryStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.providers.DedicatedModelRoutingPolicy
import org.teslasoft.assistant.providers.ProviderRoutingResolver
import org.teslasoft.assistant.providers.ProviderRoutingSerializer
import org.teslasoft.assistant.providers.RoutingBlock
import org.teslasoft.assistant.util.GenerationErrorClassifier
import org.teslasoft.assistant.providers.ReportedProviderParser
import org.teslasoft.assistant.usage.AuxiliaryUsage
import org.teslasoft.assistant.usage.ProviderUsageAttempt
import org.teslasoft.assistant.usage.TokenPricingCatalogClient
import org.teslasoft.assistant.usage.TokenUsageAccounting
import org.teslasoft.assistant.usage.UsageCategory
import org.teslasoft.assistant.usage.UsageFunction
import org.teslasoft.assistant.usage.UsageLog
import org.teslasoft.assistant.usage.UsageLogStore
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.observer.ResponseObserver
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlin.time.Duration.Companion.seconds

/**
 * The background fold-in engine (conversation-summary-plan.md decisions 2,
 * 10, 15, 16 and the whole of conversation-summary-errors.md).
 *
 * A cycle folds messages that have aged past the chat's Complete Messages
 * window into the rolling summary, one batched call at a time. The bookmark
 * advances only after a returned summary has been validated AND committed
 * together with the bookmark; until then every message after the bookmark
 * keeps being sent to the chat model in full, so a slow or failing
 * summarizer never blocks, delays, or drops conversation content.
 *
 * Steady state waits for a full batch (ten messages) before calling, so the
 * request prefix stays byte-stable between batches and provider prompt
 * caching keeps applying. A forced cycle (first enable catch-up finishing,
 * or the summary view's Update Now) also folds the final partial batch.
 */
class SummarizerController(
    private val appContext: Context,
    /** Read per batch, not captured: auto-naming can rename the chat (and
     *  move its settings file) while a catch-up is still running. */
    private val chatIdProvider: () -> String
) {

    enum class OperationKind { SUMMARIZING, COMPACTING }

    sealed interface OperationState {
        data object Idle : OperationState
        data class Running(
            val kind: OperationKind,
            val chatName: String,
            val requestedMessages: Int,
            val successfulMessages: Int
        ) : OperationState {
            /** How far through the run is, from messages actually done. */
            val percent: Int
                get() = if (requestedMessages <= 0) 0
                else (successfulMessages.toLong() * 100 / requestedMessages).toInt().coerceIn(0, 100)
        }
        data class Succeeded(val kind: OperationKind, val chatName: String) : OperationState
        data class Failed(
            val kind: OperationKind,
            val chatName: String,
            val category: SummarizerErrorCategory,
            /** The AI service's own error text, when it sent one. */
            val providerError: String? = null
        ) : OperationState
        data class Cancelled(
            val kind: OperationKind,
            val chatName: String,
            val savedMessages: Int
        ) : OperationState
    }

    /** Callbacks arrive on the main thread. */
    interface Listener {
        /** Summary, bookmark, or error log changed — refresh icons/badge. */
        fun onSummarizerStateChanged()

        /** A new failure episode began — play the dedicated sound once. */
        fun onSummarizerErrorEpisode()

        fun onSummarizerOperationChanged(state: OperationState) {}
    }

    /**
     * A snapshot of the chat for one fold-in step: one entry per STORED
     * message, in order, so indexes stay aligned with the fold-in bookmark.
     * [text] is the model-facing content ("" for messages the projection
     * skips — they still advance the bookmark but are not sent).
     */
    data class Entry(
        val isBot: Boolean,
        val text: String,
        /** The stored message's permanent ID (MessageIdentity). */
        val id: String = "",
        val timeMillis: Long? = null
    )

    data class Snapshot(val entries: List<Entry>, val window: Int) {
        fun sources(): List<SummarySections.SourceMessage> =
            entries.map { SummarySections.SourceMessage(it.id, it.isBot, it.text, it.timeMillis) }
    }

    private data class FoldRuntime(
        val prefs: Preferences,
        val endpoint: ApiEndpointObject,
        val model: String,
        val providerJson: com.google.gson.JsonObject?,
        val requestedProvider: String,
        val prompt: String
    )

    private sealed interface FoldBatchResult {
        data class Advanced(
            /** The compaction's new part for this batch, to be appended. */
            val summary: String,
            val foldedCount: Int,
            /** The token amount still allowed for the next request. */
            val tokenBudget: Int
        ) : FoldBatchResult

        data object Failed : FoldBatchResult
    }

    var listener: Listener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var manualCompactionRunning = false
    @Volatile private var operationState: OperationState = OperationState.Idle
    private var lastFailureCategory: SummarizerErrorCategory? = null
    private var lastFailureProviderError: String? = null
    private var terminalClearJob: Job? = null

    companion object {
        /** Internal fold-in batch size (decision 15) — not a user setting. */
        const val BATCH_SIZE = 10

        /** True when a summarizer endpoint profile and model resolve — the
         *  gate for showing the Quick Settings toggle (decision 8). */
        fun isConfigured(context: Context): Boolean = try {
            val prefs = Preferences.getPreferences(context, "")
            val endpointId = prefs.getSummarizerEndpointId()
            if (endpointId.isBlank()) {
                false
            } else {
                val endpoint = ApiEndpointPreferences.getApiEndpointPreferences(context)
                    .getApiEndpoint(context, endpointId)
                val model = prefs.getSummarizerModel()
                val routingReady = if (endpoint.isOpenRouterRouting() && model.isNotBlank()) {
                    val favorite = FavoriteModelsPreferences.getPreferences(context)
                        .getFavorite(model, endpointId)
                    !DedicatedModelRoutingPolicy.needsSetup(
                        prefs.getSummarizerRoutingType(), favorite
                    )
                } else {
                    true
                }
                endpoint.host.isNotBlank() && model.isNotBlank() && routingReady
            }
        } catch (_: Exception) {
            false
        }
    }

    fun isRunning(): Boolean = job?.isActive == true

    fun isManualCompactionRunning(): Boolean = manualCompactionRunning

    fun currentOperationState(): OperationState = operationState

    /**
     * Deliberate cancellation (leaving the chat, turning the toggle off, a
     * settings change) — never an error, never a log entry, bookmark
     * untouched (errors doc §4).
     */
    fun cancel() {
        job?.cancel()
        job = null
    }

    /** The chat's state from before the last finished compaction, held
     *  until the user answers Cancel or Okay in its dialog. */
    private var finishedCompactionCheckpoint: Pair<Map<String, String?>, String>? = null

    /** Cancel after "Compaction complete!": puts the chat back exactly as it
     *  was before compacting (owner ruling, Oct 4 2026). */
    fun discardFinishedCompaction(): Boolean {
        val (checkpoint, chatId) = finishedCompactionCheckpoint ?: return false
        finishedCompactionCheckpoint = null
        if (chatId != chatIdProvider()) return false
        val restored = Preferences.getPreferences(appContext, chatId).restoreCompactionCheckpoint(checkpoint)
        if (restored) notifyStateChanged()
        return restored
    }

    /** Okay after "Compaction complete!": the compaction stays. */
    fun keepFinishedCompaction() {
        finishedCompactionCheckpoint = null
    }

    /** The compaction regeneration lock when the current run started. */
    private var runStartLock: Int? = null

    /** Puts back the regeneration lock a stopped run's finished batches
     *  advanced. Never raises it, so a deletion's realignment stands. */
    private fun restoreRunLock(prefs: Preferences, startLock: Int) {
        val current = prefs.getCompactionRegenerationLockBoundary()
        if (current > startLock) prefs.setCompactionRegenerationLockBoundary(startLock)
    }

    /**
     * Stops an automatic summary update and waits until its cancellation
     * cleanup has finished, so the caller can change the stored messages
     * and bookmark afterwards (owner ruling, Oct 3 2026: deleting messages
     * stops summarizing). The unfinished batch is discarded. A user-started
     * compaction is left alone: it already discards its result if the
     * messages it froze were changed.
     */
    suspend fun cancelSummarizingAndWait() {
        if (manualCompactionRunning) return
        val running = job ?: return
        running.cancelAndJoin()
        if (job === running) job = null
    }

    /**
     * Starts a fold-in cycle. [snapshotProvider] runs on the main thread and
     * returns the current chat snapshot, or null when the chat is no longer
     * available; it is re-read before every batch so catch-up always works
     * against live state. With [force] the final partial batch is folded
     * too (Update Now / completing a catch-up); otherwise the cycle stops
     * when fewer than [BATCH_SIZE] messages wait past the window.
     */
    fun runCycle(
        force: Boolean,
        chatName: String = "",
        snapshotProvider: () -> Snapshot?,
        onFinished: ((Boolean) -> Unit)? = null
    ) {
        if (manualCompactionRunning || isRunning()) return
        job = scope.launch {
            var advancedAny = false
            try {
                while (true) {
                    val advanced = buildOneSection(force, chatName, snapshotProvider)
                    if (!advanced) break
                    advancedAny = true
                }
            } catch (_: CancellationException) {
                // Deliberate cancellation — not a Summarizer Error (§4). Each
                // section is saved on its own, so finished sections stay and
                // the unfinished one is simply discarded.
                setOperationState(
                    OperationState.Cancelled(
                        OperationKind.SUMMARIZING,
                        chatName,
                        (operationState as? OperationState.Running)?.successfulMessages ?: 0
                    )
                )
            } catch (e: Exception) {
                recordUnexpectedFailure(e, OperationKind.SUMMARIZING, chatName)
            } finally {
                if (operationState is OperationState.Running) {
                    setOperationState(
                        if (lastFailureCategory == null) {
                            OperationState.Succeeded(OperationKind.SUMMARIZING, chatName)
                        } else {
                            OperationState.Failed(
                                OperationKind.SUMMARIZING,
                                chatName,
                                lastFailureCategory!!
                            )
                        }
                    )
                }
                onFinished?.invoke(advancedAny && lastFailureCategory == null)
            }
        }
    }

    /**
     * Compacts one immutable conversation snapshot through its final stored
     * message, whether or not automatic Summarizer mode is enabled. Every
     * intermediate batch remains in memory; persisted summary state and the
     * visible manual boundary are committed together only after the complete
     * operation succeeds and [stillCurrent] confirms the frozen prefix has not
     * been edited or removed. New messages appended after the snapshot are
     * allowed and remain outside this manual checkpoint.
     */
    fun runManualCompaction(
        snapshot: Snapshot,
        chatName: String,
        stillCurrent: () -> Boolean,
        onFinished: (Boolean) -> Unit,
        /** Rewrite the compacted text from the first message instead of
         *  adding to it (Recompact after compacted messages were deleted). */
        fromScratch: Boolean = false,
        /** Redo This Summary: run only the newest compaction step again,
         *  from the text it started from, replacing that newest version. */
        redoNewest: Boolean = false
    ) {
        if (manualCompactionRunning) return
        cancel()
        manualCompactionRunning = true
        lastFailureCategory = null
        lastFailureProviderError = null
        runStartLock = null
        setOperationState(
            OperationState.Running(
                OperationKind.COMPACTING,
                chatName,
                snapshot.entries.size,
                0
            )
        )
        job = scope.launch {
            var committed = false
            try {
                committed = compactSnapshot(
                    snapshot,
                    chatName,
                    stillCurrent,
                    fromScratch,
                    redoNewest
                )
            } catch (_: CancellationException) {
                // A cancelled compaction keeps nothing.
            } catch (e: Exception) {
                recordUnexpectedFailure(e, OperationKind.COMPACTING, chatName)
            } finally {
                manualCompactionRunning = false
                if (operationState is OperationState.Running) {
                    // A failed run saves nothing and puts back the lock.
                    if (!committed) {
                        val chatId = chatIdProvider()
                        val startLock = runStartLock
                        if (chatId.isNotBlank() && startLock != null) {
                            restoreRunLock(Preferences.getPreferences(appContext, chatId), startLock)
                        }
                    }
                    setOperationState(
                        if (committed) {
                            OperationState.Succeeded(OperationKind.COMPACTING, chatName)
                        } else {
                            OperationState.Failed(
                                OperationKind.COMPACTING,
                                chatName,
                                lastFailureCategory ?: SummarizerErrorCategory.UNEXPECTED,
                                lastFailureProviderError
                            )
                        }
                    )
                }
                onFinished(committed)
            }
        }
    }

    /**
     * One-shot image-prompt summary (owner request, Aug 16 2026). Sends the
     * single [imagePrompt] to the configured Summary Model under the Image
     * Summary Prompt and returns the model's short version, or null when the
     * summarizer is not configured or the call fails. Silent in chat: an image
     * summary is a token-saving convenience, so a failure never writes a
     * Summarizer Error, never interrupts chat, and simply leaves the caller to
     * fall back to the full prompt and try again on a later turn. The failure
     * is still recorded in the Provider Failure Log or Error Log.
     */
    suspend fun summarizeImagePrompt(imagePrompt: String): String? {
        if (imagePrompt.isBlank()) return null
        val prefs = Preferences.getPreferences(appContext, "")

        val (endpoint, model, requestFavorite, routingResolution) =
            resolveOneShotTarget(prefs) ?: return null

        val instruction = SummarizerPromptSets.activeText(
            prefs,
            SummarizerPromptSets.Kind.IMAGE,
            chatIdProvider()
        )
        val body = SummarizerPrompts.imageSummaryRequestBody(instruction, imagePrompt)
        val evidence = SummarizerDiagnostics.RequestEvidence()
        val privateValues = listOf(body, imagePrompt, endpoint.apiKey)
        val text = try {
            withContext(Dispatchers.IO) {
                val request = ChatCompletionRequest(
                    model = ModelId(model),
                    maxTokens = 200,
                    messages = listOf(ChatMessage(role = ChatRole.User, content = body))
                )
                withSummarizerUsage(
                    Preferences.getPreferences(appContext, chatIdProvider()),
                    endpoint, model, requestFavorite?.selectedProvider.orEmpty(),
                    UsageFunction.IMAGE_DESCRIPTION
                ) { attempt ->
                    val client = buildClient(endpoint, routingResolution.providerJson, evidence, attempt)
                    evidence.dispatched = true
                    client.chatCompletion(request)
                }.choices.firstOrNull()?.message?.content?.toString().orEmpty()
            }.trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val classified = GenerationErrorClassifier.classify(e, evidence.snapshot())
            recordOneShotFailure(
                prefs, SummarizerDiagnostics.owner(e, classified, evidence), e, evidence,
                endpoint, model, requestFavorite?.selectedProvider, privateValues
            )
            return null
        }
        if (text.isBlank()) {
            recordOneShotFailure(
                prefs, SummarizerDiagnostics.Owner.EXTERNAL, null, evidence,
                endpoint, model, requestFavorite?.selectedProvider, privateValues
            )
            return null
        }
        return text
    }

    /**
     * A failed image prompt summary is still silent in chat, but is recorded
     * in exactly one log (owner ruling, Oct 12 2026): a provider failure in the
     * Provider Failure Log as "Summarize Image Prompt", anything else in the
     * Error Log. Cancellation and missing setup are not recorded.
     */
    private fun recordOneShotFailure(
        prefs: Preferences,
        owner: SummarizerDiagnostics.Owner,
        error: Throwable?,
        evidence: SummarizerDiagnostics.RequestEvidence,
        endpoint: ApiEndpointObject,
        model: String,
        requestedProvider: String?,
        privateValues: List<String>,
        function: String = appContext.getString(R.string.provider_function_summarize_image_prompt)
    ) {
        val allPrivate = privateValues + evidence.privateResponseValues()
        val providerEvidence = evidence.snapshot()
        SummarizerDiagnostics.record(
            owner,
            logProviders = prefs.getLogChatFailures(),
            local = {
                org.teslasoft.assistant.preferences.Logger.logAsync(appContext, "crash", function, "error",
                    SummarizerDiagnostics.localDetail(
                        function, error ?: IllegalStateException("Local $function failure"), allPrivate
                    ))
            },
            external = {
                val message = buildString {
                    append(SummarizerDetailSanitizer.sanitize(
                        providerEvidence.errorMessages.joinToString("\n").ifBlank { null }
                            ?: error?.message, allPrivate
                    ) ?: "Provider returned no usable completion")
                    SummarizerDetailSanitizer.sanitize(evidence.body, allPrivate)?.let {
                        append("\nResponse evidence: ").append(it)
                    }
                }
                scope.launch(Dispatchers.IO) {
                    org.teslasoft.assistant.preferences.Logger.logProviderFailure(
                        appContext, SummarizerDetailSanitizer.sanitize(endpoint.label, allPrivate) ?: "Not Reported",
                        SummarizerDetailSanitizer.sanitize(providerEvidence.actualServingProvider, allPrivate) ?: "Not Reported",
                        SummarizerDetailSanitizer.sanitize(model, allPrivate) ?: "Not Reported", function, message,
                        requestedRoutedProvider = SummarizerDetailSanitizer.sanitize(requestedProvider, allPrivate),
                        outerHttpStatus = providerEvidence.outerHttpStatus,
                        embeddedProviderStatus = providerEvidence.embeddedHttpStatus,
                        providerCode = SummarizerDetailSanitizer.sanitize(providerEvidence.providerCode, allPrivate),
                        providerType = SummarizerDetailSanitizer.sanitize(providerEvidence.providerType, allPrivate),
                        providerErrorType = SummarizerDetailSanitizer.sanitize(providerEvidence.providerErrorType, allPrivate),
                        contentFilterSide = providerEvidence.contentFilterSide?.wire,
                        attemptId = providerEvidence.attemptId
                    )
                }
            }
        )
    }

    /**
     * Backup chat title (owner ruling, Oct 12 2026): used only after the
     * chat's own model has used up its naming attempts. Runs whether or not
     * summarizing is on for this chat, since naming does not touch the
     * chat's summary. Sends [messages] (the conversation's text turns and the
     * naming instruction) to the configured Summary Model and returns its raw
     * reply, or null when no summarizer is set up or the call fails. Silent
     * like the image summary: a failure leaves the chat untitled and writes no
     * Summarizer Error.
     */
    suspend fun generateChatTitle(messages: List<ChatMessage>): String? {
        if (messages.isEmpty()) return null
        val prefs = Preferences.getPreferences(appContext, "")
        val (endpoint, model, requestFavorite, routingResolution) =
            resolveOneShotTarget(prefs) ?: return null

        val evidence = SummarizerDiagnostics.RequestEvidence()
        val privateValues = messages.mapNotNull { it.content } + endpoint.apiKey
        val text = try {
            withContext(Dispatchers.IO) {
                val request = ChatCompletionRequest(
                    model = ModelId(model),
                    messages = messages
                )
                withSummarizerUsage(
                    Preferences.getPreferences(appContext, chatIdProvider()),
                    endpoint, model, requestFavorite?.selectedProvider.orEmpty(),
                    UsageFunction.SUMMARIZING
                ) { attempt ->
                    val client = buildClient(endpoint, routingResolution.providerJson, evidence, attempt)
                    evidence.dispatched = true
                    client.chatCompletion(request)
                }.choices.firstOrNull()?.message?.content?.toString()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val classified = GenerationErrorClassifier.classify(e, evidence.snapshot())
            recordOneShotFailure(
                prefs, SummarizerDiagnostics.owner(e, classified, evidence), e, evidence,
                endpoint, model, requestFavorite?.selectedProvider, privateValues, "Chat Title"
            )
            return null
        }
        if (text.isNullOrBlank()) {
            recordOneShotFailure(
                prefs, SummarizerDiagnostics.Owner.EXTERNAL, null, evidence,
                endpoint, model, requestFavorite?.selectedProvider, privateValues, "Chat Title"
            )
            return null
        }
        return text
    }

    private data class OneShotTarget(
        val endpoint: ApiEndpointObject,
        val model: String,
        val requestFavorite: org.teslasoft.assistant.preferences.dto.FavoriteModelObject?,
        val routingResolution: ProviderRoutingResolver.Resolution
    )

    /** The configured Summary Model for a one-shot call, or null when it is
     *  not set up or its routing is blocked. */
    private fun resolveOneShotTarget(prefs: Preferences): OneShotTarget? {
        val endpointId = prefs.getSummarizerEndpointId()
        if (endpointId.isBlank()) return null
        val endpoint = try {
            ApiEndpointPreferences.getApiEndpointPreferences(appContext)
                .getApiEndpoint(appContext, endpointId)
        } catch (_: Exception) {
            null
        } ?: return null
        val model = prefs.getSummarizerModel()
        if (endpoint.host.isBlank() || model.isBlank()) return null

        val routingMode = prefs.getSummarizerRoutingType()
        val savedFavorite = FavoriteModelsPreferences.getPreferences(appContext)
            .getFavorite(model, endpointId)
        if (endpoint.isOpenRouterRouting() &&
            DedicatedModelRoutingPolicy.needsSetup(routingMode, savedFavorite)
        ) {
            return null
        }
        val requestFavorite = if (endpoint.isOpenRouterRouting()) {
            DedicatedModelRoutingPolicy.favoriteForRequest(
                model, endpointId, routingMode, savedFavorite
            )
        } else {
            null
        }
        val routingResolution = ProviderRoutingResolver.resolve(
            endpoint.isOpenRouterRouting(), requestFavorite
        )
        if (routingResolution.block != RoutingBlock.NONE) return null
        return OneShotTarget(endpoint, model, requestFavorite, routingResolution)
    }

    private suspend fun compactSnapshot(
        snapshot: Snapshot,
        chatName: String,
        stillCurrent: () -> Boolean,
        fromScratch: Boolean = false,
        redoNewest: Boolean = false
    ): Boolean {
        val chatId = chatIdProvider()
        if (chatId.isBlank() || snapshot.entries.isEmpty()) return false
        val prefs = Preferences.getPreferences(appContext, chatId)
        if (!prefs.ensureSummarizerProjectionCompatibility()) {
            val configuredModel = prefs.getSummarizerModel()
            recordFailure(
                prefs,
                SummarizerErrorCategory.SAVE_FAILED,
                appContext.getString(R.string.summarizer_unknown_profile),
                if (configuredModel.isBlank()) {
                    appContext.getString(R.string.summarizer_unknown_model)
                } else configuredModel,
                null,
                "The app could not establish a compatible persisted summary projection before compaction."
            )
            return false
        }

        val target = snapshot.entries.size
        // Cancel, during the run or after it finishes, puts this back.
        val checkpoint = prefs.compactionCheckpoint()
        val startingLock = prefs.getCompactionRegenerationLockBoundary()
        runStartLock = startingLock
        finishedCompactionCheckpoint = null
        val startingSummary = prefs.getSummarizerSummary()
        val startingFolded = prefs.getSummarizerFoldedCount()
        val startingVersion = prefs.getSummarizerProjectionVersion()
        val priorVersions = CompactionVersions.current(
            prefs.getCompactionVersions(), startingSummary, prefs.getManualCompactionBoundary()
        )
        // Redo applies only to the newest version while it is the one in use.
        val redone = if (redoNewest) {
            priorVersions.versions.lastOrNull()?.takeIf {
                priorVersions.activeIndex == priorVersions.versions.lastIndex && it.count == target
            } ?: return false
        } else null
        val operationStartFolded = when {
            fromScratch -> 0
            redone != null -> redone.baseCount.coerceAtMost(target)
            else -> startingFolded.coerceAtMost(target)
        }

        setOperationState(
            OperationState.Running(
                OperationKind.COMPACTING,
                chatName,
                (target - operationStartFolded).coerceAtLeast(0),
                0
            )
        )

        // An ordinary compaction with no new messages adds nothing: no new
        // version, so older versions are not pushed out.
        if (!fromScratch && redone == null && operationStartFolded >= target) return true

        // Compaction only adds (owner ruling, Nov 2 2026): the text it starts
        // from is kept exactly, and each reply is appended after it. Nothing
        // is saved until the whole run succeeds; a failure keeps nothing.
        val baseSummary = when {
            fromScratch -> ""
            redone != null -> redone.baseText
            else -> startingSummary
        }
        var summary = baseSummary
        var folded = operationStartFolded
        // Requests of about the Compaction Average Token Amount, whole
        // exchanges only; lowered for the rest of the run when the service
        // says a request is too large.
        val configuredBatchTokens = prefs.getCompactionAverageTokens()
        var tokenBudget = configuredBatchTokens

        try {
            if (folded < target) {
                val runtime = resolveFoldRuntime(prefs, SummarizerPromptSets.Kind.COMPACTION)
                    ?: return false
                while (folded < target) {
                    when (val result = foldBatch(
                        runtime = runtime,
                        entries = snapshot.entries,
                        folded = folded,
                        target = target,
                        summary = summary,
                        tokenBudget = tokenBudget,
                        configuredBatchTokens = configuredBatchTokens
                    )) {
                        is FoldBatchResult.Advanced -> {
                            summary = SummarizerPrompts.appendCompaction(summary, result.summary)
                            tokenBudget = result.tokenBudget
                            folded = result.foldedCount
                            setOperationState(
                                OperationState.Running(
                                    OperationKind.COMPACTING,
                                    chatName,
                                    (target - operationStartFolded).coerceAtLeast(0),
                                    folded - operationStartFolded
                                )
                            )
                        }
                        FoldBatchResult.Failed -> return false
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // A cancelled compaction keeps nothing (owner rulings, Oct 4 and
            // Nov 2 2026), including the regeneration lock.
            withContext(NonCancellable) { restoreRunLock(prefs, startingLock) }
            setOperationState(OperationState.Cancelled(OperationKind.COMPACTING, chatName, 0))
            throw cancelled
        }

        // Do not overwrite a summary edit, automatic fold-in, or changed
        // canonical prefix that landed while the manual API calls were in
        // flight. Appended messages are intentionally outside this checkpoint.
        if (chatIdProvider() != chatId ||
            !stillCurrent() ||
            prefs.getSummarizerSummary() != startingSummary ||
            prefs.getSummarizerFoldedCount() != startingFolded ||
            prefs.getSummarizerProjectionVersion() != startingVersion
        ) {
            val configuredModel = prefs.getSummarizerModel()
            recordFailure(
                prefs,
                SummarizerErrorCategory.UNEXPECTED,
                appContext.getString(R.string.summarizer_unknown_profile),
                if (configuredModel.isBlank()) {
                    appContext.getString(R.string.summarizer_unknown_model)
                } else configuredModel,
                null,
                "Compaction finished generating, but its frozen conversation prefix or saved summary state changed before the atomic commit. Generated work was discarded."
            )
            return false
        }

        val added = CompactionVersions.Version(target, summary, operationStartFolded, baseSummary)
        val versions = when {
            fromScratch -> CompactionVersions.afterRecompactFromScratch(added)
            redone != null -> CompactionVersions.afterRedoNewest(priorVersions, added)
            else -> CompactionVersions.afterCompaction(priorVersions, added)
        }
        if (!prefs.commitManualCompaction(
                summary, target, false, target,
                versionsJson = CompactionVersions.toJson(versions),
                regenerationLock = CompactionVersions.furthestCount(versions)
            )
        ) {
            val configuredModel = prefs.getSummarizerModel()
            recordFailure(
                prefs,
                SummarizerErrorCategory.SAVE_FAILED,
                appContext.getString(R.string.summarizer_unknown_profile),
                if (configuredModel.isBlank()) {
                    appContext.getString(R.string.summarizer_unknown_model)
                } else configuredModel,
                null,
                null
            )
            return false
        }
        finishedCompactionCheckpoint = checkpoint to chatId
        notifyStateChanged()
        return true
    }

    /**
     * Writes one summary section: first any unedited section whose messages
     * changed, otherwise the next new section past the Complete Messages
     * window. [snapshotProvider] is read live, before the request and again
     * when it returns; a result whose messages changed meanwhile is discarded.
     * The source-change handler decides whether to start another cycle.
     *
     * @return true to keep looping.
     */
    private suspend fun buildOneSection(
        force: Boolean,
        chatName: String,
        snapshotProvider: () -> Snapshot?
    ): Boolean {
        val snapshot = snapshotProvider() ?: return false
        val chatId = chatIdProvider()
        if (chatId.isBlank()) return false
        val prefs = Preferences.getPreferences(appContext, chatId)
        if (!prefs.getChatUseSummarizer()) return false
        // Summary state saved before the Include payload migration may hold
        // old inline payload material. A failed compatibility commit leaves
        // canonical history intact and simply postpones this cycle.
        if (!prefs.ensureSummarizerProjectionCompatibility()) {
            recordStorageFailure(prefs, chatName, "Could not migrate Summary Section storage.")
            return false
        }
        val current = snapshot.sources()
        if (current.any { it.id.isBlank() }) return false
        val sections = SummarySectionStore.load(prefs, current) ?: run {
            recordStorageFailure(prefs, chatName, "Could not load Summary Sections.")
            return false
        }

        val replacing = sections.firstOrNull { it.awaitsRegeneration }
        val owned: List<SummarySections.SourceMessage> = if (replacing != null) {
            val byId = current.associateBy { it.id }
            replacing.messageIds.mapNotNull { byId[it] }
        } else {
            val covered = SummarySections.coveredCount(sections, current)
            val windowEdge = (current.size - snapshot.window.coerceAtLeast(1)).coerceAtLeast(0)
            val range = SummarySections.nextRange(current, covered, windowEdge, BATCH_SIZE, force)
                ?: return false
            current.subList(range.first, range.last + 1).toList()
        }
        if (owned.isEmpty()) return false
        val start = current.indexOfFirst { it.id == owned.first().id }
        val context = SummarySections.contextBefore(current, start).toList()

        if (operationState !is OperationState.Running) {
            lastFailureCategory = null
            setOperationState(
                OperationState.Running(OperationKind.SUMMARIZING, chatName, owned.size, 0)
            )
        }

        val runtime = resolveFoldRuntime(prefs, SummarizerPromptSets.Kind.SUMMARY) ?: return false
        val text = requestSection(runtime, owned, context) ?: return false

        // Never save a summary written from messages that changed meanwhile.
        val latest = snapshotProvider()?.sources() ?: return false
        if (chatIdProvider() != chatId ||
            !SummarySections.sourceStillCurrent(owned, context, latest)
        ) return false
        val fresh = SummarySectionStore.load(prefs, latest) ?: run {
            recordStorageFailure(prefs, chatName, "Could not load Summary Sections before saving.")
            return false
        }
        val updated = if (replacing != null) {
            val target = fresh.firstOrNull { it.id == replacing.id }
            if (!SummarySections.canCommitReplacement(replacing, target)) return false
            fresh.map {
                if (it.id == replacing.id) SummarySections.newSection(owned, context, text, it.id) else it
            }
        } else {
            if (SummarySections.coveredCount(fresh, latest) != start) return true
            fresh + SummarySections.newSection(owned, context, text)
        }
        if (!SummarySectionStore.save(prefs, updated)) {
            recordFailure(
                prefs, SummarizerErrorCategory.SAVE_FAILED, runtime.endpoint.label, runtime.model,
                httpStatus = null, detail = null
            )
            return false
        }
        if (!prefs.advanceSummaryRegenerationLockBoundary(SummarySections.coveredCount(updated, latest))) {
            recordFailure(prefs, SummarizerErrorCategory.SAVE_FAILED, runtime.endpoint.label,
                runtime.model, null, "Could not save the Summary Section regeneration boundary. Technical details were recorded in the Error Log.")
            return false
        }
        notifyStateChanged()
        val running = operationState as? OperationState.Running
        if (running?.kind == OperationKind.SUMMARIZING) {
            setOperationState(running.copy(successfulMessages = running.successfulMessages + owned.size))
        }
        return true
    }

    /**
     * One section summary call with no app-imposed response length ceiling.
     * @return the summary text, or null after recording the failure.
     */
    private suspend fun requestSection(
        runtime: FoldRuntime,
        owned: List<SummarySections.SourceMessage>,
        context: List<SummarySections.SourceMessage>
    ): String? {
        fun role(m: SummarySections.SourceMessage) = if (m.isBot) "AI" else "User"
        val body = SummarizerPrompts.sectionRequestBody(
            runtime.prompt,
            context.filter { it.text.isNotBlank() }.map { role(it) to it.text },
            owned.filter { it.text.isNotBlank() }.map { role(it) to it.text }
        )
        val requestSize = SummarizerRequestSize.capture(
            body, owned.filter { it.text.isNotBlank() }.map { it.text }
        )
        val evidence = SummarizerDiagnostics.RequestEvidence()
        val privateValues = (owned + context).map { it.text } + listOf(body, runtime.endpoint.apiKey)
        val choice = try {
            withContext(Dispatchers.IO) {
                withSummarizerUsage(
                    runtime.prefs, runtime.endpoint, runtime.model, runtime.requestedProvider,
                    UsageFunction.SUMMARIZING
                ) { attempt ->
                    val client = buildClient(runtime.endpoint, runtime.providerJson, evidence, attempt)
                    evidence.dispatched = true
                    client.chatCompletion(
                        ChatCompletionRequest(
                            model = ModelId(runtime.model),
                            messages = listOf(ChatMessage(role = ChatRole.User, content = body))
                        )
                    )
                }.choices.firstOrNull()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val classified = GenerationErrorClassifier.classify(e, evidence.snapshot())
            if (classified.code == org.teslasoft.assistant.util.GenErrorCode.C1) throw CancellationException("Request cancelled", e)
            val owner = SummarizerDiagnostics.owner(e, classified, evidence)
            recordFailure(
                runtime.prefs,
                if (owner == SummarizerDiagnostics.Owner.LOCAL) SummarizerErrorCategory.UNEXPECTED else SummarizerErrorClassifier.categorize(classified),
                runtime.endpoint.label,
                runtime.model,
                classified.httpStatus,
                SummarizerErrorDetail.readable(e),
                rawProviderError = e.message,
                rawResponseBody = evidence.body,
                failureOwner = owner,
                exception = e,
                providerEvidence = evidence.snapshot(),
                requestEndpoint = runtime.endpoint,
                requestedProvider = runtime.requestedProvider,
                requestSize = requestSize,
                privateValues = privateValues + evidence.privateResponseValues()
            )
            return null
        }
        val text = choice?.message?.content?.toString().orEmpty().trim()
        if (text.isBlank()) {
            recordFailure(
                runtime.prefs, SummarizerErrorCategory.RESPONSE_UNREADABLE,
                runtime.endpoint.label, runtime.model, null, null,
                failureOwner = SummarizerDiagnostics.Owner.EXTERNAL,
                providerEvidence = evidence.snapshot(),
                requestEndpoint = runtime.endpoint,
                requestedProvider = runtime.requestedProvider,
                requestSize = requestSize,
                privateValues = privateValues + evidence.privateResponseValues(),
                rawResponseBody = evidence.body
            )
            return null
        }
        return text
    }

    /** Resolve the configured Summary Model, routing, and the [promptKind]
     *  prompt once per cycle. */
    private fun resolveFoldRuntime(
        prefs: Preferences,
        promptKind: SummarizerPromptSets.Kind
    ): FoldRuntime? {
        val endpointId = prefs.getSummarizerEndpointId()
        val endpoint = if (endpointId.isBlank()) null else try {
            ApiEndpointPreferences.getApiEndpointPreferences(appContext)
                .getApiEndpoint(appContext, endpointId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            recordUnexpectedFailure(e, (operationState as? OperationState.Running)?.kind ?: OperationKind.SUMMARIZING,
                (operationState as? OperationState.Running)?.chatName.orEmpty())
            return null
        }
        // The Summary Model is an explicit selection. Endpoint changes clear
        // it to "Select"; never fall back to the profile's chat model behind
        // that UI.
        val model = prefs.getSummarizerModel()
        if (endpoint == null || endpoint.host.isBlank() || model.isBlank()) {
            recordFailure(
                prefs, SummarizerErrorCategory.MODEL_MISSING,
                endpoint?.label ?: appContext.getString(R.string.summarizer_unknown_profile),
                model.ifBlank { appContext.getString(R.string.summarizer_unknown_model) },
                httpStatus = null, detail = null
            )
            return null
        }

        val routingMode = prefs.getSummarizerRoutingType()
        val savedFavorite = FavoriteModelsPreferences.getPreferences(appContext)
            .getFavorite(model, endpointId)
        if (endpoint.isOpenRouterRouting() &&
            DedicatedModelRoutingPolicy.needsSetup(routingMode, savedFavorite)
        ) {
            recordFailure(
                prefs, SummarizerErrorCategory.MODEL_MISSING, endpoint.label, model,
                httpStatus = null,
                detail = appContext.getString(R.string.summarizer_routing_not_configured_detail)
            )
            return null
        }
        val requestFavorite = if (endpoint.isOpenRouterRouting()) {
            DedicatedModelRoutingPolicy.favoriteForRequest(
                model, endpointId, routingMode, savedFavorite
            )
        } else {
            null
        }
        val routingResolution = ProviderRoutingResolver.resolve(
            endpoint.isOpenRouterRouting(), requestFavorite
        )
        if (routingResolution.block != RoutingBlock.NONE) {
            recordFailure(
                prefs, SummarizerErrorCategory.MODEL_MISSING, endpoint.label, model,
                httpStatus = null,
                detail = appContext.getString(R.string.summarizer_routing_not_configured_detail)
            )
            return null
        }

        return FoldRuntime(
            prefs = prefs,
            endpoint = endpoint,
            model = model,
            providerJson = routingResolution.providerJson,
            requestedProvider = when (routingMode) {
                org.teslasoft.assistant.preferences.dto.FavoriteModelObject.ROUTING_ONLY -> savedFavorite?.selectedProvider.orEmpty().ifBlank { "Not Reported" }
                org.teslasoft.assistant.preferences.dto.FavoriteModelObject.ROUTING_PREFERRED -> savedFavorite?.providerOrder?.joinToString(", ").orEmpty().ifBlank { "Automatic" }
                else -> "Automatic"
            },
            prompt = SummarizerPrompts.render(
                SummarizerPromptSets.activeText(prefs, promptKind, chatIdProvider())
            )
        )
    }

    /** Fold one bounded batch without persisting it. */
    private suspend fun foldBatch(
        runtime: FoldRuntime,
        entries: List<Entry>,
        folded: Int,
        target: Int,
        summary: String,
        /** About how many tokens of messages one request carries: a request
         *  the service called too large is not tried again at that size in
         *  the same run. */
        tokenBudget: Int,
        configuredBatchTokens: Int
    ): FoldBatchResult {
        var budget = tokenBudget.coerceAtLeast(1)
        var end = CompactionBatches.batchEnd(entries, folded, target, budget)

        // §2.9: on a too-large rejection, send fewer whole exchanges and
        // retry, down to one exchange; a user message and its AI reply are
        // never split. Only then is the error stored.
        while (true) {
            val departing = entries.subList(folded, end)
                .map { Pair(if (it.isBot) "AI" else "User", it.text) }
                .filter { it.second.isNotBlank() }

            if (departing.isEmpty()) {
                return FoldBatchResult.Advanced("", end, budget)
            }

            val body = SummarizerPrompts.foldInRequestBody(
                runtime.prompt,
                summary,
                departing
            )
            val requestSize = SummarizerRequestSize.capture(
                body, departing.map { it.second }, summary, configuredBatchTokens, budget
            )
            val evidence = SummarizerDiagnostics.RequestEvidence()
            val privateValues = departing.map { it.second } + listOf(summary, body, runtime.endpoint.apiKey)
            val text: String
            try {
                text = withContext(Dispatchers.IO) {
                    val request = ChatCompletionRequest(
                        model = ModelId(runtime.model),
                        messages = listOf(ChatMessage(role = ChatRole.User, content = body))
                    )
                    withSummarizerUsage(
                        runtime.prefs, runtime.endpoint, runtime.model, runtime.requestedProvider,
                        UsageFunction.COMPACTING
                    ) { attempt ->
                        val client = buildClient(runtime.endpoint, runtime.providerJson, evidence, attempt)
                        evidence.dispatched = true
                        client.chatCompletion(request)
                    }.choices.firstOrNull()?.message?.content?.toString().orEmpty()
                }.trim()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val classified = GenerationErrorClassifier.classify(e, evidence.snapshot())
                if (classified.code == org.teslasoft.assistant.util.GenErrorCode.C1) throw CancellationException("Request cancelled", e)
                val owner = SummarizerDiagnostics.owner(e, classified, evidence)
                val category = if (owner == SummarizerDiagnostics.Owner.LOCAL) SummarizerErrorCategory.UNEXPECTED else SummarizerErrorClassifier.categorize(classified)
                if (category == SummarizerErrorCategory.REQUEST_TOO_LARGE &&
                    end > CompactionBatches.exchangeEnd(entries, folded, target)
                ) {
                    val (smallerEnd, smallerBudget) = CompactionBatches.shrink(entries, folded, end, target)
                    end = smallerEnd
                    budget = smallerBudget
                    continue
                }
                // Keep the real error beneath the entry's plain-language
                // message — the exception message and any distinct root cause,
                // as the chat's own error passage shows — but never the
                // multi-frame stack trace, which read like a crash dump (owner
                // ruling, Aug 31 2026). The full trace still lives in the app's
                // own error/crash logs, left untouched.
                val detail = SummarizerErrorDetail.readable(e)
                recordFailure(
                    runtime.prefs, category, runtime.endpoint.label, runtime.model,
                    classified.httpStatus, detail,
                    rawProviderError = e.message,
                    rawResponseBody = evidence.body,
                    failureOwner = owner,
                    exception = e,
                    providerEvidence = evidence.snapshot(),
                    requestEndpoint = runtime.endpoint,
                    requestedProvider = runtime.requestedProvider,
                    requestSize = requestSize,
                    privateValues = privateValues + evidence.privateResponseValues()
                )
                return FoldBatchResult.Failed
            }

            if (text.isBlank()) {
                recordFailure(
                    runtime.prefs,
                    SummarizerErrorCategory.RESPONSE_UNREADABLE,
                    runtime.endpoint.label,
                    runtime.model,
                    null,
                    null,
                    failureOwner = SummarizerDiagnostics.Owner.EXTERNAL,
                    providerEvidence = evidence.snapshot(),
                    requestEndpoint = runtime.endpoint,
                    requestedProvider = runtime.requestedProvider,
                    requestSize = requestSize,
                    privateValues = privateValues + evidence.privateResponseValues(),
                    rawResponseBody = evidence.body
                )
                return FoldBatchResult.Failed
            }

            return FoldBatchResult.Advanced(text, end, budget)
        }
    }

    private fun recordFailure(
        prefs: Preferences,
        category: SummarizerErrorCategory,
        profile: String,
        model: String,
        httpStatus: Int?,
        detail: String?,
        rawProviderError: String? = null,
        rawResponseBody: String? = null,
        failureOwner: SummarizerDiagnostics.Owner = if (category == SummarizerErrorCategory.MODEL_MISSING)
            SummarizerDiagnostics.Owner.CONFIGURATION else SummarizerDiagnostics.Owner.LOCAL,
        exception: Throwable? = null,
        providerEvidence: org.teslasoft.assistant.providers.ProviderDiagnosticSnapshot? = null,
        requestEndpoint: ApiEndpointObject? = null,
        requestedProvider: String? = null,
        privateValues: List<String> = emptyList(),
        diagnosticRecorded: Boolean = false,
        requestSize: SummarizerRequestSize? = null
    ) {
        if (failureOwner == SummarizerDiagnostics.Owner.CANCELLED) return
        lastFailureCategory = category
        lastFailureProviderError = if (failureOwner == SummarizerDiagnostics.Owner.EXTERNAL)
            SummarizerDetailSanitizer.sanitize(providerEvidence?.errorMessages?.joinToString("\n")
                ?.ifBlank { null } ?: rawProviderError, privateValues) else null
        val safeDetail = SummarizerDetailSanitizer.sanitize(lastFailureProviderError ?: detail, privateValues)
        val responseDetail = if (httpStatus != null) {
            "HTTP status: $httpStatus" + (safeDetail?.let { "\n$it" } ?: "")
        } else {
            safeDetail
        }
        val decorated = listOfNotNull(responseDetail, requestSize?.format()).joinToString("\n")
            .ifBlank { null }
        // Owner ruling (Oct 4 2026, replacing July 29): a failure the AI
        // service caused goes to the Provider Failure Log with what was being
        // done (Summarizing or Compacting); any other failure goes to the
        // Error Log.
        val running = operationState as? OperationState.Running
        if (running != null && !diagnosticRecorded) {
            recordAppLogEntry(
                prefs = prefs,
                state = running,
                category = category,
                model = model,
                failureOwner = failureOwner,
                exception = exception,
                providerEvidence = providerEvidence?.let {
                    it.copy(outerHttpStatus = it.outerHttpStatus ?: httpStatus)
                },
                requestEndpoint = requestEndpoint,
                requestedProvider = requestedProvider,
                privateValues = privateValues,
                rawProviderError = rawProviderError,
                technicalDetail = responseDetail,
                rawResponseBody = rawResponseBody,
                requestSize = requestSize
            )
        }

        var newEpisode = false
        try {
            val current = SummarizerErrorLog.fromJson(prefs.getSummarizerErrors())
            val result = SummarizerErrorLog.record(
                current, prefs.getSummarizerEpisode(), category,
                System.currentTimeMillis(), profile, model, decorated
            )
            newEpisode = result.newEpisode
            prefs.setSummarizerErrors(SummarizerErrorLog.toJson(result.entries))
            prefs.setSummarizerEpisode(category.name)
            prefs.setSummarizerErrorsUnseen(true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            prefs.reportSummarizerStorageFailure("Summarizer/Compact: persist error status", e)
        }

        if (newEpisode) {
            listener?.onSummarizerErrorEpisode()
        }
        notifyStateChanged()
    }

    private fun recordAppLogEntry(
        prefs: Preferences,
        state: OperationState.Running,
        category: SummarizerErrorCategory,
        model: String,
        failureOwner: SummarizerDiagnostics.Owner,
        exception: Throwable?,
        providerEvidence: org.teslasoft.assistant.providers.ProviderDiagnosticSnapshot?,
        requestEndpoint: ApiEndpointObject?,
        requestedProvider: String?,
        privateValues: List<String>,
        rawProviderError: String?,
        technicalDetail: String?,
        rawResponseBody: String? = null,
        requestSize: SummarizerRequestSize? = null
    ) {
        val function = if (state.kind == OperationKind.COMPACTING) "Compacting" else "Summarizing"
        SummarizerDiagnostics.record(
            failureOwner,
            logProviders = failureOwner != SummarizerDiagnostics.Owner.EXTERNAL || prefs.getLogChatFailures(),
            local = {
                // Storage APIs record the original exception at the failure site,
                // including when used outside this controller. Do not duplicate it.
                if (category != SummarizerErrorCategory.SAVE_FAILED || prefs.summarizerStorageFailure == null) {
                val error = exception ?: IllegalStateException(technicalDetail ?: "Local $function failure: $category")
                org.teslasoft.assistant.preferences.Logger.logAsync(appContext, "crash", function, "error",
                    listOfNotNull(SummarizerDiagnostics.localDetail(function, error, privateValues),
                        requestSize?.format()).joinToString("\n"))
                }
            },
            external = {
                val endpoint = requestEndpoint
                val requested = requestedProvider
                val message = buildString {
                    append(SummarizerDetailSanitizer.sanitize(providerEvidence?.errorMessages?.joinToString("\n")
                        ?.ifBlank { null } ?: rawProviderError ?: technicalDetail, privateValues) ?: "Provider returned no usable completion")
                    // Strip completion and request payload fields before keeping wire evidence.
                    SummarizerDetailSanitizer.sanitize(rawResponseBody, privateValues)?.let { append("\nResponse evidence (").append(rawResponseBody?.length ?: 0).append(" characters): ").append(it) }
                    requestSize?.let { append("\n").append(it.format()) }
                }
                scope.launch(Dispatchers.IO) {
                    org.teslasoft.assistant.preferences.Logger.logProviderFailure(
                        appContext, SummarizerDetailSanitizer.sanitize(endpoint?.label, privateValues) ?: "Not Reported",
                        SummarizerDetailSanitizer.sanitize(providerEvidence?.actualServingProvider, privateValues) ?: "Not Reported",
                        SummarizerDetailSanitizer.sanitize(model, privateValues) ?: "Not Reported", function, message,
                        requestedRoutedProvider = SummarizerDetailSanitizer.sanitize(requested, privateValues),
                        outerHttpStatus = providerEvidence?.outerHttpStatus,
                        embeddedProviderStatus = providerEvidence?.embeddedHttpStatus,
                        providerCode = SummarizerDetailSanitizer.sanitize(providerEvidence?.providerCode, privateValues),
                        providerType = SummarizerDetailSanitizer.sanitize(providerEvidence?.providerType, privateValues),
                        providerErrorType = SummarizerDetailSanitizer.sanitize(providerEvidence?.providerErrorType, privateValues),
                        contentFilterSide = providerEvidence?.contentFilterSide?.wire,
                        attemptId = providerEvidence?.attemptId
                    )
                }
            }
        )
    }

    private fun recordStorageFailure(prefs: Preferences, chatName: String, detail: String) {
        if (operationState !is OperationState.Running)
            setOperationState(OperationState.Running(OperationKind.SUMMARIZING, chatName, 0, 0))
        recordFailure(prefs, SummarizerErrorCategory.SAVE_FAILED, "Not Reported",
            prefs.getSummarizerModel(), null, "$detail Technical details were recorded in the Error Log.")
    }

    private fun recordUnexpectedFailure(error: Exception, kind: OperationKind, chatName: String) {
        val function = if (kind == OperationKind.COMPACTING) "Compacting" else "Summarizing"
        org.teslasoft.assistant.preferences.Logger.logAsync(appContext, "crash", function, "error",
            SummarizerDiagnostics.localDetail(function, error))
        lastFailureCategory = SummarizerErrorCategory.UNEXPECTED
        if (operationState !is OperationState.Running)
            setOperationState(OperationState.Running(kind, chatName, 0, 0))
        try {
            val prefs = Preferences.getPreferences(appContext, chatIdProvider())
            recordFailure(prefs, SummarizerErrorCategory.UNEXPECTED, "Not Reported",
                prefs.getSummarizerModel(), null, "Technical details were recorded in the Error Log.",
                exception = error, diagnosticRecorded = true)
        } catch (statusError: Exception) {
            if (statusError is CancellationException) throw statusError
            // A broken local store must not erase the original diagnostic or
            // leave the operation falsely reported as successful.
            org.teslasoft.assistant.preferences.Logger.logAsync(appContext, "crash", function, "error",
                SummarizerDiagnostics.localDetail("$function: record failure status", statusError))
        }
    }

    private fun notifyStateChanged() {
        listener?.onSummarizerStateChanged()
    }

    private fun setOperationState(state: OperationState) {
        terminalClearJob?.cancel()
        val previous = operationState
        operationState = state
        if (state is OperationState.Running) {
            if (previous !is OperationState.Running) {
                org.teslasoft.assistant.service.SummarizerForegroundService.begin(
                    appContext,
                    chatIdProvider(),
                    state.chatName,
                    state.kind
                )
            }
            if (state.kind == OperationKind.COMPACTING) {
                org.teslasoft.assistant.service.SummarizerForegroundService.progress(
                    appContext,
                    chatIdProvider(),
                    state.percent
                )
            }
        } else if (previous is OperationState.Running) {
            org.teslasoft.assistant.service.SummarizerForegroundService.end(
                appContext,
                chatIdProvider()
            )
        }
        listener?.onSummarizerOperationChanged(state)
        if (state is OperationState.Succeeded || state is OperationState.Cancelled) {
            terminalClearJob = scope.launch {
                delay(if (state is OperationState.Succeeded) 3500L else 250L)
                if (operationState == state) setOperationState(OperationState.Idle)
            }
        }
    }

    /**
     * Runs one Summarizer or Compact request and logs what it cost to the
     * chat's usage log under summarization. Counts and charges come only from
     * the provider's response; a failed request is logged only when the
     * provider reported usage.
     */
    private suspend fun <T> withSummarizerUsage(
        prefs: Preferences,
        endpoint: ApiEndpointObject,
        model: String,
        requestedProvider: String,
        function: UsageFunction,
        block: suspend (ProviderUsageAttempt) -> T
    ): T = coroutineScope {
        val attempt = ProviderUsageAttempt(
            requestedModel = model,
            fallbackProvider = requestedProvider.trim()
                .ifBlank { TokenUsageAccounting.PROVIDER_NOT_REPORTED },
            apiEndpoint = endpoint.host
        )
        // The response observer runs beside the client; wait for it before
        // reading the attempt.
        attempt.beginObservation()
        val pricing = async(Dispatchers.IO) { TokenPricingCatalogClient.load(endpoint, model) }
        var succeeded = false
        try {
            val result = block(attempt)
            succeeded = true
            result
        } finally {
            withContext(NonCancellable) {
                val record = AuxiliaryUsage.record(attempt, pricing, succeeded) { reportedModel ->
                    TokenPricingCatalogClient.load(endpoint, reportedModel)
                }
                if (record != null) {
                    withContext(Dispatchers.IO) {
                        UsageLogStore.append(
                            prefs,
                            listOf(UsageLog.entry(UsageCategory.SUMMARIZING, null, record, function = function))
                        )
                    }
                }
            }
        }
    }

    /**
     * Same auth handling as the chat funnel and the Memory Assistant, but
     * with THIS endpoint's own Connection Timeout and Response Time — the
     * error wording tells the user to raise exactly those values (§2.3/2.4).
     * No client-level auto-retry: retry happens on the next eligible cycle,
     * never as a rapid background loop (§3).
     */
    private fun buildClient(
        endpoint: ApiEndpointObject,
        providerRouting: com.google.gson.JsonObject?,
        // Diagnostic capture (owner-approved, Aug 31 2026): when present, the
        // exact raw response body the provider returned for this call is stored
        // here, so a failure can log what the AI service actually sent back —
        // e.g. an error notice returned in place of a completion.
        rawResponseSink: SummarizerDiagnostics.RequestEvidence? = null,
        // Usage capture: the provider's reported counts and charge for this
        // call, read from the same response body.
        usageAttempt: ProviderUsageAttempt? = null
    ): OpenAI {
        val isBearerAuth = endpoint.authType == ApiEndpointObject.AUTH_BEARER
        val extraHeaders: Map<String, String> = when (endpoint.authType) {
            ApiEndpointObject.AUTH_X_API_KEY -> mapOf("x-api-key" to endpoint.apiKey)
            ApiEndpointObject.AUTH_API_KEY -> mapOf("api-key" to endpoint.apiKey)
            else -> emptyMap()
        }
        val connectSeconds = ApiEndpointObject
            .coerceConnectTimeoutSeconds(endpoint.connectTimeoutSeconds)
        val responseSeconds = ApiEndpointObject
            .coerceResponseTimeoutSeconds(endpoint.responseTimeoutSeconds)
        return OpenAI(
            OpenAIConfig(
                token = if (isBearerAuth) endpoint.apiKey else "",
                logging = LoggingConfig(LogLevel.None, com.aallam.openai.api.logging.Logger.Simple),
                timeout = Timeout(
                    connect = connectSeconds.seconds,
                    socket = responseSeconds.seconds
                ),
                organization = null,
                headers = extraHeaders,
                host = OpenAIHost(composeChatHost(endpoint.host, endpoint.chatEndpoint)),
                proxy = null,
                retry = RetryStrategy(maxRetries = 0),
                httpClientConfig = {
                    if (rawResponseSink != null || usageAttempt != null) {
                        install(ResponseObserver) {
                            onResponse { response ->
                                try {
                                    val body = response.bodyAsText()
                                    rawResponseSink?.status = response.status.value
                                    rawResponseSink?.body = body
                                    if (usageAttempt != null) {
                                        if (response.status.value in 200..299) {
                                            ReportedProviderParser.observeCompletedBody(body)
                                                ?.let(usageAttempt::noteRawObservation)
                                        } else {
                                            usageAttempt.noteHttpResponse(response.status.value, body)
                                        }
                                    }
                                } finally {
                                    usageAttempt?.finishObservation()
                                }
                            }
                        }
                    }
                    if (endpoint.isOpenRouterRouting() && providerRouting != null) {
                        // Each fold-in call and size-split retry is built through
                        // this client, so the selected Summarizer routing object
                        // is attached to every outgoing request.
                        install(createClientPlugin("SummarizerProviderRouting") {
                            on(Send) { request ->
                                val content = request.body as? TextContent
                                if (content?.contentType?.match(ContentType.Application.Json) == true) {
                                    val augmented = ProviderRoutingSerializer.augmentBody(
                                        content.text, providerRouting
                                    )
                                    request.setBody(
                                        TextContent(
                                            augmented,
                                            content.contentType ?: ContentType.Application.Json
                                        )
                                    )
                                }
                                proceed(request)
                            }
                        })
                    }
                }
            )
        )
    }

    /** Mirrors ChatActivity.composeChatHost: honour a custom chat-completions
     *  path when the endpoint profile carries one. */
    private fun composeChatHost(rawBase: String?, rawEndpoint: String?): String {
        var base = (rawBase ?: "").trim()
        if (base.isBlank()) return base
        if (!base.endsWith("/")) base += "/"
        val endpoint = (rawEndpoint ?: ApiEndpointObject.DEFAULT_CHAT_ENDPOINT).trim().trimStart('/')
        val marker = "chat/completions"
        val full = base + endpoint
        return if (full.endsWith(marker)) full.removeSuffix(marker) else base
    }
}
