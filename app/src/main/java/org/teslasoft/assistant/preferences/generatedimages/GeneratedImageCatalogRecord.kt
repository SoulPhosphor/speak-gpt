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

package org.teslasoft.assistant.preferences.generatedimages

import org.teslasoft.assistant.util.Hash

/** Lightweight durable gallery row. Prompts, message bodies, credentials and
 * image bytes deliberately do not belong in the catalog. */
data class GeneratedImageCatalogRecord(
    val imageId: String,
    val fileHash: String,
    val assetFileName: String,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val createdAt: Long,
    val originChatId: String?,
    val originChatName: String?,
    val originMessageId: String?,
    val locked: Boolean = false,
    val source: Source = Source.GENERATED
) {
    enum class Source(val storageValue: String) {
        GENERATED("generated"),
        BACKFILL("backfill");

        companion object {
            fun fromStorage(value: String?): Source =
                entries.firstOrNull { it.storageValue == value } ?: BACKFILL
        }
    }
}

/** Conservative identity for records that predate stable image IDs. A stored
 * creation time distinguishes separate generations with identical bytes. If
 * even that provenance is absent, the physical legacy file is the strongest
 * identity the old data can prove. */
object LegacyGeneratedImageIdentity {
    fun resolve(
        storedImageId: String?,
        fileHash: String,
        createdAt: Long,
        assetFileName: String
    ): String {
        storedImageId?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val tuple = if (createdAt > 0L) {
            "$fileHash|$createdAt"
        } else {
            "$fileHash|$assetFileName"
        }
        return "legacy-" + Hash.hash(tuple)
    }
}
