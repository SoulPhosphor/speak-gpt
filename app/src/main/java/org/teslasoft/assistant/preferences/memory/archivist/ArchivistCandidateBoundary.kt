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

import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.memory.MemoryMatch
import java.security.MessageDigest
import java.util.Locale

/**
 * Stage D's complete per-chat candidate boundary. It is deliberately narrow:
 * exact identity is deterministic, while differently-worded candidates remain
 * human-reviewable. Relationship hints that were validated against request-
 * local M aliases are preserved so candidates tied to the same existing memory
 * enter Possible Match instead of becoming unrelated ordinary drafts.
 */
object ArchivistCandidateBoundary {

    data class Collected(
        val memories: List<ArchivistResponseParser.DraftMemory>,
        val exactDuplicatesRemoved: Int
    )

    fun collect(chunks: List<List<ArchivistResponseParser.DraftMemory>>): Collected =
        collectFlat(chunks.flatten())

    fun collectFlat(drafts: List<ArchivistResponseParser.DraftMemory>): Collected {
        val byIdentity = LinkedHashMap<String, ArchivistResponseParser.DraftMemory>()
        var duplicates = 0
        for (draft in drafts) {
            val key = identityKey(draft)
            val prior = byIdentity[key]
            if (prior == null) {
                byIdentity[key] = draft
            } else {
                duplicates++
                // Exact repeats sometimes carry a relationship hint or tag the
                // first occurrence omitted. Preserve that validated information
                // while still filing one candidate.
                byIdentity[key] = prior.copy(
                    tags = (prior.tags + draft.tags)
                        .distinctBy { it.lowercase(Locale.ROOT) }.take(8),
                    relatedExistingMemoryIds =
                        (prior.relatedExistingMemoryIds + draft.relatedExistingMemoryIds)
                            .distinct().take(10),
                    unresolvedTargetReference =
                        prior.unresolvedTargetReference || draft.unresolvedTargetReference
                )
            }
        }
        return Collected(byIdentity.values.toList(), duplicates)
    }

    /** A staged memory proposed again by a later section: the stored
     *  payload with the repeat's tags and relationship hints merged in, as
     *  [collectFlat] merges them in an uninterrupted scan. Null for other
     *  streams or unreadable payloads (the stored payload is kept). */
    fun mergeRepeatPayload(stream: String, stored: String, repeat: String): String? {
        if (stream != "memory") return null
        val first = memoryFromPayload(stored) ?: return null
        val second = memoryFromPayload(repeat) ?: return null
        val merged = collectFlat(listOf(first, second)).memories.singleOrNull() ?: return null
        return payload(merged)
    }

    /** Exact content + supplied placement + proposed Type. Scene target is a
     * fallback only for the request's implicit Companion target. */
    fun identityKey(draft: ArchivistResponseParser.DraftMemory): String {
        val targets = draft.targetIds.ifEmpty {
            listOfNotNull(draft.scene?.targetIdFor(draft.scope))
        }
        return listOf(
            MemoryMatch.normalizeContent(draft.content),
            MemoryMatch.placementKey(draft.scope, targets),
            draft.typeIdSuggestion.orEmpty()
        ).joinToString("\u0000")
    }

    /** Hash stored in encrypted temporary run state; never copied into a
     * Pending or Active memory. */
    fun candidateHash(draft: ArchivistResponseParser.DraftMemory): String =
        sha256(identityKey(draft))

    fun ruleHash(rule: ArchivistResponseParser.DraftRule): String =
        sha256(MemoryMatch.normalizeContent(rule.text))

    /** The encrypted temporary payload of a validated working draft. It
     *  carries everything filing needs, including the stamped scene, so a
     *  resumed scan files exactly what an uninterrupted one would. */
    fun payload(draft: ArchivistResponseParser.DraftMemory): String = JSONObject().apply {
        put("content", draft.content)
        put("scope", draft.scope)
        put("type_id", draft.typeIdSuggestion)
        put("tags", JSONArray(draft.tags))
        put("target_ids", JSONArray(draft.targetIds))
        put("related_existing_memory_ids", JSONArray(draft.relatedExistingMemoryIds))
        put("unresolved_target", draft.unresolvedTargetReference)
        draft.targetName?.let { put("target_name", it) }
        draft.scene?.let { scene ->
            put("scene", JSONObject().apply {
                scene.companionId?.let { put("companion", it) }
                scene.worldId?.let { put("world", it) }
                scene.campaignId?.let { put("campaign", it) }
                scene.roleplayCharacterId?.let { put("character", it) }
                scene.projectId?.let { put("project", it) }
            })
        }
    }.toString()

    fun payload(rule: ArchivistResponseParser.DraftRule): String =
        JSONObject().put("text", rule.text).toString()

    fun payload(entry: ArchivistResponseParser.DraftLoreEntry): String =
        JSONObject().put("content", entry.content).put("triggers", JSONArray(entry.triggers)).toString()

    fun loreHash(entry: ArchivistResponseParser.DraftLoreEntry): String =
        sha256(entry.content.trim())

    fun memoryFromPayload(json: String): ArchivistResponseParser.DraftMemory? = try {
        val o = JSONObject(json)
        val scene = o.optJSONObject("scene")?.let { s ->
            fun text(key: String) = s.optString(key, "").takeIf { it.isNotBlank() }
            ArchivistSceneContext(text("companion"), text("world"), text("campaign"), text("character"), text("project"))
        }
        ArchivistResponseParser.DraftMemory(
            content = o.getString("content"),
            scope = o.getString("scope"),
            typeIdSuggestion = o.optString("type_id", "").takeIf { it.isNotBlank() && it != "null" },
            tags = strings(o.optJSONArray("tags")),
            targetName = o.optString("target_name", "").takeIf { it.isNotBlank() },
            targetIds = strings(o.optJSONArray("target_ids")),
            relatedExistingMemoryIds = strings(o.optJSONArray("related_existing_memory_ids")),
            unresolvedTargetReference = o.optBoolean("unresolved_target", false),
            scene = scene
        )
    } catch (_: Exception) {
        null
    }

    fun ruleFromPayload(json: String): ArchivistResponseParser.DraftRule? = try {
        ArchivistResponseParser.DraftRule(JSONObject(json).getString("text"))
    } catch (_: Exception) {
        null
    }

    fun loreFromPayload(json: String): ArchivistResponseParser.DraftLoreEntry? = try {
        val o = JSONObject(json)
        ArchivistResponseParser.DraftLoreEntry(o.getString("content"), strings(o.optJSONArray("triggers")))
    } catch (_: Exception) {
        null
    }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
