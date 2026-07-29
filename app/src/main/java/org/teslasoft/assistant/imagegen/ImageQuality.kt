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
 * The image quality value domain (image-generation-rebuild-plan.md §5/§11).
 * Model-initiated images always use the user's saved quality default (owner
 * ruling, 2026-07-29): the create_image tool has no quality field, so this
 * value is only ever set by the user. AUTOMATIC defers to the provider,
 * exactly like [ImageShape.AUTOMATIC].
 */
enum class ImageQuality(val storedValue: String) {
    AUTOMATIC("automatic"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    companion object {
        /** Unknown stored values read as AUTOMATIC — the option that defers
         *  to the provider rather than guessing. */
        fun fromStored(value: String?): ImageQuality =
            entries.firstOrNull { it.storedValue == value?.trim()?.lowercase() } ?: AUTOMATIC
    }
}
