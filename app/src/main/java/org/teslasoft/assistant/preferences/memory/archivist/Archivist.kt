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
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.memory.ArchivistRunRecord
import org.teslasoft.assistant.preferences.memory.CardSections
import org.teslasoft.assistant.preferences.memory.CardType
import org.teslasoft.assistant.preferences.memory.MemoryLog
import org.teslasoft.assistant.preferences.memory.MemoryRecord
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.ModelRuleRecord
import org.teslasoft.assistant.preferences.memory.TranscriptRecord
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
         *  not_configured. */
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
        val error: String? = null
    ) {
        val notConfigured: Boolean get() = outcome == "not_configured"
    }

    /**
     * Live progress for the Memory Assistant's running state (owner answer 4,
     * July 8 2026). One batch → the screen shows the plain "Conversation
     * x of x"; several → the owner's batch wording ("Conversations will be
     * batched due to size. / Batch One / x of x Conversations"). Batching is
     * display grouping only — every conversation still gets its own model
     * call(s); see [ArchivistBatchPlanner].
     */
    data class Progress(
        val batchIndex: Int,            // 1-based
        val batchCount: Int,
        val conversationInBatch: Int,   // 1-based, within the current batch
        val conversationsInBatch: Int
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

    /** Analyze every currently-eligible conversation (the user may queue any
     *  number — owner answer 4; size batching happens inside). */
    suspend fun analyze(context: Context, onProgress: (Progress) -> Unit): RunOutcome =
        run(context, eligibleConversations(context), markProcessed = true, onProgress = onProgress)

    /** Re-analyze a past run's conversations (the Rerun row action): re-feeds
     *  exactly the transcript rows that run stored, for chats that still
     *  exist. Files any NEW findings as drafts (existing identical drafts are
     *  deduplicated); records a fresh run row. */
    suspend fun rerun(context: Context, runId: String, onProgress: (Progress) -> Unit): RunOutcome {
        val store = MemoryStore.getInstance(context)
        val past = store.getArchivistRun(runId)
            ?: return RunOutcome(
                null, 0, 0, 0, 0, emptyList(),
                outcome = "full_failed", failureReason = ArchivistFailure.UNKNOWN,
                error = "run not found"
            )
        val ids = jsonToList(past.transcriptIdsJson)
        val liveChats = liveChatNamesById(context)
        val conversations = store.transcriptsByIds(ids)
            .filter { it.chatId != null && liveChats.containsKey(it.chatId) }
            .groupBy { it.chatId!! }
            .map { (chatId, rows) -> Conversation(chatId, liveChats[chatId] ?: chatId, rows) }
        return run(context, conversations, markProcessed = false, onProgress = onProgress)
    }

    private suspend fun run(
        context: Context,
        conversations: List<Conversation>,
        markProcessed: Boolean,
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
            return RunOutcome(null, 0, 0, 0, 0, emptyList(), outcome = "not_configured")
        }
        val model = prefs.getArchivistModel().ifBlank { endpoint.model }
        if (model.isBlank()) {
            MemoryLog.logAlways(context, "Archivist", "warn",
                "Archivist Not Ready — Memory Archivist needs a model before it can run. Missing: model name")
            return RunOutcome(null, 0, 0, 0, 0, emptyList(), outcome = "not_configured")
        }

        val store = MemoryStore.getInstance(context)
        val startedAt = Instant.now().toString()
        val ai = buildClient(endpoint)

        // Memory Assistant tuning (owner spec, July 9 2026): the cap and the
        // importance floor are enforced HERE in code — the prompt is never
        // trusted to do it. Temperature rides every analysis request
        // (recommended default 0.3); a user-edited extraction prompt replaces
        // the built-in one (Reset clears back to built-in).
        val maxSuggestions = prefs.getArchivistMaxSuggestions()
        val minImportance = prefs.getArchivistMinImportance()
        val temperature = prefs.getArchivistTemperature().toDouble()
        val systemPrompt = prefs.getArchivistCustomPrompt().ifBlank { ArchivistPrompt.SYSTEM }
        // The card-append toggle (§2, ON by default): off discards any
        // proposed placements — the memories themselves still file.
        val cardSuggestionsOn = prefs.getArchivistCardSuggestions()

        val memoryIds = ArrayList<String>()
        val ruleIds = ArrayList<String>()
        val failedChats = ArrayList<String>()
        val failedReasons = ArrayList<ArchivistFailure>()
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
                    onProgress(Progress(batchIndex + 1, batches.size, posInBatch + 1, batch.size))
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
                            // Code-enforced tuning (owner spec): the importance
                            // floor first, then the per-conversation cap across
                            // all of the conversation's chunks.
                            var candidates = parsed.memories.filter { it.importance >= minImportance }
                            val belowFloor = parsed.memories.size - candidates.size
                            if (maxSuggestions > 0) {
                                val room = (maxSuggestions - filedThisConversation).coerceAtLeast(0)
                                if (candidates.size > room) {
                                    MemoryLog.log(context, "Archivist", "info",
                                        "chat=${conversation.chatId}: cap $maxSuggestions reached, ${candidates.size - room} draft(s) not filed")
                                    candidates = candidates.take(room)
                                }
                            }
                            if (belowFloor > 0) {
                                MemoryLog.log(context, "Archivist", "info",
                                    "chat=${conversation.chatId}: $belowFloor draft(s) below minimum importance $minImportance skipped")
                            }
                            val before = memoryIds.size
                            duplicatesSkipped += fileMemoryDrafts(
                                context, store, conversation, candidates, memoryIds, cardSuggestionsOn
                            )
                            filedThisConversation += memoryIds.size - before
                            fileRuleDrafts(context, store, conversation, parsed.rules, ruleIds)
                        }
                        if (markProcessed) {
                            store.markTranscriptsProcessed(conversation.transcripts.map { it.transcriptId })
                        }
                        analyzedChatIds.add(conversation.chatId)
                        fedTranscriptIds.addAll(conversation.transcripts.map { it.transcriptId })
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
        }

        // Display outcome (archivist_status_wording_spec.md). A partial
        // success is never called a full failure; "no new" is not an error.
        val selected = conversations.size
        val outcome = when {
            interrupted -> "interrupted"
            runError != null -> "full_failed"
            selected == 0 -> "nothing"
            failedChats.size >= selected -> "full_failed"
            failedChats.isNotEmpty() -> "partial_failed"
            memoryIds.isEmpty() -> "no_new"
            else -> "completed"
        }
        // Dominant per-conversation reason picks the on-screen sentence; an
        // engine-level failure's own classification wins when present.
        val dominantReason: ArchivistFailure? = runErrorFailure
            ?: failedReasons.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: if (outcome == "full_failed") ArchivistFailure.UNKNOWN else null

        // Failure and partial-failure records are ALWAYS written to the
        // Memory Debug Log (owner rule — recovery information, not optional
        // debug noise).
        when (outcome) {
            "full_failed" -> MemoryLog.logAlways(context, "Archivist", "error",
                "Run Fully Failed — Memory extraction failed. No memories were created from this run. " +
                    "reason=${dominantReason?.key} error=${runError ?: "per-conversation failures"} " +
                    "selected=$selected processed=${analyzedChatIds.size} memories=0")
            "partial_failed" -> MemoryLog.logAlways(context, "Archivist", "warn",
                "Run Partially Failed — Memory extraction finished with some skipped conversations. " +
                    "reasons=${failedReasons.map { it.key }.distinct()} selected=$selected " +
                    "processed=${analyzedChatIds.size} skipped=${failedChats.size} " +
                    "memories=${memoryIds.size} failedChats=$failedChats")
            "interrupted" -> MemoryLog.logAlways(context, "Archivist", "warn",
                "Run Interrupted — Memory extraction was interrupted before it could finish. " +
                    "cause=coroutine cancellation (screen closed or system stop) selected=$selected " +
                    "processed=${analyzedChatIds.size} memories=${memoryIds.size}")
        }

        val runId = MemoryStore.newId("run-")
        try {
            store.insertArchivistRun(
                ArchivistRunRecord(
                    runId = runId,
                    startedAt = startedAt,
                    finishedAt = Instant.now().toString(),
                    status = if (outcome == "full_failed" || outcome == "interrupted") "failed" else "complete",
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
            error = runError
        )
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
        val now = Instant.now().toString()
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
            if (store.memoryExistsWithText(d.title, d.content)) { duplicates++; continue }
            // A draft the user deleted is a rejection (owner preference,
            // July 9 2026): the exact same draft from the same conversation
            // is not refiled on rerun. Deliberately narrow — different
            // wording or a different conversation files normally.
            if (store.isDraftRejected(d.title, d.content, conversation.chatName)) {
                MemoryLog.log(context, "Archivist", "info",
                    "chat=${conversation.chatId}: previously rejected draft not refiled (\"${d.title}\")")
                continue
            }
            // Resolve a proposed placement (roleplay scopes only): the section
            // must be a real key for the matched card's type.
            var sugType: String? = null
            var sugId: String? = null
            var sugSection: String? = null
            if (cardSuggestionsOn && d.cardName != null && d.cardSection != null &&
                d.scope in setOf("world", "campaign", "rp_character")
            ) {
                val match = liveCards.firstOrNull { it.third.equals(d.cardName, ignoreCase = true) }
                if (match != null && d.cardSection in CardSections.sectionsFor(match.first)) {
                    sugType = match.first
                    sugId = match.second
                    sugSection = d.cardSection
                }
            }
            val record = MemoryRecord(
                memoryId = MemoryStore.newId("m-"),
                scope = d.scope,
                kind = d.kind,
                title = d.title,
                content = d.content,
                embeddingText = null,
                tagsJson = listToJson(d.tags),
                importance = d.importance,
                worldIds = resolveTarget(d, "world") { store.getAllWorlds().map { it.worldId to it.name } },
                roleplayCharacterIds = resolveTarget(d, "rp_character") {
                    store.getAllRoleplayCharacters().map { it.roleplayCharacterId to it.name }
                },
                campaignIds = resolveTarget(d, "campaign") { store.getCampaigns().map { it.campaignId to it.name } },
                projectIds = resolveTarget(d, "project") { store.getProjects().map { it.projectId to it.name } },
                protectionJson = null,
                modeHintsJson = "[]",
                provenanceSource = if (d.stated) "user_stated" else "inferred",
                provenanceConfidence = if (d.stated) "certain" else "tentative",
                provenanceNotedOn = now,
                // §14: the editor shows which chat a draft came from and when.
                provenanceContext = conversation.chatName,
                createdAt = now,
                updatedAt = null,
                status = "draft",
                supersedes = null,
                companionIds = if (d.scope == "companion" && companionId != null) listOf(companionId) else emptyList(),
                entityRefs = emptyList(),
                changeLog = emptyList(),
                origin = "archivist",
                suggestedCardType = sugType,
                suggestedCardId = sugId,
                suggestedSection = sugSection
            )
            try {
                store.insertArchivistDraftMemory(record)
                collectedIds.add(record.memoryId)
            } catch (e: Exception) {
                MemoryLog.logAlways(context, "Archivist", "error", "draft insert failed: ${e.message}")
                throw TaggedArchivistException(ArchivistFailure.SAVE_FAILED, e)
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

    private fun buildClient(endpoint: ApiEndpointObject): OpenAI {
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
                retry = RetryStrategy()
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
