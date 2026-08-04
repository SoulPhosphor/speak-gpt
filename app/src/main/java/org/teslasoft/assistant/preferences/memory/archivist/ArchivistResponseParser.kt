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

import org.json.JSONObject

/**
 * Parses one Archivist model response into validated draft proposals (pure —
 * unit-tested in app/src/test). This layer is the gate between whatever the
 * model emitted and what may enter the store, per the owner rules and the
 * July 8 2026 addendum:
 *
 * - Only memory drafts and model-rule drafts come through. Anything else in
 *   the response — protection/handling fields (retired), companion or persona
 *   content, modes, directives, card placements (not yet designed) — is
 *   ignored, never stored.
 * - A row missing required content or carrying an unknown scope is DROPPED and
 *   counted. But an absent or unrecognized Type suggestion is NOT a drop
 *   (canonical recovery plan §5.2): it simply becomes No Type — the supported
 *   memory text is never discarded because the Type was blank or unknown.
 * - Titles are retired (§3.1) and never requested or parsed. Importance is not
 *   an AI decision (§7.2) and is never requested, parsed, or clamped — every
 *   proposal starts neutral at the filing layer. Provenance defaults to
 *   "inferred" — a memory is never marked as the user's own words unless the
 *   model explicitly claimed so ("they told me" vs "I noticed" is sacred).
 */
object ArchivistResponseParser {

    val SCOPES = setOf("global", "real_life", "companion", "project", "world", "campaign", "rp_character")

    /** Defensive bound per conversation so a runaway model can't flood the
     *  Pending queue; overflow is counted in [Parsed.dropped] and logged by
     *  the runner — never a silent cap. */
    const val MAX_MEMORIES_PER_CONVERSATION = 40
    const val MAX_RULES_PER_CONVERSATION = 5
    private const val MAX_TAGS_PER_MEMORY = 8

    data class DraftMemory(
        val content: String,
        val scope: String,
        /** The model's raw Type suggestion (may be blank or unrecognized). The
         *  filing layer maps it to a user-owned Type id — a recognized starter
         *  Type, or No Type for `lore`/blank/unknown (§5.2). Never gates the
         *  proposal. */
        val kind: String,
        val tags: List<String>,
        /** true only when the model explicitly marked provenance "stated". */
        val stated: Boolean,
        /** Free-text name of the proposed target (world/campaign/character/
         *  project) — resolved against existing records by the runner; never
         *  creates anything. Null for untargeted scopes. */
        val targetName: String?,
        /** Optional card-placement suggestion (roleplay memories only, owner
         *  design §2/§7): the NAME of an existing lore card and one of the
         *  fixed CardSections keys. Resolved and validated by the runner —
         *  an unknown card or section simply drops the suggestion, never the
         *  memory. */
        val cardName: String? = null,
        val cardSection: String? = null
    )

    data class DraftRule(val text: String)

    /** One proposed lore book entry (Step 1.7, Lorebook Memories analysis
     *  type): the [content] to inject and the [triggers] that fire it. */
    data class DraftLoreEntry(
        val content: String,
        val triggers: List<String>
    )

    data class ParsedLore(
        val entries: List<DraftLoreEntry>,
        /** Rows rejected by validation or the defensive bounds. */
        val dropped: Int
    )

    /** Defensive bounds for the lorebook path, mirroring the memory bounds. */
    const val MAX_LORE_ENTRIES_PER_CONVERSATION = 40
    private const val MAX_TRIGGERS_PER_ENTRY = 12

    data class Parsed(
        val memories: List<DraftMemory>,
        val rules: List<DraftRule>,
        /** Rows rejected by validation or the defensive bounds. */
        val dropped: Int
    )

