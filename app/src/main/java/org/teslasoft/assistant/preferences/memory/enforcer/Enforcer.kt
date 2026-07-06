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
import org.teslasoft.assistant.preferences.memory.ModeRecord
import org.teslasoft.assistant.preferences.memory.RetrievableMemory
import org.teslasoft.assistant.preferences.memory.ScoredMemory
import org.teslasoft.assistant.preferences.memory.librarian.Librarian
import org.teslasoft.assistant.preferences.memory.librarian.VectorMath
import java.util.concurrent.ConcurrentHashMap

/**
 * The guarding half of the runtime (enforcer_librarian_spec.md): assembles
 * the per-turn memory system message — standing packet, active modes,
 * retrieved memories with protection handling attached, lore notes, scene —
 * behind the app's single generation funnel. The caller (ChatActivity) gates
 * on the memory engine tier, the per-chat kill switch, and store existence;
 * everything in here may throw, and the caller degrades to the classic
 * lorebook path (never block generation).
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

        private const val META_CONTRADICTION_FLAGS = "enforcer.contradiction_flags"
        private const val MAX_CONTRADICTION_FLAGS = 50
        private const val KEYWORD_BONUS_PER_SIGNAL = 0.05
        private const val KEYWORD_BONUS_CAP = 0.15
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
        val roleplayCharacterId: String?,
        val userPersonaId: String?
    )

    /** modelTag|modeId|signalsHash -> signal embedding, per process. */
    private val signalVectors = ConcurrentHashMap<String, FloatArray>()

    /** chatId -> protective-mode stickiness across turns (process lifetime,
     *  same as a conversation session in practice). */
    private val stickyStates = ConcurrentHashMap<String, ModeSelection.StickyState>()

    /**
     * Assemble this turn's memory system message, or null when the memory
     * system has nothing to add and the caller should run the classic lore
     * path (companion opted out, or the assembly came out empty).
     */
    fun assembleTurn(input: TurnInput): String? {
        val store = MemoryStore.getInstance(appContext)
        val notes = ArrayList<String>()

        // Operating defaults (retrieval_policy + the protective mode gradient)
        // provision as origin='system' rows only into EMPTY tables — a fresh
        // store gets working machinery, imported/user data is never touched.
        try {
            if (store.provisionOperatingDefaults(
                    DefaultOperatingData.DEFAULT_POLICY_JSON,
                    DefaultOperatingData.defaultModes()
                )
            ) {
                MemoryLog.log(appContext, "Enforcer", "info", "Operating defaults provisioned (origin=system)")
            }
        } catch (e: Exception) {
            notes.add("defaults provisioning failed: ${e.message}")
        }

        val policy = parsePolicy(store)

        // Companion resolution — auto-creating the record when the persona has
        // none yet, so a chat with a persona ALWAYS resolves to a companion.
        // Draft companions are never assembled (the memory query enforces it
        // for memories; this enforces it for hard limits / adaptations /
        // packet scope). memory_participation: 'none' -> the store contributes
        // nothing for this companion; 'global_only' -> global memories only.
        var companion: CompanionRecord? = input.personaId.takeIf { it.isNotEmpty() }?.let {
            MemoryCompanionSync.ensureCompanionForPersona(appContext, it)
        }
        if (companion?.status == "draft") companion = null
        if (companion != null && companion.memoryParticipation == "none") return null
        val scopeCompanionId =
            if (companion != null && companion.memoryParticipation == "full") companion.companionId else null

        val worldId = input.worldId?.takeIf { it.isNotBlank() }

        // D8: prefs are truth, app_state is the derived mirror (best effort).
        try {
            store.updateAppState(
                companion?.companionId, worldId,
                input.roleplayCharacterId?.takeIf { it.isNotBlank() },
                input.userPersonaId?.takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) { /* mirror only; never blocks a turn */ }

        val librarian = Librarian.getInstance(appContext)
        val candidates = store.activeMemoriesForScope(scopeCompanionId, worldId)
        val alwaysLoad = candidates.filter { it.alwaysLoad }.map { toAssembled(it, 1f, 1f) }

        // Retrieval: the librarian's search inherits the eligibility gates in
        // the store query; always-load memories live in the standing packet,
        // so they're excluded from the retrieved list (never the same fact twice).
        val query = listOf(input.userMessage, input.recentContext)
            .filter { it.isNotBlank() }.joinToString("\n")
        if (!librarian.hasUsableModel()) notes.add("no embedding model — keyword retrieval")
        val retrievedRaw: List<ScoredMemory> = try {
            librarian.search(scopeCompanionId, worldId, query, policy.topK)
        } catch (e: Exception) {
            notes.add("retrieval failed: ${e.message}")
            emptyList()
        }
        var retrieved = retrievedRaw.filter { !it.memory.alwaysLoad }
            .map { toAssembled(it.memory, it.score, it.similarity) }

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

        // Mode detection: embedding similarity vs each mode's joined signals
        // (cached per process), keyword cues as a bonus, suggested_mode from
        // retrieved protected memories activating directly, protective
        // stickiness across turns.
        val modes = try {
            detectModes(store, librarian, companion, input, policy, retrieved)
        } catch (e: Exception) {
            notes.add("mode detection failed: ${e.message}")
            emptyList()
        }

        // Budget: policy-driven, lowest-scored retrieved memories cut first;
        // lore (user-authored) charges the budget but is never cut here.
        val (kept, cut) = PromptAssembler.applyBudget(retrieved, loreChars, policy.charBudget)

        // Entity-linked expansion for the memories that made it in.
        val entitySummaries = try {
            store.entitySummariesForMemories(kept.map { it.memoryId })
        } catch (_: Exception) { LinkedHashMap() }

        // Standing packet (slot 2): compressed & cached, raw records fallback.
        val prefs = Preferences.getPreferences(appContext, input.chatId)
        val packet = try {
            StandingPacketManager.get(
                appContext, store,
                scopeKey = scopeCompanionId ?: "global",
                owner = store.getOwnerProfile(),
                directives = store.getDirectives().filter { appliesTo(it.appliesToJson, companion?.companionId) },
                alwaysLoad = alwaysLoad,
                archivistEndpointId = prefs.getArchivistEndpointId(),
                archivistModel = prefs.getArchivistModel()
            )
        } catch (e: Exception) {
            notes.add("standing packet failed: ${e.message}")
            StandingPacketManager.Packet(null, null)
        }

        // Scene (slot 5): user persona presentation in any chat; world +
        // roleplay character during a world session. Missing records degrade
        // to an emptier scene, never an error.
        val scene = buildScene(store, input, worldId)

        val components = AssemblyComponents(
            modelNote = companion?.let { modelNote(it, input.modelTag) },
            hardLimits = companion?.let { parseStringArray(it.hardLimitsJson) } ?: emptyList(),
            standingPacket = packet.text,
            modes = modes.map { it.toBlock() },
            memories = kept,
            entitySummaries = entitySummaries,
            loreNotes = loreNotes,
            scene = scene
        )
        val rendered = PromptAssembler.render(components)

        AssemblyLog.record(
            AssemblyLog.Record(
                timestamp = System.currentTimeMillis(),
                userMessage = input.userMessage,
                companionName = companion?.currentName,
                packetSource = packet.source,
                modes = modes.map { it.name },
                injected = kept.map {
                    AssemblyLog.Line(
                        it.title,
                        "(${it.provenanceMarker}) score %.3f sim %.3f%s".format(
                            it.score, it.similarity, if (it.isProtected) " [protected]" else ""
                        )
                    )
                },
                cut = cut.map { AssemblyLog.Line(it.title, "over budget (score %.3f)".format(it.score)) } +
                    suppressed.map { (m, l) -> AssemblyLog.Line(m.title, "near-duplicate of lore \"${l.label}\" — flagged") },
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
    /* mode detection                                                      */
    /* ------------------------------------------------------------------ */

    private fun detectModes(
        store: MemoryStore,
        librarian: Librarian,
        companion: CompanionRecord?,
        input: TurnInput,
        policy: Policy,
        retrieved: List<AssembledMemory>
    ): List<ModeRecord> {
        val all = store.getModes()
        if (all.isEmpty()) return emptyList()
        val applicable = all.filter {
            it.scope == "global" ||
                (companion != null && parseStringArray(it.companionIdsJson).contains(companion.companionId))
        }
        if (applicable.isEmpty()) return emptyList()

        val msgVec = librarian.embedOrNull(input.userMessage, true)
        val msgLower = input.userMessage.lowercase()

        val candidates = applicable.map { mode ->
            val signals = parseStringArray(mode.signalsJson)
            var score = 0.0
            if (msgVec != null) {
                signalVector(librarian, mode, signals)?.let { score = VectorMath.cosine(msgVec, it).toDouble() }
            }
            // Keyword cues: a signal whose words appear in the message adds a
            // small bonus (and is the whole signal when there's no model).
            var bonus = 0.0
            for (signal in signals) {
                val words = NearDuplicate.tokens(signal)
                if (words.isNotEmpty() && words.count { msgLower.contains(it) } >= 2) {
                    bonus += KEYWORD_BONUS_PER_SIGNAL
                }
            }
            score += bonus.coerceAtMost(KEYWORD_BONUS_CAP)
            if (msgVec == null && bonus >= 2 * KEYWORD_BONUS_PER_SIGNAL) {
                // No embeddings: two matched signals count as a clear trigger.
                score = policy.modeThreshold + 0.01
            }
            ModeSelection.Candidate(mode.modeId, mode.name, score, parseStringArray(mode.overridesJson))
        }

        val suggested = retrieved.mapNotNull { it.suggestedMode }.toSet()
        // suggested_mode may name a mode by id or (seed convention) by name.
        val suggestedIds = applicable.filter {
            suggested.contains(it.modeId) || suggested.any { s -> s.equals(it.name, ignoreCase = true) }
        }.map { it.modeId }.toSet()

        val sticky = stickyStates[input.chatId] ?: ModeSelection.StickyState()
        val selected = ModeSelection.select(candidates, policy.modeThreshold, suggestedIds, sticky.modeIds)

        // Advance stickiness: protective modes persist until a clear exit.
        val byId = candidates.associateBy { it.modeId }
        val activeProtective = selected.filter { ModeSelection.isProtective(it.name) }.map { it.modeId }.toSet()
        val retriggered = sticky.modeIds.any { (byId[it]?.score ?: 0.0) >= policy.modeThreshold } ||
            activeProtective.any { it !in sticky.modeIds }
        val taskCleared = candidates.any { !ModeSelection.isProtective(it.name) && it.score >= policy.modeThreshold }
        stickyStates[input.chatId] = ModeSelection.updateSticky(
            sticky, activeProtective, retriggered, taskCleared, ModeSelection.isExitSignal(input.userMessage)
        )

        // Preserve the selector's order (protective first).
        return selected.mapNotNull { s -> applicable.firstOrNull { it.modeId == s.modeId } }
    }

    private fun signalVector(librarian: Librarian, mode: ModeRecord, signals: List<String>): FloatArray? {
        if (signals.isEmpty()) return null
        val text = signals.joinToString("; ")
        val key = "${librarian.activeTag()}|${mode.modeId}|${text.hashCode()}"
        signalVectors[key]?.let { return it }
        val vec = librarian.embedOrNull(text, false) ?: return null
        signalVectors[key] = vec
        return vec
    }

    private fun ModeRecord.toBlock(): ModeBlock = ModeBlock(
        modeId = modeId,
        name = name,
        purpose = purpose,
        respond = parseStringArray(respondJson),
        avoid = parseStringArray(avoidJson)
    )

    /* ------------------------------------------------------------------ */
    /* helpers                                                             */
    /* ------------------------------------------------------------------ */

    private data class Policy(val topK: Int, val charBudget: Int, val modeThreshold: Double)

    private fun parsePolicy(store: MemoryStore): Policy {
        val json = try { store.getRetrievalPolicyJson() } catch (_: Exception) { null }
        if (json != null) {
            try {
                val obj = JSONObject(json)
                return Policy(
                    topK = obj.optInt("top_k", 8),
                    charBudget = obj.optInt("memory_char_budget", PromptAssembler.DEFAULT_CHAR_BUDGET),
                    modeThreshold = obj.optDouble("mode_threshold", ModeSelection.DEFAULT_THRESHOLD)
                )
            } catch (_: Exception) { /* fall through to defaults */ }
        }
        return Policy(8, PromptAssembler.DEFAULT_CHAR_BUDGET, ModeSelection.DEFAULT_THRESHOLD)
    }

    private fun buildScene(store: MemoryStore, input: TurnInput, worldId: String?): SceneContext {
        val presentation = input.userPersonaId?.takeIf { it.isNotBlank() }?.let {
            try { store.getUserPersona(it)?.presentation } catch (_: Exception) { null }
        }
        if (worldId == null) return SceneContext(userPersonaPresentation = presentation)
        val world = try { store.getWorld(worldId) } catch (_: Exception) { null }
            ?: return SceneContext(userPersonaPresentation = presentation)
        val character = input.roleplayCharacterId?.takeIf { it.isNotBlank() }?.let {
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
        var suggestedMode: String? = null
        m.protectionJson?.takeIf { it.isNotBlank() }?.let {
            try {
                val p = JSONObject(it)
                if (p.optBoolean("is_protected", false) ||
                    p.has("handling") || p.has("never_assume")
                ) {
                    handling = jsonToList(p.optJSONArray("handling"))
                    neverAssume = jsonToList(p.optJSONArray("never_assume"))
                    suggestedMode = p.optString("suggested_mode").takeIf { s -> s.isNotBlank() }
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
            alwaysLoad = m.alwaysLoad,
            suggestedMode = suggestedMode
        )
    }

    /** told = they said it; observed = seen over time; guessed = tentative. */
    private fun provenanceMarker(source: String?, confidence: String?): String = when {
        confidence.equals("tentative", ignoreCase = true) -> "guessed"
        source.equals("user_stated", ignoreCase = true) -> "told"
        source.equals("inferred", ignoreCase = true) -> "guessed"
        else -> "observed"
    }

    private fun modelNote(companion: CompanionRecord, modelTag: String): String? {
        return try {
            val arr = JSONArray(companion.modelAdaptationsJson)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val tag = o.optString("model_tag")
                if (tag.isNotBlank() &&
                    (tag.equals(modelTag, ignoreCase = true) || modelTag.contains(tag, ignoreCase = true))
                ) {
                    return o.optString("notes").takeIf { it.isNotBlank() }
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private fun appliesTo(appliesToJson: String, companionId: String?): Boolean {
        val list = parseStringArray(appliesToJson)
        return list.isEmpty() || (companionId != null && list.contains(companionId))
    }

    private fun parseStringArray(json: String): List<String> = try {
        jsonToList(JSONArray(json))
    } catch (_: Exception) { emptyList() }

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
