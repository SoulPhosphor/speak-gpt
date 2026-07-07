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

package org.teslasoft.assistant.preferences.memory.enforcer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "(matches this chat's model)" hint (owner_approved_rules §11) must be
 * right in both directions: a false hint nudges the user toward the wrong
 * profile, and a missed match hides the right one. The matching rule from the
 * work order: case-insensitive contains, provider prefix ignored.
 */
class ModelRuleMatcherTest {

    @Test
    fun exactAndCaseInsensitiveMatch() {
        assertTrue(ModelRuleMatcher.matches("glm-5-0502", "glm-5-0502"))
        assertTrue(ModelRuleMatcher.matches("GLM-5-0502", "glm-5-0502"))
    }

    @Test
    fun providerPrefixIsIgnored() {
        assertTrue(ModelRuleMatcher.matches("openrouter/glm-5-0502", "glm-5-0502"))
        assertTrue(ModelRuleMatcher.matches("glm-5-0502", "z-ai/glm-5-0502"))
    }

    @Test
    fun containsWorksBothWays() {
        // A profile may hold the family string while the chat runs a snapshot…
        assertTrue(ModelRuleMatcher.matches("glm-5", "glm-5-0502"))
        // …or hold the dated snapshot while the chat uses the family id.
        assertTrue(ModelRuleMatcher.matches("glm-5-0502", "glm-5"))
    }

    @Test
    fun unrelatedAndBlankNeverMatch() {
        assertFalse(ModelRuleMatcher.matches("glm-5", "gpt-4o"))
        assertFalse(ModelRuleMatcher.matches("", "gpt-4o"))
        assertFalse(ModelRuleMatcher.matches("gpt-4o", ""))
    }

    @Test
    fun profileListMatchesOnAnyString() {
        val json = """["glm-5-0502", "glm-5-0219"]"""
        assertTrue(ModelRuleMatcher.profileMatchesModel(json, "z-ai/GLM-5-0219"))
        assertFalse(ModelRuleMatcher.profileMatchesModel(json, "gpt-4o"))
        // Malformed JSON is cosmetic-only: never a match, never a crash.
        assertFalse(ModelRuleMatcher.profileMatchesModel("not json", "glm-5"))
    }
}
