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
 * - A row missing required fields or carrying an unknown scope/type is
 *   DROPPED and counted, never coerced into a guess (the integration plan's
 *   "anything failing validation is dropped", not silently mutated).
 * - Importance is clamped to 1..5; provenance defaults to "inferred" — a
 *   memory is never marked as the user's own words unless the model
 *   explicitly claimed so ("they told me" vs "I noticed" is sacred).
 */
object ArchivistResponseParser {

    val SCOPES = setOf("global", "real_life", "companion", "project", "world", "campaign", "rp_character")
    val KINDS = setOf("fact", "preference", "event", "status", "instruction", "lore")

    /** Defensive bound per conversation so a runaway model can't flood the
     *  Pending queue; overflow is counted in [Parsed.dropped] and logged by
     *  the runner — never a silent cap. */
    const val MAX_MEMORIES_PER_CONVERSATION = 40
    const val MAX_RULES_PER_CONVERSATION = 5
    private const val MAX_TAGS_PER_MEMORY = 8

    data class DraftMemory(
        val title: String,
        val content: String,
        val scope: String,
        val kind: String,
        val importance: Int,
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
                val title = o.optString("title").trim()
                val content = o.optString("content").trim()
                val scope = o.optString("scope").trim().lowercase()
                val kind = o.optString("type").trim().lowercase()
                    .ifEmpty { o.optString("kind").trim().lowercase() }
                if (title.isEmpty() || content.isEmpty() || scope !in SCOPES || kind !in KINDS) {
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
                        title = title,
                        content = content,
                        scope = scope,
                        kind = kind,
                        importance = o.optInt("importance", 3).coerceIn(1, 5),
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

    /** Models often wrap JSON in prose or a markdown fence; take the outermost
     *  object. Throws (caller catches per conversation) when there is none. */
    private fun extractJsonObject(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start in 0 until end) { "no JSON object in response" }
        return raw.substring(start, end + 1)
    }
}
