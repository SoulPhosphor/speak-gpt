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
 * Separate, VALIDATED domain objects for the three kinds of thing the memory
 * system can propose for review (canonical recovery plan Phase 2, items 5 & 6).
 *
 * These are deliberately three distinct types, not one loosely-typed object
 * with flags:
 *
 *  1. [MemoryCandidate.General]            — a General Associative Memory proposal.
 *  2. [MemoryCandidate.CompanionTargeted] — a Companion-targeted Associative
 *     Memory proposal (exactly one companion target, by construction).
 *  3. [ModelRuleCandidate]        — a Model Rule proposal. NOT an Associative
 *     Memory: it is a separate output/storage stream, carries no Memory Type
 *     and no importance rating, and never passes through Associative-Memory
 *     filing.
 *
 * Because the companion target lives on [MemoryCandidate.CompanionTargeted] alone and
 * is a single non-null id, a General candidate CANNOT secretly carry companion
 * targeting and a Companion candidate CANNOT carry zero or several targets. The
 * validator ([MemoryCandidateValidator]) is the only supported way to build a
 * candidate from raw proposal data; it returns explicit errors rather than
 * silently converting one candidate kind into another.
 *
 * Pure Kotlin (no Android), unit tested (MemoryCandidateValidatorTest).
 */

/** The Companion-memory domain definition (Phase 2, item 5). A Companion
 *  memory is an Associative Memory with `scope = companion` and exactly one
 *  specific companion target. */
const val SCOPE_COMPANION = "companion"

/**
 * Approved memory fields common to both Associative candidate kinds. The
 * candidate is deliberately transport-blind and authorship-blind: it does NOT
 * carry where it came from (API Memory Assistant vs computer import vs manual),
 * any source authorship (`origin`), any importance (every proposal files at 0 —
 * importance is a review/edit decision, not a candidate input), or any
 * provenance. None of that reaches the memory object through this path.
 */
sealed class MemoryCandidate {
    /** The memory body. Never blank (the validator rejects blank content). */
    abstract val content: String
    /** The user-owned Memory Type id, or null for No Type. */
    abstract val typeId: String?
    /** Organizing tags. */
    abstract val tags: List<String>

    /** The memory's primary scope category. */
    abstract val scope: String

    /**
     * A General Associative Memory proposal: any scope EXCEPT companion. It has
     * no companion field at all, so it can never carry hidden companion
     * targeting. Named targets (world / campaign / rp_character / project) ride
     * their own id lists and are multi-select, exactly as [MemoryRecord] models
     * them. It carries NO analyzer card-placement suggestion (Phase 2 review):
     * that metadata is not part of the approved response contract.
     */
    data class General(
        override val scope: String,
        override val content: String,
        override val typeId: String? = null,
        override val tags: List<String> = emptyList(),
        val worldIds: List<String> = emptyList(),
        val campaignIds: List<String> = emptyList(),
        val roleplayCharacterIds: List<String> = emptyList(),
        val projectIds: List<String> = emptyList()
    ) : MemoryCandidate()

    /**
     * A Companion-targeted Associative Memory proposal. `scope` is always
     * [SCOPE_COMPANION] and [companionId] is a single non-null target — the
     * exactly-one-target rule is structural, not a runtime check. Retrieval of
     * this memory is restricted to [companionId]; a Companion memory never
     * becomes a generic General memory.
     */
    data class CompanionTargeted(
        val companionId: String,
        override val content: String,
        override val typeId: String? = null,
        override val tags: List<String> = emptyList()
    ) : MemoryCandidate() {
        override val scope: String get() = SCOPE_COMPANION
    }
}

/**
 * A Model Rule proposal (Phase 2, item 6). Deliberately NOT a [MemoryCandidate]:
 * Model Rules are a separate stream. No Memory Type, no importance — the fields
 * simply do not exist here, so a Model Rule can never acquire them by passing
 * through Associative-Memory code.
 */
data class ModelRuleCandidate(
    val text: String,
    val sourceModelString: String? = null
)

/** An explicit validation failure. Validation NEVER silently converts one
 *  candidate kind into another; a malformed candidate becomes one of these. */
enum class CandidateError {
    /** The memory body is blank. */
    EMPTY_CONTENT,
    /** A Companion candidate arrived with no companion target. */
    COMPANION_NO_TARGET,
    /** A Companion candidate arrived with more than one companion target. */
    COMPANION_MULTIPLE_TARGETS,
    /** A Companion candidate targets a companion other than the selected /
     *  current intended companion. */
    COMPANION_WRONG_TARGET,
    /** A Companion candidate's target is not an available companion — it must
     *  be rejected/quarantined, NOT allowed to fall back to General. */
    COMPANION_TARGET_UNAVAILABLE,
    /** A General candidate arrived carrying companion targeting. */
    GENERAL_HAS_COMPANION_TARGET,
    /** A General candidate declared the companion scope (it must use the
     *  companion validator instead). */
    GENERAL_SCOPE_IS_COMPANION,
    /** A Model Rule candidate has blank text. Exact targets are assigned only
     *  by the user when approving the draft. */
    MODEL_RULE_INCOMPLETE
}

