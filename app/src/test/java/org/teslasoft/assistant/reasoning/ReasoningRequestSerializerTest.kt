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

package org.teslasoft.assistant.reasoning

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningRequestSerializerTest {

    private fun resolved(
        effort: ReasoningEffort,
        show: Boolean = true
    ) = ResolvedReasoning(effort, show, ResolvedReasoning.Source.FAVORITE_DEFAULT)

    // ---- OpenRouter shape --------------------------------------------------

    @Test
    fun openRouterExplicitEffortEmitsReasoningObject() {
        val fields = ReasoningRequestSerializer.requestFields(
            resolved(ReasoningEffort.HIGH), isOpenRouter = true, reasoningCapable = true
        )!!
        assertEquals("high", fields.getAsJsonObject("reasoning").get("effort").asString)
    }

    @Test
    fun openRouterOffEmitsEnabledFalse() {
        val fields = ReasoningRequestSerializer.requestFields(
            resolved(ReasoningEffort.OFF), isOpenRouter = true, reasoningCapable = true
        )!!
        assertFalse(fields.getAsJsonObject("reasoning").get("enabled").asBoolean)
    }

    @Test
    fun openRouterShowReasoningOffExcludesReturnButKeepsEffort() {
        val fields = ReasoningRequestSerializer.requestFields(
            resolved(ReasoningEffort.MEDIUM, show = false), isOpenRouter = true, reasoningCapable = true
        )!!
        val reasoning = fields.getAsJsonObject("reasoning")
        assertEquals("medium", reasoning.get("effort").asString)
        assertTrue(reasoning.get("exclude").asBoolean)
    }

    @Test
    fun openRouterAutoWithShowReasoningOnEmitsNothing() {
        assertNull(
            ReasoningRequestSerializer.requestFields(
                resolved(ReasoningEffort.AUTO), isOpenRouter = true, reasoningCapable = true
            )
        )
    }

    @Test
    fun openRouterAutoWithShowReasoningOffStillExcludes() {
        val fields = ReasoningRequestSerializer.requestFields(
            resolved(ReasoningEffort.AUTO, show = false), isOpenRouter = true, reasoningCapable = true
        )!!
        assertTrue(fields.getAsJsonObject("reasoning").get("exclude").asBoolean)
    }

    // ---- Generic shape -----------------------------------------------------

    @Test
    fun genericExplicitEffortEmitsReasoningEffort() {
        val fields = ReasoningRequestSerializer.requestFields(
            resolved(ReasoningEffort.LOW), isOpenRouter = false, reasoningCapable = true
        )!!
        assertEquals("low", fields.get("reasoning_effort").asString)
    }

    @Test
    fun genericOffEmitsNone() {
        val fields = ReasoningRequestSerializer.requestFields(
            resolved(ReasoningEffort.OFF), isOpenRouter = false, reasoningCapable = true
        )!!
        assertEquals("none", fields.get("reasoning_effort").asString)
    }

    @Test
    fun genericAutoEmitsNothingAndIgnoresShowReasoning() {
        assertNull(
            ReasoningRequestSerializer.requestFields(
                resolved(ReasoningEffort.AUTO, show = false), isOpenRouter = false, reasoningCapable = true
            )
        )
    }

    // ---- Capability gate & body merge -------------------------------------

    @Test
    fun nonReasoningPathNeverEmitsFields() {
        assertNull(
            ReasoningRequestSerializer.requestFields(
                resolved(ReasoningEffort.HIGH), isOpenRouter = true, reasoningCapable = false
            )
        )
        assertNull(
            ReasoningRequestSerializer.requestFields(
                resolved(ReasoningEffort.HIGH), isOpenRouter = false, reasoningCapable = false
            )
        )
    }

    @Test
    fun augmentBodyMergesReasoningAndPreservesExistingFields() {
        val body = """{"model":"x/y","messages":[],"temperature":0.7}"""
        val out = ReasoningRequestSerializer.augmentBody(
            body, resolved(ReasoningEffort.HIGH), isOpenRouter = true, reasoningCapable = true
        )
        val root = JsonParser.parseString(out).asJsonObject
        assertEquals("x/y", root.get("model").asString)
        assertEquals(0.7, root.get("temperature").asDouble, 0.0001)
        assertEquals("high", root.getAsJsonObject("reasoning").get("effort").asString)
    }

    @Test
    fun augmentBodyIsIdempotentOnReplay() {
        val body = """{"model":"x/y","reasoning":{"effort":"low"}}"""
        val out = ReasoningRequestSerializer.augmentBody(
            body, resolved(ReasoningEffort.HIGH), isOpenRouter = true, reasoningCapable = true
        )
        val root = JsonParser.parseString(out).asJsonObject
        // Overwritten, not duplicated/merged into a second object.
        assertEquals("high", root.getAsJsonObject("reasoning").get("effort").asString)
    }

    @Test
    fun augmentBodyLeavesMalformedBodyUnchanged() {
        val body = "not json"
        assertEquals(
            body,
            ReasoningRequestSerializer.augmentBody(
                body, resolved(ReasoningEffort.HIGH), isOpenRouter = true, reasoningCapable = true
            )
        )
    }

    @Test
    fun augmentBodyNoOpWhenNothingToAdd() {
        val body = """{"model":"x/y"}"""
        assertEquals(
            body,
            ReasoningRequestSerializer.augmentBody(
                body, resolved(ReasoningEffort.AUTO), isOpenRouter = true, reasoningCapable = true
            )
        )
    }
}
