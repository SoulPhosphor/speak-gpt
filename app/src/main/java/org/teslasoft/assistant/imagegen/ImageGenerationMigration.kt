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

import android.content.Context
import org.teslasoft.assistant.preferences.Preferences

/**
 * One-time seeding of the app-wide image-generation settings from the
 * default settings profile (image-generation-rebuild-plan.md §14). The
 * rebuilt settings are app-wide (owner ruling, 2026-07-29), so migration
 * seeds one global configuration instead of rewriting every chat. Legacy
 * per-chat values are NOT deleted here; they stop being read as the
 * rebuild rewires each path, and removal waits for migration tests plus a
 * stable release (§14).
 */
object ImageGenerationMigration {

    /** The seeded global configuration, kept as a pure value so the
     *  decision logic is unit-testable without Android. */
    data class Seed(
        val endpointId: String,
        val model: String,
        val shape: ImageShape,
        val quality: ImageQuality,
        val aiCreateImages: Boolean
    )

    /**
     * §14 seeding rules, in order: the generator endpoint and model come
     * from the default profile so behavior does not abruptly change; the
     * shape is the legacy resolution mapped to the closest shape; quality
     * is a new setting with no legacy value, so it starts AUTOMATIC; and
     * Let the AI Create Images follows the old Function Calling state only
     * to preserve the user's current choice — afterwards it is
     * independent. The `/imagine` toggle has no legacy source any more —
     * it keeps its own default (on) instead of being seeded.
     */
    fun seedFromLegacy(
        legacyEndpointId: String,
        legacyImageModel: String,
        legacyResolution: String,
        legacyFunctionCalling: Boolean
    ): Seed = Seed(
        endpointId = legacyEndpointId,
        model = legacyImageModel,
        shape = shapeFromLegacyResolution(legacyResolution),
        quality = ImageQuality.AUTOMATIC,
        aiCreateImages = legacyFunctionCalling
    )

    /**
     * Maps a legacy "WIDTHxHEIGHT" resolution to the closest shape:
     * equal sides are SQUARE, a wider image is LANDSCAPE, a taller one is
     * PORTRAIT. Anything unparseable is AUTOMATIC, which defers to the
     * provider instead of guessing.
     */
    fun shapeFromLegacyResolution(resolution: String): ImageShape {
        val parts = resolution.trim().lowercase().split("x")
        if (parts.size != 2) return ImageShape.AUTOMATIC
        val width = parts[0].toIntOrNull() ?: return ImageShape.AUTOMATIC
        val height = parts[1].toIntOrNull() ?: return ImageShape.AUTOMATIC
        if (width <= 0 || height <= 0) return ImageShape.AUTOMATIC
        return when {
            width == height -> ImageShape.SQUARE
            width > height -> ImageShape.LANDSCAPE
            else -> ImageShape.PORTRAIT
        }
    }

    /**
     * Seeds the global configuration exactly once. Idempotent and safe to
     * call from any entry point that needs the settings; the marker is only
     * stamped after every value is written.
     */
    fun runIfNeeded(context: Context) {
        val preferences = Preferences.getPreferences(context, "")
        if (preferences.getImageGenerationSeeded()) return

        val seed = seedFromLegacy(
            legacyEndpointId = preferences.getApiEndpointId(),
            legacyImageModel = preferences.getImageModel(),
            legacyResolution = preferences.getResolution(),
            legacyFunctionCalling = preferences.getLegacyFunctionCallingForMigration()
        )

        preferences.setImageGeneratorEndpointId(seed.endpointId)
        preferences.setImageGeneratorModel(seed.model)
        preferences.setImageGeneratorShape(seed.shape)
        preferences.setImageGeneratorQuality(seed.quality)
        preferences.setAiCreateImagesEnabled(seed.aiCreateImages)
        preferences.setImageGenerationSeeded()
    }
}
