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
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.setBody
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
        ) : OperationState
        data class Succeeded(val kind: OperationKind, val chatName: String) : OperationState
        data class Failed(
            val kind: OperationKind,
            val chatName: String,
            val category: SummarizerErrorCategory
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
    data class Entry(val isBot: Boolean, val text: String)

    data class Snapshot(val entries: List<Entry>, val window: Int)

    private data class FoldRuntime(
        val prefs: Preferences,
        val endpoint: ApiEndpointObject,
        val model: String,
        val providerJson: com.google.gson.JsonObject?,
        val prompt: String,
        val lengthWords: Int
    )

    private sealed interface FoldBatchResult {
        data class Advanced(
            val summary: String,
            val foldedCount: Int,
            val overLength: Boolean
        ) : FoldBatchResult

        data object Failed : FoldBatchResult
    }

    var listener: Listener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var manualCompactionRunning = false
    @Volatile private var operationState: OperationState = OperationState.Idle
    private var lastFailureCategory: SummarizerErrorCategory? = null
    private var terminalClearJob: Job? = null

    companion object {
        /** Internal fold-in batch size (decision 15) — not a user setting. */
        const val BATCH_SIZE = 10

        /** Response-token budget for a summary call: roomy enough that a
         *  summary near the word limit is never cut off mid-sentence. */
        fun responseTokenBudget(lengthWords: Int): Int =
            (lengthWords * 3).coerceIn(300, 4096)

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

        /**
         * The prompt text actually used for fold-ins: the selected slot,
         * falling back per decision 7 (most recently used slot with text,
         * else slot one's shipped prompt) so a fold-in can never run on
         * empty instructions even if the settings-screen guard was bypassed.
         */
        fun effectivePrompt(prefs: Preferences): String {
            fun slotText(slot: Int): String =
                prefs.getSummarizerSlotPrompt(slot).ifBlank { SummarizerPrompts.shippedPrompt(slot) }

            val selected = slotText(prefs.getSummarizerSelectedSlot())
            if (selected.isNotBlank()) return selected

            val recency = prefs.getSummarizerSlotRecency()
                .split(",").mapNotNull { it.trim().toIntOrNull() }
            for (slot in recency) {
                val text = slotText(slot)
                if (text.isNotBlank()) return text
            }
            return SummarizerPrompts.STORYTELLER
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
            val initialSnapshot = snapshotProvider()
            val initialPrefs = chatIdProvider().takeIf { it.isNotBlank() }?.let {
                Preferences.getPreferences(appContext, it)
            }
            val initialSummary = initialPrefs?.getSummarizerSummary().orEmpty()
            val initialFolded = initialPrefs?.getSummarizerFoldedCount() ?: 0
            val initialOverLength = initialPrefs?.getSummarizerOverLength() == true
            val initialEpisode = initialPrefs?.getSummarizerEpisode().orEmpty()
            val initialKind = initialPrefs?.getCondensedConversationKind().orEmpty()
            try {
                while (true) {
                    val advanced = foldOneBatch(force, chatName, snapshotProvider)
                    if (!advanced) break
                    advancedAny = true
                }
            } catch (_: CancellationException) {
                // Deliberate cancellation — not a Summarizer Error (§4).
                val running = operationState as? OperationState.Running
                if (running?.requestedMessages?.let { it >= 21 } == true &&
                    initialPrefs?.getSavePartialCompactionOnCancel() != true
                ) {
                    withContext(NonCancellable) {
                        initialPrefs?.restoreSummarizerState(
                            initialSummary,
                            initialFolded.coerceAtMost(initialSnapshot?.entries?.size ?: initialFolded),
                            initialOverLength,
                            initialEpisode,
                            initialKind
                        )
                    }
                    notifyStateChanged()
                }
                setOperationState(
                    OperationState.Cancelled(
                        OperationKind.SUMMARIZING,
                        chatName,
                        (operationState as? OperationState.Running)?.successfulMessages ?: 0
                    )
                )
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
        savePartialOnCancel: Boolean,
        stillCurrent: () -> Boolean,
        onFinished: (Boolean) -> Unit
    ) {
        if (manualCompactionRunning) return
        cancel()
        manualCompactionRunning = true
        lastFailureCategory = null
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
                    savePartialOnCancel,
                    stillCurrent
                )
            } catch (_: CancellationException) {
                // compactSnapshot owns the optional partial commit.
            } finally {
                manualCompactionRunning = false
                if (operationState is OperationState.Running) {
                    setOperationState(
                        if (committed) {
                            OperationState.Succeeded(OperationKind.COMPACTING, chatName)
                        } else {
                            OperationState.Failed(
                                OperationKind.COMPACTING,
                                chatName,
                                lastFailureCategory ?: SummarizerErrorCategory.UNEXPECTED
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
     * summarizer is not configured or the call fails. Deliberately silent: an
     * image summary is a token-saving convenience, so a failure never writes a
     * Summarizer Error, never interrupts chat, and simply leaves the caller to
     * fall back to the full prompt and try again on a later turn.
     */
    suspend fun summarizeImagePrompt(imagePrompt: String): String? {
        if (imagePrompt.isBlank()) return null
        val prefs = Preferences.getPreferences(appContext, "")

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

        val instruction = prefs.getImageSummaryPrompt().ifBlank { SummarizerPrompts.IMAGE_SUMMARY }
        val body = SummarizerPrompts.imageSummaryRequestBody(instruction, imagePrompt)
        return try {
            withContext(Dispatchers.IO) {
                val client = buildClient(endpoint, routingResolution.providerJson)
                val request = ChatCompletionRequest(
                    model = ModelId(model),
                    maxTokens = 200,
                    messages = listOf(ChatMessage(role = ChatRole.User, content = body))
                )
                client.chatCompletion(request)
                    .choices.firstOrNull()?.message?.content?.toString().orEmpty()
            }.trim().ifBlank { null }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun compactSnapshot(
        snapshot: Snapshot,
        chatName: String,
        savePartialOnCancel: Boolean,
        stillCurrent: () -> Boolean
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
        val startingSummary = prefs.getSummarizerSummary()
        val startingFolded = prefs.getSummarizerFoldedCount()
        val startingOverLength = prefs.getSummarizerOverLength()
        val startingVersion = prefs.getSummarizerProjectionVersion()
        val operationStartFolded = startingFolded.coerceAtMost(target)

        setOperationState(
            OperationState.Running(
                OperationKind.COMPACTING,
                chatName,
                (target - operationStartFolded).coerceAtLeast(0),
                0
            )
        )

        var summary = startingSummary
        var folded = operationStartFolded
        var overLength = startingOverLength

        try {
            if (folded < target) {
                val runtime = resolveFoldRuntime(prefs) ?: return false
                while (folded < target) {
                    when (val result = foldBatch(
                        runtime = runtime,
                        entries = snapshot.entries,
                        folded = folded,
                        pending = target - folded,
                        summary = summary,
                        priorOverLength = overLength
                    )) {
                        is FoldBatchResult.Advanced -> {
                            summary = result.summary
                            folded = result.foldedCount
                            overLength = result.overLength
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
            val completedThisRun = folded - startingFolded.coerceAtMost(target)
            val saved = if (savePartialOnCancel && completedThisRun > 0 && stillCurrent()) {
                withContext(NonCancellable) {
                    prefs.commitManualCompaction(summary, folded, overLength, folded)
                }
            } else false
            setOperationState(
                OperationState.Cancelled(
                    OperationKind.COMPACTING,
                    chatName,
                    if (saved) completedThisRun else 0
                )
            )
            if (saved) notifyStateChanged()
            throw cancelled
        }

        // Do not overwrite a summary edit, automatic fold-in, or changed
        // canonical prefix that landed while the manual API calls were in
        // flight. Appended messages are intentionally outside this checkpoint.
        if (chatIdProvider() != chatId ||
            !stillCurrent() ||
            prefs.getSummarizerSummary() != startingSummary ||
            prefs.getSummarizerFoldedCount() != startingFolded ||
            prefs.getSummarizerOverLength() != startingOverLength ||
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

        if (!prefs.commitManualCompaction(summary, target, overLength, target)) {
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
        notifyStateChanged()
        return true
    }

    /** @return true when a batch was folded and committed (keep looping). */
    private suspend fun foldOneBatch(
        force: Boolean,
        chatName: String,
        snapshotProvider: () -> Snapshot?
    ): Boolean {
        val snapshot = snapshotProvider() ?: return false
        val chatId = chatIdProvider()
        if (chatId.isBlank()) return false
        val prefs = Preferences.getPreferences(appContext, chatId)
        if (!prefs.getChatUseSummarizer()) return false
        // Phase 6.2: never fold onto or advance from a rolling summary that
        // may already contain old inline Include payload material. A failed
        // compatibility commit leaves canonical history intact and simply
        // postpones this cycle.
        if (!prefs.ensureSummarizerProjectionCompatibility()) return false

        val entries = snapshot.entries
        val folded = prefs.getSummarizerFoldedCount().coerceAtMost(entries.size)
        val windowEdge = (entries.size - snapshot.window.coerceAtLeast(1)).coerceAtLeast(0)
        val pending = windowEdge - folded
        if (pending <= 0) return false
        if (!force && pending < BATCH_SIZE) return false

        if (operationState !is OperationState.Running) {
            lastFailureCategory = null
            setOperationState(
                OperationState.Running(OperationKind.SUMMARIZING, chatName, pending, 0)
            )
        }

        val runtime = resolveFoldRuntime(prefs) ?: return false
        val result = foldBatch(
            runtime = runtime,
            entries = entries,
            folded = folded,
            pending = pending,
            summary = prefs.getSummarizerSummary(),
            priorOverLength = prefs.getSummarizerOverLength()
        )
        if (result !is FoldBatchResult.Advanced) return false
        if (!prefs.commitSummarizerFoldIn(
                result.summary,
                result.foldedCount,
                result.overLength
            )
        ) {
            recordFailure(
                prefs,
                SummarizerErrorCategory.SAVE_FAILED,
                runtime.endpoint.label,
                runtime.model,
                null,
                null
            )
            return false
        }
        notifyStateChanged()
        val running = operationState as? OperationState.Running
        if (running?.kind == OperationKind.SUMMARIZING) {
            setOperationState(
                running.copy(successfulMessages = running.successfulMessages +
                    (result.foldedCount - folded))
            )
        }
        return true
    }

    /** Resolve the configured Summary Model and routing once per cycle. */
    private fun resolveFoldRuntime(prefs: Preferences): FoldRuntime? {
        val lengthWords = prefs.getSummarizerLength()
        val endpointId = prefs.getSummarizerEndpointId()
        val endpoint = if (endpointId.isBlank()) null else try {
            ApiEndpointPreferences.getApiEndpointPreferences(appContext)
                .getApiEndpoint(appContext, endpointId)
        } catch (_: Exception) {
            null
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
            prompt = SummarizerPrompts.render(effectivePrompt(prefs), lengthWords),
            lengthWords = lengthWords
        )
    }

    /** Fold one bounded batch without persisting it. */
    private suspend fun foldBatch(
        runtime: FoldRuntime,
        entries: List<Entry>,
        folded: Int,
        pending: Int,
        summary: String,
        priorOverLength: Boolean
    ): FoldBatchResult {
        var batch = pending.coerceAtMost(BATCH_SIZE)

        // §2.9: on a too-large rejection, split the batch and retry, down to
        // one message; only then is the error stored.
        while (true) {
            val departing = entries.subList(folded, folded + batch)
                .map { Pair(if (it.isBot) "Assistant" else "User", it.text) }
                .filter { it.second.isNotBlank() }

            if (departing.isEmpty()) {
                return FoldBatchResult.Advanced(
                    summary,
                    folded + batch,
                    priorOverLength
                )
            }

            val body = SummarizerPrompts.foldInRequestBody(
                runtime.prompt,
                summary,
                departing
            )
            val text: String
            try {
                text = withContext(Dispatchers.IO) {
                    val client = buildClient(runtime.endpoint, runtime.providerJson)
                    val request = ChatCompletionRequest(
                        model = ModelId(runtime.model),
                        maxTokens = responseTokenBudget(runtime.lengthWords),
                        messages = listOf(ChatMessage(role = ChatRole.User, content = body))
                    )
                    client.chatCompletion(request)
                        .choices.firstOrNull()?.message?.content?.toString().orEmpty()
                }.trim()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val classified = GenerationErrorClassifier.classify(e)
                val category = SummarizerErrorClassifier.categorize(classified)
                if (category == SummarizerErrorCategory.REQUEST_TOO_LARGE && batch > 1) {
                    batch = (batch / 2).coerceAtLeast(1)
                    continue
                }
                val detail = if (category == SummarizerErrorCategory.UNEXPECTED) {
                    e::class.qualifiedName + ": " + (e.message ?: "") + "\n" + e.stackTraceToString()
                } else {
                    e.message
                }
                recordFailure(
                    runtime.prefs, category, runtime.endpoint.label, runtime.model,
                    classified.httpStatus, SummarizerDetailSanitizer.sanitize(detail),
                    rawProviderError = e.message
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
                    null
                )
                return FoldBatchResult.Failed
            }

            // Owner ruling: count the words ourselves, allow 10% over, and
            // beyond that save unchanged but flag for compression on the
            // next regular fold-in. Never truncated, never discarded, never
            // a separate corrective call.
            val overLength = SummarizerLengthPolicy.isOverLength(
                text,
                runtime.lengthWords
            )
            return FoldBatchResult.Advanced(text, folded + batch, overLength)
        }
    }

    private fun recordFailure(
        prefs: Preferences,
        category: SummarizerErrorCategory,
        profile: String,
        model: String,
        httpStatus: Int?,
        detail: String?,
        rawProviderError: String? = null
    ) {
        lastFailureCategory = category
        val decorated = if (httpStatus != null) {
            "HTTP status: $httpStatus" + (detail?.let { "\n$it" } ?: "")
        } else {
            detail
        }
        val current = SummarizerErrorLog.fromJson(prefs.getSummarizerErrors())
        val result = SummarizerErrorLog.record(
            current, prefs.getSummarizerEpisode(), category,
            System.currentTimeMillis(), profile, model, decorated
        )
        prefs.setSummarizerErrors(SummarizerErrorLog.toJson(result.entries))
        prefs.setSummarizerEpisode(category.name)

        val running = operationState as? OperationState.Running
        if (running?.kind == OperationKind.COMPACTING) {
            recordCompactionDiagnostics(
                prefs = prefs,
                state = running,
                category = category,
                model = model,
                rawProviderError = rawProviderError,
                technicalDetail = decorated
            )
        }

        // Owner ruling (July 29 2026): summarizer failures are recorded ONLY
        // in the per-chat Summarizer Errors log. No app-wide Error Log entry
        // is written for this feature.

        if (result.newEpisode) {
            listener?.onSummarizerErrorEpisode()
        }
        notifyStateChanged()
    }

    private fun recordCompactionDiagnostics(
        prefs: Preferences,
        state: OperationState.Running,
        category: SummarizerErrorCategory,
        model: String,
        rawProviderError: String?,
        technicalDetail: String?
    ) {
        val endpointId = prefs.getSummarizerEndpointId()
        val endpoint = try {
            ApiEndpointPreferences.getApiEndpointPreferences(appContext)
                .getApiEndpoint(appContext, endpointId)
        } catch (_: Exception) { null }
        val favorite = FavoriteModelsPreferences.getPreferences(appContext)
            .getFavorite(model, endpointId)
        val provider = when (prefs.getSummarizerRoutingType()) {
            org.teslasoft.assistant.preferences.dto.FavoriteModelObject.ROUTING_ONLY ->
                favorite?.selectedProvider.orEmpty().ifBlank { "Not Reported" }
            org.teslasoft.assistant.preferences.dto.FavoriteModelObject.ROUTING_PREFERRED ->
                favorite?.providerOrder?.joinToString(", ").orEmpty().ifBlank { "Automatic" }
            else -> "Automatic"
        }
        val outcome = if (state.successfulMessages > 0) "Partially Failed" else "Failed Completely"
        val explanation = when (category) {
            SummarizerErrorCategory.RESPONSE_TIMEOUT,
            SummarizerErrorCategory.CONNECT_TIMEOUT -> "The configured timeout expired before a usable compacted summary was saved."
            SummarizerErrorCategory.SERVICE_UNREACHABLE -> "The app could not reach the configured compaction endpoint."
            SummarizerErrorCategory.REQUEST_TOO_LARGE -> "The compaction request exceeded a provider or model input limit."
            SummarizerErrorCategory.SAVE_FAILED -> "The model returned usable work, but the app could not save the compacted state."
            else -> "The compaction operation stopped before the requested range could be committed."
        }
        val body = buildString {
            append("Function: Compacting\n")
            append("Conversation: ").append(state.chatName.ifBlank { "Untitled chat" }).append('\n')
            append("Summarizer Model: ").append(model).append('\n')
            append("Summarizer Endpoint: ")
                .append(endpoint?.label.orEmpty().ifBlank { "Not Reported" })
                .append(" (").append(endpoint?.host.orEmpty().ifBlank { "Not Reported" }).append(")\n")
            append("Summarizer Provider: ").append(provider).append('\n')
            append("Messages Requested: ").append(state.requestedMessages).append('\n')
            append("Outcome: ").append(outcome).append('\n')
            if (state.successfulMessages > 0) {
                append("Messages Successfully Processed: ").append(state.successfulMessages).append('\n')
            }
            rawProviderError?.takeIf { it.isNotBlank() }?.let {
                append("Provider Error: ").append(it).append('\n')
            }
            technicalDetail?.takeIf { it.isNotBlank() && it != rawProviderError }?.let {
                append("Technical Detail: ").append(it).append('\n')
            }
            append("Explanation: ").append(explanation)
        }
        org.teslasoft.assistant.preferences.Logger.logAsync(
            appContext, "crash", "Compaction", "error", body
        )
        if (rawProviderError != null && prefs.getLogChatFailures()) {
            scope.launch(Dispatchers.IO) {
                org.teslasoft.assistant.preferences.Logger.logProviderFailure(
                    appContext,
                    endpoint?.label.orEmpty().ifBlank { "Not Reported" },
                    provider,
                    model,
                    "Compacting",
                    rawProviderError
                )
            }
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
     * Same auth handling as the chat funnel and the Memory Assistant, but
     * with THIS endpoint's own Connection Timeout and Response Time — the
     * error wording tells the user to raise exactly those values (§2.3/2.4).
     * No client-level auto-retry: retry happens on the next eligible cycle,
     * never as a rapid background loop (§3).
     */
    private fun buildClient(
        endpoint: ApiEndpointObject,
        providerRouting: com.google.gson.JsonObject?
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
