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

package org.teslasoft.assistant.preferences.memory

/**
 * The one shared importance-access path for ranking (canonical recovery plan
 * Phase 2, item 3).
 *
 * The "Use Importance Ratings" master toggle
 * ([org.teslasoft.assistant.preferences.Preferences.getUseImportanceRatings])
 * decides ONLY whether a memory's stored importance rating influences its
 * retrieval score. Every ranking path routes the stored importance weight
 * through [effectiveImportanceWeight] so the toggle behaves identically
 * everywhere:
 *
 *  - Off — importance contributes EXACTLY ZERO to the score. The stored
 *    importance value on each memory is never read into the score, never
 *    modified, never reset, never deleted.
 *  - On  — the stored importance weight is used as-is, so the ratings the user
 *    already set take effect again with no rewrite.
 *
 * This gate touches only the scoring WEIGHT. It never rewrites memories and
 * never changes eligibility: importance is added to an already-eligible
 * candidate's score after the relevance floor / token-hit gate in the
 * librarian, so a zero (or non-zero) importance weight can never make an
 * otherwise ineligible memory eligible.
 *
 * Pure Kotlin, unit tested (ImportanceRankingTest).
 */
object ImportanceRanking {

    /**
     * The importance weight the ranker should actually use.
     *
     * @param storedWeight the importance blend coefficient from the (bounded)
     *   retrieval policy — the value that would apply if the setting were On.
     * @param useImportanceRatings the "Use Importance Ratings" master toggle.
     * @return [storedWeight] when the toggle is On; `0.0` when Off, so
     *   importance contributes exactly zero. The stored ratings themselves are
     *   untouched either way — turning the toggle back On restores their effect.
     */
    fun effectiveImportanceWeight(storedWeight: Double, useImportanceRatings: Boolean): Double =
        if (useImportanceRatings) storedWeight else 0.0
}
