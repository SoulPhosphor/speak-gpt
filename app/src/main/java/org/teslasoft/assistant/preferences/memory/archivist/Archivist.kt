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
        val conversationsAnalyzed: Int,
        val memoriesFound: Int,
        val ruleDraftsFound: Int,
        /** Chats whose analysis failed this run; their transcripts stay
         *  pending and are picked up by the next run. */
        val failedChatIds: List<String>,
        /** True when no Archivist endpoint/model is configured — the screen
         *  handles this state; no analysis was attempted. */
        val notConfigured: Boolean = false,
        val error: String? = null
    )

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
            ?: return RunOutcome(null, 0, 0, 0, emptyList(), error = "run not found")
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
            return RunOutcome(null, 0, 0, 0, emptyList(), notConfigured = true)
        }
        val model = prefs.getArchivistModel().ifBlank { endpoint.model }
        if (model.isBlank()) {
            return RunOutcome(null, 0, 0, 0, emptyList(), notConfigured = true)
        }

        val store = MemoryStore.getInstance(context)
        val startedAt = Instant.now().toString()
        val ai = buildClient(endpoint)

        val memoryIds = ArrayList<String>()
        val ruleIds = ArrayList<String>()
        val failedChats = ArrayList<String>()
        val analyzedChatIds = ArrayList<String>()
        val fedTranscriptIds = ArrayList<String>()
        var runError: String? = null

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
                        for (chunk in chunks) {
                            val rows = chunk.map { conversation.transcripts[it] }
                            val response = ai.chatCompletion(
                                ChatCompletionRequest(
                                    model = ModelId(model),
                                    messages = listOf(
                                        ChatMessage(role = ChatRole.System, content = ArchivistPrompt.SYSTEM),
                                        ChatMessage(
                                            role = ChatRole.User,
                                            content = ArchivistPrompt.userMessage(
                                                conversation.chatName, companionName, rows
                                            )
                                        )
                                    )
                                )
                            )
                            val raw = response.choices.firstOrNull()?.message?.content.orEmpty()
                            val parsed = ArchivistResponseParser.parse(raw)
                            if (parsed.dropped > 0) {
                                MemoryLog.log(context, "Archivist", "warn",
                                    "chat=${conversation.chatId}: ${parsed.dropped} proposal(s) failed validation and were dropped")
                            }
                            fileMemoryDrafts(context, store, conversation, parsed.memories, memoryIds)
                            fileRuleDrafts(context, store, conversation, parsed.rules, ruleIds)
                        }
                        if (markProcessed) {
                            store.markTranscriptsProcessed(conversation.transcripts.map { it.transcriptId })
                        }
                        analyzedChatIds.add(conversation.chatId)
                        fedTranscriptIds.addAll(conversation.transcripts.map { it.transcriptId })
                    } catch (e: Exception) {
                        // One conversation failing must not sink the run: its
                        // rows stay pending for the next run (drafts a partial
                        // chunk already filed stay pending drafts; the text
                        // dedup stops identical refiling on the retry).
                        failedChats.add(conversation.chatId)
                        MemoryLog.log(context, "Archivist", "error",
                            "chat=${conversation.chatId} failed: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            runError = e.message ?: e.javaClass.simpleName
        }

        val runId = MemoryStore.newId("run-")
        try {
            store.insertArchivistRun(
                ArchivistRunRecord(
                    runId = runId,
                    startedAt = startedAt,
                    finishedAt = Instant.now().toString(),
                    status = if (runError == null) "complete" else "failed",
                    chatIdsJson = listToJson(analyzedChatIds),
                    transcriptIdsJson = listToJson(fedTranscriptIds),
                    memoryIdsJson = listToJson(memoryIds),
                    ruleIdsJson = listToJson(ruleIds),
                    foundCount = memoryIds.size,
                    failedChatIdsJson = listToJson(failedChats),
                    error = runError
                )
            )
        } catch (e: Exception) {
            MemoryLog.log(context, "Archivist", "error", "run record write failed: ${e.message}")
        }
        return RunOutcome(
            runId = runId,
            conversationsAnalyzed = analyzedChatIds.size,
            memoriesFound = memoryIds.size,
            ruleDraftsFound = ruleIds.size,
            failedChatIds = failedChats,
            error = runError
        )
    }

    private fun fileMemoryDrafts(
        context: Context,
        store: MemoryStore,
        conversation: Conversation,
        drafts: List<ArchivistResponseParser.DraftMemory>,
        collectedIds: MutableList<String>
    ) {
        if (drafts.isEmpty()) return
        val now = Instant.now().toString()
        val companionId = conversation.transcripts.firstNotNullOfOrNull { it.companionId }
        for (d in drafts) {
            if (store.memoryExistsWithText(d.title, d.content)) continue
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
                origin = "archivist"
            )
            try {
                store.insertArchivistDraftMemory(record)
                collectedIds.add(record.memoryId)
            } catch (e: Exception) {
                MemoryLog.log(context, "Archivist", "error", "draft insert failed: ${e.message}")
            }
        }
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
