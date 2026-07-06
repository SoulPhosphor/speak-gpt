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

package org.teslasoft.assistant.preferences.memory.enforcer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.lorebook.LoreBookMatch
import org.teslasoft.assistant.preferences.lorebook.LoreBookStore
import org.teslasoft.assistant.preferences.memory.CompanionRecord
import org.teslasoft.assistant.preferences.memory.MemoryCompanionSync
import org.teslasoft.assistant.preferences.memory.MemoryLog
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.RetrievableMemory
import org.teslasoft.assistant.preferences.memory.RetrievalScope
import org.teslasoft.assistant.preferences.memory.ScoredMemory
import org.teslasoft.assistant.preferences.memory.librarian.Librarian
import org.teslasoft.assistant.preferences.memory.librarian.VectorMath

/**
 * The guarding half of the runtime: assembles the per-turn memory system
 * message behind the app's single generation funnel. The caller (ChatActivity)
 * gates on the memory engine tier, the per-chat kill switch, and store
 * existence; everything in here may throw, and the caller degrades to the
 * classic lorebook path (never block generation).
 *
 * Stage 3.4 (owner_approved_rules, July 6 2026) reduced assembly to what the
 * rules allow into a prompt: memories retrieved by relevance — with Instruction
 * memories rendered as context rules — the user's hand-written lore notes
 * (which outrank memories), and the scene. The retired machinery (standing
 * packet with owner portrait and directives, modes and mode detection,
 * per-companion hard-limits and model-adaptation renders, entity summaries,
 * always-load memories) is GONE from assembly: always-on material is card /
 * system-prompt text the user writes (law 4), what the system knows about the
 * user is Preference/Fact memories retrieved like everything else, and people
 * in the user's life are ordinary memories, not entities (§15). The store
 * tables underneath stay dormant; nothing here reads them any more.
 *
 * Returns the FULL extra system message including the lore notes section, so
 * the caller injects exactly one of {enforcer message, classic lore message}.
 */
class Enforcer private constructor(private val appContext: Context) {

    companion object {
        @Volatile private var instance: Enforcer? = null

        fun getInstance(context: Context): Enforcer =
            instance ?: synchronized(this) {
                instance ?: Enforcer(context.applicationContext).also { instance = it }
            }

        /** Recent-context window for the retrieval query (spec: the message
         *  plus a short rolling summary of the last few turns). */
        const val RECENT_CONTEXT_TURNS = 4
        const val RECENT_CONTEXT_CHARS_PER_TURN = 200

        /**
         * Freshness cooldown (rules §10 / Stage 3.3): an entry that reached the
         * prompt is not re-injected until this many turns pass — the
         * conversation carries it in the meantime. A tuned constant, NOT a user
         * setting (§10). First-time entries always inject; an edit resets the
         * entry's clock (hooked in MemoryStore's edit paths); every suppression
         * shows in the AssemblyLog debug view.
         *
         * The approved rule's second refresh trigger — "when the conversation
         * outgrows the old mention", i.e. the old injection falling out of the
         * actually-sent history window — is DEFERRED: TurnInput only carries a
         * short recent-context digest (~4 turns / 200 chars), never the real
         * sent window, and widening TurnInput for it is out of scope for this
         * stage. Only the after-N-turns half ships here.
         */
        const val COOLDOWN_TURNS = 10

        private const val META_CONTRADICTION_FLAGS = "enforcer.contradiction_flags"
        private const val MAX_CONTRADICTION_FLAGS = 50
    }

    data class TurnInput(
        val chatId: String,
        /** The app persona id ("" = none) — resolves to a companion via app_character_id. */
        val personaId: String,
        val userMessage: String,
        /** Short rolling summary of the last few turns, built by the caller. */
        val recentContext: String,
        val modelTag: String,
        val loreMatches: List<LoreBookMatch>,
        val worldId: String?,
        /** Quick Settings Campaign selection (Stage 3.0) — the owner-chosen
         *  explicit signal that this chat is inside that playthrough. */
        val campaignId: String?,
        val roleplayCharacterId: String?,
        val userPersonaId: String?,
        /** Quick Settings Project selection — a ranking boost, never a gate
         *  (owner_approved_rules §4 rev 3). */
        val projectId: String?
    )

