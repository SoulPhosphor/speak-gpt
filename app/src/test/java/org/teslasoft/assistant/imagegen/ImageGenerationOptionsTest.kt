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
import org.junit.Test

/**
 * The shape and quality value domains of the rebuilt image-generation
 * settings (image-generation-rebuild-plan.md §5/§11): stored values round
 * trip, and anything unknown reads as AUTOMATIC — the option that defers
 * to the provider instead of guessing.
 */
class ImageGenerationOptionsTest {

    @Test
    fun everyShapeRoundTripsThroughItsStoredValue() {
        for (shape in ImageShape.entries) {
            assertEquals(shape, ImageShape.fromStored(shape.storedValue))
        }
    }

    @Test
    fun everyQualityRoundTripsThroughItsStoredValue() {
        for (quality in ImageQuality.entries) {
            assertEquals(quality, ImageQuality.fromStored(quality.storedValue))
        }
    }

    @Test
    fun storedShapeValuesAreCaseAndWhitespaceTolerant() {
        assertEquals(ImageShape.LANDSCAPE, ImageShape.fromStored(" Landscape "))
        assertEquals(ImageQuality.HIGH, ImageQuality.fromStored("HIGH"))
    }

    @Test
    fun unknownStoredValuesReadAsAutomatic() {
        assertEquals(ImageShape.AUTOMATIC, ImageShape.fromStored("1024x1024"))
        assertEquals(ImageShape.AUTOMATIC, ImageShape.fromStored(""))
        assertEquals(ImageShape.AUTOMATIC, ImageShape.fromStored(null))
        assertEquals(ImageQuality.AUTOMATIC, ImageQuality.fromStored("hd"))
        assertEquals(ImageQuality.AUTOMATIC, ImageQuality.fromStored(""))
        assertEquals(ImageQuality.AUTOMATIC, ImageQuality.fromStored(null))
    }
}
