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

package org.teslasoft.assistant.preferences.memory.librarian

import org.json.JSONObject
import org.teslasoft.assistant.preferences.memory.RetrievalPolicy
import org.teslasoft.assistant.preferences.memory.enforcer.PromptAssembler

/**
 * Every number that controls live memory search, as stored in the
 * `retrieval_policy` record: the memory limit (`top_k`), the memory space
 * (`memory_char_budget`), and the [LiveRetrieval.Params] under `live_search`.
 * The Memory Search Test screen reads and writes this; chats read it every
 * message, so applied changes take effect without new code.
 */
data class SearchTuning(
    val params: LiveRetrieval.Params = LiveRetrieval.Params(),
    val maxMemories: Int = RetrievalPolicy.DEFAULT_TOP_K,
    val memorySpace: Int = PromptAssembler.DEFAULT_CHAR_BUDGET
) {
    companion object {
        const val KEY_LIVE_SEARCH = "live_search"
        private const val KEY_TOP_K = "top_k"
        private const val KEY_CHAR_BUDGET = "memory_char_budget"

        val MAX_MEMORIES_RANGE = RetrievalPolicy.MIN_TOP_K..RetrievalPolicy.MAX_TOP_K
        val MEMORY_SPACE_RANGE = RetrievalPolicy.MIN_CHAR_BUDGET..RetrievalPolicy.MAX_CHAR_BUDGET
        const val SMALL_LIBRARY_SIZE_MAX = 1000

        /** Reads the stored policy. Missing or unreadable values keep their
         *  defaults; stored values are bounded to safe ranges. */
        fun fromPolicyJson(json: String?): SearchTuning {
            val obj = try { json?.let { JSONObject(it) } } catch (_: Exception) { null }
                ?: return SearchTuning()
            val raw = HashMap<String, Double?>()
            obj.optJSONObject(KEY_LIVE_SEARCH)?.let { live ->
                val keys = live.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    raw[key] = (live.opt(key) as? Number)?.toDouble()
                }
            }
            return SearchTuning(
                params = LiveRetrieval.Params.bounded(raw).first,
                maxMemories = RetrievalPolicy.boundTopK(obj.opt(KEY_TOP_K)).value,
                memorySpace = RetrievalPolicy.boundCharBudget(obj.opt(KEY_CHAR_BUDGET)).value
            )
        }

        /** Writes [tuning] into the stored policy, keeping every other key
         *  (such as the Memory Assistant's ranking weights) unchanged. */
        fun mergeIntoPolicyJson(existing: String?, tuning: SearchTuning): String {
            val obj = try { existing?.let { JSONObject(it) } } catch (_: Exception) { null } ?: JSONObject()
            obj.put(KEY_TOP_K, tuning.maxMemories)
            obj.put(KEY_CHAR_BUDGET, tuning.memorySpace)
            val live = JSONObject()
            for ((key, value) in tuning.liveSearchValues()) live.put(key, value)
            obj.put(KEY_LIVE_SEARCH, live)
            return obj.toString()
        }
    }

    /** The `live_search` values, keyed as [LiveRetrieval.Params.bounded] reads them. */
    fun liveSearchValues(): Map<String, Double> = mapOf(
        LiveRetrieval.Params.KEY_SIMILARITY_FLOOR to params.similarityFloor,
        LiveRetrieval.Params.KEY_STANDOUT_MIN to params.standoutMin,
        LiveRetrieval.Params.KEY_KEYWORD_MIN to params.keywordMin,
        LiveRetrieval.Params.KEY_MIN_SPREAD to params.minSpread,
        LiveRetrieval.Params.KEY_SMALL_LIBRARY_SIZE to params.smallLibrarySize.toDouble(),
        LiveRetrieval.Params.KEY_SMALL_LIBRARY_FLOOR to params.smallLibraryFloor,
        LiveRetrieval.Params.KEY_FULL_STRENGTH_SIMILARITY to params.fullStrengthSimilarity,
        LiveRetrieval.Params.KEY_AGREEMENT_BONUS to params.agreementBonus,
        LiveRetrieval.Params.KEY_IMPORTANCE_WEIGHT to params.importanceWeight
    )
}
