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

/**
 * The three Default Shape values for uploaded Profile Images
 * (profile-images-plan.md, "Default Shape"). Pure and Android-free so shape
 * normalization and the Glide disk-cache-key component can be unit-tested
 * without a device; [ProfileShapeTransformation] and every settings screen
 * must go through this rather than comparing raw preference strings.
 */
object ProfileImageShape {

    const val FLOWER = "flower"
    const val CIRCLE = "circle"
    const val SQUARE = "square"

    /** The GlobalPreferences default (plan: "Default value: flower"). */
    const val DEFAULT = FLOWER

    private val KNOWN = setOf(FLOWER, CIRCLE, SQUARE)

    /**
     * Normalizes a raw preference value to one of the three known shapes,
     * falling back to [DEFAULT] for anything unrecognized (empty string,
     * a value from a future version, corrupted preferences, etc.) rather
     * than propagating an invalid shape into drawing code.
     */
    fun normalize(raw: String?): String = if (raw != null && KNOWN.contains(raw)) raw else DEFAULT

    /**
     * The exact string embedded in the Glide disk-cache key for this shape
     * (via [ProfileShapeTransformation.updateDiskCacheKey]). Always the
     * normalized value, so a stale/invalid stored preference can never
     * collide with - or fail to invalidate - a valid shape's cached output.
     */
    fun cacheKeyComponent(raw: String?): String = normalize(raw)
}
