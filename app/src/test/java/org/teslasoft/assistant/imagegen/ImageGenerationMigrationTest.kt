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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §14 seeding rules of image-generation-rebuild-plan.md: the one-time
 * migration seeds the app-wide configuration from the default settings
 * profile — generator endpoint and model carried over, legacy resolution
 * mapped to the closest shape, quality starting AUTOMATIC (a new setting
 * with no legacy value), and Let the AI Create Images following the old
 * Function Calling state only so the user's current choice is preserved.
 * The `/imagine` toggle has no legacy source any more (its per-chat copy
 * was removed outright) and is no longer part of the seed.
 */
class ImageGenerationMigrationTest {

    @Test
    fun legacySquareResolutionsMapToSquare() {
        assertEquals(ImageShape.SQUARE, ImageGenerationMigration.shapeFromLegacyResolution("256x256"))
        assertEquals(ImageShape.SQUARE, ImageGenerationMigration.shapeFromLegacyResolution("512x512"))
        assertEquals(ImageShape.SQUARE, ImageGenerationMigration.shapeFromLegacyResolution("1024x1024"))
    }

    @Test
    fun widerThanTallMapsToLandscape() {
        assertEquals(ImageShape.LANDSCAPE, ImageGenerationMigration.shapeFromLegacyResolution("1792x1024"))
    }

    @Test
    fun tallerThanWideMapsToPortrait() {
        assertEquals(ImageShape.PORTRAIT, ImageGenerationMigration.shapeFromLegacyResolution("1024x1792"))
    }

    @Test
    fun unparseableResolutionsMapToAutomatic() {
        assertEquals(ImageShape.AUTOMATIC, ImageGenerationMigration.shapeFromLegacyResolution(""))
        assertEquals(ImageShape.AUTOMATIC, ImageGenerationMigration.shapeFromLegacyResolution("large"))
        assertEquals(ImageShape.AUTOMATIC, ImageGenerationMigration.shapeFromLegacyResolution("1024"))
        assertEquals(ImageShape.AUTOMATIC, ImageGenerationMigration.shapeFromLegacyResolution("0x0"))
        assertEquals(ImageShape.AUTOMATIC, ImageGenerationMigration.shapeFromLegacyResolution("axb"))
    }

    @Test
    fun seedCarriesEndpointAndModelUnchanged() {
        val seed = ImageGenerationMigration.seedFromLegacy(
            legacyEndpointId = "endpoint-123",
            legacyImageModel = "gpt-image-1",
            legacyResolution = "1024x1024",
            legacyFunctionCalling = false
        )
        assertEquals("endpoint-123", seed.endpointId)
        assertEquals("gpt-image-1", seed.model)
    }

    @Test
    fun qualityAlwaysSeedsAutomatic() {
        val seed = ImageGenerationMigration.seedFromLegacy(
            legacyEndpointId = "e",
            legacyImageModel = "m",
            legacyResolution = "1792x1024",
            legacyFunctionCalling = true
        )
        assertEquals(ImageQuality.AUTOMATIC, seed.quality)
        assertEquals(ImageShape.LANDSCAPE, seed.shape)
    }

    @Test
    fun aiImageCreationPreservesTheOldFunctionCallingChoice() {
        assertTrue(
            ImageGenerationMigration.seedFromLegacy(
                "e", "m", "1024x1024", legacyFunctionCalling = true
            ).aiCreateImages
        )
        assertFalse(
            ImageGenerationMigration.seedFromLegacy(
                "e", "m", "1024x1024", legacyFunctionCalling = false
            ).aiCreateImages
        )
    }
}
