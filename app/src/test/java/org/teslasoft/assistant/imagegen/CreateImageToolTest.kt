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

package org.teslasoft.assistant.imagegen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §6 tool contract of image-generation-rebuild-plan.md: required
 * prompt and description, optional shape, NO quality field (owner ruling,
 * 2026-07-29 — a model-initiated image always uses the saved quality
 * default), unknown fields rejected, excessive prompts rejected, and the
 * tool offered only when enabled AND a generator is configured.
 */
class CreateImageToolTest {

    private fun args(vararg pairs: Pair<String, Any>): String {
        val json = JSONObject()
        for ((key, value) in pairs) json.put(key, value)
        return json.toString()
    }

    @Test
    fun validArgumentsParse() {
        val validation = CreateImageTool.validate(
            args(
                "prompt" to "a fox beneath glowing mushrooms",
                "description" to "A sleeping fox in a mushroom grove",
                "shape" to "landscape"
            )
        ) as CreateImageTool.Validation.Valid
        assertEquals("a fox beneath glowing mushrooms", validation.prompt)
        assertEquals("A sleeping fox in a mushroom grove", validation.description)
        assertEquals(ImageShape.LANDSCAPE, validation.shapeOverride)
    }

    @Test
    fun omittedShapeMeansTheSavedDefaultApplies() {
        val validation = CreateImageTool.validate(
            args("prompt" to "a fox", "description" to "A fox")
        ) as CreateImageTool.Validation.Valid
        assertNull(validation.shapeOverride)
    }

    @Test
    fun invalidJsonIsACleanToolError() {
        assertTrue(
            CreateImageTool.validate("not json at all")
                is CreateImageTool.Validation.Invalid
        )
        assertTrue(
            CreateImageTool.validate("{\"prompt\": \"truncated")
                is CreateImageTool.Validation.Invalid
        )
    }

    @Test
    fun emptyPromptIsRejected() {
        assertTrue(
            CreateImageTool.validate(args("prompt" to "  ", "description" to "d"))
                is CreateImageTool.Validation.Invalid
        )
    }

    @Test
    fun excessivePromptLengthIsRejected() {
        val validation = CreateImageTool.validate(
            args(
                "prompt" to "x".repeat(CreateImageTool.MAX_PROMPT_LENGTH + 1),
                "description" to "d"
            )
        )
        assertTrue(validation is CreateImageTool.Validation.Invalid)
    }

    @Test
    fun missingDescriptionIsRejected() {
        assertTrue(
            CreateImageTool.validate(args("prompt" to "a fox"))
                is CreateImageTool.Validation.Invalid
        )
    }

    @Test
    fun unknownFieldsAreRejected() {
        assertTrue(
            CreateImageTool.validate(
                args("prompt" to "a fox", "description" to "d", "count" to 4)
            ) is CreateImageTool.Validation.Invalid
        )
    }

    /** The tool has no quality field: any attempt to pass one is an
     *  unknown-field rejection, so no tool input can change quality. */
    @Test
    fun qualityCanNeverBeSetThroughTheTool() {
        val validation = CreateImageTool.validate(
            args("prompt" to "a fox", "description" to "d", "quality" to "high")
        )
        assertTrue(validation is CreateImageTool.Validation.Invalid)
        assertTrue(
            (validation as CreateImageTool.Validation.Invalid).toolError.contains("quality")
        )
    }

    @Test
    fun invalidShapeValueIsRejected() {
        assertTrue(
            CreateImageTool.validate(
                args("prompt" to "a fox", "description" to "d", "shape" to "circular")
            ) is CreateImageTool.Validation.Invalid
        )
    }

    // --- availability gate (§7/§13) ---

    @Test
    fun toolIsOfferedOnlyWhenEnabledAndConfigured() {
        assertTrue(CreateImageTool.shouldOfferTool(true, "endpoint", "model"))
        assertFalse(CreateImageTool.shouldOfferTool(false, "endpoint", "model"))
        assertFalse(CreateImageTool.shouldOfferTool(true, "", "model"))
        assertFalse(CreateImageTool.shouldOfferTool(true, "endpoint", ""))
    }

    // --- tool results (§7.6) ---

    @Test
    fun successResultCarriesImageIdentityAndDescription() {
        val parsed = JSONObject(CreateImageTool.successResult("abc123", "A fox"))
        assertTrue(parsed.getBoolean("success"))
        assertEquals("abc123", parsed.getString("image_id"))
        assertEquals("A fox", parsed.getString("description"))
        assertFalse(parsed.has("note"))
    }

    @Test
    fun fallbackNoteRidesTheSuccessResult() {
        val parsed = JSONObject(
            CreateImageTool.successResult("abc", "d", "shape fell back to the provider default")
        )
        assertTrue(parsed.has("note"))
    }

    @Test
    fun cancellationAndErrorsAreConciseFailures() {
        assertFalse(JSONObject(CreateImageTool.cancelledResult()).getBoolean("success"))
        val error = JSONObject(CreateImageTool.errorResult("prompt must be a non-empty string"))
        assertFalse(error.getBoolean("success"))
        assertTrue(error.getString("error").isNotBlank())
    }
}
