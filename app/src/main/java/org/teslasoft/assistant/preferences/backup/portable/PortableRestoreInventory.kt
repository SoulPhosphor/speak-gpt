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

package org.teslasoft.assistant.preferences.backup.portable

/** Stable keys used by the on-screen category selection and restore journal. */
enum class PortableRestoreCategory(val key: String) {
    CHATS("chats"),
    GENERATED_IMAGES("generated_images"),
    COMPANIONS("companions"),
    GLAMOURS("glamours"),
    ROLEPLAY("roleplay"),
    PROFILE_IMAGES("profile_images"),
    ACTIVATION_PROMPTS("activation_prompts"),
    SYSTEM_PROMPTS("system_prompts"),
    MODEL_ENDPOINT_SETTINGS("model_endpoint_settings"),
    MODEL_RULES("model_rules"),
    MEMORIES("memories"),
    LOREBOOKS("lorebooks")
}

/**
 * Derives the categories actually carried by an already decoded and validated
 * package. Absence is distinct from damage: [PortablePackage] rejects damaged
 * artifacts before this inventory exists.
 */
object PortableRestoreInventory {
    data class Inventory(
        val available: Set<PortableRestoreCategory>,
        /** Categories deliberately represented as empty by a current package. */
        val explicitlyEmpty: Set<PortableRestoreCategory> = emptySet()
    ) {
        fun missingFrom(selected: Set<PortableRestoreCategory>): Set<PortableRestoreCategory> =
            selected - available

        fun availableFrom(selected: Set<PortableRestoreCategory>): Set<PortableRestoreCategory> =
            selected intersect available
    }

    fun from(
        artifacts: List<PortablePackage.ValidatedArtifact>,
        declaredCategories: Set<PortableRestoreCategory>? = null
    ): Inventory {
        val categories = LinkedHashSet<PortableRestoreCategory>()
        artifacts.forEach { artifact ->
            when (artifact.type) {
                PortablePackage.TYPE_CHATS_JSON -> categories.add(PortableRestoreCategory.CHATS)
                PortablePackage.TYPE_GENERATED_IMAGES_CATALOG ->
                    categories.add(PortableRestoreCategory.GENERATED_IMAGES)
                PortablePackage.TYPE_COMPANION_ROLEPLAY_ARCHIVE -> categories.addAll(
                    listOf(
                        PortableRestoreCategory.COMPANIONS,
                        PortableRestoreCategory.GLAMOURS,
                        PortableRestoreCategory.ROLEPLAY,
                        PortableRestoreCategory.ACTIVATION_PROMPTS,
                        PortableRestoreCategory.SYSTEM_PROMPTS
                    )
                )
                PortablePackage.TYPE_SQLITE_DB -> if (artifact.entryName == "user_images.db") {
                    categories.add(PortableRestoreCategory.PROFILE_IMAGES)
                }
                PortablePackage.TYPE_MODEL_ENDPOINT_SETTINGS ->
                    categories.add(PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS)
                PortablePackage.TYPE_SQLCIPHER_DB -> when (artifact.entryName) {
                    "memory.db" -> categories.addAll(
                        listOf(
                            PortableRestoreCategory.MEMORIES,
                            PortableRestoreCategory.MODEL_RULES
                        )
                    )
                    "lorebook.db" -> categories.add(PortableRestoreCategory.LOREBOOKS)
                }
            }
        }
        if (declaredCategories == null) return Inventory(categories)
        return Inventory(
            available = declaredCategories,
            explicitlyEmpty = declaredCategories - categories
        )
    }
}
