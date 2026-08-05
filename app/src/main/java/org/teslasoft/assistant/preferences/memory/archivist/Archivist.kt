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
import org.teslasoft.assistant.preferences.memory.CardSections
import org.teslasoft.assistant.preferences.memory.CardType
import org.teslasoft.assistant.preferences.memory.LorebookSuggestionRecord
import org.teslasoft.assistant.preferences.memory.MemoryCandidate
import org.teslasoft.assistant.preferences.memory.MemoryCandidateValidator
import org.teslasoft.assistant.preferences.memory.MemoryLog
import org.teslasoft.assistant.preferences.memory.MemoryMatch
import org.teslasoft.assistant.preferences.memory.MemoryScopeGrouping
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.MemoryTypeMigration
import org.teslasoft.assistant.preferences.memory.ModelRuleRecord
import org.teslasoft.assistant.preferences.memory.PendingMemoryFiler
import org.teslasoft.assistant.preferences.memory.SCOPE_COMPANION
import org.teslasoft.assistant.preferences.memory.TranscriptRecord
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

    /**
     * Live eligibility (owner rules: a query on CURRENT state, never a stored
     * watermark): pending, unprocessed, and belonging to a chat that still
     * exists. Deleted conversations don't count; a chat re-included after
     * "don't archive" re-queues its rows as pending upstream and reappears
     * here automatically.
     */
    fun eligibleConversations(context: Context): List<Conversation> {
        if (!MemoryStore.isProvisioned(context)) return emptyList()
        val liveChats = liveChatNamesById(context)
        return MemoryStore.getInstance(context).pendingUnprocessedTranscripts()
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
        if (markProcessed) {
            val claimed = store.beginAnalysisRun(
                runningRow, conversations.flatMap { c -> c.transcripts.map { it.transcriptId } }
            )
            conversations = conversations.mapNotNull { c ->
                val rows = c.transcripts.filter { it.transcriptId in claimed }
                if (rows.isEmpty()) null else c.copy(transcripts = rows)
            }
        } else {
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
        // different output schemas. Whatever text is shown in the matching field
        // on the Advanced Memory Assistant Settings screen is exactly what is
        // sent here; nothing is appended or substituted (the cap and importance
        // floor are response-side filters, not prompt text).
        val systemPrompt = if (lorebookMode)
            prefs.getArchivistLorebookPrompt().ifBlank { ArchivistPrompt.LOREBOOK_SYSTEM }
        else
            prefs.getArchivistCustomPrompt().ifBlank { ArchivistPrompt.SYSTEM }
        // The card-append toggle (§2, ON by default): off discards any
        // proposed placements — the memories themselves still file.
        val cardSuggestionsOn = prefs.getArchivistCardSuggestions()

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
                    try {
                        val companionName = conversation.transcripts
                            .firstNotNullOfOrNull { it.companionId }
                            ?.let { store.getCompanion(it)?.currentName }
                        // A single oversized conversation (the "30 pages" case)
                        // is split across several calls, whole rows at a time,
                        // so one request never overruns the model's context.
                        val chunks = ArchivistBatchPlanner.splitIntoRequests(
                            conversation.transcripts.map { it.content.length }
                        )
                        if (chunks.size > 1) {
                            MemoryLog.log(context, "Archivist", "info",
                                "chat=${conversation.chatId}: oversized conversation split into ${chunks.size} requests")
                        }
                        var filedThisConversation = 0
                        for (chunk in chunks) {
                            val rows = chunk.map { conversation.transcripts[it] }
                            val rendered = ArchivistPrompt.userMessage(
                                conversation.chatName, companionName, rows
                            )
                            incompleteTurnsExcluded += rendered.incompleteAssistantTurnsDropped
                            // Fresh capture window per request: the observer only
                            // writes on an error response, so a success leaves
                            // this null.
                            capturedErrorBody = null
                            val response = ai.chatCompletion(
                                ChatCompletionRequest(
                                    model = ModelId(model),
                                    messages = listOf(
                                        ChatMessage(role = ChatRole.System, content = systemPrompt),
                                        ChatMessage(
                                            role = ChatRole.User,
                                            content = rendered.text
                                        )
                                    ),
                                    temperature = temperature
                                )
                            )
                            val raw = response.choices.firstOrNull()?.message?.content.orEmpty()
                            if (lorebookMode) {
                                // Lorebook Memories analysis (Step 1.7): parse
                                // lore book entries and file them as pending
                                // suggestions instead of memory drafts. The
                                // per-conversation cap still applies; there is
                                // no importance floor (lore entries carry none).
                                val parsedLore = try {
                                    ArchivistResponseParser.parseLore(raw)
                                } catch (e: Exception) {
                                    throw TaggedArchivistException(ArchivistFailure.UNREADABLE, e)
                                }
                                if (parsedLore.dropped > 0) {
                                    MemoryLog.log(context, "Archivist", "warn",
                                        "chat=${conversation.chatId}: ${parsedLore.dropped} lore proposal(s) failed validation and were dropped")
                                }
                                var loreCandidates = parsedLore.entries
                                if (maxSuggestions > 0) {
                                    val room = (maxSuggestions - filedThisConversation).coerceAtLeast(0)
                                    if (loreCandidates.size > room) {
                                        MemoryLog.log(context, "Archivist", "info",
                                            "chat=${conversation.chatId}: cap $maxSuggestions reached, ${loreCandidates.size - room} lore suggestion(s) not filed")
                                        loreCandidates = loreCandidates.take(room)
                                    }
                                }
                                val before = memoryIds.size
                                duplicatesSkipped += fileLorebookSuggestions(
                                    context, store, conversation, runId, loreCandidates, memoryIds
                                )
                                filedThisConversation += memoryIds.size - before
                            } else {
                            // A parse failure is reason D (unreadable result) —
                            // tag it so the generic classifier can't misfile it.
                            val parsed = try {
                                ArchivistResponseParser.parse(raw)
                            } catch (e: Exception) {
                                throw TaggedArchivistException(ArchivistFailure.UNREADABLE, e)
                            }
                            if (parsed.dropped > 0) {
                                MemoryLog.log(context, "Archivist", "warn",
                                    "chat=${conversation.chatId}: ${parsed.dropped} proposal(s) failed validation and were dropped")
                            }
                            // Code-enforced tuning (owner spec): the per-
                            // conversation cap across all of the conversation's
                            // chunks. No importance floor (§7.2) — the analyzer no
                            // longer rates memories, so nothing is dropped for a
                            // low AI importance it never assigned.
                            var candidates = parsed.memories
                            if (maxSuggestions > 0) {
                                val room = (maxSuggestions - filedThisConversation).coerceAtLeast(0)
                                if (candidates.size > room) {
                                    MemoryLog.log(context, "Archivist", "info",
                                        "chat=${conversation.chatId}: cap $maxSuggestions reached, ${candidates.size - room} draft(s) not filed")
                                    candidates = candidates.take(room)
                                }
                            }
                            val before = memoryIds.size
                            duplicatesSkipped += fileMemoryDrafts(
                                context, store, conversation, candidates, memoryIds, cardSuggestionsOn
                            )
                            filedThisConversation += memoryIds.size - before
                            fileRuleDrafts(context, store, conversation, parsed.rules, ruleIds)
                            }
                        }
                        if (markProcessed) {
                            // Only rows still carrying THIS run's claim stamp
                            // advance — a turn appended mid-run started a new
                            // unclaimed row and stays pending (§4(a)).
                            store.markTranscriptsProcessed(
                                conversation.transcripts.map { it.transcriptId }, runId
                            )
                        }
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
                        throw ce
                    } catch (e: Exception) {
                        // One conversation failing must not sink the run: its
                        // rows stay pending for the next run (drafts a partial
                        // chunk already filed stay pending drafts; the text
                        // dedup stops identical refiling on the retry).
                        val reason = ArchivistFailure.classify(e)
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
        val anySaved = memoryIds.isNotEmpty()
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
            memoryIds.isEmpty() -> "no_new"
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

    /**
     * File the run's proposed lore book entries as pending suggestions (Step
     * 1.7, Lorebook Memories analysis type). Mirrors [fileMemoryDrafts]'s
     * durability rules: an identical pending suggestion from the same chat is
     * skipped (content dedup, so an interrupted-then-rerun conversation never
     * doubles up), and a suggestion the user already deleted from this chat is
     * not refiled (rejection dedup). Returns how many candidates were skipped
     * as duplicates. A store insert failure aborts the conversation as reason E
     * (save failed), exactly like the memory path.
     */
    private fun fileLorebookSuggestions(
        context: Context,
        store: MemoryStore,
        conversation: Conversation,
        runId: String,
        entries: List<ArchivistResponseParser.DraftLoreEntry>,
        collectedIds: MutableList<String>
    ): Int {
        if (entries.isEmpty()) return 0
        var duplicates = 0
        val now = Instant.now().toString()
        for (e in entries) {
            if (store.lorebookSuggestionExists(e.content, conversation.chatId)) { duplicates++; continue }
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
            try {
                store.insertLorebookSuggestion(record)
                collectedIds.add(record.suggestionId)
            } catch (ex: Exception) {
                MemoryLog.logAlways(context, "Archivist", "error", "lore suggestion insert failed: ${ex.message}")
                throw TaggedArchivistException(ArchivistFailure.SAVE_FAILED, ex)
            }
        }
        return duplicates
    }

    /** Returns how many candidates were skipped as duplicates of memories
     *  that already exist ("Archivist found only memories that already
     *  exist" needs the distinction). A store insert failure aborts the
     *  conversation as reason E (save failed). */
    private fun fileMemoryDrafts(
        context: Context,
        store: MemoryStore,
        conversation: Conversation,
        drafts: List<ArchivistResponseParser.DraftMemory>,
        collectedIds: MutableList<String>,
        cardSuggestionsOn: Boolean
    ): Int {
        if (drafts.isEmpty()) return 0
        var duplicates = 0
        val companionId = conversation.transcripts.firstNotNullOfOrNull { it.companionId }
        // Live cards for placement-suggestion resolution: name → (type, id).
        // Loaded once per conversation; exact case-insensitive name match
        // against EXISTING cards only — an unknown card name just drops the
        // suggestion, never the memory, and nothing is ever created.
        val liveCards: List<Triple<String, String, String>> = if (cardSuggestionsOn) {
            buildList {
                store.getAllWorlds().filter { it.status == "active" }
                    .forEach { add(Triple(CardType.WORLD, it.worldId, it.name)) }
                store.getActiveCampaigns()
                    .forEach { add(Triple(CardType.CAMPAIGN, it.campaignId, it.name)) }
                store.getAllRoleplayCharacters().filter { it.status == "active" }
                    .forEach { add(Triple(CardType.RP_CHARACTER, it.roleplayCharacterId, it.name)) }
                store.getPartyMembers(includeArchived = false)
                    .forEach { add(Triple(CardType.PARTY_MEMBER, it.partyMemberId, it.name)) }
            }
        } else emptyList()
        for (d in drafts) {
            // Resolve placement once: the target sets are both the Possible
            // Match identity (scope + sorted target IDs) and the record's links.
            val worldIds = resolveTarget(d, "world") { store.getAllWorlds().map { it.worldId to it.name } }
            val rpCharIds = resolveTarget(d, "rp_character") {
                store.getAllRoleplayCharacters().map { it.roleplayCharacterId to it.name }
            }
            val campaignIds = resolveTarget(d, "campaign") { store.getCampaigns().map { it.campaignId to it.name } }
            val projectIds = resolveTarget(d, "project") { store.getProjects().map { it.projectId to it.name } }
            val companionIds =
                if (d.scope == "companion" && companionId != null) listOf(companionId) else emptyList()
            // The user-owned Type is the source of truth (§5): map the model's
            // raw suggestion to a Type id (recognized starter Type, or No Type
            // for lore/blank/unknown), and derive the inert legacy kind from it
            // so the stored kind can never disagree with the Type (item 4).
            val resolvedTypeId = MemoryTypeMigration.typeIdForLegacyKind(d.kind)
            val inertKind = MemoryTypeMigration.legacyKindForTypeId(resolvedTypeId)
            // Step 1.5 staging gate (counterplan §5.2(b)): only an exact
            // normalized match with the same placement AND the same Type, on an
            // active or pending memory, is a true duplicate that must not create
            // a second draft. Everything else — a different Type, an archived or
            // superseded exact match, or a semantic near-match — is filed and
            // surfaces as a Possible Match at review time. Placement-aware and
            // title-independent.
            val candidate = MemoryMatch.Candidate(
                content = d.content,
                scope = d.scope,
                kind = inertKind,
                targetIds = worldIds + rpCharIds + campaignIds + projectIds + companionIds
            )
            if (store.classifyCandidate(candidate) is MemoryMatch.Outcome.AlreadyPresent) {
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
            // Resolve a proposed placement (roleplay scopes only): the section
            // must be a real key for the matched card's type.
            var sugType: String? = null
            var sugId: String? = null
            var sugSection: String? = null
            if (cardSuggestionsOn && d.cardName != null && d.cardSection != null &&
                MemoryScopeGrouping.isRoleplayGroup(d.scope)
            ) {
                val match = liveCards.firstOrNull { it.third.equals(d.cardName, ignoreCase = true) }
                if (match != null && d.cardSection in CardSections.sectionsFor(match.first)) {
                    sugType = match.first
                    sugId = match.second
                    sugSection = d.cardSection
                }
            }
            // Build a VALIDATED candidate and file it through the one canonical
            // Pending filing service (Phase 2, items 5/6/8). The Memory Assistant
            // is just one transport wrapper: it resolves scope/targets/Type above,
            // then converges on the same validated candidate and the same filer
            // the computer-import and manual paths use. Every generated proposal
            // starts at the neutral importance 0 (§7.2) — the Memory Assistant
            // never assigns importance; the user sets it while reviewing Pending.
            val provSource = if (d.stated) "user_stated" else "inferred"
            val provConfidence = if (d.stated) "certain" else "tentative"
            val filingResult: CandidateResult<out MemoryCandidate> = if (d.scope == SCOPE_COMPANION) {
                MemoryCandidateValidator.validateCompanion(
                    content = d.content,
                    companionTargetIds = companionIds,
                    intendedCompanionId = companionId,
                    availableCompanionIds = if (companionId != null) setOf(companionId) else emptySet(),
                    typeId = resolvedTypeId,
                    tags = d.tags,
                    importance = 0,
                    provenanceSource = provSource,
                    provenanceConfidence = provConfidence,
                    origin = "archivist"
                )
            } else {
                MemoryCandidateValidator.validateGeneral(
                    scope = d.scope,
                    content = d.content,
                    typeId = resolvedTypeId,
                    tags = d.tags,
                    importance = 0,
                    provenanceSource = provSource,
                    provenanceConfidence = provConfidence,
                    origin = "archivist",
                    worldIds = worldIds,
                    campaignIds = campaignIds,
                    roleplayCharacterIds = rpCharIds,
                    projectIds = projectIds,
                    suggestedCardType = sugType,
                    suggestedCardId = sugId,
                    suggestedSection = sugSection
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
                    try {
                        val id = PendingMemoryFiler.getInstance(context).file(filingResult.candidate)
                        collectedIds.add(id)
                    } catch (e: Exception) {
                        MemoryLog.logAlways(context, "Archivist", "error", "draft insert failed: ${e.message}")
                        throw TaggedArchivistException(ArchivistFailure.SAVE_FAILED, e)
                    }
                }
            }
        }
        return duplicates
    }

    /** A proposed target NAME only ever links to a record that already exists
     *  (exact name match, case-insensitive). The Archivist never creates
     *  worlds/campaigns/characters/projects — emergence stays a Phase 6+
     *  question for the owner. No match → the draft arrives untargeted and
     *  the user assigns targets in the editor before accepting. */
    private fun resolveTarget(
        d: ArchivistResponseParser.DraftMemory,
        scope: String,
        candidates: () -> List<Pair<String, String>>
    ): List<String> {
        if (d.scope != scope) return emptyList()
        val name = d.targetName ?: return emptyList()
        return candidates()
            .filter { it.second.equals(name, ignoreCase = true) }
            .map { it.first }
            .take(1)
    }

    private fun fileRuleDrafts(
        context: Context,
        store: MemoryStore,
        conversation: Conversation,
        drafts: List<ArchivistResponseParser.DraftRule>,
        collectedIds: MutableList<String>
    ) {
        if (drafts.isEmpty()) return
        val sourceModel = conversation.transcripts.firstNotNullOfOrNull { it.modelTag }
        val existing = store.getModelRules().map { it.text.trim() }.toHashSet()
        for (d in drafts) {
            if (d.text.trim() in existing) continue
            val rule = ModelRuleRecord(
                ruleId = MemoryStore.newId("mr_"),
                text = d.text,
                // §11: the user assigns model strings on accept; the source
                // model string seeds that list.
                modelStringsJson = "[]",
                status = "draft",
                sourceModelString = sourceModel,
                createdAt = Instant.now().toString(),
                updatedAt = null
            )
            try {
                store.upsertModelRule(rule)
                collectedIds.add(rule.ruleId)
                existing.add(d.text.trim())
            } catch (e: Exception) {
                MemoryLog.log(context, "Archivist", "error", "rule draft insert failed: ${e.message}")
            }
        }
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
