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

package org.teslasoft.assistant.preferences.memory.assistant

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
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.memory.CampaignRecord
import org.teslasoft.assistant.preferences.memory.CompanionRecord
import org.teslasoft.assistant.preferences.memory.MemoryLog
import org.teslasoft.assistant.preferences.memory.MemoryRecord
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.RoleplayCharacterRecord
import org.teslasoft.assistant.preferences.memory.TranscriptRecord
import org.teslasoft.assistant.preferences.memory.WorldRecord
import org.teslasoft.assistant.util.Hash
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 6, task 6.1 (phase6_memory_assistant_work_order.md): the Memory
 * Assistant's backend. Reads every PENDING transcript, sends each to the
 * user's configured Archivist endpoint/model with the extraction prompt, and
 * files what comes back as memory DRAFTS (`status='draft'`,
 * `origin='archivist'`) for the existing Pending screen. Manual only —
 * nothing here runs on its own; the single caller is the Analyze History
 * control on the Memory Assistant page.
 *
 * The safety laws live HERE, in code, never trusted to the model
 * (owner_approved_rules.md laws 3/6/8, §3, §14):
 *  - Everything the model returns becomes a draft; nothing is auto-applied.
 *  - Only memory suggestions are accepted. Any other shape the model emits
 *    is dropped on the floor — this runner has no code path that writes to
 *    companions, personas, or any roleplay card.
 *  - The fiction wall is structural: a transcript's scene columns decide the
 *    scopes its suggestions may use. A real-life conversation cannot produce
 *    world/campaign/RP-character memories; a roleplay conversation cannot
 *    produce real-life or companion memories. Suggestions outside the
 *    allowed set are discarded regardless of what the prompt said.
 *  - Excluded transcripts are invisible (filtered in SQL); a companion set
 *    to global-only participation restricts its chats to global suggestions.
 *  - A transcript is marked processed only after its drafts were filed; any
 *    failure leaves it pending for the next run. An empty result is a
 *    success ("a run that finds nothing is a success").
 */
object MemoryAssistantRunner {

    data class RunResult(
        val conversationsAnalyzed: Int,
        val suggestionsFiled: Int,
        /** Transcripts left pending because the call or the reply failed. */
        val failures: Int,
        /** Setup problem that stopped the run before/mid-way (null = ran to the end). */
        val error: String?
    )

    internal data class Suggestion(
        val title: String,
        val content: String,
        val type: String,
        val scope: String,
        val importance: Int,
        val tags: List<String>
    )

    /** The scene a transcript stood in, resolved from its stamped columns.
     *  Stale ids resolve to null and simply drop out of the allowed set. */
    private data class Scene(
        val roleplay: Boolean,
        val companion: CompanionRecord?,
        val world: WorldRecord?,
        val campaign: CampaignRecord?,
        val character: RoleplayCharacterRecord?,
        val allowedScopes: List<String>
    )

    private val TYPES = setOf("fact", "preference", "event", "status", "instruction", "lore")
    private const val MAX_TITLE_CHARS = 200
    private const val MAX_CONTENT_CHARS = 4000
    private const val MAX_TAGS = 5
    private const val MAX_TAG_CHARS = 64