    fun parse(raw: String): Parsed {
        val json = JSONObject(extractJsonObject(raw))
        var dropped = 0

        val memories = ArrayList<DraftMemory>()
        val memArray = json.optJSONArray("memories")
        if (memArray != null) {
            for (i in 0 until memArray.length()) {
                val o = memArray.optJSONObject(i)
                if (o == null) { dropped++; continue }
                val content = o.optString("content").trim()
                val scope = o.optString("scope").trim().lowercase()
                // The Type suggestion is optional and never a gate (§5.2). An
                // unknown or blank value is carried as-is and becomes No Type at
                // filing — it does not drop the proposal.
                val kind = o.optString("type").trim().lowercase()
                    .ifEmpty { o.optString("kind").trim().lowercase() }
                if (content.isEmpty() || scope !in SCOPES) {
                    dropped++; continue
                }
                if (memories.size >= MAX_MEMORIES_PER_CONVERSATION) { dropped++; continue }
                val tags = ArrayList<String>()
                o.optJSONArray("tags")?.let { arr ->
                    for (t in 0 until arr.length()) {
                        val tag = arr.optString(t).trim()
                        if (tag.isNotEmpty() && tag.length <= 64 &&
                            tags.none { it.equals(tag, ignoreCase = true) } &&
                            tags.size < MAX_TAGS_PER_MEMORY
                        ) tags.add(tag)
                    }
                }
                memories.add(
                    DraftMemory(
                        content = content,
                        scope = scope,
                        kind = kind,
                        tags = tags,
                        stated = o.optString("provenance").trim().equals("stated", ignoreCase = true),
                        targetName = o.optString("target").trim().ifEmpty { null },
                        cardName = o.optString("card").trim().ifEmpty { null },
                        cardSection = o.optString("card_section").trim().lowercase().ifEmpty { null }
                    )
                )
            }
        }

        val rules = ArrayList<DraftRule>()
        val ruleArray = json.optJSONArray("model_rules")
        if (ruleArray != null) {
            for (i in 0 until ruleArray.length()) {
                val o = ruleArray.optJSONObject(i)
                val text = o?.optString("text")?.trim().orEmpty()
                if (text.isEmpty()) { dropped++; continue }
                if (rules.size >= MAX_RULES_PER_CONVERSATION) { dropped++; continue }
                rules.add(DraftRule(text))
            }
        }

        return Parsed(memories, rules, dropped)
    }

    /**
     * Parse one model response in the Lorebook Memories analysis type (Step
     * 1.7). Only lore book entries come through. An entry missing content OR
     * every trigger keyword is DROPPED and counted — a lore book entry with no
     * trigger could never fire, so it is never coerced into a keywordless
     * entry. Same gate philosophy as [parse].
     */
    fun parseLore(raw: String): ParsedLore {
        val json = JSONObject(extractJsonObject(raw))
        var dropped = 0
        val entries = ArrayList<DraftLoreEntry>()
        val arr = json.optJSONArray("entries")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null) { dropped++; continue }
                val content = o.optString("content").trim()
                if (content.isEmpty()) { dropped++; continue }
                val triggers = ArrayList<String>()
                o.optJSONArray("triggers")?.let { t ->
                    for (k in 0 until t.length()) {
                        val kw = t.optString(k).trim()
                        if (kw.isNotEmpty() && kw.length <= 128 &&
                            triggers.none { it.equals(kw, ignoreCase = true) } &&
                            triggers.size < MAX_TRIGGERS_PER_ENTRY
                        ) triggers.add(kw)
                    }
                }
                // A lore book entry with no trigger keyword can never fire —
                // reject it rather than file a dead entry.
                if (triggers.isEmpty()) { dropped++; continue }
                if (entries.size >= MAX_LORE_ENTRIES_PER_CONVERSATION) { dropped++; continue }
                entries.add(DraftLoreEntry(content, triggers))
            }
        }
        return ParsedLore(entries, dropped)
    }

    /** Models often wrap JSON in prose or a markdown fence; take the outermost
     *  object. Throws (caller catches per conversation) when there is none. */
    private fun extractJsonObject(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start in 0 until end) { "no JSON object in response" }
        return raw.substring(start, end + 1)
    }
}