    /**
     * Assemble this turn's memory system message, or null when the memory
     * system has nothing to add and the caller should run the classic lore
     * path (companion opted out, or the assembly came out empty).
     */
    fun assembleTurn(input: TurnInput): String? {
        val store = MemoryStore.getInstance(appContext)
        val notes = ArrayList<String>()

        // Operating defaults: only the neutral retrieval_policy, and only into
        // an EMPTY policy row. No default modes are provisioned — the app
        // pre-authors no memory content (owner_approved_rules.md §15).
        try {
            if (store.provisionOperatingDefaults(DefaultOperatingData.DEFAULT_POLICY_JSON)) {
                MemoryLog.log(appContext, "Enforcer", "info", "Retrieval policy provisioned")
            }
        } catch (e: Exception) {
            notes.add("defaults provisioning failed: ${e.message}")
        }

        val policy = parsePolicy(store)

        // Companion resolution — auto-creating the record when the persona has
        // none yet, so a chat with a persona ALWAYS resolves to a companion.
        // Draft companions are never assembled (the memory query enforces it
        // for memories; this enforces it for the scope anchor).
        // memory_participation: 'none' -> the store contributes nothing for
        // this companion; 'global_only' -> the companion branch stays out of
        // the eligibility query.
        var companion: CompanionRecord? = input.personaId.takeIf { it.isNotEmpty() }?.let {
            MemoryCompanionSync.ensureCompanionForPersona(appContext, it)
        }
        if (companion?.status == "draft") companion = null
        if (companion != null && companion.memoryParticipation == "none") return null
        val scopeCompanionId =
            if (companion != null && companion.memoryParticipation == "full") companion.companionId else null

        // Campaign wiring (Stage 3.0): one Quick Settings selection implies the
        // rest — the campaign's world and the user's character in it fill in
        // when the chat has no explicit pick of its own; an explicit pick wins.
        // A dangling campaign id degrades to "none".
        val campaign = input.campaignId?.takeIf { it.isNotBlank() }?.let {
            try { store.getCampaign(it) } catch (_: Exception) { null }
        }
        val worldId = input.worldId?.takeIf { it.isNotBlank() } ?: campaign?.worldId
        val roleplayCharacterId =
            input.roleplayCharacterId?.takeIf { it.isNotBlank() } ?: campaign?.roleplayCharacterId

        // D8: prefs are truth, app_state is the derived mirror (best effort).
        try {
            store.updateAppState(
                companion?.companionId, worldId,
                roleplayCharacterId,
                input.userPersonaId?.takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) { /* mirror only; never blocks a turn */ }

        val librarian = Librarian.getInstance(appContext)
        val prefs = Preferences.getPreferences(appContext, input.chatId)

        // §3 (rev 3): companion memories in roleplay need an explicit door —
        // the narrator/GM match (the selected campaign's GM companion IS the
        // chat's active companion) or the global "Allow active companion
        // memories in roleplay" toggle (default OFF). Two independent paths;
        // the toggle does not require narrator status.
        val narratorMatch = campaign?.companionId != null &&
            campaign.companionId == companion?.companionId
        val companionInRoleplayAllowed =
            narratorMatch || prefs.getAllowCompanionMemoriesInRoleplay()

        // The seven-category eligibility context (Stage 3.1) — the store query
        // is the single gate; ranking (Stage 3.2) only orders what's eligible.
        val retrievalScope = RetrievalScope(
            companionId = scopeCompanionId,
            worldId = worldId,
            campaignId = campaign?.campaignId,
            roleplayCharacterId = roleplayCharacterId,
            allowCompanionInRoleplay = companionInRoleplayAllowed
        )

        // Retrieval: the librarian's search inherits the eligibility gates in the
        // store query. The per-memory always-load flag is retired
        // (owner_approved_rules §10): nothing is force-injected every turn.
        val query = listOf(input.userMessage, input.recentContext)
            .filter { it.isNotBlank() }.joinToString("\n")
        if (!librarian.hasUsableModel()) notes.add("no embedding model — keyword retrieval")
        val retrievedRaw: List<ScoredMemory> = try {
            librarian.search(retrievalScope, query, policy.topK, input.projectId?.takeIf { it.isNotBlank() })
        } catch (e: Exception) {
            notes.add("retrieval failed: ${e.message}")
            emptyList()
        }
        var retrieved = retrievedRaw.map { toAssembled(it.memory, it.score, it.similarity) }

        // Lore notes: the user's hand-written tier, same budget caps as the
        // classic path (core-book-first order is preserved from the caller).
        val loreNotes = ArrayList<LoreNote>()
        var loreChars = 0
        for (match in input.loreMatches) {
            if (loreNotes.size >= LoreBookStore.MAX_INJECTED_ENTRIES) break
            if (loreNotes.isNotEmpty() && loreChars + match.entry.content.length > LoreBookStore.MAX_INJECTED_CHARS) break
            loreChars += match.entry.content.length
            loreNotes.add(LoreNote(match.entry.label, match.entry.content))
        }

        // Near-duplicate suppression (coexistence rule): a retrieved memory
        // that duplicates an injected lore entry is dropped — user-authored
        // wins — and the pair is flagged for the next Archivist run report
        // (silent disagreement between the tiers is the one thing never allowed).
        val suppressed = ArrayList<Pair<AssembledMemory, LoreNote>>()
        if (loreNotes.isNotEmpty() && retrieved.isNotEmpty()) {
            val loreVectors = loreNotes.map { note ->
                note to librarian.embedOrNull("${note.label}: ${note.content}", false)
            }
            retrieved = retrieved.filter { mem ->
                val memText = "${mem.title}: ${mem.content}"
                val memVec = if (loreVectors.any { it.second != null }) librarian.embedOrNull(memText, false) else null
                val dupOf = loreVectors.firstOrNull { (note, loreVec) ->
                    if (memVec != null && loreVec != null) {
                        VectorMath.cosine(memVec, loreVec) >= NearDuplicate.COSINE_THRESHOLD
                    } else {
                        NearDuplicate.isTextNearDup(memText, "${note.label}: ${note.content}")
                    }
                }
                if (dupOf != null) {
                    suppressed.add(mem to dupOf.first)
                    false
                } else true
            }
            if (suppressed.isNotEmpty()) flagContradictions(store, suppressed)
        }

        // Freshness cooldown (§10 / Stage 3.3): entries whose last injection is
        // still fresh in this chat are suppressed BEFORE the budget, so a
        // cooling memory never crowds out one that may actually inject. A
        // clock/store failure skips suppression for the turn (inject rather
        // than silently starve) and is noted in the log.
        var turn: Long? = null
        val cooled = ArrayList<Pair<AssembledMemory, Long>>()
        try {
            val thisTurn = store.nextTurnNumber(input.chatId)
            turn = thisTurn
            if (retrieved.isNotEmpty()) {
                val lastTurns = store.lastInjectedTurns(
                    input.chatId, MemoryStore.COOLDOWN_SOURCE_MEMORY, retrieved.map { it.memoryId }
                )
                retrieved = retrieved.filter { m ->
                    val last = lastTurns[m.memoryId]
                    if (last != null && thisTurn - last < COOLDOWN_TURNS) {
                        cooled.add(m to (thisTurn - last))
                        false
                    } else true
                }
            }
        } catch (e: Exception) {
            notes.add("cooldown unavailable this turn: ${e.message}")
        }

        // Budget: policy-driven, lowest-scored retrieved memories cut first;
        // lore (user-authored) charges the budget but is never cut here.
        val (kept, cut) = PromptAssembler.applyBudget(retrieved, loreChars, policy.charBudget)

        // Stamp what actually reached the prompt so the next turns suppress it.
        if (turn != null && kept.isNotEmpty()) {
            try {
                store.recordInjections(
                    input.chatId, MemoryStore.COOLDOWN_SOURCE_MEMORY, kept.map { it.memoryId }, turn
                )
            } catch (e: Exception) {
                notes.add("cooldown stamp failed: ${e.message}")
            }
        }

        // Scene: user persona presentation in any chat; world + roleplay
        // character during a world session. Missing records degrade to an
        // emptier scene, never an error.
        val scene = buildScene(store, input, worldId, roleplayCharacterId)

        val components = AssemblyComponents(
            memories = kept,
            loreNotes = loreNotes,
            scene = scene
        )
        val rendered = PromptAssembler.render(components)

        // The room this turn stood in, for the debug view (§12.4: eligibility
        // defines the room; the ladder only orders what is in it).
        val eligibility = if (retrievalScope.isRoleplay) {
            val companionDoor = when {
                scopeCompanionId == null -> "no companion"
                narratorMatch -> "companion memories via narrator/GM"
                companionInRoleplayAllowed -> "companion memories via global toggle"
                else -> "companion memories blocked"
            }
            listOfNotNull(
                "roleplay",
                worldId?.let { "world" },
                campaign?.let { "campaign \"${it.name}\"" },
                roleplayCharacterId?.let { "rp character" },
                companionDoor
            ).joinToString(", ")
        } else {
            "ordinary chat" +
                (if (scopeCompanionId != null) ", companion" else "") +
                (input.projectId?.takeIf { it.isNotBlank() }?.let { ", project boost" } ?: "")
        }

        AssemblyLog.record(
            AssemblyLog.Record(
                timestamp = System.currentTimeMillis(),
                userMessage = input.userMessage,
                companionName = companion?.currentName,
                eligibility = eligibility,
                injected = kept.map {
                    AssemblyLog.Line(
                        it.title,
                        "(${it.provenanceMarker}) score %.3f sim %.3f%s%s".format(
                            it.score, it.similarity,
                            if (it.isProtected) " [protected]" else "",
                            if (it.isInstruction) " [instruction rule]" else ""
                        )
                    )
                },
                cut = cut.map { AssemblyLog.Line(it.title, "over budget (score %.3f)".format(it.score)) } +
                    suppressed.map { (m, l) -> AssemblyLog.Line(m.title, "near-duplicate of lore \"${l.label}\" — flagged") } +
                    cooled.map { (m, ago) ->
                        AssemblyLog.Line(m.title, "cooldown — injected $ago turn(s) ago, refreshes after $COOLDOWN_TURNS")
                    },
                loreNotes = loreNotes.map { it.label },
                scene = scene.takeIf { !it.isEmpty }?.let {
                    listOfNotNull(it.worldName?.let { w -> "world $w" },
                        it.characterName?.let { c -> "as $c" },
                        it.userPersonaPresentation?.let { "persona set" }).joinToString(", ")
                },
                notes = notes
            )
        )
        return rendered.ifBlank { null }
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                             */
    /* ------------------------------------------------------------------ */

    private data class Policy(val topK: Int, val charBudget: Int)

    private fun parsePolicy(store: MemoryStore): Policy {
        val json = try { store.getRetrievalPolicyJson() } catch (_: Exception) { null }
        if (json != null) {
            try {
                val obj = JSONObject(json)
                return Policy(
                    topK = obj.optInt("top_k", 8),
                    charBudget = obj.optInt("memory_char_budget", PromptAssembler.DEFAULT_CHAR_BUDGET)
                )
            } catch (_: Exception) { /* fall through to defaults */ }
        }
        return Policy(8, PromptAssembler.DEFAULT_CHAR_BUDGET)
    }

    private fun buildScene(
        store: MemoryStore,
        input: TurnInput,
        worldId: String?,
        roleplayCharacterId: String?
    ): SceneContext {
        val presentation = input.userPersonaId?.takeIf { it.isNotBlank() }?.let {
            try { store.getUserPersona(it)?.presentation } catch (_: Exception) { null }
        }
        if (worldId == null) return SceneContext(userPersonaPresentation = presentation)
        val world = try { store.getWorld(worldId) } catch (_: Exception) { null }
            ?: return SceneContext(userPersonaPresentation = presentation)
        val character = roleplayCharacterId?.let {
            try { store.getRoleplayCharacter(it) } catch (_: Exception) { null }
        }
        return SceneContext(
            userPersonaPresentation = presentation,
            worldName = world.name,
            worldPremise = world.premise,
            worldRules = world.rules,
            characterName = character?.name,
            characterDescription = character?.description,
            characterArc = character?.arc
        )
    }

    private fun toAssembled(m: RetrievableMemory, score: Float, similarity: Float): AssembledMemory {
        var handling: List<String> = emptyList()
        var neverAssume: List<String> = emptyList()
        m.protectionJson?.takeIf { it.isNotBlank() }?.let {
            try {
                val p = JSONObject(it)
                if (p.optBoolean("is_protected", false) ||
                    p.has("handling") || p.has("never_assume")
                ) {
                    handling = jsonToList(p.optJSONArray("handling"))
                    neverAssume = jsonToList(p.optJSONArray("never_assume"))
                }
            } catch (_: Exception) { /* unparseable protection: memory still renders, unprotected fields empty */ }
        }
        return AssembledMemory(
            memoryId = m.memoryId,
            title = m.title,
            content = m.content,
            provenanceMarker = provenanceMarker(m.provenanceSource, m.provenanceConfidence),
            handling = handling,
            neverAssume = neverAssume,
            score = score,
            similarity = similarity,
            kind = m.kind
        )
    }

    /** told = they said it; observed = seen over time; guessed = tentative. */
    private fun provenanceMarker(source: String?, confidence: String?): String = when {
        confidence.equals("tentative", ignoreCase = true) -> "guessed"
        source.equals("user_stated", ignoreCase = true) -> "told"
        source.equals("inferred", ignoreCase = true) -> "guessed"
        else -> "observed"
    }

    private fun jsonToList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) arr.optString(i)?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        return out
    }

    /** Persist suppressed memory↔lore pairs for the next Archivist run report
     *  (Phase 6 reads and clears this list). */
    private fun flagContradictions(store: MemoryStore, pairs: List<Pair<AssembledMemory, LoreNote>>) {
        try {
            val arr = try {
                JSONArray(store.getMeta(META_CONTRADICTION_FLAGS) ?: "[]")
            } catch (_: Exception) { JSONArray() }
            for ((mem, lore) in pairs) {
                arr.put(
                    JSONObject()
                        .put("at", MemoryStore.nowIso())
                        .put("memory_id", mem.memoryId)
                        .put("memory_title", mem.title)
                        .put("lore_label", lore.label)
                )
            }
            // Cap: keep the newest entries.
            val trimmed = JSONArray()
            val start = (arr.length() - MAX_CONTRADICTION_FLAGS).coerceAtLeast(0)
            for (i in start until arr.length()) trimmed.put(arr.get(i))
            store.setMeta(META_CONTRADICTION_FLAGS, trimmed.toString())
        } catch (e: Exception) {
            MemoryLog.log(appContext, "Enforcer", "error", "Contradiction flagging failed: ${e.message}")
        }
    }
}
