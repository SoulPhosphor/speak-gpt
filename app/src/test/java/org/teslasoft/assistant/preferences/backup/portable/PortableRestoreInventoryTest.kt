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

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class PortableRestoreInventoryTest {
    @Test
    fun currentArtifactsExposeTheirIndependentCategories() {
        val inventory = PortableRestoreInventory.from(
            listOf(
                artifact("chats.json", PortablePackage.TYPE_CHATS_JSON),
                artifact("generated_images/catalog.json", PortablePackage.TYPE_GENERATED_IMAGES_CATALOG),
                artifact("companion_roleplay.zip", PortablePackage.TYPE_COMPANION_ROLEPLAY_ARCHIVE),
                artifact("user_images.db", PortablePackage.TYPE_SQLITE_DB),
                artifact("model_endpoint_settings.json", PortablePackage.TYPE_MODEL_ENDPOINT_SETTINGS),
                artifact("memory.db", PortablePackage.TYPE_SQLCIPHER_DB),
                artifact("lorebook.db", PortablePackage.TYPE_SQLCIPHER_DB)
            )
        )

        assertEquals(PortableRestoreCategory.entries.toSet(), inventory.available)
    }

    @Test
    fun olderBackupNamesExactlyWhichSelectedCategoriesAreMissing() {
        val inventory = PortableRestoreInventory.from(
            listOf(artifact("chats.json", PortablePackage.TYPE_CHATS_JSON))
        )
        val selected = linkedSetOf(
            PortableRestoreCategory.CHATS,
            PortableRestoreCategory.COMPANIONS,
            PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS
        )

        assertEquals(
            linkedSetOf(
                PortableRestoreCategory.COMPANIONS,
                PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS
            ),
            inventory.missingFrom(selected)
        )
        assertEquals(setOf(PortableRestoreCategory.CHATS), inventory.availableFrom(selected))
    }

    private fun artifact(name: String, type: String) = PortablePackage.ValidatedArtifact(
        entryName = name,
        type = type,
        stagedFile = Files.createTempFile("restore-inventory", ".artifact").toFile(),
        databaseKeyHex = null,
        keySemantics = null,
        schemaVersion = null
    )
}
