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

package org.teslasoft.assistant.preferences.memory.archivist

import android.content.Context
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.logging.Logger
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.RetryStrategy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.DedicatedModelRouting
import org.teslasoft.assistant.preferences.FavoriteModelParameters
import org.teslasoft.assistant.preferences.ModelPickerScope
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.includes.IncludeTextPolicy
import org.teslasoft.assistant.preferences.memory.ArchivistRunRecord
import org.teslasoft.assistant.preferences.memory.CandidateResult
import org.teslasoft.assistant.preferences.memory.LorebookSuggestionRecord
import org.teslasoft.assistant.preferences.memory.MemoryCandidate
import org.teslasoft.assistant.preferences.memory.MemoryCandidateValidator
import org.teslasoft.assistant.preferences.memory.MemoryLog
import org.teslasoft.assistant.preferences.memory.MemoryMatch
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.ModelRuleRecord
import org.teslasoft.assistant.preferences.memory.SCOPE_COMPANION
import org.teslasoft.assistant.preferences.memory.StagedAnalysisCandidate
import org.teslasoft.assistant.preferences.memory.RetrievalScope
import org.teslasoft.assistant.preferences.memory.librarian.Librarian
import org.teslasoft.assistant.preferences.damaged.DamagedChatLog
import org.teslasoft.assistant.preferences.damaged.DamagedChatRepair
import org.teslasoft.assistant.preferences.memory.ScanRangeRecord
import org.teslasoft.assistant.preferences.memory.ScanReport
import org.teslasoft.assistant.preferences.memory.scan.ChatScanSource
import org.teslasoft.assistant.preferences.memory.scan.ScanChatStatus
import org.teslasoft.assistant.preferences.memory.scan.ScanContext
import org.teslasoft.assistant.preferences.memory.scan.ScanSourceRow
import org.teslasoft.assistant.preferences.memory.scan.ScanCheckpoint
import org.teslasoft.assistant.preferences.memory.scan.ScanMessage
import org.teslasoft.assistant.preferences.memory.scan.ScanMessageScope
import org.teslasoft.assistant.preferences.memory.scan.ScanPosition
import org.teslasoft.assistant.preferences.memory.scan.ScanScopeResolution
import org.teslasoft.assistant.preferences.memory.scan.ScanSourceMigration
import org.teslasoft.assistant.preferences.memory.scan.ScanSourceRanges
import org.teslasoft.assistant.preferences.memory.scan.ScanSourceRows
import org.teslasoft.assistant.R
import org.teslasoft.assistant.providers.DedicatedModelRoutingPolicy
import org.teslasoft.assistant.providers.ModelParameterRejection
import org.teslasoft.assistant.providers.ModelRequestParameters
import org.teslasoft.assistant.providers.ModelSupportedParametersStore
import org.teslasoft.assistant.providers.ProviderRoutingSerializer
import org.teslasoft.assistant.providers.ProviderRoutingResolver
import org.teslasoft.assistant.providers.RoutingBlock
import org.teslasoft.assistant.util.GenErrorResult
import org.teslasoft.assistant.util.GenerationErrorClassifier
import org.teslasoft.assistant.util.ProviderErrorInfo
import org.teslasoft.assistant.util.reachedServer
import io.ktor.client.plugins.observer.ResponseObserver
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import org.teslasoft.assistant.util.Hash
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * The Archivist run engine (Phase 6 backend; the Memory Scanner). Reads
 * conversations directly from the original chat storage (Stage 6A, "The
 * chat is the source"), asks the Archivist model what is worth
 * remembering, and files EVERY result as a draft — memory drafts land in the
 * existing Pending screen (`memories.status='draft'`, their one home, rules
 * §14), model-rule drafts in the Model rules Pending area (§11). Nothing is
 * ever auto-applied, nothing is written mid-conversation (runs are user-
 * triggered), and the companion/persona/mode/directive/protection surfaces
 * are untouchable — see `owner_approved_rules.md` + the July 8 2026 addendum.
 *
 * Card-placement proposals are deliberately ABSENT: that flow is not yet
 * designed with the owner (their message, July 8 2026). When it is, it plugs
 * in after parsing, before filing.
 *
 * This object is backend-only — it produces no user-visible text. The Memory
 * Assistant screen renders outcomes in the owner's approved words.
 */
object Archivist {

    /** One chat the run reads. Its text is read from the original chat
     *  storage section by section and never saved (Stage 6A). */
    data class Conversation(
        val chatId: String,
        val chatName: String
    )

    /** How an in-process stop ends the run (Stage 6A requirements, "Four
     *  ways a scan ends"). A process death is recovered at the next start
     *  as an interruption. */
    enum class StopKind {
        /** The user's Cancel: unfinished progress is discarded. */
        CANCEL,
        /** The user's Pause: everything is kept until the user resumes. */
        PAUSE,
        /** Android's time limit or another system stop: everything is kept
         *  and the scan resumes after the next chat reply. */
        INTERRUPT
    }

    private data class ParsedChunk(
        val memories: List<ArchivistResponseParser.DraftMemory> = emptyList(),
        val rules: List<ArchivistResponseParser.DraftRule> = emptyList(),
        val loreEntries: List<ArchivistResponseParser.DraftLoreEntry> = emptyList()
    )

    private data class PreparedMemories(
        val records: List<org.teslasoft.assistant.preferences.memory.MemoryRecord>,
        val relationshipHintsByMemoryId: Map<String, List<String>>,
        val duplicatesSkipped: Int,
        val sourcesByMemoryId: Map<String, List<org.teslasoft.assistant.preferences.memory.MemorySource>> =
            emptyMap()
    )

    private data class PreparedLore(
        val records: List<LorebookSuggestionRecord>,
        val duplicatesSkipped: Int
    )

    private data class ChatCommit(
        val chatId: String,
        val memoryIds: List<String>,
        val ruleIds: List<String>,
        val duplicatesSkipped: Int,
        val sourceRange: ScanSourceRanges.Range?
    )

    /** The chat changed in a part already read, more often than a scan
     *  should keep starting over; the chat is left for a later scan. */
    private class SourceKeepsChangingException :
        Exception("already-read messages kept changing during the scan")

    /** The chat's storage could not be read. [damaged] when the stored
     *  history itself is damaged rather than temporarily unavailable. */
    private class ChatSourceUnavailableException(val damaged: Boolean) :
        Exception(if (damaged) "chat storage is damaged" else "chat storage could not be read")

    data class RunOutcome(
        val runId: String?,
        val conversationsSelected: Int,
        val conversationsAnalyzed: Int,
        val memoriesFound: Int,
        val ruleDraftsFound: Int,
        /** Chats whose analysis failed this run; their unscanned part stays
         *  eligible for the next run. */
        val failedChatIds: List<String>,
        /** Display outcome per archivist_status_wording_spec.md: completed |
         *  full_failed | partial_failed | nothing | no_new | interrupted |
         *  not_configured — plus already_running (a start that lost the
         *  one-live-run gate; nothing was claimed, written, or recorded) and,
         *  since Stage 6A, paused (the run is suspended until Resume). */
        val outcome: String,
        /** Dominant failure category when (partially) failed — picks the
         *  on-screen reason sentence and the action button. */
        val failureReason: ArchivistFailure? = null,
        /** Candidates skipped because an identical memory already exists —
         *  distinguishes "found only memories that already exist" from
         *  "did not find anything new". */
        val duplicatesSkipped: Int = 0,
        val error: String? = null,
        /** Which analysis type produced this run (Step 1.7): "associative"
         *  (saved-memory drafts) or "lorebook" (lore book entry suggestions).
         *  The Memory Assistant uses it to show the matching result surface —
         *  memoriesFound counts whichever kind the run created. */
        val analysisType: String = "associative",
        /** Classified transport/provider result for a failed run (Aug 1 2026),
         *  reused from the chat funnel so the Memory Assistant can name the
         *  precise failure and render the same provider-detail block. Null when
         *  the run did not fail against a provider. */
        val genError: GenErrorResult? = null,
        /** The connection profile's name (API Provider line). */
        val apiProvider: String? = null,
        /** The upstream model service the provider reported (Model Service
         *  Provider line); null when none was reported. */
        val upstreamProvider: String? = null,
        /** The server's own error message, when captured. */
        val providerMessage: String? = null,
        /** The model the run used (Model line). */
        val model: String? = null,
        /** For a partial failure, how many failed conversations fell into each
         *  ArchivistFailureCategory — the screen uses a specific title when they
         *  all share one cause, or a mixed-cause breakdown otherwise. */
        val failureCategoryCounts: Map<String, Int> = emptyMap(),
        /** Chat scans run between this run's conversations that ended in a
         *  stop ("cancelled"), so the caller can refine their records the same
         *  way it refines this run's. */
        val stoppedChatScanRunIds: List<String> = emptyList()
    ) {
        val notConfigured: Boolean get() = outcome == "not_configured"
    }

    /**
     * Live progress for the Memory Scanner Tool's running state. The approved
     * surface shows a spinner and "Analyzing Conversations", then a
     * determinate bar and "X%" computed from [overallIndex]/[overallCount]
     * once the fixed total is known.
     */
    data class Progress(
        val batchIndex: Int,            // 1-based
        val batchCount: Int,
        val conversationInBatch: Int,   // 1-based, within the current batch
        val conversationsInBatch: Int,
        /** Whole-run position (1-based) and the fixed total the run sealed at
         *  its start — the basis for the approved percentage. 0 on legacy
         *  callers. */
        val overallIndex: Int = 0,
        val overallCount: Int = 0,
        /** Sections of the conversation in progress already finished, and how
         *  many it was split into (0 until it has been split). */
        val sectionsDone: Int = 0,
        val sectionsTotal: Int = 0
    ) {
        /**
         * The approved percentage, from work actually done. A run of one chat
         * counts finished sections of that chat (owner ruling, Nov 18 2026);
         * a batch counts finished conversations, as before. 0 until the
         * total is known.
         */
        fun percent(): Int {
            if (overallCount <= 0) return 0
            if (overallCount == 1 && sectionsTotal > 0) {
                return (sectionsDone * 100 / sectionsTotal).coerceIn(0, 100)
            }
            val completed = (overallIndex - 1).coerceAtLeast(0)
            return (completed * 100 / overallCount).coerceIn(0, 100)
        }
    }

    /**
     * The chats a run would scan now: chats in the chat list whose Archive
     * switch is on, that no run holds, with an eligible message after their
     * bookmark. Oldest unscanned message first. Reads the original chat
     * storage without saving anything.
     */
    fun eligibleConversations(context: Context): List<Conversation> {
        if (!MemoryStore.isProvisioned(context)) return emptyList()
        val store = MemoryStore.getInstance(context)
        val unmigrated = unmigratedChats(store)
        val damaged = DamagedChatLog.chatIds(context)
        val progress = store.allScanProgress()
        val out = ArrayList<Pair<Conversation, Long>>()
        for ((chatId, name) in liveChatNamesById(context)) {
            val p = progress[chatId]
            if (p?.claimRunId != null || chatId in unmigrated) continue
            val paused = try {
                Preferences.getPreferences(context, chatId).isChatExcludedFromMemory()
            } catch (_: Exception) {
                true
            }
            if (paused) continue
            val messages = ChatScanSource.peek(context, chatId)
            if (messages == null) {
                // A damaged chat is still offered to the scan, which runs the
                // existing repair on it and reports the result.
                if (chatId in damaged) out.add(Conversation(chatId, name) to Long.MAX_VALUE)
                continue
            }
            val index = ScanChatStatus.bookmarkIndex(p, messages)
            val first = (index + 1 until messages.size).firstOrNull { messages[it].eligible } ?: continue
            out.add(Conversation(chatId, name) to (messages[first].timeMillis ?: Long.MAX_VALUE))
        }
        return out.sortedBy { it.second }.map { it.first }
    }

    /** Chats whose earlier progress still waits in the legacy store because
     *  the migration could not read them yet; they are never scanned until
     *  they migrate, so their earlier progress is never lost. */
    private fun unmigratedChats(store: MemoryStore): Set<String> =
        if (store.scanSourceMigrationComplete()) emptySet() else store.legacyTranscriptChatIds()

    /** The Memory Assistant facts line "Total conversations since last run" —
     *  by owner decision this is the currently-eligible set (what the button
     *  would analyze next), NOT anything keyed to backups. */
    fun eligibleConversationCount(context: Context): Int = eligibleConversations(context).size

    /** In-process half of the one-live-run rule (counterplan §4(a)): the
     *  durable 'running' row guards across process death; this guards
     *  concurrent starts inside one process. A losing start returns outcome
     *  "already_running" and touches nothing. */
    private val liveRun = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True while a run is live in this process. */
    fun isRunLive(): Boolean = liveRun.get()

    /** Raw error-response body of the most recent failed request in the live
     *  run (Aug 1 2026), captured by the client's ResponseObserver so the
     *  failure surface can name the upstream provider and quote the server's
     *  own message — exactly the chat funnel's approach. One run at a time
     *  (the liveRun gate), reset before each request. */
    @Volatile
    private var capturedErrorBody: String? = null

    /**
     * Startup recovery entry point (counterplan §4(a)): first the transcript
     * migration (Stage 6A), then the reconcile, behind the same in-process
     * gate the runs use. A run that was live when the process ended keeps
     * its progress and waits to resume. Returns how many were found.
     */
    fun reconcileAtStartup(context: Context): Int {
        if (!MemoryStore.isProvisioned(context)) return 0
        ScanSourceMigration.runIfNeeded(context)
        if (!liveRun.compareAndSet(false, true)) return 0
        return try {
            val found = MemoryStore.getInstance(context).reconcileInterruptedAnalysisRuns()
            if (found > 0) {
                MemoryLog.logAlways(context, "Archivist", "warn",
                    "Found $found interrupted scans; kept their progress to resume.")
            }
            found
        } finally {
            liveRun.set(false)
        }
    }

    /** Analyze every currently-eligible conversation (the user may queue any
     *  number — owner answer 4; size batching happens inside). */
    suspend fun analyze(
        context: Context,
        analysisType: String,
        onProgress: (Progress) -> Unit,
        stopKind: () -> StopKind = { StopKind.INTERRUPT }
    ): RunOutcome = startRun(context, RUN_ANALYZE, analysisType, onProgress, stopKind) {
        eligibleConversations(context).map { it to false }
    }

    /** One chat's Scan for Memories request (owner design, Nov 18 2026). */
    data class ChatScanRequest(val chatId: String, val analysisType: String)

    /** Chat scans asked for while another run is live. That run takes them
     *  between conversations — it never stops a conversation part way — and
     *  then continues its own work. Anything still waiting when it ends is
     *  started by the analysis service. In memory only. */
    private val pendingChatScans = java.util.concurrent.ConcurrentLinkedQueue<ChatScanRequest>()

    fun requestChatScan(request: ChatScanRequest) {
        pendingChatScans.add(request)
    }

    fun pollChatScanRequest(): ChatScanRequest? = pendingChatScans.poll()

    fun clearChatScanRequests() = pendingChatScans.clear()

    /** True when [chatId] has an eligible message after its bookmark, or is
     *  still held by a run that has not finished it. False means Scan for
     *  Memories has nothing new to do for this chat. */
    fun chatHasUnscannedMessages(context: Context, chatId: String): Boolean {
        if (!MemoryStore.isProvisioned(context)) return false
        val store = MemoryStore.getInstance(context)
        if (chatId in unmigratedChats(store)) return false
        val progress = store.scanProgress(chatId)
        if (progress?.claimRunId != null) return true
        // A damaged chat is offered too: the scan runs the existing repair.
        val messages = ChatScanSource.peek(context, chatId)
            ?: return chatId in DamagedChatLog.chatIds(context)
        return ScanChatStatus.hasUnscanned(progress, messages)
    }

    /** Scan only [chatId]'s not-yet-scanned part, even while the chat's
     *  Archive switch is off (owner ruling: the user's own request decides).
     *  When the chat belongs to a paused or interrupted batch, only this chat
     *  is taken out of it and continues from its checkpoint; the batch stays
     *  suspended (owner decision, Stage 6A question 7). */
    suspend fun analyzeChat(
        context: Context,
        chatId: String,
        analysisType: String,
        onProgress: (Progress) -> Unit,
        stopKind: () -> StopKind = { StopKind.INTERRUPT }
    ): RunOutcome = startRun(context, RUN_CHAT, analysisType, onProgress, stopKind) {
        if (liveChatNamesById(context)[chatId] == null) emptyList()
        else listOf(Conversation(chatId, liveChatNamesById(context)[chatId] ?: chatId) to true)
    }

    /** True when the Memory Assistant has everything a run needs before it
     *  can start: an endpoint with a host, a model, and satisfiable provider
     *  routing. The same checks [run] makes, without logging. */
    fun isScannerReady(context: Context): Boolean = scannerSetup(context) is ScannerSetup.Ready

    /** Re-analyze a past run's conversations (the Rerun row action): reads
     *  again exactly the message ranges that run finished, for chats that
     *  still exist. Files any NEW findings as drafts; records a fresh run
     *  row. No bookmark moves and nothing is claimed. A rerun always uses
     *  the ORIGINAL run's stored analysis type (owner ruling, Step 1.7). */
    suspend fun rerun(
        context: Context,
        runId: String,
        analysisType: String,
        onProgress: (Progress) -> Unit,
        stopKind: () -> StopKind = { StopKind.INTERRUPT }
    ): RunOutcome {
        val store = MemoryStore.getInstance(context)
        val past = store.getArchivistRun(runId)
            ?: return RunOutcome(
                null, 0, 0, 0, 0, emptyList(),
                outcome = "full_failed", failureReason = ArchivistFailure.UNKNOWN,
                error = "run not found",
                analysisType = analysisType
            )
        return runWithSetup(context, past.analysisType) { ready ->
            val names = liveChatNamesById(context)
            val runRow = newRunRow(RUN_RERUN, past.analysisType)
            val ranges = ScanSourceRanges.decode(past.sourceRangesJson)
                .filter { names.containsKey(it.chatId) }
                .mapIndexed { ordinal, r ->
                    ScanRangeRecord(
                        rangeId = MemoryStore.newId("range-"),
                        runId = runRow.runId,
                        chatId = r.chatId,
                        ordinal = ordinal,
                        kind = ScanRangeRecord.KIND_RERUN,
                        startAnchors = r.startAnchors,
                        endAnchors = r.endAnchors
                    )
                }
            val opened = store.beginScanRun(runRow, ranges)
            executeRun(context, ready, runRow, opened, names, onProgress, stopKind, takeChatScanRequests = true)
        }
    }

    /**
     * Resumes a paused or interrupted run from its saved checkpoints: the
     * same history entry continues (owner decision, Stage 6A question 8).
     * Each range rechecks its already-read messages first and starts over
     * from its bookmark if any of them changed.
     */
    suspend fun resume(
        context: Context,
        runId: String,
        onProgress: (Progress) -> Unit,
        stopKind: () -> StopKind = { StopKind.INTERRUPT }
    ): RunOutcome {
        val store = MemoryStore.getInstance(context)
        val run = store.getArchivistRun(runId)?.takeIf { it.status == "running" && it.suspended != null }
            ?: return RunOutcome(null, 0, 0, 0, 0, emptyList(), outcome = "nothing")
        return runWithSetup(context, run.analysisType) { ready ->
            val resumed = run.copy(suspended = null, outcome = null, failureReason = null, error = null)
            store.insertArchivistRun(resumed)
            executeRun(
                context, ready, resumed, store.scanRangesForRun(runId), liveChatNamesById(context),
                onProgress, stopKind, takeChatScanRequests = true
            )
        }
    }

    /** The newest paused or interrupted run, or null. */
    fun suspendedRun(context: Context): ArchivistRunRecord? = try {
        if (!MemoryStore.isProvisioned(context)) null
        else MemoryStore.getInstance(context).suspendedScanRuns().firstOrNull()
    } catch (_: Exception) {
        null
    }

    /** The oldest interrupted run (automatic resume takes these in order). */
    fun interruptedRun(context: Context): ArchivistRunRecord? = try {
        if (!MemoryStore.isProvisioned(context)) null
        else MemoryStore.getInstance(context).suspendedScanRuns()
            .lastOrNull { it.suspended == MemoryStore.SUSPENDED_INTERRUPTED }
    } catch (_: Exception) {
        null
    }

    /**
     * Cancel while a scan is paused or interrupted (owner decision, Stage 6A
     * question 6): the unfinished progress is discarded, drafts already filed
     * stay, and the history entry ends as the user's cancel.
     */
    fun cancelSuspended(context: Context, runId: String): Boolean {
        if (!liveRun.compareAndSet(false, true)) return false
        return try {
            val store = MemoryStore.getInstance(context)
            val run = store.getArchivistRun(runId) ?: return false
            if (run.status != "running" || run.suspended == null) return false
            store.releaseScanRun(runId)
            store.insertArchivistRun(
                run.copy(
                    status = "failed", suspended = null, finishedAt = Instant.now().toString(),
                    outcome = "cancelled_user", failureReason = null, error = "interrupted"
                )
            )
            true
        } finally {
            liveRun.set(false)
        }
    }

    /**
     * The automatic complete rescan of chats whose earlier progress could
     * not be migrated (owner decision, Stage 6A question 1). Archive-paused
     * chats wait until their Archive switch is on again. Existing memories
     * and Pending suggestions are untouched; exact duplicates are skipped by
     * the usual filing checks.
     */
    suspend fun rescanUnmigratedChats(
        context: Context,
        onProgress: (Progress) -> Unit,
        stopKind: () -> StopKind = { StopKind.INTERRUPT }
    ): RunOutcome {
        val analysisType = "associative"
        return startRun(context, RUN_RESCAN, analysisType, onProgress, stopKind) {
            val names = liveChatNamesById(context)
            MemoryStore.getInstance(context).chatsNeedingFullRescan()
                .filter { names.containsKey(it) }
                .filter { id ->
                    !(try { Preferences.getPreferences(context, id).isChatExcludedFromMemory() } catch (_: Exception) { true })
                }
                .map { Conversation(it, names[it] ?: it) to false }
        }
    }

    /** What [run] needs from the Memory Assistant's settings, or which piece
     *  is missing. */
    private sealed interface ScannerSetup {
        data object NoEndpointSelected : ScannerSetup
        data object EndpointHostEmpty : ScannerSetup
        data object NoModel : ScannerSetup
        data object RoutingNotConfigured : ScannerSetup
        data object RoutingUnsatisfiable : ScannerSetup
        data class Ready(
            val prefs: Preferences,
            val endpoint: ApiEndpointObject,
            val model: String,
            val providerRouting: com.google.gson.JsonObject?
        ) : ScannerSetup
    }

    private fun scannerSetup(context: Context): ScannerSetup {
        val prefs = Preferences.getPreferences(context, "")
        val endpointId = prefs.getArchivistEndpointId()
        if (endpointId.isBlank()) return ScannerSetup.NoEndpointSelected
        val endpoint = ApiEndpointPreferences.getApiEndpointPreferences(context).getApiEndpoint(context, endpointId)
        if (endpoint == null || endpoint.host.isBlank()) return ScannerSetup.EndpointHostEmpty
        // The Memory Assistant model is an explicit selection. Changing its
        // endpoint clears this value and shows "Select"; never silently fall
        // back to the endpoint profile's ordinary-chat model behind that UI.
        val model = prefs.getArchivistModel()
        if (model.isBlank()) return ScannerSetup.NoModel

        // The Memory Scanner's own routing (its favorites, or its own routing
        // for the selected model), never the chat's.
        val requestFavorite = if (endpoint.isOpenRouterRouting()) {
            DedicatedModelRouting.MEMORY_SCANNER.effective(context, endpointId, model)
        } else {
            null
        }
        if (requestFavorite != null &&
            DedicatedModelRoutingPolicy.needsSetup(requestFavorite.routingType, requestFavorite)
        ) return ScannerSetup.RoutingNotConfigured
        val routingResolution = ProviderRoutingResolver.resolve(
            endpoint.isOpenRouterRouting(), requestFavorite
        )
        if (routingResolution.block != RoutingBlock.NONE) return ScannerSetup.RoutingUnsatisfiable
        return ScannerSetup.Ready(prefs, endpoint, model, routingResolution.providerJson)
    }

    private const val RUN_ANALYZE = "analyze"
    private const val RUN_CHAT = "chat"
    private const val RUN_RERUN = "rerun"
    private const val RUN_RESCAN = "rescan"

    /** Most times one range starts over because already-read messages
     *  changed while it was being read. */
    private const val MAX_SOURCE_RESTARTS = 3

    private fun newRunRow(kind: String, analysisType: String) = ArchivistRunRecord(
        runId = MemoryStore.newId("run-"),
        startedAt = Instant.now().toString(),
        finishedAt = null,
        status = "running",
        chatIdsJson = "[]",
        transcriptIdsJson = "[]",
        memoryIdsJson = "[]",
        ruleIdsJson = "[]",
        foundCount = 0,
        failedChatIdsJson = "[]",
        error = null,
        outcome = null,
        failureReason = null,
        transport = "api",
        analysisType = analysisType,
        runKind = kind
    )

    /** The setup check, the transcript migration and the one-live-run gate
     *  shared by every way of starting or resuming a run. */
    private suspend fun runWithSetup(
        context: Context,
        analysisType: String,
        body: suspend (ScannerSetup.Ready) -> RunOutcome
    ): RunOutcome {
        val ready = when (val setup = scannerSetup(context)) {
            is ScannerSetup.Ready -> setup
            else -> {
                // Always logged (spec): user-relevant recovery information.
                MemoryLog.logAlways(context, "Archivist", "warn", when (setup) {
                    ScannerSetup.NoModel ->
                        "Archivist Not Ready — Memory Archivist needs a model before it can run. Missing: model name"
                    ScannerSetup.RoutingNotConfigured ->
                        "Archivist Not Ready — selected provider routing is not configured for the Memory Assistant model"
                    ScannerSetup.RoutingUnsatisfiable ->
                        "Archivist Not Ready — selected provider routing cannot be satisfied"
                    else ->
                        "Archivist Not Ready — Memory Archivist needs a model before it can run. " +
                            "Missing: ${if (setup == ScannerSetup.NoEndpointSelected) "endpoint profile not selected" else "endpoint host empty"}"
                })
                return RunOutcome(null, 0, 0, 0, 0, emptyList(), outcome = "not_configured", analysisType = analysisType)
            }
        }
        if (!liveRun.compareAndSet(false, true)) {
            MemoryLog.logAlways(context, "Archivist", "warn",
                "duplicate start ignored — an analysis run is already in progress")
            return RunOutcome(null, 0, 0, 0, 0, emptyList(), outcome = "already_running", analysisType = analysisType)
        }
        try {
            // The migration runs first (a no-op once complete). A chat it
            // could not read yet keeps its legacy rows and stays out of every
            // scan until it migrates; other chats scan normally.
            ScanSourceMigration.runIfNeeded(context)
            return body(ready)
        } finally {
            liveRun.set(false)
        }
    }

    /**
     * Starts a new run over [select]'s chats (each with whether it ignores
     * the chat's Archive switch). Selection and claims are sealed with the
     * durable run row in one transaction; a chat another run holds, or whose
     * bookmark moved meanwhile, drops out. A chat a suspended batch holds is
     * moved out of that batch with its checkpoint when the types match, or
     * released from it otherwise.
     */
    private suspend fun startRun(
        context: Context,
        kind: String,
        analysisType: String,
        onProgress: (Progress) -> Unit,
        stopKind: () -> StopKind,
        select: () -> List<Pair<Conversation, Boolean>>
    ): RunOutcome = runWithSetup(context, analysisType) { ready ->
        val store = MemoryStore.getInstance(context)
        val runRow = newRunRow(kind, analysisType)
        val names = liveChatNamesById(context)
        val ranges = ArrayList<ScanRangeRecord>()
        val transferred = ArrayList<ScanRangeRecord>()
        val held = if (kind == RUN_CHAT) store.chatsHeldBySuspendedRuns() else emptyMap()
        val touchedSuspended = LinkedHashSet<String>()
        val unmigrated = unmigratedChats(store)
        for ((ordinal, pair) in select().withIndex()) {
            val conversation = pair.first
            if (conversation.chatId in unmigrated) continue
            held[conversation.chatId]?.let { suspendedRunId ->
                val suspended = store.getArchivistRun(suspendedRunId)
                val range = store.scanRangesForRun(suspendedRunId).firstOrNull { it.chatId == conversation.chatId }
                if (suspended != null && range != null) {
                    if (suspended.analysisType == analysisType) {
                        transferred.add(range.copy(runId = runRow.runId, ordinal = ordinal))
                    } else {
                        store.releaseScanRange(range.rangeId)
                    }
                    touchedSuspended.add(suspendedRunId)
                }
            }
            if (transferred.any { it.chatId == conversation.chatId }) continue
            // A chat already damaged when the scan starts goes through the
            // existing repair first; an unrepaired chat is reported and left
            // out, and its earlier progress is kept.
            val messages = try {
                readRangeSource(context, store, conversation.chatId)
            } catch (_: ChatSourceUnavailableException) {
                continue
            }
            val progress = store.scanProgress(conversation.chatId)
            if (progress?.claimRunId != null) continue
            val index = ScanChatStatus.bookmarkIndex(progress, messages)
            if ((index + 1 until messages.size).none { messages[it].eligible }) continue
            ranges.add(
                ScanRangeRecord(
                    rangeId = MemoryStore.newId("range-"),
                    runId = runRow.runId,
                    chatId = conversation.chatId,
                    ordinal = ordinal,
                    kind = ScanRangeRecord.KIND_SCAN,
                    // A chat due a complete rescan is read from its start.
                    startAnchors = if (progress?.needsFullRescan == true) emptyList()
                        else progress?.bookmarkAnchors.orEmpty(),
                    endAnchors = ScanPosition.anchors(messages.map { it.id }, messages.lastIndex)
                )
            )
        }
        val opened = store.beginScanRun(runRow, ranges).toMutableList()
        for (range in transferred) {
            store.transferScanRange(range.rangeId, runRow.runId, range.ordinal)?.let { opened.add(it) }
        }
        // Only now, after the transfer, can a batch that gave up its last
        // chat be seen as empty and finished.
        touchedSuspended.forEach { finishEmptySuspendedRun(store, it) }
        opened.sortBy { it.ordinal }
        executeRun(context, ready, runRow, opened, names, onProgress, stopKind, takeChatScanRequests = kind != RUN_CHAT)
    }

    /** A suspended batch whose last chat was taken out of it has nothing
     *  left to resume: it ends with what it already filed. */
    private fun finishEmptySuspendedRun(store: MemoryStore, runId: String) {
        if (store.scanRangesForRun(runId).isNotEmpty()) return
        val run = store.getArchivistRun(runId) ?: return
        if (run.status != "running" || run.suspended == null) return
        store.releaseScanRun(runId)
        store.insertArchivistRun(
            run.copy(
                status = "complete", suspended = null, finishedAt = Instant.now().toString(),
                outcome = if (run.foundCount > 0) "completed" else "no_new",
                failureReason = null, error = null
            )
        )
    }

    /**
     * Reads every range of a run, in order, then ends the run one of the four
     * ways (finished, cancelled, paused, interrupted). [runRow] is either a
     * new row or a resumed one; its stored counts continue.
     */
    private suspend fun executeRun(
        context: Context,
        ready: ScannerSetup.Ready,
        runRow: ArchivistRunRecord,
        initialRanges: List<ScanRangeRecord>,
        chatNames: Map<String, String>,
        onProgress: (Progress) -> Unit,
        stopKind: () -> StopKind,
        takeChatScanRequests: Boolean
    ): RunOutcome {
        val store = MemoryStore.getInstance(context)
        val analysisType = runRow.analysisType
        val lorebookMode = analysisType == "lorebook"
        val prefs = ready.prefs
        val endpoint = ready.endpoint
        val model = ready.model
        val markProcessed = runRow.runKind != RUN_RERUN

        val ai = buildClient(endpoint, ready.providerRouting)
        // The Memory Scanner's parameters (never an API profile's), less any
        // the service reports this model does not accept. A parameter a
        // provider explicitly refuses is left out of the retry and of the
        // rest of this run; saved values are never changed.
        var scanParameters = ModelRequestParameters.from(
            FavoriteModelParameters.live(context, ModelPickerScope.MEMORY_SCANNER),
            ModelSupportedParametersStore.get(context).supported(endpoint.id, model)
        )
        val modelTransport = ArchivistModelTransport { request ->
            suspend fun send(parameters: ModelRequestParameters): Pair<String, String?> {
                capturedErrorBody = null
                val chatRequest = ChatCompletionRequest(
                    model = ModelId(request.model),
                    messages = listOf(
                        ChatMessage(role = ChatRole.System, content = request.systemPrompt),
                        ChatMessage(role = ChatRole.User, content = request.conversationData)
                    ),
                    temperature = request.temperature.takeIf { parameters.temperature != null },
                    topP = parameters.topP,
                    frequencyPenalty = parameters.frequencyPenalty,
                    presencePenalty = parameters.presencePenalty
                )
                if (!parameters.stream) {
                    val choice = ai.chatCompletion(chatRequest).choices.firstOrNull()
                    return choice?.message?.content.orEmpty() to choice?.finishReason?.value
                }
                val text = StringBuilder()
                var finish: String? = null
                ai.chatCompletions(chatRequest).collect { chunk ->
                    val choice = chunk.choices.firstOrNull()
                    choice?.delta?.content?.takeIf { it != "null" }?.let(text::append)
                    choice?.finishReason?.value?.let { finish = it }
                }
                return text.toString() to finish
            }
            val (raw, finishReason) = try {
                try {
                    send(scanParameters)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val refused = ModelParameterRejection.rejected(
                        ModelParameterRejection.statusOf(e), capturedErrorBody, e.message,
                        scanParameters.sentNames()
                    )
                    if (refused.isEmpty()) throw e
                    scanParameters = scanParameters.without(refused)
                    send(scanParameters)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                recordRequestProviderFailure(context, endpoint, request.model, e,
                    capturedErrorBody, listOf(request.systemPrompt, request.conversationData))
                throw e
            }
            DedicatedModelRouting.MEMORY_SCANNER.recordUse(context, endpoint.id, request.model)
            if (ArchivistRetryPolicy.looksClearlyTruncated(raw, finishReason)) {
                throw ArchivistClearlyTruncatedException()
            }
            raw
        }

        // Memory Assistant tuning (owner spec, July 9 2026): the per-conversation
        // cap is enforced HERE in code — the prompt is never trusted to do it.
        val maxSuggestions = prefs.getArchivistMaxSuggestions()
        val temperature = prefs.getArchivistTemperature().toDouble()
        val scannerAverageTokens = prefs.getMemoryScannerAverageTokens()
        val scannerMaximumTokens = prefs.getMemoryScannerMaximumTokens()
        val verifiedContextTokens = endpoint.contextWindowTokens?.takeIf {
            endpoint.contextWindowModelId == model
        }
        // Each analysis type sends its OWN editable prompt (Step 1.7).
        val basePrompt = if (lorebookMode)
            prefs.getArchivistLorebookPrompt().ifBlank { ArchivistPrompt.LOREBOOK_SYSTEM }
        else
            prefs.getArchivistCustomPrompt().ifBlank { ArchivistPrompt.SYSTEM }
        val memoryTypes = if (lorebookMode) emptyList()
            else store.getMemoryTypes().map { it.typeId to it.name }

        // A resumed run continues its own counts.
        val memoryIds = ArrayList(jsonToList(runRow.memoryIdsJson))
        val ruleIds = ArrayList(jsonToList(runRow.ruleIdsJson))
        val failedChats = ArrayList(jsonToList(runRow.failedChatIdsJson))
        val analyzedChatIds = ArrayList(jsonToList(runRow.chatIdsJson))
        val sourceRanges = ArrayList(ScanSourceRanges.decode(runRow.sourceRangesJson))
        val failedReasons = ArrayList<ArchivistFailure>()
        val genResults = ArrayList<GenErrorResult>()
        var failureBody: String? = null
        var duplicatesSkipped = 0
        var runError: String? = null
        var runErrorFailure: ArchivistFailure? = null
        var stopped: StopKind? = null
        var lastPercent = runRow.progressPercent ?: 0
        val stoppedChatScans = ArrayList<String>()
        val alreadyDone = analyzedChatIds.size + failedChats.size

        fun progressRow(): ArchivistRunRecord = runRow.copy(
            chatIdsJson = listToJson(analyzedChatIds),
            memoryIdsJson = listToJson(memoryIds),
            ruleIdsJson = listToJson(ruleIds),
            foundCount = memoryIds.size,
            failedChatIdsJson = listToJson(failedChats),
            sourceRangesJson = ScanSourceRanges.encode(sourceRanges),
            progressPercent = lastPercent
        )

        /** The run record as it stands once [pending] is filed: saved in the
         *  same transaction as that chat's outputs and bookmark, so history
         *  never misses a chat that was committed. */
        fun progressRowWith(pending: ChatCommit): ArchivistRunRecord = runRow.copy(
            chatIdsJson = listToJson(analyzedChatIds + pending.chatId),
            memoryIdsJson = listToJson(memoryIds + pending.memoryIds),
            ruleIdsJson = listToJson(ruleIds + pending.ruleIds),
            foundCount = memoryIds.size + pending.memoryIds.size,
            failedChatIdsJson = listToJson(failedChats),
            sourceRangesJson = ScanSourceRanges.encode(sourceRanges + listOfNotNull(pending.sourceRange)),
            progressPercent = lastPercent
        )

        val queue = initialRanges.toMutableList()
        var chatScansDone = 0
        var chatScansTotal = 0
        try {
            val batches = ArchivistBatchPlanner.planBatches(queue.map { 1 })
            var position = 0
            while (position < queue.size) {
                if (takeChatScanRequests) {
                    val requests = generateSequence { pendingChatScans.poll() }.toList()
                    // Only a run that scans and advances bookmarks, of the
                    // type the user chose, can take a requested chat in its
                    // own queue. Anything else scans the chat separately.
                    val (sameType, otherType) = if (markProcessed) {
                        requests.partition { it.analysisType == analysisType }
                    } else {
                        Pair(emptyList(), requests)
                    }
                    val promotedSeparate = ChatScanOrder.promote(queue, position, sameType) { it.chatId }
                    val otherTypeChats = otherType.distinctBy { it.chatId }
                    if (markProcessed) {
                        // A chat still waiting in this run is handed back out
                        // so the separate scan can claim it with its own type.
                        for (request in otherTypeChats) {
                            val waiting = (position until queue.size)
                                .firstOrNull { queue[it].chatId == request.chatId } ?: continue
                            val range = queue.removeAt(waiting)
                            try {
                                store.releaseScanRange(range.rangeId)
                            } catch (_: Exception) { /* its scan then finds nothing to claim */ }
                        }
                    }
                    val separate = (promotedSeparate + otherTypeChats).distinctBy { it.chatId }
                    for (request in separate) {
                        if (!chatHasUnscannedMessages(context, request.chatId)) continue
                        if (store.scanProgress(request.chatId)?.claimRunId != null) {
                            // Still held (by this run or a suspended one):
                            // the service starts it once released.
                            pendingChatScans.add(request)
                            continue
                        }
                        chatScansTotal += 1
                        liveRun.set(false)
                        try {
                            val chatOutcome = analyzeChat(context, request.chatId, request.analysisType, { p ->
                                onProgress(Progress(
                                    1, 1, 1, 1,
                                    overallIndex = alreadyDone + position + chatScansDone + p.overallIndex,
                                    overallCount = alreadyDone + queue.size + chatScansTotal
                                ))
                            }, stopKind)
                            if (chatOutcome.outcome == "cancelled") {
                                chatOutcome.runId?.let { stoppedChatScans.add(it) }
                            }
                        } finally {
                            liveRun.set(true)
                        }
                        chatScansDone += 1
                        // A stop during the chat scan stops this run too.
                        currentCoroutineContext().ensureActive()
                    }
                }
                if (position >= queue.size) break
                val range = queue[position]
                val overallIndex = alreadyDone + position + chatScansDone + 1
                val overallCount = alreadyDone + queue.size + chatScansTotal
                val emit: (Int, Int) -> Unit = { done, total ->
                    val p = Progress(
                        1, batches.size.coerceAtLeast(1), position + 1, queue.size,
                        overallIndex = overallIndex, overallCount = overallCount,
                        sectionsDone = done, sectionsTotal = total
                    )
                    lastPercent = p.percent()
                    onProgress(p)
                }
                emit(0, 0)
                val chatName = chatNames[range.chatId] ?: range.chatId
                // A chat whose earlier progress could not be migrated reports
                // its complete rescan only once it has actually been scanned.
                val fullRescan = range.kind == ScanRangeRecord.KIND_SCAN &&
                    store.scanProgress(range.chatId)?.needsFullRescan == true
                try {
                    scanRange(
                        context, store, range, chatName, lorebookMode, basePrompt, memoryTypes,
                        modelTransport, model, temperature, maxSuggestions,
                        scannerAverageTokens, scannerMaximumTokens, verifiedContextTokens,
                        runRow.runId, prefs, emit, ::progressRowWith,
                        onCommitted = { commit ->
                            memoryIds.addAll(commit.memoryIds)
                            ruleIds.addAll(commit.ruleIds)
                            duplicatesSkipped += commit.duplicatesSkipped
                            analyzedChatIds.add(range.chatId)
                            commit.sourceRange?.let { sourceRanges.add(it) }
                        }
                    )
                    if (fullRescan) {
                        store.removeScanReports(range.chatId, listOf(ScanReport.FULL_RESCAN_FAILED))
                        store.addScanReport(range.chatId, ScanReport.FULL_RESCAN_COMPLETED)
                    }
                    try {
                        store.insertArchivistRun(progressRow())
                    } catch (e: Exception) {
                        MemoryLog.log(context, "Archivist", "error", "run progress write failed: ${e.message}")
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    // One conversation failing must not sink the run. Its
                    // range is released and its bookmark stays where it was.
                    val reason = ArchivistFailure.classify(e)
                    try {
                        store.releaseScanRange(range.rangeId)
                    } catch (_: Exception) { /* reconciled at next start */ }
                    failedChats.add(range.chatId)
                    failedReasons.add(reason)
                    genResults.add(classifyGenerationFailure(e, capturedErrorBody))
                    capturedErrorBody?.let { failureBody = it }
                    if (fullRescan) {
                        store.addScanReport(range.chatId, ScanReport.FULL_RESCAN_FAILED)
                    }
                    MemoryLog.logAlways(context, "Archivist", "error",
                        "chat=${range.chatId} failed (${reason.key}): ${e.message}")
                }
                position++
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            stopped = stopKind()
            runError = "interrupted"
            runErrorFailure = ArchivistFailure.INTERRUPTED
        } catch (e: Exception) {
            runError = e.message ?: e.javaClass.simpleName
            runErrorFailure = ArchivistFailure.classify(e)
            genResults.add(classifyGenerationFailure(e, capturedErrorBody))
            capturedErrorBody?.let { failureBody = it }
        }

        val selected = alreadyDone + queue.size
        val apiProviderName = endpoint.label.trim().ifBlank { null } ?: endpoint.host.trim().ifBlank { null }
        val runModel = model.trim().ifBlank { null } ?: endpoint.model.trim().ifBlank { null }

        // Paused or interrupted: everything the run holds is kept for Resume.
        if (stopped == StopKind.PAUSE || stopped == StopKind.INTERRUPT) {
            val pausing = stopped == StopKind.PAUSE
            try {
                store.suspendScanRun(
                    progressRow().copy(
                        outcome = if (pausing) null else "cancelled",
                        failureReason = if (pausing) null else ArchivistFailure.INTERRUPTED.key,
                        error = if (pausing) null else "interrupted"
                    ),
                    if (pausing) MemoryStore.SUSPENDED_PAUSED else MemoryStore.SUSPENDED_INTERRUPTED,
                    lastPercent
                )
            } catch (e: Exception) {
                MemoryLog.logAlways(context, "Archivist", "error",
                    "scan checkpoint could not be kept: ${e.message}")
            }
            return RunOutcome(
                runId = runRow.runId,
                conversationsSelected = selected,
                conversationsAnalyzed = analyzedChatIds.size,
                memoriesFound = memoryIds.size,
                ruleDraftsFound = ruleIds.size,
                failedChatIds = failedChats,
                outcome = if (pausing) "paused" else "cancelled",
                failureReason = if (pausing) null else ArchivistFailure.INTERRUPTED,
                duplicatesSkipped = duplicatesSkipped,
                error = if (pausing) null else "interrupted",
                analysisType = analysisType,
                apiProvider = apiProviderName,
                model = runModel,
                stoppedChatScanRunIds = stoppedChatScans
            )
        }

        // Display outcome (archivist_status_wording_spec.md). A partial
        // success is never called a full failure; "no new" is not an error.
        val anySaved = memoryIds.isNotEmpty() || ruleIds.isNotEmpty()
        val outcome = when {
            stopped == StopKind.CANCEL -> "cancelled"
            runError != null -> if (anySaved) "partial_failed" else "full_failed"
            selected == 0 -> "nothing"
            failedChats.size >= selected -> if (anySaved) "partial_failed" else "full_failed"
            failedChats.isNotEmpty() -> "partial_failed"
            !anySaved -> "no_new"
            else -> "completed"
        }
        val dominantReason: ArchivistFailure? = runErrorFailure
            ?: failedReasons.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: if (outcome == "full_failed") ArchivistFailure.UNKNOWN else null
        val dominantGen: GenErrorResult? = if (outcome == "full_failed") {
            genResults.groupingBy { it.code }.eachCount().maxByOrNull { it.value }?.key
                ?.let { code -> genResults.firstOrNull { it.code == code } }
        } else null
        val providerInfo = ProviderErrorInfo.parse(failureBody)
        val failureCategoryCounts: Map<String, Int> =
            failedReasons.zip(genResults)
                .map { ArchivistFailureCategory.of(it.first, it.second) }
                .groupingBy { it }.eachCount()

        // Failure and partial-failure records are ALWAYS written to the
        // Memory Debug Log (owner rule — recovery information).
        when (outcome) {
            "full_failed" -> MemoryLog.logAlways(context, "Archivist", "error",
                "Run Fully Failed — Memory extraction failed. " +
                    (if (memoryIds.isEmpty()) "No memories were created from this run. " else "") +
                    "reason=${dominantReason?.key} error=${runError ?: "per-conversation failures"} " +
                    "selected=$selected processed=${analyzedChatIds.size} memories=${memoryIds.size}")
            "partial_failed" -> MemoryLog.logAlways(context, "Archivist", "warn",
                "Run Partially Failed — Memory extraction finished with some skipped conversations. " +
                    "reasons=${failedReasons.map { it.key }.distinct()} selected=$selected " +
                    "processed=${analyzedChatIds.size} skipped=${failedChats.size} " +
                    "memories=${memoryIds.size} failedChats=$failedChats")
            "cancelled" -> MemoryLog.logAlways(context, "Archivist", "warn",
                "Run Cancelled — analysis was stopped before it could finish. " +
                    "cause=Cancel button selected=$selected " +
                    "processed=${analyzedChatIds.size} memories=${memoryIds.size}")
        }

        // Finished or cancelled: the run's unfinished ranges, claims and
        // working drafts are cleared; filed drafts stay.
        try {
            store.releaseScanRun(runRow.runId)
        } catch (e: Exception) {
            MemoryLog.logAlways(context, "Archivist", "error", "claim release failed: ${e.message}")
        }
        try {
            store.insertArchivistRun(
                progressRow().copy(
                    finishedAt = Instant.now().toString(),
                    status = if (outcome == "full_failed" || outcome == "cancelled") "failed" else "complete",
                    suspended = null,
                    error = runError,
                    outcome = outcome,
                    failureReason = dominantReason?.key
                )
            )
        } catch (e: Exception) {
            MemoryLog.logAlways(context, "Archivist", "error", "run record write failed: ${e.message}")
        }
        return RunOutcome(
            runId = runRow.runId,
            conversationsSelected = selected,
            conversationsAnalyzed = analyzedChatIds.size,
            memoriesFound = memoryIds.size,
            ruleDraftsFound = ruleIds.size,
            failedChatIds = failedChats,
            outcome = outcome,
            failureReason = dominantReason,
            duplicatesSkipped = duplicatesSkipped,
            error = runError,
            analysisType = analysisType,
            genError = dominantGen,
            apiProvider = apiProviderName,
            upstreamProvider = providerInfo.providerName?.trim()?.ifBlank { null },
            providerMessage = providerInfo.message?.trim()?.ifBlank { null },
            model = runModel,
            failureCategoryCounts = failureCategoryCounts,
            stoppedChatScanRunIds = stoppedChatScans
        )
    }

    /** The current readable messages of a range's chat. A damaged chat goes
     *  through the existing repair first (owner decision, Stage 6A question
     *  9); only a verified repair lets the scan continue. */
    private fun readRangeSource(context: Context, store: MemoryStore, chatId: String): List<ScanMessage> {
        when (val load = ChatScanSource.load(context, chatId)) {
            is ChatScanSource.Load.Readable -> return load.messages
            ChatScanSource.Load.Unavailable -> throw ChatSourceUnavailableException(damaged = false)
            ChatScanSource.Load.Damaged -> {
                val repaired = try {
                    chatId in DamagedChatRepair.repairChats(context, listOf(chatId)).repaired
                } catch (_: Exception) {
                    false
                }
                val again = if (repaired) ChatScanSource.load(context, chatId) else null
                if (again is ChatScanSource.Load.Readable) {
                    store.removeScanReports(chatId, listOf(ScanReport.DAMAGE_NOT_REPAIRED))
                    store.addScanReport(chatId, ScanReport.DAMAGE_REPAIRED)
                    return again.messages
                }
                store.removeScanReports(chatId, listOf(ScanReport.DAMAGE_REPAIRED))
                store.addScanReport(chatId, ScanReport.DAMAGE_NOT_REPAIRED)
                throw ChatSourceUnavailableException(damaged = true)
            }
        }
    }

    /** The range's messages in the chat as it is now: after its start
     *  position, up to and including its frozen end. */
    private fun rangeMessages(messages: List<ScanMessage>, range: ScanRangeRecord): List<ScanMessage> {
        val ids = messages.map { it.id }
        val start = if (range.startAnchors.isEmpty()) -1 else ScanPosition.resolve(ids, range.startAnchors) ?: -1
        val end = if (range.endAnchors.isEmpty()) messages.lastIndex
            else ScanPosition.resolve(ids, range.endAnchors) ?: messages.lastIndex
        if (end <= start) return emptyList()
        return messages.subList(start + 1, end + 1)
    }

    /**
     * Reads one range section by section from the original chat, saving
     * each completed section's drafts and reading position together, and
     * files the whole range at the end. Already-read messages are checked
     * before each section, after each model reply and before filing; a
     * change restarts the range from its bookmark.
     */
    private suspend fun scanRange(
        context: Context,
        store: MemoryStore,
        range: ScanRangeRecord,
        chatName: String,
        lorebookMode: Boolean,
        basePrompt: String,
        memoryTypes: List<Pair<String, String>>,
        modelTransport: ArchivistModelTransport,
        model: String,
        temperature: Double,
        maxSuggestions: Int,
        scannerAverageTokens: Int,
        scannerMaximumTokens: Int,
        verifiedContextTokens: Int?,
        runId: String,
        prefs: Preferences,
        emit: (Int, Int) -> Unit,
        progressRowWith: (ChatCommit) -> ArchivistRunRecord,
        onCommitted: (ChatCommit) -> Unit
    ): ChatCommit {
        val chatId = range.chatId
        val analysisNote = Preferences.getPreferences(context, chatId).getChatAnalysisNote()
        val conversationBasePrompt = ArchivistPrompt.withAnalysisNote(basePrompt, analysisNote)
        val baselinePrompt = if (lorebookMode) conversationBasePrompt
            else ArchivistPrompt.withCurrentTypes(conversationBasePrompt, memoryTypes)
        val initialMaximum = ArchivistRequestBudget.effectiveMaximumTokens(
            scannerMaximumTokens, verifiedContextTokens,
            ArchivistRequestBudget.headroomTokens(baselinePrompt)
        )
        val initialTranscriptTarget = minOf(scannerAverageTokens, initialMaximum)
        val initialAllowance = minOf(
            org.teslasoft.assistant.util.ConversationTokenChunks.EXCHANGE_ALLOWANCE_TOKENS,
            initialMaximum - initialTranscriptTarget
        )
        val resolveScope = scopeResolver(store)

        var restarts = 0
        var checkpoint = ScanCheckpoint.decode(range.checkpointJson)
        var messages = readRangeSource(context, store, chatId)
        var inRange = rangeMessages(messages, range)
        val staged = ArrayList<ParsedChunk>()
        if (checkpoint.stillValid(inRange)) {
            staged.add(restoreStaged(store.stagedScanCandidates(range.rangeId)))
        } else {
            store.restartScanRange(range.rangeId)
            checkpoint = ScanCheckpoint()
        }

        fun restart() {
            restarts++
            if (restarts > MAX_SOURCE_RESTARTS) throw SourceKeepsChangingException()
            store.restartScanRange(range.rangeId)
            checkpoint = ScanCheckpoint()
            staged.clear()
        }

        reading@ while (true) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val next = checkpoint.nextUnread(inRange) ?: break
            val rows = ScanSourceRows.build(chatId, inRange, next.first, next.second, resolveScope)
            if (rows.isEmpty()) break
            val chunks = ArchivistConversationChunker.split(
                rows, initialTranscriptTarget, scannerMaximumTokens, initialAllowance
            )
            emit(checkpoint.sectionsDone, checkpoint.sectionsDone + chunks.size)
            // Pieces of a split message carry "part k of n" of that message.
            val labelAt: (List<ScanSourceRow>, Int) -> List<ScanSourceRow> = { list, maximum ->
                ScanContext.labelParts(list, { id -> inRange.firstOrNull { it.id == id }?.text }, maximum)
            }
            val chunk = chunks.first().let { it.copy(rows = labelAt(it.rows, scannerMaximumTokens)) }
            val earlier = staged.toList()
            val chatNow = messages
            val earlierFor: (ScanSourceRow) -> List<ScanSourceRow> = { first ->
                ScanContext.earlierExcerpts(chatNow, first, resolveScope)
            }
            // Every message the section traverses, sent or not (hidden,
            // unfinished, a companion not taking part), as it was before the
            // request: a change to any of them while the request is in flight
            // means the section must be read again.
            val traversedEnd = inRange.indexOfFirst { it.id == chunk.rows.last().messageId }
            val traversedBefore = inRange.subList(next.first, traversedEnd + 1).map { it.id to it.fingerprint }
            val retried = ArchivistBoundedShrinkExecutor.execute<ArchivistRequestChunk, ParsedChunk>(
                initial = chunk,
                analyze = { requestChunk, successfulSubChunks ->
                    analyzeChunk(
                        context, store, prefs, chatName, lorebookMode, conversationBasePrompt,
                        memoryTypes, requestChunk, earlier + successfulSubChunks, modelTransport,
                        model, temperature, verifiedContextTokens, earlierFor
                    )
                },
                shouldShrink = { error ->
                    ArchivistRetryPolicy.isVerifiedContextRejection(error, capturedErrorBody)
                },
                shrink = { failedChunk, error ->
                    val nextTarget =
                        (error as? ArchivistShrinkRequiredException)?.nextTargetTokens
                            ?: maxOf(
                                ArchivistRequestBudget.MIN_RUNTIME_TRANSCRIPT_TOKENS,
                                failedChunk.estimatedTranscriptTokens / 2
                            )
                    // After a confirmed size rejection the single-message
                    // maximum comes down with the target, so an oversized
                    // message is split too (lossless, Stage 6A).
                    ArchivistConversationChunker.split(
                        failedChunk.rows, nextTarget, nextTarget, allowanceTokens = 0
                    ).map { it.copy(rows = labelAt(it.rows, nextTarget)) }
                }
            )
            val parsed = ParsedChunk(
                memories = retried.flatMap { it.memories },
                rules = retried.flatMap { it.rules },
                loreEntries = retried.flatMap { it.loreEntries }
            )
            // Changes made while the request was in flight: an outdated reply
            // is discarded, never saved.
            messages = readRangeSource(context, store, chatId)
            inRange = rangeMessages(messages, range)
            val readThrough = chunk.rows.last()
            val endIndex = inRange.indexOfFirst { it.id == readThrough.messageId }
            val sectionMessagesUnchanged = chunk.rows.all { row ->
                inRange.firstOrNull { it.id == row.messageId }?.let { m ->
                    m.eligible && row.end <= m.text.length && m.text.substring(row.start, row.end) == row.text
                } == true
            }
            val traversedStart = inRange.indexOfFirst { it.id == traversedBefore.first().first }
            val traversedAfter = if (traversedStart < 0 || endIndex < traversedStart) emptyList()
                else inRange.subList(traversedStart, endIndex + 1).map { it.id to it.fingerprint }
            if (!checkpoint.stillValid(inRange) || endIndex < 0 || !sectionMessagesUnchanged ||
                traversedAfter != traversedBefore
            ) {
                restart()
                continue
            }
            checkpoint = checkpoint.advancedTo(inRange, endIndex, readThrough.end)
            store.saveScanSection(
                range.rangeId, checkpoint.sectionsDone, stagedCandidates(parsed), checkpoint.encode()
            )
            staged.add(parsed)
            emit(checkpoint.sectionsDone, checkpoint.sectionsDone + chunks.size - 1)
        }

        // Publication check: nothing read may have changed since.
        messages = readRangeSource(context, store, chatId)
        inRange = rangeMessages(messages, range)
        if (!checkpoint.stillValid(inRange)) {
            restart()
            continue@reading
        }
        break@reading
        }
        val ids = messages.map { it.id }
        val bookmarkEnd = ScanChatStatus.lastSettled(inRange)
        val endIndexInChat = if (bookmarkEnd == null) {
            if (range.startAnchors.isEmpty()) -1 else ScanPosition.resolve(ids, range.startAnchors) ?: -1
        } else ids.indexOf(bookmarkEnd.id)
        val newBookmark = ScanPosition.anchors(ids, endIndexInChat)
        val sourceRange = ScanSourceRanges.Range(chatId, range.startAnchors, newBookmark)
        val conversation = Conversation(chatId, chatName)
        val sourceModel = inRange.lastOrNull { it.model != null }?.model
        val commit = try {
            if (lorebookMode) {
                val prepared = prepareLorebookSuggestions(
                    context, store, conversation, runId, staged.flatMap { it.loreEntries }, maxSuggestions
                )
                val stored = store.commitScanRange(
                    range, newBookmark, emptyList(), emptyList(), prepared.records,
                    runProgress = progressRowWith(
                        ChatCommit(chatId, prepared.records.map { it.suggestionId }, emptyList(), 0, sourceRange)
                    )
                )
                ChatCommit(chatId, stored.lorebookSuggestionIds, emptyList(), prepared.duplicatesSkipped, sourceRange)
            } else {
                val preparedMemories = prepareMemoryDrafts(
                    context, store, conversation, staged.flatMap { it.memories }, maxSuggestions
                )
                val preparedRules = prepareRuleDrafts(context, store, sourceModel, staged.flatMap { it.rules })
                val stored = store.commitScanRange(
                    range, newBookmark, preparedMemories.records, preparedRules, emptyList(),
                    relationshipHintsByMemoryId = preparedMemories.relationshipHintsByMemoryId,
                    runProgress = progressRowWith(
                        ChatCommit(
                            chatId, preparedMemories.records.map { it.memoryId },
                            preparedRules.map { it.ruleId }, 0, sourceRange
                        )
                    ),
                    sourcesByMemoryId = preparedMemories.sourcesByMemoryId
                )
                ChatCommit(chatId, stored.memoryIds, stored.ruleIds, preparedMemories.duplicatesSkipped, sourceRange)
            }
        } catch (e: Exception) {
            throw TaggedArchivistException(ArchivistFailure.SAVE_FAILED, e)
        }
        onCommitted(commit)
        return commit
    }

    /** Recorded scope as the scanner applies it: the companion resolved to
     *  its memory record, and a companion whose memory participation is
     *  "none" not read at all. Unknown scope reads as general. */
    private fun scopeResolver(store: MemoryStore): (ScanMessageScope?) -> ScanScopeResolution {
        val cache = HashMap<ScanMessageScope, ScanScopeResolution>()
        return { scope ->
            if (scope == null) ScanSourceRows.GENERAL
            else cache.getOrPut(scope) {
                val companion = scope.companionId?.let { store.getCompanion(it) }
                    ?: scope.companionPersonaId?.let { store.findCompanionByAppCharacterId(it) }
                if (companion?.memoryParticipation == "none") ScanScopeResolution.Excluded
                else ScanScopeResolution.Read(
                    companionId = companion?.companionId,
                    worldId = scope.worldId,
                    campaignId = scope.campaignId,
                    roleplayCharacterId = scope.roleplayCharacterId,
                    userPersonaId = scope.userPersonaId,
                    projectId = scope.projectId
                )
            }
        }
    }

    /** The working drafts a range saved before it was paused or
     *  interrupted, rebuilt from their encrypted payloads. */
    private fun restoreStaged(candidates: List<StagedAnalysisCandidate>): ParsedChunk = ParsedChunk(
        memories = candidates.filter { it.stream == "memory" }
            .mapNotNull { ArchivistCandidateBoundary.memoryFromPayload(it.payloadJson) },
        rules = candidates.filter { it.stream == "model_rule" }
            .mapNotNull { ArchivistCandidateBoundary.ruleFromPayload(it.payloadJson) },
        loreEntries = candidates.filter { it.stream == "lore" }
            .mapNotNull { ArchivistCandidateBoundary.loreFromPayload(it.payloadJson) }
    )

    /** One request: the section rendered for the model, with the retrieved
     *  memories and earlier working drafts, parsed and validated. */
    private suspend fun analyzeChunk(
        context: Context,
        store: MemoryStore,
        prefs: Preferences,
        chatName: String,
        lorebookMode: Boolean,
        conversationBasePrompt: String,
        memoryTypes: List<Pair<String, String>>,
        requestChunk: ArchivistRequestChunk,
        allEarlier: List<ParsedChunk>,
        modelTransport: ArchivistModelTransport,
        model: String,
        temperature: Double,
        verifiedContextTokens: Int?,
        earlierFor: (ScanSourceRow) -> List<ScanSourceRow>
    ): ParsedChunk {
        val rows = requestChunk.rows
        val chatId = rows.first().chatId
        val scene = ArchivistSceneContext.from(rows.first())
        val companionName = scene.companionId?.let { store.getCompanion(it)?.currentName }
        var rendered = ArchivistPrompt.userMessage(chatName, companionName, rows, earlierFor(rows.first()))

        val protocol: ArchivistRequestProtocol?
        val requestSystemPrompt: String
        if (lorebookMode) {
            protocol = null
            requestSystemPrompt = conversationBasePrompt
        } else {
            val retrievalScope = reconciliationScope(store, prefs, scene)
            var retrievalDiag: Librarian.RetrievalDiagnostics? = null
            val retrieved = Librarian.getInstance(context).searchForReconciliation(
                scope = retrievalScope,
                queryWindows = ArchivistPrompt.retrievalWindows(rows),
                candidateCeiling = ArchivistRuntimeProtocol.MAX_RETRIEVAL_CANDIDATES,
                selectedProjectId = scene.projectId?.takeIf { !scene.isRoleplay },
                diag = { retrievalDiag = it }
            )
            retrievalDiag?.takeIf { !it.semantic }?.let { d ->
                MemoryLog.log(
                    context, "Archivist", "info",
                    "chat=$chatId: reconciliation used bounded lexical retrieval " +
                        "(${d.eligible} eligible; ${d.withVector} current vectors)"
                )
            }
            val targetNames = store.activeMemoryTargetNames()
            val typeNames = memoryTypes.associate { it.first to it.second }
            val retrievedRecords = retrieved.mapNotNull { hit -> store.getMemory(hit.memory.memoryId) }
            val existingContext = retrieved.map { hit ->
                ArchivistExistingMemory(
                    stableId = hit.memory.memoryId,
                    content = hit.memory.content,
                    scope = hit.memory.scope,
                    targetNames = targetNames[hit.memory.memoryId].orEmpty(),
                    typeName = hit.memory.typeId?.let { typeNames[it] }
                )
            }
            val earlierMemoryCandidates = ArchivistCandidateBoundary.collect(allEarlier.map { it.memories })
                .memories.map { draft -> earlierCandidateForPrompt(store, typeNames, draft) }
            val earlierRuleCandidates = allEarlier
                .flatMap { it.rules }
                .distinctBy { MemoryMatch.normalizeContent(it.text) }
                .map {
                    ArchivistEarlierCandidate(
                        content = it.text, scope = null, targetNames = emptyList(),
                        typeName = null, tags = emptyList(), stream = "model_rule"
                    )
                }
            val builtProtocol = ArchivistRuntimeProtocol.create(
                scene = scene,
                existingMemories = existingContext,
                validTargets = validTargetCatalog(store, scene, retrievalScope, retrievedRecords),
                earlierCandidates = earlierMemoryCandidates + earlierRuleCandidates
            )
            protocol = builtProtocol
            // Request order for provider caching (Stage 6A): the fixed
            // instructions, Types and Analysis Note form the system prompt,
            // which stays the same for every request of this conversation;
            // the changing protocol (retrieved memories, working drafts,
            // targets) follows the conversation in the user message.
            requestSystemPrompt = ArchivistPrompt.withCurrentTypes(conversationBasePrompt, memoryTypes)
        }
        val protocolText = protocol?.let { "\n" + ArchivistRuntimeProtocol.render(it) }.orEmpty()

        val requestHeadroom = ArchivistRequestBudget.headroomTokens(requestSystemPrompt + protocolText)
        val safeTranscriptTarget = ArchivistRequestBudget.effectiveMaximumTokens(
            // The user maximum is a preference; this check enforces actual
            // model capacity only.
            configuredMaximum = Int.MAX_VALUE,
            verifiedContextTokens = verifiedContextTokens,
            requestHeadroomTokens = requestHeadroom
        )
        var renderedTranscriptTokens = IncludeTextPolicy.estimateTokens(rendered.text)
        if (verifiedContextTokens != null && renderedTranscriptTokens > safeTranscriptTarget) {
            // A tight request gives up the earlier context first; the new
            // section is always sent whole.
            rendered = ArchivistPrompt.userMessage(chatName, companionName, rows)
            renderedTranscriptTokens = IncludeTextPolicy.estimateTokens(rendered.text)
        }
        if (verifiedContextTokens != null && renderedTranscriptTokens > safeTranscriptTarget) {
            throw ArchivistShrinkRequiredException(
                nextTargetTokens = safeTranscriptTarget,
                message = "verified request headroom requires a smaller transcript chunk"
            )
        }

        return try {
            if (lorebookMode) {
                val raw = modelTransport.complete(
                    ArchivistModelRequest(
                        systemPrompt = requestSystemPrompt,
                        conversationData = rendered.text,
                        model = model,
                        temperature = temperature,
                        attempt = 1
                    )
                )
                val parsed = try {
                    ArchivistResponseParser.parseLore(raw)
                } catch (e: Exception) {
                    throw TaggedArchivistException(ArchivistFailure.UNREADABLE, e)
                }
                if (parsed.dropped > 0) {
                    MemoryLog.log(context, "Archivist", "warn",
                        "chat=$chatId: ${parsed.dropped} lore proposal(s) failed validation and were dropped")
                }
                ParsedChunk(loreEntries = parsed.entries)
            } else {
                val parsed = ArchivistRequestExecutor.associativePrepared(
                    systemPrompt = requestSystemPrompt,
                    protocol = checkNotNull(protocol),
                    conversationData = rendered.text + protocolText,
                    model = model,
                    temperature = temperature,
                    transport = modelTransport
                )
                if (parsed.dropped > 0) {
                    MemoryLog.log(context, "Archivist", "warn",
                        "chat=$chatId: ${parsed.dropped} proposal(s) failed validation and were dropped")
                }
                ParsedChunk(memories = parsed.memories, rules = parsed.rules)
            }
        } catch (e: Exception) {
            if (e is ArchivistClearlyTruncatedException ||
                ArchivistRetryPolicy.isVerifiedContextRejection(e, capturedErrorBody)
            ) {
                throw ArchivistShrinkRequiredException(
                    nextTargetTokens = maxOf(
                        ArchivistRequestBudget.MIN_RUNTIME_TRANSCRIPT_TOKENS,
                        requestChunk.estimatedTranscriptTokens / 2
                    ),
                    message = if (e is ArchivistClearlyTruncatedException) {
                        "Archivist response was clearly truncated"
                    } else {
                        "provider rejected the request context size"
                    },
                    cause = e
                )
            }
            throw e
        }
    }

    private fun stagedCandidates(chunk: ParsedChunk): List<StagedAnalysisCandidate> {
        val out = ArrayList<StagedAnalysisCandidate>()
        val memories = ArchivistCandidateBoundary.collectFlat(chunk.memories).memories
        for (draft in memories) {
            val targetId = draft.targetIds.firstOrNull()
                ?: draft.scene?.targetIdFor(draft.scope)
            out.add(
                StagedAnalysisCandidate(
                    stream = "memory",
                    targetType = draft.scope.takeIf {
                        it in setOf("companion", "project", "world", "campaign", "rp_character")
                    },
                    targetId = targetId,
                    candidateHash = ArchivistCandidateBoundary.candidateHash(draft),
                    payloadJson = ArchivistCandidateBoundary.payload(draft)
                )
            )
        }
        for (rule in chunk.rules.distinctBy { MemoryMatch.normalizeContent(it.text) }) {
            out.add(
                StagedAnalysisCandidate(
                    stream = "model_rule",
                    targetType = null,
                    targetId = null,
                    candidateHash = ArchivistCandidateBoundary.ruleHash(rule),
                    payloadJson = ArchivistCandidateBoundary.payload(rule)
                )
            )
        }
        // Lorebook entries are staged too, so a paused or interrupted
        // Lorebook scan resumes with the entries it already found.
        for (entry in chunk.loreEntries.distinctBy { it.content.trim() }) {
            out.add(
                StagedAnalysisCandidate(
                    stream = "lore",
                    targetType = null,
                    targetId = null,
                    candidateHash = ArchivistCandidateBoundary.loreHash(entry),
                    payloadJson = ArchivistCandidateBoundary.payload(entry)
                )
            )
        }
        return out
    }

    /** The response observer can hold a more specific provider body than the
     * client exception exposes. Prefer its classified limit kind when present
     * so a final bounded context failure is reported as request-too-large, not
     * an unknown local error. */
    private fun recordRequestProviderFailure(
        context: Context,
        endpoint: ApiEndpointObject,
        model: String,
        error: Throwable,
        responseBody: String?,
        privateValues: List<String>
    ) {
        val classified = classifyGenerationFailure(error, responseBody)
        if (!classified.reachedServer() || !Preferences.getPreferences(context, "").getLogChatFailures()) return
        val raw = org.teslasoft.assistant.util.summarizer.SummarizerDetailSanitizer.sanitize(
            responseBody?.takeIf { it.isNotBlank() } ?: error.message, privateValues + endpoint.apiKey
        ) ?: "Provider returned no error message"
        val provider = ProviderErrorInfo.parse(raw)
        try {
            org.teslasoft.assistant.preferences.Logger.logProviderFailure(
                context, endpoint.label, provider.providerName ?: "Not Reported", model,
                context.getString(R.string.mem_arch_function_archiving), raw,
                outerHttpStatus = classified.httpStatus
            )
        } catch (_: Exception) { /* A diagnostic failure must not replace the request failure. */ }
    }

    private fun classifyGenerationFailure(error: Throwable, providerBody: String?): GenErrorResult {
        val direct = GenerationErrorClassifier.classify(error)
        val body = providerBody?.takeIf { it.isNotBlank() }?.let {
            GenerationErrorClassifier.classify(IllegalStateException(it))
        }
        return if (body?.providerLimit != null) body else direct
    }

    private fun earlierCandidateForPrompt(
        store: MemoryStore,
        typeNames: Map<String, String>,
        draft: ArchivistResponseParser.DraftMemory
    ): ArchivistEarlierCandidate {
        val ids = draft.targetIds.ifEmpty {
            listOfNotNull(draft.scene?.targetIdFor(draft.scope))
        }.toSet()
        val names = when (draft.scope) {
            "companion" -> store.getCompanions()
                .filter { it.companionId in ids }.map { it.currentName }
            "world" -> store.getAllWorlds()
                .filter { it.worldId in ids }.map { it.name }
            "campaign" -> store.getCampaigns()
                .filter { it.campaignId in ids }.map { it.name }
            "rp_character" -> store.getAllRoleplayCharacters()
                .filter { it.roleplayCharacterId in ids }.map { it.name }
            "project" -> store.getProjects()
                .filter { it.projectId in ids }.map { it.name }
            else -> emptyList()
        }
        return ArchivistEarlierCandidate(
            content = draft.content,
            scope = draft.scope,
            targetNames = names,
            typeName = draft.typeIdSuggestion?.let { typeNames[it] },
            tags = draft.tags
        )
    }

    /** Build reconciliation eligibility from this chunk's stamped scene, not
     * from any earlier row in the chat. The same owner-approved scope gate the
     * Librarian uses for live retrieval remains authoritative; only the live
     * delivery quotas/cooldown/priority are bypassed. */
    private fun reconciliationScope(
        store: MemoryStore,
        prefs: Preferences,
        scene: ArchivistSceneContext
    ): RetrievalScope {
        val companion = scene.companionId?.let { store.getCompanion(it) }
        val eligibleCompanionId = companion
            ?.takeIf { it.status != "draft" && it.memoryParticipation == "full" }
            ?.companionId
        val campaign = scene.campaignId?.let { store.getCampaign(it) }
        val narratorMatch = campaign?.companionId != null &&
            campaign.companionId == eligibleCompanionId
        val allowCompanion = narratorMatch || prefs.getAllowCompanionMemoriesInRoleplay()
        return RetrievalScope(
            companionId = eligibleCompanionId,
            worldId = scene.worldId,
            campaignId = scene.campaignId,
            roleplayCharacterId = scene.roleplayCharacterId,
            allowCompanionInRoleplay = allowCompanion
        )
    }

    /** Only targets stamped on this scene can be referenced by this request.
     * Existing lifecycle status is included as data; a missing/deleted target
     * is absent and therefore cannot be normalized by the parser. */
    private fun validTargetCatalog(
        store: MemoryStore,
        scene: ArchivistSceneContext,
        retrievalScope: RetrievalScope,
        existingMemories: List<org.teslasoft.assistant.preferences.memory.MemoryRecord>
    ): List<ArchivistTarget> {
        val available = ArrayList<ArchivistTarget>()
        store.getCompanions().forEach {
            available.add(ArchivistTarget(it.companionId, "companion", it.currentName, it.status))
        }
        store.getAllWorlds().forEach {
            available.add(ArchivistTarget(it.worldId, "world", it.name, it.status))
        }
        store.getCampaigns().forEach {
            available.add(ArchivistTarget(it.campaignId, "campaign", it.name, it.status))
        }
        store.getAllRoleplayCharacters().forEach {
            available.add(
                ArchivistTarget(it.roleplayCharacterId, "rp_character", it.name, it.status)
            )
        }
        store.getProjects().forEach {
            available.add(ArchivistTarget(it.projectId, "project", it.name, it.status))
        }
        val relevantTargetIds = mapOf(
            "world" to existingMemories.flatMap { it.worldIds }.toSet(),
            "campaign" to existingMemories.flatMap { it.campaignIds }.toSet(),
            "rp_character" to existingMemories.flatMap { it.roleplayCharacterIds }.toSet(),
            "project" to existingMemories.flatMap { it.projectIds }.toSet()
        )
        return ArchivistTargetCatalog.select(
            scene = scene,
            eligibleCompanionId = retrievalScope.companionId,
            relevantTargetIds = relevantTargetIds,
            availableTargets = available
        )
    }

    /**
     * File the run's proposed lore book entries as pending suggestions (Step
     * 1.7, Lorebook Memories analysis type). Mirrors [prepareMemoryDrafts]'s
     * durability rules: an identical pending suggestion from the same chat is
     * skipped (content dedup, so an interrupted-then-rerun conversation never
     * doubles up), and a suggestion the user already deleted from this chat is
     * not refiled (rejection dedup). Returns how many candidates were skipped
     * as duplicates. A store insert failure aborts the conversation as reason E
     * (save failed), exactly like the memory path.
     */
    private fun prepareLorebookSuggestions(
        context: Context,
        store: MemoryStore,
        conversation: Conversation,
        runId: String,
        entries: List<ArchivistResponseParser.DraftLoreEntry>,
        maxSuggestions: Int
    ): PreparedLore {
        if (entries.isEmpty()) return PreparedLore(emptyList(), 0)
        var duplicates = 0
        val now = Instant.now().toString()
        val records = ArrayList<LorebookSuggestionRecord>()
        val stagedContent = HashSet<String>()
        for ((index, e) in entries.withIndex()) {
            if (maxSuggestions > 0 && records.size >= maxSuggestions) {
                MemoryLog.log(
                    context, "Archivist", "info",
                    "chat=${conversation.chatId}: cap $maxSuggestions reached, ${entries.size - index} lore suggestion(s) not filed"
                )
                break
            }
            if (store.lorebookSuggestionExists(e.content, conversation.chatId) ||
                !stagedContent.add(e.content)
            ) { duplicates++; continue }
            if (store.isLorebookSuggestionRejected(e.content, conversation.chatId)) {
                MemoryLog.log(context, "Archivist", "info",
                    "chat=${conversation.chatId}: previously rejected lore suggestion not refiled")
                continue
            }
            val record = LorebookSuggestionRecord(
                suggestionId = MemoryStore.newId("ls-"),
                runId = runId,
                content = e.content,
                triggers = e.triggers,
                sourceChatId = conversation.chatId,
                sourceChatName = conversation.chatName,
                assignedLorebookId = null,
                createdAt = now
            )
            records.add(record)
        }
        return PreparedLore(records, duplicates)
    }

    /** Returns how many candidates were skipped as duplicates of memories
     *  that already exist ("Archivist found only memories that already
     *  exist" needs the distinction). A store insert failure aborts the
     *  conversation as reason E (save failed). */
    private fun prepareMemoryDrafts(
        context: Context,
        store: MemoryStore,
        conversation: Conversation,
        drafts: List<ArchivistResponseParser.DraftMemory>,
        maxSuggestions: Int
    ): PreparedMemories {
        if (drafts.isEmpty()) return PreparedMemories(emptyList(), emptyMap(), 0)
        val collected = ArchivistCandidateBoundary.collectFlat(drafts)
        var duplicates = collected.exactDuplicatesRemoved
        val tokenReader = org.teslasoft.assistant.preferences.memory.librarian.EmbeddingTokenCounter.reader(context)
        val finalParts = StagedMemoryParts(tokenReader)
        val records = finalParts.records
        val relationshipHints = finalParts.relationshipHints
        val sources = linkedMapOf<String, List<org.teslasoft.assistant.preferences.memory.MemorySource>>()
        val stagedIdentities = HashSet<String>()
        var acceptedProposals = 0
        // The current user-owned Type ids: a suggestion is honored only if it
        // names one of these, otherwise the memory files as No Type (never
        // dropped). Legacy `kind` is not consulted.
        val validTypeIds = store.getMemoryTypes().map { it.typeId }.toHashSet()
        val worlds = store.getAllWorlds()
        val roleplayCharacters = store.getAllRoleplayCharacters()
        val campaigns = store.getCampaigns()
        val projects = store.getProjects()
        val companions = store.getCompanions()
        val worldIdsNow = worlds.map { it.worldId }.toSet()
        val roleplayCharacterIdsNow = roleplayCharacters
            .map { it.roleplayCharacterId }.toSet()
        val campaignIdsNow = campaigns.map { it.campaignId }.toSet()
        val projectIdsNow = projects.map { it.projectId }.toSet()
        val companionIdsNow = companions.map { it.companionId }.toSet()
        for ((index, d) in collected.memories.withIndex()) {
            if (maxSuggestions > 0 && acceptedProposals >= maxSuggestions) {
                MemoryLog.log(
                    context, "Archivist", "info",
                    "chat=${conversation.chatId}: cap $maxSuggestions reached, ${collected.memories.size - index} draft(s) not filed"
                )
                break
            }
            // Resolve placement once: the target sets are both the Possible
            // Match identity (scope + sorted target IDs) and the record's links.
            val worldIds = resolvedDraftTargets(d, "world") {
                worlds.map { it.worldId to it.name }
            }.filter { it in worldIdsNow }
            val rpCharIds = resolvedDraftTargets(d, "rp_character") {
                roleplayCharacters.map { it.roleplayCharacterId to it.name }
            }.filter { it in roleplayCharacterIdsNow }
            val campaignIds = resolvedDraftTargets(d, "campaign") {
                campaigns.map { it.campaignId to it.name }
            }.filter { it in campaignIdsNow }
            val projectIds = resolvedDraftTargets(d, "project") {
                projects.map { it.projectId to it.name }
            }.filter { it in projectIdsNow }
            val suppliedCompanionIds = resolvedDraftTargets(d, "companion") {
                companions.map { it.companionId to it.currentName }
            }
            val intendedCompanionId = d.scene?.companionId
                ?: suppliedCompanionIds.singleOrNull()
            val companionIds = if (d.scope == "companion") {
                when {
                    suppliedCompanionIds.isNotEmpty() -> suppliedCompanionIds
                    d.scene == null && intendedCompanionId != null -> listOf(intendedCompanionId)
                    else -> emptyList()
                }
            } else emptyList()
            // The user-owned Type is the source of truth (§5): honor the model's
            // suggested stable Type id only when it names a current Type; anything
            // else (absent or unknown) becomes No Type. Legacy `kind` is never
            // consulted or stored for a new memory (Phase 2 review).
            val resolvedTypeId = d.typeIdSuggestion?.takeIf { it in validTypeIds }
            // Step 1.5 staging gate (counterplan §5.2(b)): only an exact
            // normalized match with the same placement AND the same Type, on an
            // active or pending memory, is a true duplicate that must not create
            // a second draft. Everything else — a different Type, an archived or
            // superseded exact match, or a semantic near-match — is filed and
            // surfaces as a Possible Match at review time. Type identity is the
            // stable type_id (the source of truth), placement-aware and
            // title-independent.
            val candidate = MemoryMatch.Candidate(
                content = d.content,
                scope = d.scope,
                typeId = resolvedTypeId,
                targetIds = worldIds + rpCharIds + campaignIds + projectIds + companionIds
            )
            val stagedIdentity = listOf(
                MemoryMatch.normalizeContent(candidate.content),
                MemoryMatch.placementKey(candidate.scope, candidate.targetIds),
                candidate.typeId.orEmpty()
            ).joinToString("\u0000")
            if (store.classifyCandidate(candidate) is MemoryMatch.Outcome.AlreadyPresent ||
                !stagedIdentities.add(stagedIdentity)
            ) {
                duplicates++; continue
            }
            // A draft the user deleted is a rejection (owner preference,
            // July 9 2026): the exact same draft is not refiled on rerun. The
            // rejection is keyed on the memory CONTENT only (canonical recovery
            // plan §3.2 / item 1): a memory never remembers which chat produced
            // it, so the dedup no longer carries source-chat identity.
            if (store.isDraftRejected(d.content)) {
                MemoryLog.log(context, "Archivist", "info",
                    "chat=${conversation.chatId}: previously rejected draft not refiled")
                continue
            }
            // Build a VALIDATED candidate and file it through the one canonical
            // Pending filing service (Phase 2, items 5/6/8). The Memory Assistant
            // is just one transport wrapper: it resolves scope/targets/Type above,
            // then converges on the same validated candidate and the same filer
            // the computer-import and manual paths use. The candidate carries no
            // importance (the factory always files a proposal at 0), no source
            // authorship, and no provenance — the memory keeps none of them.
            val filingResult: CandidateResult<out MemoryCandidate> = if (d.scope == SCOPE_COMPANION) {
                MemoryCandidateValidator.validateCompanion(
                    content = d.content,
                    companionTargetIds = companionIds,
                    intendedCompanionId = intendedCompanionId,
                    availableCompanionIds = companionIdsNow,
                    typeId = resolvedTypeId,
                    tags = d.tags
                )
            } else {
                MemoryCandidateValidator.validateGeneral(
                    scope = d.scope,
                    content = d.content,
                    typeId = resolvedTypeId,
                    tags = d.tags,
                    worldIds = worldIds,
                    campaignIds = campaignIds,
                    roleplayCharacterIds = rpCharIds,
                    projectIds = projectIds
                )
            }
            when (filingResult) {
                is CandidateResult.Invalid -> {
                    // A malformed candidate (e.g. a companion-scoped draft with no
                    // resolvable companion target) is quarantined, never filed as
                    // the wrong kind of memory (item 5).
                    MemoryLog.log(
                        context, "Archivist", "info",
                        "chat=${conversation.chatId}: candidate quarantined (${filingResult.error}) — not filed"
                    )
                }
                is CandidateResult.Valid -> {
                    val record =
                        org.teslasoft.assistant.preferences.memory.PendingMemoryRecordFactory.build(
                            filingResult.candidate,
                            org.teslasoft.assistant.preferences.memory.MemoryId
                                .generate(org.teslasoft.assistant.preferences.memory.MemoryId.Type.ASSOCIATIVE),
                            MemoryStore.nowIso()
                        )
                    // Apply the suggestion cap to original proposals. All parts
                    // of an accepted proposal are filed, with no second cap.
                    val beforeParts = records.size
                    val parts = if (tokenReader == null) {
                        listOf(org.teslasoft.assistant.preferences.memory.librarian.MemoryProposalSplitter.Part(
                            record.content, d.tags))
                    } else {
                        org.teslasoft.assistant.preferences.memory.librarian.MemoryProposalSplitter.split(
                            record.content, d.tags, tokenReader)
                    }
                    for ((partIndex, part) in parts.withIndex()) {
                        val splitRecord = record.copy(
                            memoryId = if (partIndex == 0) record.memoryId else
                                org.teslasoft.assistant.preferences.memory.MemoryId.generate(
                                    org.teslasoft.assistant.preferences.memory.MemoryId.Type.ASSOCIATIVE),
                            content = part.content,
                            tagsJson = org.json.JSONArray(part.tags).toString()
                        )
                        // Re-running a conversation must not duplicate a part
                        // already filed, or resurrect a part the user rejected.
                        // Unsplit proposals already passed this same gate above.
                        if (parts.size > 1) {
                            val partCandidate = candidate.copy(content = splitRecord.content)
                            if (store.classifyCandidate(partCandidate) is MemoryMatch.Outcome.AlreadyPresent ||
                                store.isDraftRejected(splitRecord.content)) {
                                duplicates++
                                continue
                            }
                        }
                        val validHints = d.relatedExistingMemoryIds
                            .filter { it != splitRecord.memoryId && store.getMemory(it) != null }.distinct()
                        if (!finalParts.add(splitRecord, validHints)) {
                            duplicates++
                            continue
                        }
                        sources[splitRecord.memoryId] = listOf(
                            org.teslasoft.assistant.preferences.memory.MemorySourceRules.conversationOnly(
                                MemoryStore.newId(org.teslasoft.assistant.preferences.memory.MemorySource.ID_PREFIX),
                                splitRecord.memoryId, conversation.chatId, MemoryStore.nowIso()
                            )
                        )
                    }
                    if (records.size > beforeParts) acceptedProposals++
                }
            }
        }
        return PreparedMemories(records, relationshipHints, duplicates, sources)
    }

    /** Production requests use only stable ids normalized from that exact
     * scene request's supplied target catalog. This preserves a validated
     * relevant Project/roleplay target as well as the directly stamped target;
     * it never falls back to a different row in the chat. The name-match branch
     * remains only for legacy/parser-only callers. */
    private fun resolvedDraftTargets(
        d: ArchivistResponseParser.DraftMemory,
        scope: String,
        candidates: () -> List<Pair<String, String>>
    ): List<String> {
        if (d.scope != scope) return emptyList()
        if (d.scene != null) {
            // Production Stage-C responses already contain stable ids resolved
            // from this request's bounded catalog and matching this scope.
            return d.targetIds.distinct()
        }
        // Compatibility for parser-only/legacy callers that did not receive a
        // runtime protocol. Production requests never use display-name guessing.
        val name = d.targetName ?: return emptyList()
        return candidates()
            .filter { it.second.equals(name, ignoreCase = true) }
            .map { it.first }
            .take(1)
    }

    private fun prepareRuleDrafts(
        context: Context,
        store: MemoryStore,
        sourceModel: String?,
        drafts: List<ArchivistResponseParser.DraftRule>
    ): List<ModelRuleRecord> {
        if (drafts.isEmpty()) return emptyList()
        val existing = store.getModelRules()
            .map { MemoryMatch.normalizeContent(it.text) }.toHashSet()
        val records = ArrayList<ModelRuleRecord>()
        for (d in drafts) {
            val identity = MemoryMatch.normalizeContent(d.text)
            if (!existing.add(identity)) continue
            // Validate through the Model Rule candidate path (review finding 2):
            // a valid Draft needs only nonblank text — the model list stays empty
            // until the user assigns it on approval (§11). A blank-text draft is
            // rejected here, never filed. Model Rules never touch Associative
            // Memory filing.
            val validated = MemoryCandidateValidator.validateModelRule(d.text, sourceModelString = sourceModel)
            if (validated is CandidateResult.Invalid) {
                MemoryLog.log(context, "Archivist", "info", "rule draft rejected (${validated.error}) — not filed")
                continue
            }
            val candidate = (validated as CandidateResult.Valid).candidate
            val rule = ModelRuleRecord(
                ruleId = MemoryStore.newId("mr_"),
                text = candidate.text,
                // §11 Revision 6: the user assigns exact endpoint/model targets
                // on accept. A Draft is filed with no exact or legacy target.
                modelStringsJson = "[]",
                status = "draft",
                sourceModelString = candidate.sourceModelString,
                createdAt = Instant.now().toString(),
                updatedAt = null
            )
            records.add(rule)
        }
        return records
    }

    private fun buildClient(
        endpoint: ApiEndpointObject,
        providerRouting: com.google.gson.JsonObject?
    ): OpenAI {
        // Same auth handling as the chat funnel: token only for bearer auth,
        // alternate header modes carry the key themselves (double auth 4xx's
        // at some providers).
        val isBearerAuth = endpoint.authType == ApiEndpointObject.AUTH_BEARER
        val extraHeaders: Map<String, String> = when (endpoint.authType) {
            ApiEndpointObject.AUTH_X_API_KEY -> mapOf("x-api-key" to endpoint.apiKey)
            ApiEndpointObject.AUTH_API_KEY -> mapOf("api-key" to endpoint.apiKey)
            else -> emptyMap()
        }
        return OpenAI(
            OpenAIConfig(
                token = if (isBearerAuth) endpoint.apiKey else "",
                logging = LoggingConfig(LogLevel.None, Logger.Simple),
                // Analysis reads whole conversations and answers with one
                // large JSON object — allow far more than the chat turn's 30s.
                timeout = Timeout(socket = 180.seconds),
                organization = null,
                headers = extraHeaders,
                host = OpenAIHost(composeChatHost(endpoint.host, endpoint.chatEndpoint)),
                proxy = null,
                retry = RetryStrategy(),
                // Capture the raw error-response body of a failed request (error
                // responses only — a success is never buffered) so the failure
                // surface can name the upstream provider and quote the server's
                // message, the same way the chat funnel does.
                httpClientConfig = {
                    install(ResponseObserver) {
                        filter { call -> !call.response.status.isSuccess() }
                        onResponse { response ->
                            try {
                                capturedErrorBody = response.bodyAsText()
                            } catch (_: Exception) { /* best effort; never disturb the failure path */ }
                        }
                    }
                    if (endpoint.isOpenRouterRouting() && providerRouting != null) {
                        // Memory Assistant sends one or many chat-completion
                        // chunks through this client. Attach the same resolved
                        // feature-specific provider object to every chunk.
                        install(createClientPlugin("MemoryAssistantProviderRouting") {
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

    private fun liveChatNamesById(context: Context): Map<String, String> {
        val out = HashMap<String, String>()
        for (chat in ChatPreferences.getChatPreferences().getChatList(context)) {
            val name = chat["name"] ?: continue
            out[ChatPreferences.storedChatId(chat)] = name
        }
        return out
    }

    private fun listToJson(items: List<String>): String {
        val arr = JSONArray()
        for (s in items) arr.put(s)
        return arr.toString()
    }

    private fun jsonToList(json: String): List<String> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
    } catch (_: Exception) {
        emptyList()
    }
}