    /**
     * Runs one full pass over the pending queue. Call on a worker dispatcher;
     * [onProgress] reports (done, total) after each transcript and is invoked
     * on the caller's thread — the UI marshals to main itself.
     */
    suspend fun run(context: Context, onProgress: (Int, Int) -> Unit = { _, _ -> }): RunResult {
        val app = context.applicationContext
        if (!MemoryStore.isProvisioned(app)) {
            return RunResult(0, 0, 0, "store_not_provisioned")
        }
        val preferences = Preferences.getPreferences(app, "")
        val endpointId = preferences.getArchivistEndpointId()
        val model = preferences.getArchivistModel()
        if (endpointId.isBlank() || model.isBlank()) {
            return RunResult(0, 0, 0, "not_configured")
        }
        val endpoint = ApiEndpointPreferences.getApiEndpointPreferences(app)
            .getApiEndpointsList(app)
            .firstOrNull { Hash.hash(it.label) == endpointId }
            ?: return RunResult(0, 0, 0, "not_configured")

        val store = MemoryStore.getInstance(app)
        val queue = store.listPendingTranscripts()
        if (queue.isEmpty()) return RunResult(0, 0, 0, null)

        val ai = buildClient(endpoint)
        val instructions = MemoryAssistantPrompt.effectivePrompt(app)
        val temperature = preferences.getMemoryAssistantTemperature()
        val cap = preferences.getMemoryAssistantMaxSuggestions()

        var analyzed = 0
        var filed = 0
        var failures = 0
        queue.forEachIndexed { index, transcript ->
            try {
                val scene = resolveScene(store, transcript)
                val request = ChatCompletionRequest(
                    model = ModelId(model),
                    messages = listOf(
                        ChatMessage(role = ChatRole.System, content = instructions),
                        ChatMessage(role = ChatRole.User, content = conversationBlock(scene, transcript, cap))
                    ),
                    temperature = temperature.toDouble()
                )
                val reply = ai.chatCompletion(request).choices.firstOrNull()?.message?.content ?: ""
                val parsed = extractJsonArray(reply)
                if (parsed == null) {
                    // The model didn't return the agreed shape. Nothing is
                    // filed and the transcript STAYS PENDING — a bad model/
                    // temperature choice must never burn a conversation.
                    failures++
                    MemoryLog.log(app, "MemoryAssistant", "error",
                        "Unparseable reply for ${transcript.transcriptId} (${reply.length} chars); left pending")
                } else {
                    val suggestions = sanitize(parsed, scene.allowedScopes, cap)
                    for (s in suggestions) {
                        store.insertMemory(toDraft(s, scene, transcript), actor = "archivist")
                    }
                    store.markTranscriptProcessed(transcript.transcriptId)
                    analyzed++
                    filed += suggestions.size
                    MemoryLog.log(app, "MemoryAssistant", "info",
                        "Transcript ${transcript.transcriptId}: ${suggestions.size} draft(s) filed" +
                            if (scene.roleplay) " (roleplay scene)" else "")
                }
            } catch (e: Exception) {
                // Transport/API failure: with the endpoint down every later
                // call would also fail (each eating its own timeout), so end
                // the run and report where it stopped. Everything unprocessed
                // stays pending.
                MemoryLog.log(app, "MemoryAssistant", "error",
                    "Run stopped at ${transcript.transcriptId}: ${e.message ?: e.javaClass.simpleName}")
                return RunResult(analyzed, filed, failures + 1, e.message ?: e.javaClass.simpleName)
            }
            onProgress(index + 1, queue.size)
        }
        return RunResult(analyzed, filed, failures, null)
    }

    /* ------------------------- scene / fiction wall ------------------------- */

    private fun resolveScene(store: MemoryStore, t: TranscriptRecord): Scene {
        val world = t.worldId?.let { store.getWorld(it) }
        val campaign = t.campaignId?.let { store.getCampaign(it) }
        val character = t.roleplayCharacterId?.let { store.getRoleplayCharacter(it) }
        val companion = t.companionId?.let { store.getCompanion(it) }
        val roleplay = world != null || campaign != null || character != null
        val allowed = if (roleplay) {
            buildList {
                add("global")
                if (world != null) add("world")
                if (campaign != null) add("campaign")
                if (character != null) add("rp_character")
            }
        } else {
            // 'global_only' participation is enforced Archivist-side (the
            // recorder's contract): such a companion's chats may only ever
            // produce global suggestions. 'none' rows arrive pre-excluded
            // and never reach this queue at all.
            if (companion?.memoryParticipation == "global_only") listOf("global")
            else buildList {
                add("global")
                add("real_life")
                if (companion != null) add("companion")
            }
        }
        return Scene(roleplay, companion, world, campaign, character, allowed)
    }

    /** The per-conversation half of the prompt: scene + allowed scopes +
     *  optional cap + the transcript. Data the runner owns — never editable,
     *  so a prompt edit can't detach a conversation from its scene. */
    private fun conversationBlock(scene: Scene, t: TranscriptRecord, cap: Int): String {
        val sb = StringBuilder("THIS CONVERSATION\n")
        if (scene.roleplay) {
            sb.append("This is a roleplay conversation")
            scene.world?.let { sb.append(" in the world \"").append(it.name).append("\"") }
            scene.campaign?.let { sb.append(", campaign \"").append(it.name).append("\"") }
            scene.character?.let { sb.append(", the user playing \"").append(it.name).append("\"") }
            sb.append(". Fiction only: nothing here is about the user's real life.\n")
        } else {
            sb.append("This is an ordinary real-life conversation")
            scene.companion?.let { sb.append(" with the companion \"").append(it.currentName).append("\"") }
            sb.append(".\n")
        }
        sb.append("Allowed scopes: ").append(scene.allowedScopes.joinToString(", ")).append(".\n")
        if (cap > 0) sb.append("Suggest at most ").append(cap).append(" memories — pick the most valuable.\n")
        sb.append("\nTRANSCRIPT\n")
        sb.append(renderTranscript(t.content))
        return sb.toString()
    }

    /** Transcript rows store a JSON array of {role, content} turns. Render as
     *  plain dialogue; if the stored content is somehow not that shape, send
     *  it raw rather than fail the transcript. */
    private fun renderTranscript(content: String): String {
        return try {
            val turns = JSONArray(content)
            val sb = StringBuilder()
            for (i in 0 until turns.length()) {
                val turn = turns.optJSONObject(i) ?: continue
                val speaker = if (turn.optString("role") == "user") "User" else "Assistant"
                sb.append(speaker).append(": ").append(turn.optString("content").trim()).append("\n")
            }
            if (sb.isEmpty()) content else sb.toString()
        } catch (_: Exception) {
            content
        }
    }

