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

package org.teslasoft.assistant.preferences.backup.portable

import org.teslasoft.assistant.preferences.dto.CompanionPromptVariant

/**
 * One prompt in an ordered, user-editable prompt collection, as carried by a
 * portable backup (multi-prompt-backup-restore-plan.md, "Shared portable
 * prompt-variant model"). Plain data, no Android APIs, reusable by any system
 * that later gains a variable prompt collection.
 *
 * [id] is the stable identity. It is never derived from [name] and never
 * generated, rewritten, or deduplicated while reading a backup.
 */
data class PortablePromptVariant(
    val id: String,
    val name: String,
    val text: String,
    val isDefault: Boolean
) {
    /** Storage form used by `<personaId>_prompt_variants`. Copies every field as-is. */
    fun toCompanionVariant(): CompanionPromptVariant =
        CompanionPromptVariant(id = id, name = name, text = text, isDefault = isDefault)

    companion object {
        /** Copies every field as-is; never mints an id. */
        fun fromCompanionVariant(variant: CompanionPromptVariant): PortablePromptVariant =
            PortablePromptVariant(variant.id, variant.name, variant.text, variant.isDefault)
    }
}

/**
 * Data-integrity rules for a portable prompt collection. These describe what
 * a sound backup contains; they are not UI behavior.
 *
 * - list order is meaningful and preserved;
 * - at least one variant;
 * - every id nonblank and unique within the collection;
 * - exactly one default;
 * - blank names and blank text are permitted (intentional user content).
 */
object PortablePromptVariantRules {

    enum class Violation { EMPTY, BLANK_ID, DUPLICATE_ID, NO_DEFAULT, MULTIPLE_DEFAULTS }

    /** The first rule [variants] breaks, or null when the collection is sound. */
    fun violation(variants: List<PortablePromptVariant>): Violation? {
        if (variants.isEmpty()) return Violation.EMPTY
        if (variants.any { it.id.isBlank() }) return Violation.BLANK_ID
        if (variants.map { it.id }.distinct().size != variants.size) return Violation.DUPLICATE_ID
        return when (variants.count { it.isDefault }) {
            0 -> Violation.NO_DEFAULT
            1 -> null
            else -> Violation.MULTIPLE_DEFAULTS
        }
    }

    /** The single default of a sound collection. */
    fun defaultVariant(variants: List<PortablePromptVariant>): PortablePromptVariant =
        variants.single { it.isDefault }

    /**
     * The in-memory form of a companion that predates prompt variants: exactly
     * one default variant named "Prompt 1" holding [prompt], with the same
     * deterministic id the app assigns when it first loads such a companion
     * (derived from `personaId + "_prompt_1"`). Nothing is written anywhere.
     */
    fun legacySingleVariant(personaId: String, prompt: String): List<PortablePromptVariant> {
        require(personaId.isNotBlank()) { "legacy conversion needs the companion id" }
        return CompanionPromptVariant.migrateFromSinglePrompt(prompt, personaId)
            .map(PortablePromptVariant::fromCompanionVariant)
    }
}
