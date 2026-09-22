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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.CompanionPromptVariant
import java.util.UUID

/** The shared portable prompt-variant rules and their storage mapping. */
class PortablePromptVariantTest {

    private val sound = listOf(
        PortablePromptVariant("a", "First", "one", false),
        PortablePromptVariant("b", "", "", true),
        PortablePromptVariant("c", "Third", "three", false)
    )

    @Test
    fun soundCollectionHasNoViolation() {
        assertNull(PortablePromptVariantRules.violation(sound))
        assertEquals("b", PortablePromptVariantRules.defaultVariant(sound).id)
    }

    @Test
    fun everyRuleIsEnforced() {
        assertEquals(
            PortablePromptVariantRules.Violation.EMPTY,
            PortablePromptVariantRules.violation(emptyList())
        )
        assertEquals(
            PortablePromptVariantRules.Violation.BLANK_ID,
            PortablePromptVariantRules.violation(sound.mapIndexed { i, x -> if (i == 0) x.copy(id = "") else x })
        )
        assertEquals(
            PortablePromptVariantRules.Violation.DUPLICATE_ID,
            PortablePromptVariantRules.violation(sound.mapIndexed { i, x -> if (i == 2) x.copy(id = "a") else x })
        )
        assertEquals(
            PortablePromptVariantRules.Violation.NO_DEFAULT,
            PortablePromptVariantRules.violation(sound.map { it.copy(isDefault = false) })
        )
        assertEquals(
            PortablePromptVariantRules.Violation.MULTIPLE_DEFAULTS,
            PortablePromptVariantRules.violation(sound.map { it.copy(isDefault = true) })
        )
    }

    @Test
    fun storageMappingCopiesEveryFieldBothWays() {
        for (variant in sound) {
            val stored = variant.toCompanionVariant()
            assertEquals(variant.id, stored.id)
            assertEquals(variant.name, stored.name)
            assertEquals(variant.text, stored.text)
            assertEquals(variant.isDefault, stored.isDefault)
            assertEquals(variant, PortablePromptVariant.fromCompanionVariant(stored))
        }
    }

    @Test
    fun legacyConversionMatchesTheAppsOwnFirstLoadIdentity() {
        val converted = PortablePromptVariantRules.legacySingleVariant("p-legacy", "Old prompt")
        val app = CompanionPromptVariant.migrateFromSinglePrompt("Old prompt", "p-legacy").single()
        assertEquals(
            listOf(
                PortablePromptVariant(
                    UUID.nameUUIDFromBytes("p-legacy_prompt_1".toByteArray()).toString(),
                    "Prompt 1",
                    "Old prompt",
                    true
                )
            ),
            converted
        )
        assertEquals(app.id, converted.single().id)
    }
}