    /* ------------------------- reply parsing / laws ------------------------- */

    /** Finds the JSON array in the reply (tolerates code fences and chatter
     *  around it). Null = the reply is not usable at all. Internal for the
     *  unit tests — the law layer is exactly what must not regress quietly. */
    internal fun extractJsonArray(reply: String): JSONArray? {
        val start = reply.indexOf('[')
        val end = reply.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return try { JSONArray(reply.substring(start, end + 1)) } catch (_: Exception) { null }
    }

    /**
     * The in-code law layer: every entry must be a well-formed memory
     * suggestion whose scope the transcript's scene allows; everything else
     * is dropped, never repaired into something the model didn't say. The
     * max-suggestions cap is enforced here too — the prompt line alone is a
     * request, this is the guarantee.
     */
    internal fun sanitize(parsed: JSONArray, allowedScopes: List<String>, cap: Int): List<Suggestion> {
        val out = ArrayList<Suggestion>()
        for (i in 0 until parsed.length()) {
            if (cap > 0 && out.size >= cap) break
            val o = parsed.optJSONObject(i) ?: continue
            val title = o.optString("title").trim().take(MAX_TITLE_CHARS)
            val content = o.optString("content").trim().take(MAX_CONTENT_CHARS)
            val type = o.optString("type").trim().lowercase()
            val scope = o.optString("scope").trim().lowercase()
            if (title.isEmpty() || content.isEmpty()) continue
            if (type !in TYPES) continue
            if (scope !in allowedScopes) continue
            val importance = o.optInt("importance", 3).coerceIn(1, 5)
            val tags = ArrayList<String>()
            val rawTags = o.optJSONArray("tags")
            if (rawTags != null) {
                for (j in 0 until rawTags.length()) {
                    if (tags.size >= MAX_TAGS) break
                    val tag = rawTags.optString(j).trim().take(MAX_TAG_CHARS)
                    if (tag.isNotEmpty()) tags.add(tag)
                }
            }
            out.add(Suggestion(title, content, type, scope, importance, tags))
        }
        return out
    }

    /** A validated suggestion becomes a DRAFT memory — §14's single home for
     *  drafts, shown by the existing Pending screen. Target links follow the
     *  scope; provenance records which chat and when, and anything not
     *  user_entered displays as "Learned from chat" in the browser. */
    private fun toDraft(s: Suggestion, scene: Scene, t: TranscriptRecord): MemoryRecord {
        val now = MemoryStore.nowIso()
        return MemoryRecord(
            memoryId = MemoryStore.newId("m-"),
            scope = s.scope,
            kind = s.type,
            title = s.title,
            content = s.content,
            embeddingText = null,
            tagsJson = JSONArray(s.tags).toString(),
            importance = s.importance,
            worldIds = if (s.scope == "world" && scene.world != null) listOf(scene.world.worldId) else emptyList(),
            roleplayCharacterIds = if (s.scope == "rp_character" && scene.character != null) listOf(scene.character.roleplayCharacterId) else emptyList(),
            campaignIds = if (s.scope == "campaign" && scene.campaign != null) listOf(scene.campaign.campaignId) else emptyList(),
            projectIds = emptyList(),
            protectionJson = null,
            modeHintsJson = "[]",
            provenanceSource = "conversation",
            provenanceConfidence = null,
            provenanceNotedOn = t.endedAt ?: t.startedAt ?: now,
            provenanceContext = "chat:${t.chatId ?: "?"} transcript:${t.transcriptId}",
            createdAt = now,
            updatedAt = null,
            status = "draft",
            supersedes = null,
            companionIds = if (s.scope == "companion" && scene.companion != null) listOf(scene.companion.companionId) else emptyList(),
            entityRefs = emptyList(),
            changeLog = emptyList(),
            origin = "archivist"
        )
    }

    /* ------------------------------- client ------------------------------- */

    /**
     * Mirrors ChatActivity's client discipline exactly: alternate auth modes
     * go through headers with an empty token (a non-empty token would ALSO
     * send a Bearer header, which providers like Anthropic reject), and the
     * host composition strips a trailing chat/completions so the client
     * re-appends it at the profile's configured location. Never add custom
     * TLS handling here (see CLAUDE.md).
     */
    private fun buildClient(endpoint: ApiEndpointObject): OpenAI {
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
                // Non-streaming call: the whole reply arrives in one read, so
                // the socket timeout must cover full generation, not one chunk.
                timeout = Timeout(socket = 180.seconds),
                organization = null,
                headers = extraHeaders,
                host = OpenAIHost(composeChatHost(endpoint.host, endpoint.chatEndpoint)),
                proxy = null,
                retry = RetryStrategy()
            )
        )
    }

    /** Same composition rule as ChatActivity.composeChatHost (private there):
     *  base + endpoint with a trailing chat/completions stripped, because the
     *  client always re-appends it. */
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
