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
import org.json.JSONArray
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.memory.ArchivistRunRecord
import org.teslasoft.assistant.preferences.memory.CandidateResult
import org.teslasoft.assistant.preferences.memory.FrozenChatRange
import org.teslasoft.assistant.preferences.memory.FrozenChatRangeExecutor
import org.teslasoft.assistant.preferences.memory.LorebookSuggestionRecord
import org.teslasoft.assistant.preferences.memory.MemoryCandidate
import org.teslasoft.assistant.preferences.memory.MemoryCandidateValidator
import org.teslasoft.assistant.preferences.memory.MemoryLog
import org.teslasoft.assistant.preferences.memory.MemoryMatch
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.ModelRuleRecord
import org.teslasoft.assistant.preferences.memory.SCOPE_COMPANION
import org.teslasoft.assistant.preferences.memory.StagedAnalysisCandidate
import org.teslasoft.assistant.preferences.memory.TranscriptRecord
import org.teslasoft.assistant.preferences.memory.RetrievalScope
import org.teslasoft.assistant.preferences.memory.librarian.Librarian
import org.teslasoft.assistant.R
import org.teslasoft.assistant.providers.DedicatedModelRoutingPolicy
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
 * The Archivist run engine (Phase 6 backend). Reads finished conversations
 * from the transcript queue, asks the Archivist model what is worth
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

    /** One chat's worth of pending transcripts — the unit of analysis and of
     *  the "Conversation N of M" progress. */
    data class Conversation(
        val chatId: String,
        val chatName: String,
        val transcripts: List<TranscriptRecord>
    )

    private data class ParsedChunk(
        val memories: List<ArchivistResponseParser.DraftMemory> = emptyList(),
        val rules: List<ArchivistResponseParser.DraftRule> = emptyList(),
        val loreEntries: List<ArchivistResponseParser.DraftLoreEntry> = emptyList()
    )

    private data class PreparedMemories(
        val records: List<org.teslasoft.assistant.preferences.memory.MemoryRecord>,
        val relationshipHintsByMemoryId: Map<String, List<String>>,
        val duplicatesSkipped: Int
    )

    private data class PreparedLore(
        val records: List<LorebookSuggestionRecord>,
        val duplicatesSkipped: Int
    )

    private data class ChatCommit(
        val memoryIds: List<String>,
        val ruleIds: List<String>,
        val duplicatesSkipped: Int
    )

    data class RunOutcome(
        val runId: String?,
        val conversationsSelected: Int,
        val conversationsAnalyzed: Int,
        val memoriesFound: Int,
        val ruleDraftsFound: Int,
        /** Chats whose analysis failed this run; their transcripts stay
         *  pending and are picked up by the next run. */
        val failedChatIds: List<String>,
        /** Display outcome per archivist_status_wording_spec.md: completed |
         *  full_failed | partial_failed | nothing | no_new | interrupted |
         *  not_configured — plus already_running (DB v17): a start that lost
         *  the one-live-run gate; nothing was claimed, written, or recorded. */
        val outcome: String,
        /** Dominant failure category when (partially) failed — picks the
         *  on-screen reason sentence and the action button. */
        val failureReason: ArchivistFailure? = null,
        /** Candidates skipped because an identical memory already exists —
         *  distinguishes "found only memories that already exist" from
         *  "did not find anything new". */
        val duplicatesSkipped: Int = 0,
        /** Assistant transcript turns excluded from analysis because Round 3
         *  marked them `complete:false` (a truncated fragment is never mined as
         *  fact; the user turn beside it is still sent). In-memory run
         *  diagnostic only — not persisted, logged, or shown. */
        val incompleteTurnsExcluded: Int = 0,
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
        val failureCategoryCounts: Map<String, Int> = emptyMap()
    ) {
        val notConfigured: Boolean get() = outcome == "not_configured"
    }

    /**
     * Live progress for the Memory Assistant's running state. The approved
     * surface (Memory System/plan_one_page.md) shows a spinner and
     * "Analyzing Conversations", then a determinate bar and "X%" computed from
     * [overallIndex]/[overallCount] once the fixed total is known. The batch
     * fields below remain the internal size-grouping bookkeeping (every
     * conversation still gets its own model call(s); see [ArchivistBatchPlanner])
     * and are no longer presented to the user.
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
        val overallCount: Int = 0
    )

    /** Bookmark-led eligibility for chats that still exist in app storage.
     * Deleted conversations do not count; claim state temporarily seals an
     * in-flight range, while legacy transcript review columns are not gates. */
    fun eligibleConversations(context: Context): List<Conversation> {
        if (!MemoryStore.isProvisioned(context)) return emptyList()
        val liveChats = liveChatNamesById(context)
        return MemoryStore.getInstance(context).bookmarkEligibleTranscripts()
            .filter { it.chatId != null && liveChats.containsKey(it.chatId) }
            .groupBy { it.chatId!! }
            .map { (chatId, rows) -> Conversation(chatId, liveChats[chatId] ?: chatId, rows) }
            .sortedBy { it.transcripts.first().startedAt ?: "" }
    }

    /** The Memory Assistant facts line "Total conversations since last run" —
     *  by owner decision this is the currently-eligible set (what the button
     *  would analyze next), NOT anything keyed to backups. */
    fun eligibleConversationCount(context: Context): Int = eligibleConversations(context).size

    /** In-process half of the one-live-run rule (counterplan §4(a)): the
     *  durable 'running' row guards across process death; this guards
     *  concurrent starts inside one process (two screen instances, a rerun
     *  racing an analyze). A losing start returns outcome "already_running"
     *  and touches nothing — claims make an overlap structurally harmless,
     *  but two live runs would still fight over progress bookkeeping. */
    private val liveRun = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Raw error-response body of the most recent failed request in the live
     *  run (Aug 1 2026), captured by the client's ResponseObserver so the
     *  failure surface can name the upstream provider and quote the server's
     *  own message — exactly the chat funnel's approach. One run at a time
     *  (the liveRun gate), reset before each request. */
    @Volatile
    private var capturedErrorBody: String? = null

    /**
     * Startup recovery entry point (counterplan §4(a)): reconcile dead runs
     * behind the same in-process gate the runs use, so a reconcile can never
     * mistake a just-started live run's durable record for a stale one. If a
     * run is already live, recovery already happened at its start — skip.
     * Returns how many dead runs were recovered (0 when skipped).
     */
    fun reconcileAtStartup(context: Context): Int {
        if (!MemoryStore.isProvisioned(context)) return 0
        if (!liveRun.compareAndSet(false, true)) return 0
        return try {
            MemoryStore.getInstance(context).reconcileInterruptedAnalysisRuns()
        } finally {
            liveRun.set(false)
        }
    }

    /** Analyze every currently-eligible conversation (the user may queue any
     *  number — owner answer 4; size batching happens inside). Eligibility is
     *  re-derived and atomically claimed inside the run, after stale-run
     *  reconciliation, so the run analyzes exactly what it sealed. */
    suspend fun analyze(
        context: Context,
        analysisType: String,
        onProgress: (Progress) -> Unit
    ): RunOutcome =
        run(context, { eligibleConversations(context) }, markProcessed = true,
            analysisType = analysisType, onProgress = onProgress)

    /** Re-analyze a past run's conversations (the Rerun row action): re-feeds
     *  exactly the transcript rows that run stored, for chats that still
     *  exist. Files any NEW findings as drafts (existing identical drafts are
     *  deduplicated); records a fresh run row. Rerun rows are already
     *  processed, so nothing is claimed or re-marked. */
    suspend fun rerun(
        context: Context,
        runId: String,
        analysisType: String,
        onProgress: (Progress) -> Unit
    ): RunOutcome {
        val store = MemoryStore.getInstance(context)
        val past = store.getArchivistRun(runId)
            ?: return RunOutcome(
                null, 0, 0, 0, 0, emptyList(),
                outcome = "full_failed", failureReason = ArchivistFailure.UNKNOWN,
                error = "run not found",
                analysisType = analysisType
            )
        val ids = jsonToList(past.transcriptIdsJson)
        // A rerun always re-runs the ORIGINAL run's stored analysis type (owner
        // ruling, Step 1.7): a Lorebook rerun stays Lorebook even if the picker
        // now shows Associative, and vice versa — a rerun is never silently
        // converted. The passed-in type is only the display fallback for the
        // run-not-found case above.
        return run(context, {
            val liveChats = liveChatNamesById(context)
            store.transcriptsByIds(ids)
                .filter { it.chatId != null && liveChats.containsKey(it.chatId) }
                .groupBy { it.chatId!! }
                .map { (chatId, rows) -> Conversation(chatId, liveChats[chatId] ?: chatId, rows) }
        }, markProcessed = false, analysisType = past.analysisType, onProgress = onProgress)
    }

    private suspend fun run(
        context: Context,
        selectConversations: () -> List<Conversation>,
        markProcessed: Boolean,
        analysisType: String,
        onProgress: (Progress) -> Unit
    ): RunOutcome {
        val prefs = Preferences.getPreferences(context, "")
        val endpointId = prefs.getArchivistEndpointId()
        val endpoint = if (endpointId.isBlank()) null
        else ApiEndpointPreferences.getApiEndpointPreferences(context).getApiEndpoint(context, endpointId)
        if (endpoint == null || endpoint.host.isBlank()) {
            // Always logged (spec): user-relevant recovery information.
            MemoryLog.logAlways(context, "Archivist", "warn",
                "Archivist Not Ready — Memory Archivist needs a model before it can run. " +
                    "Missing: ${if (endpointId.isBlank()) "endpoint profile not selected" else "endpoint host empty"}")
            return RunOutcome(null, 0, 0, 0, 0, emptyList(), outcome = "not_configured", analysisType = analysisType)
        }
        // The Memory Assistant model is an explicit selection. Changing its
        // endpoint clears this value and shows "Select"; never silently fall
        // back to the endpoint profile's ordinary-chat model behind that UI.
        val model = prefs.getArchivistModel()
        if (model.isBlank()) {
            MemoryLog.logAlways(context, "Archivist", "warn",
                "Archivist Not Ready — Memory Archivist needs a model before it can run. Missing: model name")
            return RunOutcome(null, 0, 0, 0, 0, emptyList(), outcome = "not_configured", analysisType = analysisType)
        }

        val routingMode = prefs.getArchivistRoutingType()
        val savedFavorite = FavoriteModelsPreferences.getPreferences(context)
            .getFavorite(model, endpointId)
        if (endpoint.isOpenRouterRouting() &&
            DedicatedModelRoutingPolicy.needsSetup(routingMode, savedFavorite)
        ) {
            MemoryLog.logAlways(context, "Archivist", "warn",
                "Archivist Not Ready — selected provider routing is not configured for the Memory Assistant model")
            return RunOutcome(null, 0, 0, 0, 0, emptyList(), outcome = "not_configured", analysisType = analysisType)
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
            MemoryLog.logAlways(context, "Archivist", "warn",
                "Archivist Not Ready — selected provider routing cannot be satisfied")
            return RunOutcome(null, 0, 0, 0, 0, emptyList(), outcome = "not_configured", analysisType = analysisType)
        }

        // One live run at a time (§4(a)). The in-process gate must be held
        // BEFORE reconciliation: reconciling while another run is live would
        // read its durable 'running' row as stale and release its claims.
        if (!liveRun.compareAndSet(false, true)) {
            MemoryLog.logAlways(context, "Archivist", "warn",
                "duplicate start ignored — an analysis run is already in progress")
            return RunOutcome(null, 0, 0, 0, 0, emptyList(), outcome = "already_running", analysisType = analysisType)
        }
        try {
            return runLocked(
                context, selectConversations, markProcessed, analysisType,
                onProgress, prefs, endpoint, model, routingResolution.providerJson
            )
        } finally {
            liveRun.set(false)
        }
    }

    private suspend fun runLocked(
        context: Context,
        selectConversations: () -> List<Conversation>,
        markProcessed: Boolean,
        analysisType: String,
        onProgress: (Progress) -> Unit,
        prefs: Preferences,
        endpoint: ApiEndpointObject,
        model: String,
        providerRouting: com.google.gson.JsonObject?
    ): RunOutcome {
        val lorebookMode = analysisType == "lorebook"
        val store = MemoryStore.getInstance(context)

        // Recover-at-startup, applied at next-run too (§4(a)): any 'running'
        // API run found now is dead — the gate above proves nothing else is
        // live in this process, and a run never survives its process. Its
        // claims are released so this run can pick those rows up.
        val recovered = store.reconcileInterruptedAnalysisRuns()
        if (recovered > 0) {
            MemoryLog.logAlways(context, "Archivist", "warn",
                "recovered $recovered interrupted run(s) — unfinished conversations were released back to the review queue; nothing unseen was marked processed")
        }

        val startedAt = Instant.now().toString()
        val runId = MemoryStore.newId("run-")

        // Selection + claim seal (§4(a)): the durable 'running' row (the
        // active-run record) and the claim stamps are written in ONE store
        // transaction; the run then analyzes exactly the rows it sealed. A
        // row someone else claimed in the gap simply drops out. Rerun feeds
        // already-processed rows, so it registers the run but claims nothing.
        var conversations = selectConversations()
        val runningRow = ArchivistRunRecord(
            runId = runId,
            startedAt = startedAt,
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
            analysisType = analysisType
        )
        val frozenRanges: Map<String, FrozenChatRange>
        if (markProcessed) {
            frozenRanges = store.beginAnalysisRun(
                runningRow,
                conversations.associate { c -> c.chatId to c.transcripts.map { it.transcriptId } }
            )
            conversations = conversations.mapNotNull { c ->
                frozenRanges[c.chatId]?.let { c.copy(transcripts = it.transcripts) }
            }
        } else {
            frozenRanges = emptyMap()
            store.insertArchivistRun(runningRow)
        }

        val ai = buildClient(endpoint, providerRouting)

        // Memory Assistant tuning (owner spec, July 9 2026): the per-conversation
        // cap is enforced HERE in code — the prompt is never trusted to do it.
        // The old minimum-importance floor is retired (canonical recovery plan
        // §7.2): the Memory Assistant does not assign importance, so an invisible
        // AI rating must not be able to discard proposals. Temperature rides
        // every analysis request (recommended default 0.3); a user-edited
        // extraction prompt replaces the built-in one (Reset clears to built-in).
        val maxSuggestions = prefs.getArchivistMaxSuggestions()
        val temperature = prefs.getArchivistTemperature().toDouble()
        // Each analysis type sends its OWN editable prompt (Step 1.7): the
        // saved prompt for that type, or its built-in default when the saved
        // prompt is empty. The two are kept strictly separate — a Lorebook run
        // never borrows the Associative prompt, because the two require
        // different output schemas. The base text is whatever is shown in the
        // matching field on the Advanced Memory Assistant Settings screen. For the
        // associative type the analyzer contract needs the CURRENT user-owned Type
        // list, so it is appended as a bounded, explicit block (the only run-time
        // addition) — the model can only pick a Type id that actually exists, or
        // omit it for No Type. The lorebook type has no Types and is sent verbatim.
        val basePrompt = if (lorebookMode)
            prefs.getArchivistLorebookPrompt().ifBlank { ArchivistPrompt.LOREBOOK_SYSTEM }
        else
            prefs.getArchivistCustomPrompt().ifBlank { ArchivistPrompt.SYSTEM }
        val memoryTypes = if (lorebookMode) emptyList()
            else store.getMemoryTypes().map { it.typeId to it.name }

        val memoryIds = ArrayList<String>()
        val ruleIds = ArrayList<String>()
        val failedChats = ArrayList<String>()
        val failedReasons = ArrayList<ArchivistFailure>()
        // Chat-funnel classification of each failure and the last captured
        // provider error body, for the precise failure surface (Aug 1 2026).
        val genResults = ArrayList<GenErrorResult>()
        var failureBody: String? = null
        val analyzedChatIds = ArrayList<String>()
        val fedTranscriptIds = ArrayList<String>()
        var duplicatesSkipped = 0
        var incompleteTurnsExcluded = 0
        var runError: String? = null
        var runErrorFailure: ArchivistFailure? = null
        var interrupted = false

        try {
            // Display batches (owner answer 4): size-grouped, presentation
            // only — requests stay per conversation (or per chunk below).
            val batches = ArchivistBatchPlanner.planBatches(
                conversations.map { c -> c.transcripts.sumOf { it.content.length } }
            )
            for ((batchIndex, range) in batches.withIndex()) {
                val batch = conversations.slice(range)
                for ((posInBatch, conversation) in batch.withIndex()) {
                    onProgress(Progress(
                        batchIndex + 1, batches.size, posInBatch + 1, batch.size,
                        overallIndex = range.first + posInBatch + 1,
                        overallCount = conversations.size
                    ))
                    var candidateCollectionId: String? = null
                    try {
                        // A single oversized conversation (the "30 pages" case)
                        // is split across several calls, whole rows at a time,
                        // so one request never overruns the model's context.
                        val sizeChunks = ArchivistBatchPlanner.splitIntoRequests(
                            conversation.transcripts.map { it.content.length }
                        )
                        // Associative requests are also split at captured scene
                        // changes. Lorebook analysis is a separate output path
                        // and keeps its existing size-only request shape.
                        val chunks = if (lorebookMode) sizeChunks else
                            ArchivistScenePlanner.splitAtSceneBoundaries(
                                sizeChunks, conversation.transcripts
                            )
                        if (chunks.size > 1) {
                            MemoryLog.log(context, "Archivist", "info",
                                "chat=${conversation.chatId}: oversized conversation split into ${chunks.size} requests")
                        }
                        if (!lorebookMode) {
                            candidateCollectionId = if (markProcessed) {
                                frozenRanges.getValue(conversation.chatId).rangeId
                            } else {
                                MemoryStore.newId("range-").also {
                                    store.beginCandidateCollection(it, conversation.chatId)
                                }
                            }
                        }
                        val committed = FrozenChatRangeExecutor
                            .executeWithStaged<List<Int>, ParsedChunk, ChatCommit>(
                            chunks = chunks,
                            analyzeChunk = { chunk, earlierChunks ->
                                val rows = chunk.map { conversation.transcripts[it] }
                                val scene = ArchivistSceneContext.from(rows.first())
                                val companionName = scene.companionId
                                    ?.let { store.getCompanion(it)?.currentName }
                                val rendered = ArchivistPrompt.userMessage(
                                    conversation.chatName, companionName, rows
                                )
                                incompleteTurnsExcluded += rendered.incompleteAssistantTurnsDropped

                                val protocol: ArchivistRequestProtocol?
                                val requestSystemPrompt: String
                                if (lorebookMode) {
                                    protocol = null
                                    requestSystemPrompt = basePrompt
                                } else {
                                    val retrievalScope = reconciliationScope(store, prefs, scene)
                                    var retrievalDiag: Librarian.RetrievalDiagnostics? = null
                                    val retrieved = Librarian.getInstance(context)
                                        .searchForReconciliation(
                                            scope = retrievalScope,
                                            queryWindows = ArchivistPrompt.retrievalWindows(rows),
                                            candidateCeiling = ArchivistRuntimeProtocol.MAX_RETRIEVAL_CANDIDATES,
                                            selectedProjectId = scene.projectId
                                                ?.takeIf { !scene.isRoleplay },
                                            diag = { retrievalDiag = it }
                                        )
                                    retrievalDiag?.takeIf { !it.semantic }?.let { d ->
                                        MemoryLog.log(
                                            context, "Archivist", "info",
                                            "chat=${conversation.chatId}: reconciliation used bounded lexical retrieval " +
                                                "(${d.eligible} eligible; ${d.withVector} current vectors)"
                                        )
                                    }

                                    val targetNames = store.activeMemoryTargetNames()
                                    val typeNames = memoryTypes.associate { it.first to it.second }
                                    val retrievedRecords = retrieved.mapNotNull { hit ->
                                        store.getMemory(hit.memory.memoryId)
                                    }
                                    val existingContext = retrieved.map { hit ->
                                        ArchivistExistingMemory(
                                            stableId = hit.memory.memoryId,
                                            content = hit.memory.content,
                                            scope = hit.memory.scope,
                                            targetNames = targetNames[hit.memory.memoryId].orEmpty(),
                                            typeName = hit.memory.typeId?.let { typeNames[it] }
                                        )
                                    }
                                    val earlierMemoryCandidates = ArchivistCandidateBoundary.collect(
                                        earlierChunks.map { it.memories }
                                    ).memories.map { draft ->
                                        earlierCandidateForPrompt(store, typeNames, draft)
                                    }
                                    val earlierRuleCandidates = earlierChunks
                                        .flatMap { it.rules }
                                        .distinctBy { MemoryMatch.normalizeContent(it.text) }
                                        .map {
                                            ArchivistEarlierCandidate(
                                                content = it.text,
                                                scope = null,
                                                targetNames = emptyList(),
                                                typeName = null,
                                                tags = emptyList(),
                                                stream = "model_rule"
                                            )
                                        }
                                    val builtProtocol = ArchivistRuntimeProtocol.create(
                                        scene = scene,
                                        existingMemories = existingContext,
                                        validTargets = validTargetCatalog(
                                            store, scene, retrievalScope, retrievedRecords
                                        ),
                                        earlierCandidates =
                                            earlierMemoryCandidates + earlierRuleCandidates
                                    )
                                    protocol = builtProtocol
                                    requestSystemPrompt = ArchivistPrompt.withRuntimeProtocol(
                                        basePrompt, memoryTypes, builtProtocol
                                    )
                                }
                                // Fresh capture window per request: the observer
                                // writes only on an error response.
                                capturedErrorBody = null
                                val response = ai.chatCompletion(
                                    ChatCompletionRequest(
                                        model = ModelId(model),
                                        messages = listOf(
                                            ChatMessage(role = ChatRole.System, content = requestSystemPrompt),
                                            ChatMessage(role = ChatRole.User, content = rendered.text)
                                        ),
                                        temperature = temperature
                                    )
                                )
                                val raw = response.choices.firstOrNull()?.message?.content.orEmpty()
                                if (lorebookMode) {
                                    val parsed = try {
                                        ArchivistResponseParser.parseLore(raw)
                                    } catch (e: Exception) {
                                        throw TaggedArchivistException(ArchivistFailure.UNREADABLE, e)
                                    }
                                    if (parsed.dropped > 0) {
                                        MemoryLog.log(
                                            context, "Archivist", "warn",
                                            "chat=${conversation.chatId}: ${parsed.dropped} lore proposal(s) failed validation and were dropped"
                                        )
                                    }
                                    ParsedChunk(loreEntries = parsed.entries)
                                } else {
                                    val parsed = try {
                                        ArchivistResponseParser.parse(raw, protocol)
                                    } catch (e: Exception) {
                                        throw TaggedArchivistException(ArchivistFailure.UNREADABLE, e)
                                    }
                                    if (parsed.dropped > 0) {
                                        MemoryLog.log(
                                            context, "Archivist", "warn",
                                            "chat=${conversation.chatId}: ${parsed.dropped} proposal(s) failed validation and were dropped"
                                        )
                                    }
                                    val parsedChunk = ParsedChunk(
                                        memories = parsed.memories,
                                        rules = parsed.rules
                                    )
                                    store.stageAnalysisCandidates(
                                        collectionId = checkNotNull(candidateCollectionId),
                                        chunkOrdinal = earlierChunks.size + 1,
                                        candidates = stagedCandidates(parsedChunk)
                                    )
                                    parsedChunk
                                }
                            },
                            commit = { stagedChunks ->
                                if (lorebookMode) {
                                    val prepared = prepareLorebookSuggestions(
                                        context = context,
                                        store = store,
                                        conversation = conversation,
                                        runId = runId,
                                        entries = stagedChunks.flatMap { it.loreEntries },
                                        maxSuggestions = maxSuggestions
                                    )
                                    val preparedIds = prepared.records.map { it.suggestionId }
                                    val progressRow = runningRow.copy(
                                        chatIdsJson = listToJson(analyzedChatIds + conversation.chatId),
                                        transcriptIdsJson = listToJson(
                                            fedTranscriptIds + conversation.transcripts.map { it.transcriptId }
                                        ),
                                        memoryIdsJson = listToJson(memoryIds + preparedIds),
                                        ruleIdsJson = listToJson(ruleIds),
                                        foundCount = memoryIds.size + preparedIds.size,
                                        failedChatIdsJson = listToJson(failedChats)
                                    )
                                    val stored = try {
                                        if (markProcessed) {
                                            store.commitFrozenChatRange(
                                                frozenRanges.getValue(conversation.chatId),
                                                memories = emptyList(), rules = emptyList(),
                                                lorebookSuggestions = prepared.records,
                                                runProgress = progressRow
                                            )
                                        } else {
                                            store.commitRerunChatOutputs(
                                                memories = emptyList(), rules = emptyList(),
                                                lorebookSuggestions = prepared.records,
                                                runProgress = progressRow
                                            )
                                        }
                                    } catch (e: Exception) {
                                        throw TaggedArchivistException(ArchivistFailure.SAVE_FAILED, e)
                                    }
                                    ChatCommit(
                                        memoryIds = stored.lorebookSuggestionIds,
                                        ruleIds = emptyList(),
                                        duplicatesSkipped = prepared.duplicatesSkipped
                                    )
                                } else {
                                    val preparedMemories = prepareMemoryDrafts(
                                        context = context,
                                        store = store,
                                        conversation = conversation,
                                        drafts = stagedChunks.flatMap { it.memories },
                                        maxSuggestions = maxSuggestions
                                    )
                                    val preparedRules = prepareRuleDrafts(
                                        context, store, conversation,
                                        stagedChunks.flatMap { it.rules }
                                    )
                                    val preparedMemoryIds = preparedMemories.records.map { it.memoryId }
                                    val preparedRuleIds = preparedRules.map { it.ruleId }
                                    val progressRow = runningRow.copy(
                                        chatIdsJson = listToJson(analyzedChatIds + conversation.chatId),
                                        transcriptIdsJson = listToJson(
                                            fedTranscriptIds + conversation.transcripts.map { it.transcriptId }
                                        ),
                                        memoryIdsJson = listToJson(memoryIds + preparedMemoryIds),
                                        ruleIdsJson = listToJson(ruleIds + preparedRuleIds),
                                        foundCount = memoryIds.size + preparedMemoryIds.size,
                                        failedChatIdsJson = listToJson(failedChats)
                                    )
                                    val stored = try {
                                        if (markProcessed) {
                                            store.commitFrozenChatRange(
                                                frozenRanges.getValue(conversation.chatId),
                                                preparedMemories.records, preparedRules, emptyList(),
                                                relationshipHintsByMemoryId =
                                                    preparedMemories.relationshipHintsByMemoryId,
                                                runProgress = progressRow
                                            )
                                        } else {
                                            store.commitRerunChatOutputs(
                                                preparedMemories.records, preparedRules, emptyList(),
                                                relationshipHintsByMemoryId =
                                                    preparedMemories.relationshipHintsByMemoryId,
                                                candidateCollectionId = candidateCollectionId,
                                                runProgress = progressRow
                                            )
                                        }
                                    } catch (e: Exception) {
                                        throw TaggedArchivistException(ArchivistFailure.SAVE_FAILED, e)
                                    }
                                    ChatCommit(
                                        memoryIds = stored.memoryIds,
                                        ruleIds = stored.ruleIds,
                                        duplicatesSkipped = preparedMemories.duplicatesSkipped
                                    )
                                }
                            }
                        )
                        memoryIds.addAll(committed.memoryIds)
                        ruleIds.addAll(committed.ruleIds)
                        duplicatesSkipped += committed.duplicatesSkipped
                        analyzedChatIds.add(conversation.chatId)
                        fedTranscriptIds.addAll(conversation.transcripts.map { it.transcriptId })
                        // Durable per-conversation progress on the 'running'
                        // row: a process death now loses at most the
                        // conversation in flight, and the reconciled
                        // interrupted record reports real counts, not zeros.
                        try {
                            store.insertArchivistRun(runningRow.copy(
                                chatIdsJson = listToJson(analyzedChatIds),
                                transcriptIdsJson = listToJson(fedTranscriptIds),
                                memoryIdsJson = listToJson(memoryIds),
                                ruleIdsJson = listToJson(ruleIds),
                                foundCount = memoryIds.size,
                                failedChatIdsJson = listToJson(failedChats)
                            ))
                        } catch (e: Exception) {
                            MemoryLog.log(context, "Archivist", "error",
                                "run progress write failed: ${e.message}")
                        }
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        // Interruption is a RUN-level state, not a conversation
                        // failure — handled by the outer catch.
                        candidateCollectionId?.let {
                            discardCandidateCollectionSafely(context, store, it)
                        }
                        throw ce
                    } catch (e: Exception) {
                        // One conversation failing must not sink the run. Its
                        // staged chunks are discarded, its bookmark stays put,
                        // and its claims are released for the next run. Another
                        // chat that already committed remains successful.
                        val reason = ArchivistFailure.classify(e)
                        candidateCollectionId?.let {
                            discardCandidateCollectionSafely(context, store, it)
                        }
                        failedChats.add(conversation.chatId)
                        failedReasons.add(reason)
                        genResults.add(GenerationErrorClassifier.classify(e))
                        capturedErrorBody?.let { failureBody = it }
                        MemoryLog.logAlways(context, "Archivist", "error",
                            "chat=${conversation.chatId} failed (${reason.key}): ${e.message}")
                    }
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // The run was stopped before completion (user left the screen,
            // process/system interruption). Record what happened — saved
            // drafts are kept; unprocessed conversations stay pending. The
            // record writes are plain blocking calls, safe in a cancelled
            // coroutine; the cancellation is rethrown after bookkeeping.
            interrupted = true
            runError = "interrupted"
            runErrorFailure = ArchivistFailure.INTERRUPTED
        } catch (e: Exception) {
            runError = e.message ?: e.javaClass.simpleName
            runErrorFailure = ArchivistFailure.classify(e)
            genResults.add(GenerationErrorClassifier.classify(e))
            capturedErrorBody?.let { failureBody = it }
        }

        // Display outcome (archivist_status_wording_spec.md). A partial
        // success is never called a full failure; "no new" is not an error.
        val selected = conversations.size
        // A run that saved at least one draft/suggestion before a later failure
        // is NEVER a full failure (owner ruling, Fix #5): full failure requires
        // that nothing completed AND nothing was saved. Anything saved before an
        // engine-level abort or an all-conversations-failed run is reported as a
        // partial/incomplete run so the saved items are acknowledged, never
        // denied.
        val anySaved = memoryIds.isNotEmpty() || ruleIds.isNotEmpty()
        val outcome = when {
            // An in-process stop (the Cancel button, or the Android 15+ dataSync
            // timeout) is reported as "cancelled". A process DEATH is recovered
            // separately by the startup reconcile as "interrupted" — the two are
            // kept distinct so the screen can say which happened.
            interrupted -> "cancelled"
            runError != null -> if (anySaved) "partial_failed" else "full_failed"
            selected == 0 -> "nothing"
            failedChats.size >= selected -> if (anySaved) "partial_failed" else "full_failed"
            failedChats.isNotEmpty() -> "partial_failed"
            !anySaved -> "no_new"
            else -> "completed"
        }
        // Dominant per-conversation reason picks the on-screen sentence; an
        // engine-level failure's own classification wins when present.
        val dominantReason: ArchivistFailure? = runErrorFailure
            ?: failedReasons.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: if (outcome == "full_failed") ArchivistFailure.UNKNOWN else null

        // Precise failure surface (Aug 1 2026): the dominant chat-funnel
        // classification, the provider detail captured from the failed request,
        // and the connection/model this run used. Only meaningful for a full
        // failure; the Memory Assistant maps these to the owner-approved state
        // and renders the shared provider-detail block (Function: Archiving).
        val dominantGen: GenErrorResult? = if (outcome == "full_failed") {
            genResults.groupingBy { it.code }.eachCount().maxByOrNull { it.value }?.key
                ?.let { code -> genResults.firstOrNull { it.code == code } }
        } else null
        val providerInfo = ProviderErrorInfo.parse(failureBody)
        val apiProviderName = endpoint.label.trim().ifBlank { null } ?: endpoint.host.trim().ifBlank { null }
        val runModel = model.trim().ifBlank { null } ?: endpoint.model.trim().ifBlank { null }
        // Per-failed-conversation categories for the partial-failure surface:
        // one specific title when every failed conversation shares a cause, or
        // a mixed-cause breakdown otherwise (aligned lists — a reason and a
        // classified result are recorded together for each failed conversation).
        val failureCategoryCounts: Map<String, Int> =
            failedReasons.zip(genResults)
                .map { ArchivistFailureCategory.of(it.first, it.second) }
                .groupingBy { it }.eachCount()

        // Record archivist provider failures to the SAME Provider Failure Log as
        // chat (owner ruling, Aug 1 2026) — when logging is on and the server
        // actually answered — with Function: Archiving. Background, so a failure
        // is logged even when no screen is watching.
        if (outcome == "full_failed" && dominantGen != null && dominantGen.reachedServer() &&
            prefs.getLogChatFailures()
        ) {
            try {
                val notReported = context.getString(R.string.provider_value_not_reported)
                val raw = listOfNotNull(
                    dominantGen.httpStatus?.toString(),
                    (providerInfo.message ?: runError)?.trim()?.ifBlank { null }
                ).joinToString(" ").ifBlank { "(no message)" }
                org.teslasoft.assistant.preferences.Logger.logProviderFailure(
                    context,
                    apiProviderName ?: notReported,
                    providerInfo.providerName?.trim()?.ifBlank { null } ?: notReported,
                    runModel ?: notReported,
                    context.getString(R.string.mem_arch_function_archiving),
                    raw
                )
            } catch (e: Exception) {
                MemoryLog.log(context, "Archivist", "error", "provider failure log write failed: ${e.message}")
            }
        }

        // Failure and partial-failure records are ALWAYS written to the
        // Memory Debug Log (owner rule — recovery information, not optional
        // debug noise).
        when (outcome) {
            // §4(g): the counts are the stored run record's counts — never a
            // hardcoded zero. Drafts filed before the failure are kept, and
            // this line must not deny they exist.
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
                    "cause=coroutine cancellation (Cancel button or system stop) selected=$selected " +
                    "processed=${analyzedChatIds.size} memories=${memoryIds.size}")
        }

        // Terminal cleanup (§4(a)): release any claim this run still holds —
        // rows it never finished return to the pending queue — then finalize
        // the durable 'running' row into ordinary run history.
        try {
            store.releaseAnalysisClaims(runId)
        } catch (e: Exception) {
            MemoryLog.logAlways(context, "Archivist", "error", "claim release failed: ${e.message}")
        }
        try {
            store.insertArchivistRun(
                runningRow.copy(
                    finishedAt = Instant.now().toString(),
                    status = if (outcome == "full_failed" || outcome == "interrupted" || outcome == "cancelled") "failed" else "complete",
                    chatIdsJson = listToJson(analyzedChatIds),
                    transcriptIdsJson = listToJson(fedTranscriptIds),
                    memoryIdsJson = listToJson(memoryIds),
                    ruleIdsJson = listToJson(ruleIds),
                    foundCount = memoryIds.size,
                    failedChatIdsJson = listToJson(failedChats),
                    error = runError,
                    outcome = outcome,
                    failureReason = dominantReason?.key
                )
            )
        } catch (e: Exception) {
            MemoryLog.logAlways(context, "Archivist", "error", "run record write failed: ${e.message}")
        }
        return RunOutcome(
            runId = runId,
            conversationsSelected = selected,
            conversationsAnalyzed = analyzedChatIds.size,
            memoriesFound = memoryIds.size,
            ruleDraftsFound = ruleIds.size,
            failedChatIds = failedChats,
            outcome = outcome,
            failureReason = dominantReason,
            duplicatesSkipped = duplicatesSkipped,
            incompleteTurnsExcluded = incompleteTurnsExcluded,
            error = runError,
            analysisType = analysisType,
            genError = dominantGen,
            apiProvider = apiProviderName,
            upstreamProvider = providerInfo.providerName?.trim()?.ifBlank { null },
            providerMessage = providerInfo.message?.trim()?.ifBlank { null },
            model = runModel,
            failureCategoryCounts = failureCategoryCounts
        )
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
        return out
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

    private fun discardCandidateCollectionSafely(
        context: Context,
        store: MemoryStore,
        collectionId: String
    ) {
        try {
            store.discardCandidateCollection(collectionId)
        } catch (cleanup: Exception) {
            MemoryLog.log(
                context, "Archivist", "error",
                "temporary candidate cleanup failed: ${cleanup.message}"
            )
        }
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
        val records = ArrayList<org.teslasoft.assistant.preferences.memory.MemoryRecord>()
        val relationshipHints = linkedMapOf<String, List<String>>()
        val stagedIdentities = HashSet<String>()
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
            if (maxSuggestions > 0 && records.size >= maxSuggestions) {
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
                            filingResult.candidate, MemoryStore.newId("m-"), MemoryStore.nowIso()
                        )
                    records.add(record)
                    // Request-local M aliases were validated by the parser.
                    // Re-check existence at the complete chat boundary; a
                    // vanished reference is discarded rather than guessed.
                    val validHints = d.relatedExistingMemoryIds
                        .filter { it != record.memoryId && store.getMemory(it) != null }
                        .distinct()
                    if (validHints.isNotEmpty()) relationshipHints[record.memoryId] = validHints
                }
            }
        }
        return PreparedMemories(records, relationshipHints, duplicates)
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
        conversation: Conversation,
        drafts: List<ArchivistResponseParser.DraftRule>
    ): List<ModelRuleRecord> {
        if (drafts.isEmpty()) return emptyList()
        val sourceModel = conversation.transcripts.firstNotNullOfOrNull { it.modelTag }
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
                // §11: the user assigns model strings on accept; the source model
                // string seeds that list. A Draft is filed with the list empty.
                modelStringsJson = listToJson(candidate.modelStrings),
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
            out[Hash.hash(name)] = name
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
