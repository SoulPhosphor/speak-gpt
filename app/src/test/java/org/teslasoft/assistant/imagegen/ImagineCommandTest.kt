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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rebuilt `/imagine` parser (image-generation-rebuild-plan.md §2.1,
 * §4.6, §11). This deliberately replaces the legacy characterization tests:
 * the mid-text trigger and fixed-position prompt slice were documented
 * defects, and the rebuild removes them on purpose.
 */
class ImagineCommandTest {

    private fun request(raw: String): ImagineCommand.Parse.Request =
        ImagineCommand.parse(raw) as ImagineCommand.Parse.Request

    // --- detection (§4.6) ---

    @Test
    fun commandAtStartWithPromptParses() {
        val parsed = request("/imagine a fox sleeping beneath glowing mushrooms")
        assertEquals("a fox sleeping beneath glowing mushrooms", parsed.prompt)
        assertNull(parsed.shapeOverride)
        assertNull(parsed.qualityOverride)
    }

    @Test
    fun midTextMentionIsAnOrdinaryMessage() {
        assertEquals(
            ImagineCommand.Parse.NotImagine,
            ImagineCommand.parse("we talked about /imagine yesterday")
        )
        assertEquals(
            ImagineCommand.Parse.NotImagine,
            ImagineCommand.parse("what does /imagine do?")
        )
    }

    @Test
    fun detectionIsCaseInsensitiveAndTrimsLeadingWhitespace() {
        assertEquals("a cat", request("/IMAGINE a cat").prompt)
        assertEquals("a cat", request("  /imagine a cat").prompt)
    }

    @Test
    fun commandMustBeAWholeWord() {
        assertEquals(
            ImagineCommand.Parse.NotImagine,
            ImagineCommand.parse("/imagineer a theme park")
        )
    }

    @Test
    fun bareCommandIsAnEmptyPrompt() {
        assertEquals(ImagineCommand.Parse.EmptyPrompt, ImagineCommand.parse("/imagine"))
        assertEquals(ImagineCommand.Parse.EmptyPrompt, ImagineCommand.parse("/imagine   "))
    }

    @Test
    fun isImagineAttemptCoversTheErrorFormsButNotOrdinaryText() {
        assertTrue(ImagineCommand.isImagineAttempt("/imagine"))
        assertTrue(ImagineCommand.isImagineAttempt("/imagine a cat --size big"))
        assertTrue(!ImagineCommand.isImagineAttempt("tell me about /imagine"))
    }

    // --- trailing options (§2.1/§11) ---

    @Test
    fun trailingShapeAndQualityOverridesAreParsedAndStripped() {
        val parsed = request("/imagine a luminous forest temple --shape landscape --quality high")
        assertEquals("a luminous forest temple", parsed.prompt)
        assertEquals(ImageShape.LANDSCAPE, parsed.shapeOverride)
        assertEquals(ImageQuality.HIGH, parsed.qualityOverride)
    }

    @Test
    fun optionOrderDoesNotMatter() {
        val parsed = request("/imagine a temple --quality low --shape portrait")
        assertEquals("a temple", parsed.prompt)
        assertEquals(ImageShape.PORTRAIT, parsed.shapeOverride)
        assertEquals(ImageQuality.LOW, parsed.qualityOverride)
    }

    @Test
    fun aDoubleDashInsideThePromptStaysPromptText() {
        val parsed = request("/imagine a sign reading --caution wet floor")
        assertEquals("a sign reading --caution wet floor", parsed.prompt)
    }

    @Test
    fun unknownTrailingOptionIsACorrectableError() {
        val parsed = ImagineCommand.parse("/imagine a cat --size large")
        assertTrue(parsed is ImagineCommand.Parse.InvalidOption)
        assertEquals("--size large", (parsed as ImagineCommand.Parse.InvalidOption).optionText)
    }

    @Test
    fun invalidOptionValueIsACorrectableError() {
        val parsed = ImagineCommand.parse("/imagine a cat --shape circular")
        assertTrue(parsed is ImagineCommand.Parse.InvalidOption)
        assertEquals("--shape circular", (parsed as ImagineCommand.Parse.InvalidOption).optionText)
    }

    @Test
    fun optionNameWithoutAValueIsACorrectableError() {
        val parsed = ImagineCommand.parse("/imagine a cat --shape")
        assertTrue(parsed is ImagineCommand.Parse.InvalidOption)
    }

    @Test
    fun optionsWithoutAPromptAreAnEmptyPrompt() {
        assertEquals(
            ImagineCommand.Parse.EmptyPrompt,
            ImagineCommand.parse("/imagine --shape landscape")
        )
    }

    @Test
    fun theOptionNearestTheEndWinsWhenRepeated() {
        val parsed = request("/imagine a cat --shape square --shape landscape")
        assertEquals("a cat", parsed.prompt)
        assertEquals(ImageShape.LANDSCAPE, parsed.shapeOverride)
    }

    // --- §11 precedence and capability resolution ---

    private val fullSupport = ImageAdapterCapabilities(supportsShape = true, supportsQuality = true)
    private val noSupport = ImageAdapterCapabilities(supportsShape = false, supportsQuality = false)

    @Test
    fun explicitOverrideBeatsTheSavedDefault() {
        val resolved = ImagineCommand.resolveOptions(
            ImageShape.LANDSCAPE, null,
            ImageShape.SQUARE, ImageQuality.HIGH,
            fullSupport
        )
        assertEquals(ImageShape.LANDSCAPE, resolved.shape)
        assertEquals(ImageQuality.HIGH, resolved.quality)
        assertTrue(resolved.unsupportedExplicit.isEmpty())
        assertTrue(resolved.silentFallbacks.isEmpty())
    }

    @Test
    fun explicitlyRequestedUnsupportedOptionsNeedTheNotice() {
        val resolved = ImagineCommand.resolveOptions(
            ImageShape.LANDSCAPE, ImageQuality.HIGH,
            ImageShape.AUTOMATIC, ImageQuality.AUTOMATIC,
            noSupport
        )
        assertEquals(ImageShape.AUTOMATIC, resolved.shape)
        assertEquals(ImageQuality.AUTOMATIC, resolved.quality)
        assertEquals(
            listOf(ImagineCommand.OPTION_SHAPE, ImagineCommand.OPTION_QUALITY),
            resolved.unsupportedExplicit
        )
        assertTrue(resolved.silentFallbacks.isEmpty())
    }

    @Test
    fun unsupportedSavedDefaultsFallBackSilentlyButAreReported() {
        val resolved = ImagineCommand.resolveOptions(
            null, null,
            ImageShape.PORTRAIT, ImageQuality.LOW,
            noSupport
        )
        assertEquals(ImageShape.AUTOMATIC, resolved.shape)
        assertEquals(ImageQuality.AUTOMATIC, resolved.quality)
        assertTrue(resolved.unsupportedExplicit.isEmpty())
        assertEquals(
            listOf(ImagineCommand.OPTION_SHAPE, ImagineCommand.OPTION_QUALITY),
            resolved.silentFallbacks
        )
    }

    @Test
    fun automaticNeverTriggersTheNoticeEvenWhenUnsupported() {
        val resolved = ImagineCommand.resolveOptions(
            ImageShape.AUTOMATIC, ImageQuality.AUTOMATIC,
            ImageShape.AUTOMATIC, ImageQuality.AUTOMATIC,
            noSupport
        )
        assertTrue(resolved.unsupportedExplicit.isEmpty())
        assertTrue(resolved.silentFallbacks.isEmpty())
    }
}
