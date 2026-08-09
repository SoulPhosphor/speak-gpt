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

    /** Minimal restart/discard payload for encrypted temporary candidate state. */
    fun payload(draft: ArchivistResponseParser.DraftMemory): String = JSONObject().apply {
        put("content", draft.content)
        put("scope", draft.scope)
        put("type_id", draft.typeIdSuggestion)
        put("tags", JSONArray(draft.tags))
        put("target_ids", JSONArray(draft.targetIds))
        put("related_existing_memory_ids", JSONArray(draft.relatedExistingMemoryIds))
        put("unresolved_target", draft.unresolvedTargetReference)
    }.toString()

    fun payload(rule: ArchivistResponseParser.DraftRule): String =
        JSONObject().put("text", rule.text).toString()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
