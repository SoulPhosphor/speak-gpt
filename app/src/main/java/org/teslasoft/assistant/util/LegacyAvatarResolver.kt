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

package org.teslasoft.assistant.util

import java.io.File

/**
 * Resolves the on-disk file for a legacy per-chat assistant avatar.
 *
 * CustomizeAssistantDialog.writeImageToCache() saves the file using the
 * literal extension "png" for a PNG source or "jpg" for anything else
 * (Bitmap.CompressFormat.JPEG sources included) - never "jpeg". Every
 * existing display site used to hardcode the ".png" suffix only, so an
 * avatar saved from a non-PNG source was never found. This resolver is
 * the single place that knows the deterministic extension order; do not
 * hand-construct "avatar_<id>.<ext>" paths elsewhere.
 */
object LegacyAvatarResolver {

    private val LEGACY_EXTENSIONS = arrayOf("png", "jpg")

    /**
     * Returns the first existing legacy avatar file for [avatarId] inside
     * [imagesDir] (the app's getExternalFilesDir("images")), checking
     * ".png" then ".jpg". Returns null when neither exists so callers
     * never attempt to open a nonexistent file.
     */
    fun resolve(imagesDir: File?, avatarId: String): File? {
        if (imagesDir == null || avatarId.isEmpty()) return null

        for (extension in LEGACY_EXTENSIONS) {
            val candidate = File(imagesDir, "avatar_$avatarId.$extension")
            if (candidate.exists()) {
                return candidate
            }
        }

        return null
    }
}
