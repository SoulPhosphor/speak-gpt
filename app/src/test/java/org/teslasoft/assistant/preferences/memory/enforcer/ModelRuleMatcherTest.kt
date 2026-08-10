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
import org.teslasoft.assistant.preferences.models.ModelIdentity
import org.teslasoft.assistant.preferences.models.ModelIdentityCodec

class ModelRuleMatcherTest {

    private val exact = ModelIdentityCodec.encode(
        listOf(ModelIdentity("openrouter-endpoint", "openai/gpt-5.1"))
    )

    @Test
    fun exactTargetRequiresSameEndpointAndExactModelId() {
        assertTrue(
            ModelRuleMatcher.exactTargetsMatch(
                exact, "openrouter-endpoint", "openai/gpt-5.1"
            )
        )
        assertFalse(
            ModelRuleMatcher.exactTargetsMatch(
                exact, "deepseek-endpoint", "openai/gpt-5.1"
            )
        )
        assertFalse(
            ModelRuleMatcher.exactTargetsMatch(
                exact, "openrouter-endpoint", "gpt-5.1"
            )
        )
    }

    @Test
    fun exactTargetDoesNotInferModelFamiliesOrIgnoreCase() {
        assertFalse(
            ModelRuleMatcher.exactTargetsMatch(
                exact, "openrouter-endpoint", "openai/gpt-5.1-0810"
            )
        )
        assertFalse(
            ModelRuleMatcher.exactTargetsMatch(
                exact, "openrouter-endpoint", "OPENAI/GPT-5.1"
            )
        )
    }

    @Test
    fun ruleWithMultipleExactTargetsMatchesEachTargetIndependently() {
        val multiple = ModelIdentityCodec.encode(
            listOf(
                ModelIdentity("openrouter-endpoint", "openai/gpt-5.1"),
                ModelIdentity("deepseek-endpoint", "deepseek-chat")
            )
        )

        assertTrue(
            ModelRuleMatcher.exactTargetsMatch(
                multiple, "openrouter-endpoint", "openai/gpt-5.1"
            )
        )
        assertTrue(
            ModelRuleMatcher.exactTargetsMatch(
                multiple, "deepseek-endpoint", "deepseek-chat"
            )
        )
        assertFalse(
            ModelRuleMatcher.exactTargetsMatch(
                multiple, "deepseek-endpoint", "openai/gpt-5.1"
            )
        )
    }

    @Test
    fun preservedLegacyTargetsKeepOldFuzzyBehaviorOnlyInLegacyPath() {
        assertTrue(ModelRuleMatcher.legacyMatches("glm-5", "openrouter/glm-5-0502"))
        assertTrue(ModelRuleMatcher.legacyListMatches("""["glm-5"]""", "glm-5-0219"))
        assertFalse(ModelRuleMatcher.legacyListMatches("not json", "glm-5"))
    }

    @Test
    fun ruleMatchesEitherExactOrPreservedLegacyTarget() {
        assertTrue(
            ModelRuleMatcher.ruleMatches(
                exact, "[]", "openrouter-endpoint", "openai/gpt-5.1"
            )
        )
        assertTrue(
            ModelRuleMatcher.ruleMatches(
                "[]", """["glm-5"]""", "any-endpoint", "glm-5-0502"
            )
        )
        assertFalse(
            ModelRuleMatcher.ruleMatches(
                exact, "[]", "deepseek-endpoint", "openai/gpt-5.1"
            )
        )
    }
}
