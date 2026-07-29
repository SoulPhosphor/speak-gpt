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

/**
 * The image shape value domain (image-generation-rebuild-plan.md §5/§11).
 * AUTOMATIC never means the conversation model chooses: the provider
 * adapter sends the provider's own "auto" value when one exists, or omits
 * the parameter so the image provider applies its default.
 */
enum class ImageShape(val storedValue: String) {
    AUTOMATIC("automatic"),
    SQUARE("square"),
    PORTRAIT("portrait"),
    LANDSCAPE("landscape");

    companion object {
        /** Unknown or legacy stored values read as AUTOMATIC — the option
         *  that defers to the provider rather than guessing. */
        fun fromStored(value: String?): ImageShape =
            entries.firstOrNull { it.storedValue == value?.trim()?.lowercase() } ?: AUTOMATIC
    }
}
