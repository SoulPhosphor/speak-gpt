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
import org.teslasoft.assistant.preferences.memory.CampaignRecord
import org.teslasoft.assistant.preferences.memory.CardEntryRecord
import org.teslasoft.assistant.preferences.memory.CardType
import org.teslasoft.assistant.preferences.memory.CompanionRecord
import org.teslasoft.assistant.preferences.memory.MemoryCompanionSync
import org.teslasoft.assistant.preferences.memory.MemoryLog
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.PartyMemberRecord
import org.teslasoft.assistant.preferences.memory.RetrievableMemory
import org.teslasoft.assistant.preferences.memory.RetrievalScope
import org.teslasoft.assistant.preferences.memory.RoleplayCharacterRecord
import org.teslasoft.assistant.preferences.memory.ScoredMemory
import org.teslasoft.assistant.preferences.memory.WorldRecord
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

        // Campaign wiring (Stage 3.0, precedence re-ruled in 3.6c — spec
        // §2/§8b): a selected campaign's OWN links are the facts; there is no
        // per-chat override, so the campaign's world/character take precedence
        // over any leftover chat-level pick (which Quick Settings now shows as
        // a display, not a selector). Chat-level picks apply only where the
        // campaign has no link of its own — or when no campaign is selected.
        // A dangling campaign id degrades to "none".
        val campaign = input.campaignId?.takeIf { it.isNotBlank() }?.let {
            try { store.getCampaign(it) } catch (_: Exception) { null }
        }
        val worldId = campaign?.worldId ?: input.worldId?.takeIf { it.isNotBlank() }
        val roleplayCharacterId =
            campaign?.roleplayCharacterId ?: input.roleplayCharacterId?.takeIf { it.isNotBlank() }

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
        val narratorMatch = campaign != null && campaign.companionId != null &&
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
        // wins — and the pair is recorded via flagContradictions (silent
        // disagreement between the tiers is the one thing never allowed). Those
        // flags are write-only today; see flagContradictions for the details.
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

        // The active cards this turn (3.6d): the resolved world, campaign,
        // user character, and the campaign's linked party members. Fetched
        // once and shared by the cores (Zone 1) and the retrieval pass
        // (Zone 2). Any failure degrades to "no cards this turn", never an
        // error — same contract as everything else here.
        val world = worldId?.let { try { store.getWorld(it) } catch (_: Exception) { null } }
        val character = roleplayCharacterId?.let {
            try { store.getRoleplayCharacter(it) } catch (_: Exception) { null }
        }
        val party: List<PartyMemberRecord> = campaign?.let {
            try { store.partyMembersForCampaign(it.campaignId) } catch (_: Exception) { emptyList() }
        } ?: emptyList()

        // Zone 2 card retrieval (3.6d): trigger-matched, never embedded.
        // Entry names, section names and auto-trigger tag names fire against
        // the user message; tag-sharing siblings become one-hop pull-along
        // candidates. Dead/enemy party members' entries stay reachable here
        // even though their cores don't inject (§4).
        var cardDirect: List<CardRetrieval.Fired> = emptyList()
        var cardPull: List<CardRetrieval.Fired> = emptyList()
        var cardEntriesById: Map<String, CardEntryRecord> = emptyMap()
        var entryTags: Map<String, List<String>> = emptyMap()
        val cardCooled = ArrayList<Pair<String, Long>>()
        try {
            val activeCards = ArrayList<Pair<String, String>>()
            world?.let { activeCards.add(CardType.WORLD to it.worldId) }
            campaign?.let { activeCards.add(CardType.CAMPAIGN to it.campaignId) }
            character?.let { activeCards.add(CardType.RP_CHARACTER to it.roleplayCharacterId) }
            party.forEach { activeCards.add(CardType.PARTY_MEMBER to it.partyMemberId) }
            if (activeCards.isNotEmpty()) {
                val entries = activeCards.flatMap { (type, id) -> store.entriesForCard(type, id) }
                if (entries.isNotEmpty()) {
                    cardEntriesById = entries.associateBy { it.entryId }
                    val allLinks = store.cardEntryTagLinks()
                    entryTags = entries.associate { it.entryId to (allLinks[it.entryId] ?: emptyList()) }
                    val tagsById = store.getAllRpTags().associateBy { it.tagId }
                    val fired = CardRetrieval.fire(input.userMessage, entries, tagsById, entryTags)
                    cardDirect = fired.direct
                    cardPull = fired.pullAlong
                    // Freshness cooldown, per source_type (§10): fired entries
                    // whose last injection is still fresh sit this turn out.
                    val thisTurn = turn
                    if (thisTurn != null && (cardDirect.isNotEmpty() || cardPull.isNotEmpty())) {
                        val lastTurns = store.lastInjectedTurns(
                            input.chatId, MemoryStore.COOLDOWN_SOURCE_CARD_ENTRY,
                            (cardDirect + cardPull).map { it.entry.entryId }
                        )
                        fun fresh(f: CardRetrieval.Fired): Boolean {
                            val last = lastTurns[f.entry.entryId] ?: return true
                            val ago = thisTurn - last
                            return if (ago < COOLDOWN_TURNS) {
                                cardCooled.add(f.entry.name to ago)
                                false
                            } else true
                        }
                        cardDirect = cardDirect.filter { fresh(it) }
                        cardPull = cardPull.filter { fresh(it) }
                    }
                }
            }
        } catch (e: Exception) {
            notes.add("card retrieval failed: ${e.message}")
            cardDirect = emptyList()
            cardPull = emptyList()
        }

        fun assembleCard(f: CardRetrieval.Fired): AssembledCardEntry = AssembledCardEntry(
            entryId = f.entry.entryId,
            sectionLabel = CardRetrieval.SECTION_LABELS[f.entry.section] ?: f.entry.section,
            name = f.entry.name,
            body = CardRetrieval.composeBody(f.entry, cardEntriesById),
            connectedTo = CardRetrieval.connectedNames(f.entry, cardEntriesById.values.toList(), entryTags)
        )

        fun cardCost(a: AssembledCardEntry): Int =
            a.name.length + a.body.length + a.connectedTo.sumOf { it.length }

        // Budget (3.6d): user-authored material outranks system memories
        // (§12.1) — lore notes and DIRECTLY fired card entries charge the
        // budget first and memories absorb the squeeze; speculative
        // pull-alongs come last, into whatever the kept memories left over,
        // so a broad tag can never flood the prompt (§3). Every cut is logged.
        val cardCut = ArrayList<Pair<String, String>>()
        val directKept = ArrayList<Pair<AssembledCardEntry, String>>()
        var directChars = 0
        val directAvailable = (policy.charBudget - loreChars).coerceAtLeast(0)
        for (f in cardDirect) {
            val a = assembleCard(f)
            val cost = cardCost(a)
            if (directChars + cost > directAvailable && directKept.isNotEmpty()) {
                cardCut.add(a.name to "over budget (fired by ${f.reason})")
            } else if (cost > directAvailable) {
                cardCut.add(a.name to "over budget (fired by ${f.reason})")
            } else {
                directKept.add(a to f.reason)
                directChars += cost
            }
        }

        val (kept, cut) = PromptAssembler.applyBudget(retrieved, loreChars + directChars, policy.charBudget)

        val keptMemoryChars = kept.sumOf {
            it.title.length + it.content.length +
                it.handling.sumOf { h -> h.length } + it.neverAssume.sumOf { n -> n.length }
        }
        var pullRemaining =
            (policy.charBudget - loreChars - directChars - keptMemoryChars).coerceAtLeast(0)
        val pullKept = ArrayList<Pair<AssembledCardEntry, String>>()
        for (f in cardPull) {
            val a = assembleCard(f)
            val cost = cardCost(a)
            if (cost <= pullRemaining) {
                pullKept.add(a to f.reason)
                pullRemaining -= cost
            } else {
                cardCut.add(a.name to "over budget (${f.reason})")
            }
        }
        val cardsFinal = directKept + pullKept

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
        if (turn != null && cardsFinal.isNotEmpty()) {
            try {
                store.recordInjections(
                    input.chatId, MemoryStore.COOLDOWN_SOURCE_CARD_ENTRY,
                    cardsFinal.map { it.first.entryId }, turn
                )
            } catch (e: Exception) {
                notes.add("card cooldown stamp failed: ${e.message}")
            }
        }

        // Scene (3.6d): user persona presentation plus the Zone 1 cores in
        // the FIXED order — world core → campaign bookmark → user character
        // core → alive/incapacitated party cores → the dead/enemy roster
        // line. Cores are cooldown-exempt. Missing records degrade to an
        // emptier scene, never an error.
        val scene = buildScene(store, input, world, campaign, character, party)

        val components = AssemblyComponents(
            memories = kept,
            loreNotes = loreNotes,
            scene = scene,
            cardEntries = cardsFinal.map { it.first }
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
                } + cardsFinal.map { (a, reason) ->
                    AssemblyLog.Line("card: ${a.name}", "§${a.sectionLabel} — fired by $reason")
                },
                cut = cut.map { AssemblyLog.Line(it.title, "over budget (score %.3f)".format(it.score)) } +
                    suppressed.map { (m, l) -> AssemblyLog.Line(m.title, "near-duplicate of lore \"${l.label}\" — flagged") } +
                    cooled.map { (m, ago) ->
                        AssemblyLog.Line(m.title, "cooldown — injected $ago turn(s) ago, refreshes after $COOLDOWN_TURNS")
                    } +
                    cardCooled.map { (name, ago) ->
                        AssemblyLog.Line("card: $name", "cooldown — injected $ago turn(s) ago, refreshes after $COOLDOWN_TURNS")
                    } +
                    cardCut.map { (name, why) -> AssemblyLog.Line("card: $name", why) },
                loreNotes = loreNotes.map { it.label },
                scene = scene.takeIf { !it.isEmpty }?.let { s ->
                    listOfNotNull(
                        s.cores.takeIf { it.isNotEmpty() }?.let { cores ->
                            "cores: " + cores.joinToString("; ") { it.heading }
                        },
                        s.rosterLine,
                        s.userPersonaPresentation?.let { "persona set" }
                    ).joinToString(", ")
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

    /**
     * The Zone 1 cores in the work order's FIXED sequence (3.6d): world core
     * → campaign bookmark → user character core → alive/incapacitated
     * party-member cores, then the generated §6b roster line for dead/enemy
     * members. Field labels are the spec §6 names; ONLY card fields render —
     * the dormant pre-card premise/rules/description/arc text never reaches
     * a prompt (owner ruling, spec §8a). Blank fields are omitted, so a lean
     * core stays lean.
     */
    private fun buildScene(
        store: MemoryStore,
        input: TurnInput,
        world: WorldRecord?,
        campaign: CampaignRecord?,
        character: RoleplayCharacterRecord?,
        party: List<PartyMemberRecord>
    ): SceneContext {
        val presentation = input.userPersonaId?.takeIf { it.isNotBlank() }?.let {
            try { store.getUserPersona(it)?.presentation } catch (_: Exception) { null }
        }

        fun field(label: String, value: String?): CoreField? =
            value?.trim()?.takeIf { it.isNotEmpty() }?.let { CoreField(label, it) }

        val cores = ArrayList<CardCore>()
        world?.let {
            cores.add(
                CardCore(
                    "World: ${it.name}",
                    listOfNotNull(
                        field("Premise / Vibe", it.premiseVibe),
                        field("Cosmology", it.cosmology),
                        field("Magic Rules", it.magicRules)
                    )
                )
            )
        }
        campaign?.let {
            cores.add(
                CardCore(
                    "Campaign: ${it.name}",
                    listOfNotNull(
                        field("Quest Anchor", it.questAnchor),
                        field("Active Scene", it.activeScene)
                    )
                )
            )
        }
        character?.let {
            cores.add(
                CardCore(
                    "They are playing: ${it.name}",
                    listOfNotNull(
                        field("Species", it.species),
                        field("Class", it.charClass),
                        field("Core Personality", it.corePersonality),
                        field("Physical Description", it.physicalDescription),
                        field("Goals & Drives", it.goalsDrives)
                    )
                )
            )
        }
        // Status gates NPC cores (§6b): alive/incapacitated inject in full;
        // dead/enemy drop to the roster line but stay reachable via Zone 2.
        for (m in party.filter { it.status == "alive" || it.status == "incapacitated" }) {
            cores.add(
                CardCore(
                    "Party member: ${m.name}",
                    listOfNotNull(
                        field("Species", m.species),
                        field("Class", m.charClass),
                        field("Core Personality", m.corePersonality),
                        field("Physical Description", m.physicalDescription),
                        field("Goals & Drives", m.goalsDrives),
                        field("Speech Style", m.speechStyle),
                        // Always injected so the narrator knows how to treat
                        // the character (§6b).
                        field("Status", m.status.replaceFirstChar { it.uppercase() })
                    )
                )
            )
        }
        val gone = party.filter { it.status == "dead" || it.status == "enemy" }
        val rosterLine = gone.takeIf { it.isNotEmpty() }
            ?.joinToString("; ") { "${it.name} — ${it.status}" }
            ?.let { "No longer with the party: $it" }

        return SceneContext(
            userPersonaPresentation = presentation,
            cores = cores,
            rosterLine = rosterLine
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

    /** Persist suppressed memory↔lore pairs — a silent disagreement between the
     *  user's hand-written lore and a retrieved memory is the one thing the
     *  coexistence rule never allows, so the pair is recorded when the memory
     *  is dropped in the lore note's favour.
     *
     *  NOTE (corrected July 2026): these flags are currently WRITE-ONLY. No
     *  code reads or clears `enforcer.contradiction_flags` — the earlier claim
     *  that the Phase 6 Archivist "reads and clears this list" was never true
     *  (the built Archivist does not touch it). The list only accumulates,
     *  bounded to MAX_CONTRADICTION_FLAGS (newest kept). A consumer — surfacing
     *  the disagreements and a lifecycle for retiring resolved ones — is future
     *  work; until it exists nothing here changes contradiction behaviour. */
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
