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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The validated candidate domain objects (canonical recovery plan Phase 2,
 * items 5 & 6). Covers:
 *
 *  9.  Companion candidates require exactly one companion target.
 *  10. A Companion candidate cannot leak to a different companion (validation
 *      side; the retrieval-isolation side is a real-storage test).
 *  11. General, Companion, and Model Rule candidates remain distinct validated
 *      types — and Model Rules carry no Type and no importance.
 */
class MemoryCandidateValidatorTest {

    private val available = setOf("c-a", "c-b")

    private fun companion(targets: List<String>, intended: String?) =
        MemoryCandidateValidator.validateCompanion(
            content = "she prefers tea",
            companionTargetIds = targets,
            intendedCompanionId = intended,
            availableCompanionIds = available
        )

    /* --------------------------- item 9 / item 5 --------------------------- */

    @Test
    fun companionRequiresExactlyOneTarget() {
        // Zero targets → rejected, not filed as General.
        assertEquals(
            CandidateError.COMPANION_NO_TARGET,
            (companion(emptyList(), "c-a") as CandidateResult.Invalid).error
        )
        // Several targets → rejected.
        assertEquals(
            CandidateError.COMPANION_MULTIPLE_TARGETS,
            (companion(listOf("c-a", "c-b"), null) as CandidateResult.Invalid).error
        )
        // Exactly one available target → valid, and it is that companion.
        val ok = companion(listOf("c-a"), "c-a")
        assertTrue(ok is CandidateResult.Valid)
        assertEquals("c-a", (ok as CandidateResult.Valid).candidate.companionId)
        assertEquals(SCOPE_COMPANION, ok.candidate.scope)
    }

    /* -------------------------------- item 10 ------------------------------ */

    @Test
    fun companionCandidateCannotTargetADifferentCompanion() {
        // Target B while the intended/current companion is A → rejected.
        assertEquals(
            CandidateError.COMPANION_WRONG_TARGET,
            (companion(listOf("c-b"), "c-a") as CandidateResult.Invalid).error
        )
    }

    @Test
    fun unavailableCompanionTargetIsRejectedNotFalledBackToGeneral() {
        // A target that is not an available companion must NOT quietly become a
        // General memory (item 5): explicit rejection instead.
        assertEquals(
            CandidateError.COMPANION_TARGET_UNAVAILABLE,
            (companion(listOf("c-ghost"), null) as CandidateResult.Invalid).error
        )
    }

    /* ------------------------------ item 6 --------------------------------- */

    @Test
    fun generalCandidateCannotSecretlyCarryCompanionTargeting() {
        val result = MemoryCandidateValidator.validateGeneral(
            scope = "global",
            content = "the office is on 5th street",
            companionTargetIds = listOf("c-a")
        )
        assertEquals(
            CandidateError.GENERAL_HAS_COMPANION_TARGET,
            (result as CandidateResult.Invalid).error
        )
    }

    @Test
    fun generalCandidateCannotDeclareCompanionScope() {
        val result = MemoryCandidateValidator.validateGeneral(
            scope = SCOPE_COMPANION,
            content = "belongs on the companion path"
        )
        assertEquals(
            CandidateError.GENERAL_SCOPE_IS_COMPANION,
            (result as CandidateResult.Invalid).error
        )
    }

    /* ------------------------------ item 11 -------------------------------- */

    @Test
    fun theThreeCandidateKindsAreDistinctTypes() {
        val general: MemoryCandidate =
            (MemoryCandidateValidator.validateGeneral("global", "a general fact") as CandidateResult.Valid).candidate
        val comp: MemoryCandidate = (companion(listOf("c-a"), "c-a") as CandidateResult.Valid).candidate
        val rule = (MemoryCandidateValidator.validateModelRule("avoid purple prose", listOf("glm-5"))
            as CandidateResult.Valid).candidate

        assertTrue(general is MemoryCandidate.General)
        assertFalse(general is MemoryCandidate.CompanionTargeted)
        assertTrue(comp is MemoryCandidate.CompanionTargeted)
        assertFalse(comp is MemoryCandidate.General)
        // A Model Rule is NOT a MemoryCandidate at all — a separate stream.
        assertFalse("Model Rule must not be an Associative Memory candidate",
            MemoryCandidate::class.java.isInstance(rule))
    }

    /* ------------------ item 6 / review finding 2: Model Rules ------------- */

    @Test
    fun modelRuleDraftIsValidWithAnEmptyModelList() {
        // The approved Draft workflow lets the model list stay empty until the
        // user assigns it on approval (review finding 2): nonblank text alone is
        // a valid draft.
        val result = MemoryCandidateValidator.validateModelRule("prefers terse replies")
        assertTrue(result is CandidateResult.Valid)
        val rule = (result as CandidateResult.Valid).candidate
        assertEquals("prefers terse replies", rule.text)
        assertTrue("draft keeps an empty model list until approval", rule.modelStrings.isEmpty())
    }

    @Test
    fun modelRulePreservesAssignedModelStringsWhenPresent() {
        val rule = (MemoryCandidateValidator.validateModelRule("no purple prose", listOf("glm-5", " glm-5 ", ""))
            as CandidateResult.Valid).candidate
        // Trimmed, de-duplicated, blanks dropped.
        assertEquals(listOf("glm-5"), rule.modelStrings)
    }

    @Test
    fun modelRuleWithBlankTextIsRejected() {
        assertEquals(
            CandidateError.MODEL_RULE_INCOMPLETE,
            (MemoryCandidateValidator.validateModelRule("   ") as CandidateResult.Invalid).error
        )
    }

    @Test
    fun modelRuleCarriesNoTypeAndNoImportance() {
        // Item 14 at the domain level: ModelRuleCandidate has no Memory Type and
        // no importance to acquire — the fields simply do not exist on the type.
        val rule = (MemoryCandidateValidator.validateModelRule("keep replies short", listOf("glm-5", "glm-5-air"))
            as CandidateResult.Valid).candidate
        val fieldNames = rule.javaClass.declaredFields.map { it.name }
        assertFalse("Model Rule must have no Memory Type", fieldNames.any { it.equals("typeId", true) })
        assertFalse("Model Rule must have no importance", fieldNames.any { it.contains("importance", true) })
    }
}
