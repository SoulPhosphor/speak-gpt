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

package org.teslasoft.assistant.preferences.profileimages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProfileImageShapeTest {

    @Test
    fun knownShapesNormalizeToThemselves() {
        assertEquals(ProfileImageShape.FLOWER, ProfileImageShape.normalize("flower"))
        assertEquals(ProfileImageShape.CIRCLE, ProfileImageShape.normalize("circle"))
        assertEquals(ProfileImageShape.SQUARE, ProfileImageShape.normalize("square"))
    }

    @Test
    fun unknownOrMissingValuesFallBackToTheDefault() {
        assertEquals(ProfileImageShape.DEFAULT, ProfileImageShape.normalize(null))
        assertEquals(ProfileImageShape.DEFAULT, ProfileImageShape.normalize(""))
        assertEquals(ProfileImageShape.DEFAULT, ProfileImageShape.normalize("hexagon"))
        assertEquals(ProfileImageShape.DEFAULT, ProfileImageShape.normalize("Flower")) // case-sensitive
    }

    @Test
    fun defaultIsFlower() {
        assertEquals(ProfileImageShape.FLOWER, ProfileImageShape.DEFAULT)
    }

    @Test
    fun cacheKeyComponentIsTheNormalizedShape() {
        assertEquals(ProfileImageShape.CIRCLE, ProfileImageShape.cacheKeyComponent("circle"))
        assertEquals(ProfileImageShape.DEFAULT, ProfileImageShape.cacheKeyComponent("garbage"))
    }

    @Test
    fun cacheKeyComponentDiffersAcrossShapesSoCachedOutputCannotBeReusedIncorrectly() {
        val flowerKey = ProfileImageShape.cacheKeyComponent(ProfileImageShape.FLOWER)
        val circleKey = ProfileImageShape.cacheKeyComponent(ProfileImageShape.CIRCLE)
        val squareKey = ProfileImageShape.cacheKeyComponent(ProfileImageShape.SQUARE)

        assertNotEquals(flowerKey, circleKey)
        assertNotEquals(circleKey, squareKey)
        assertNotEquals(flowerKey, squareKey)
    }
}