/** The result of validating raw proposal data into a typed candidate. */
sealed class CandidateResult<out T> {
    data class Valid<T>(val candidate: T) : CandidateResult<T>()
    data class Invalid(val error: CandidateError) : CandidateResult<Nothing>()
}

/**
 * The one supported path from raw proposal data to a validated candidate
 * (Phase 2, items 5 & 6). Every filing route validates here; the errors are
 * explicit so a caller can reject or quarantine a malformed candidate instead
 * of it silently becoming the wrong kind of memory.
 */
object MemoryCandidateValidator {

    /**
     * Validate a General Associative Memory proposal. Rejects a companion scope
     * (that belongs to [validateCompanion]) and any companion targeting sneaking
     * in through [companionTargetIds]. Named targets are passed through as-is.
     */
    fun validateGeneral(
        scope: String,
        content: String,
        typeId: String? = null,
        tags: List<String> = emptyList(),
        worldIds: List<String> = emptyList(),
        campaignIds: List<String> = emptyList(),
        roleplayCharacterIds: List<String> = emptyList(),
        projectIds: List<String> = emptyList(),
        companionTargetIds: List<String> = emptyList()
    ): CandidateResult<MemoryCandidate.General> {
        if (content.isBlank()) return CandidateResult.Invalid(CandidateError.EMPTY_CONTENT)
        if (scope == SCOPE_COMPANION) return CandidateResult.Invalid(CandidateError.GENERAL_SCOPE_IS_COMPANION)
        if (companionTargetIds.isNotEmpty()) {
            return CandidateResult.Invalid(CandidateError.GENERAL_HAS_COMPANION_TARGET)
        }
        return CandidateResult.Valid(
            MemoryCandidate.General(
                scope = scope,
                content = content.trim(),
                typeId = typeId,
                tags = tags,
                worldIds = worldIds,
                campaignIds = campaignIds,
                roleplayCharacterIds = roleplayCharacterIds,
                projectIds = projectIds
            )
        )
    }

    /**
     * Validate a Companion-targeted Associative Memory proposal against the
     * Phase 2 item 5 rules. [companionTargetIds] is the raw target list a
     * transport supplied; [intendedCompanionId] is the selected/current
     * companion the candidate must be for; [availableCompanionIds] is the set of
     * companions that actually exist to be targeted.
     *
     * A malformed candidate is rejected with an explicit [CandidateError]; it is
     * NEVER quietly re-filed as a General memory (in particular an unavailable
     * target does not fall back to General — [CandidateError.COMPANION_TARGET_UNAVAILABLE]).
     */
    fun validateCompanion(
        content: String,
        companionTargetIds: List<String>,
        intendedCompanionId: String?,
        availableCompanionIds: Set<String>,
        typeId: String? = null,
        tags: List<String> = emptyList()
    ): CandidateResult<MemoryCandidate.CompanionTargeted> {
        if (content.isBlank()) return CandidateResult.Invalid(CandidateError.EMPTY_CONTENT)
        val targets = companionTargetIds.filter { it.isNotBlank() }.distinct()
        when {
            targets.isEmpty() -> return CandidateResult.Invalid(CandidateError.COMPANION_NO_TARGET)
            targets.size > 1 -> return CandidateResult.Invalid(CandidateError.COMPANION_MULTIPLE_TARGETS)
        }
        val target = targets.first()
        if (intendedCompanionId != null && target != intendedCompanionId) {
            return CandidateResult.Invalid(CandidateError.COMPANION_WRONG_TARGET)
        }
        if (target !in availableCompanionIds) {
            return CandidateResult.Invalid(CandidateError.COMPANION_TARGET_UNAVAILABLE)
        }
        return CandidateResult.Valid(
            MemoryCandidate.CompanionTargeted(
                companionId = target,
                content = content.trim(),
                typeId = typeId,
                tags = tags
            )
        )
    }

    /**
     * Validate a Model Rule proposal. Model Rules never receive a Memory Type or
     * importance, so none are accepted here. A valid rule requires only nonblank
     * text. Archivist candidates cannot carry model targets: the approved
     * Draft workflow requires the user to assign an exact endpoint/model pair
     * when approving the rule. Blank text is the only rejection.
     */
    fun validateModelRule(
        text: String,
        sourceModelString: String? = null
    ): CandidateResult<ModelRuleCandidate> {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            return CandidateResult.Invalid(CandidateError.MODEL_RULE_INCOMPLETE)
        }
        return CandidateResult.Valid(ModelRuleCandidate(cleanText, sourceModelString))
    }
}
