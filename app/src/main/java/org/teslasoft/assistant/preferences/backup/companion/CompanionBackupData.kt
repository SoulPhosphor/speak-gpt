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

package org.teslasoft.assistant.preferences.backup.companion

/**
 * In-memory model of a Companion & Roleplay Backup manifest
 * (companion-roleplay-backup-plan.md §2/§3). Plain data, no Android APIs.
 */
data class CompanionBackupManifest(
    val formatVersion: Int,
    val appVersion: String,
    val exportedAt: String,
    /** §2.1 companion profiles (the `personas` app-settings storage). */
    val companionProfiles: List<CompanionProfileEntry>,
    /** §2.2 activation prompts. */
    val activationPrompts: List<ActivationPromptEntry>,
    /** §2.3 system prompts library, in stored order. */
    val systemPrompts: List<SystemPromptEntry>,
    /** §2.3 selected library entry id ("" = none explicitly selected). */
    val selectedSystemPromptId: String,
    /** §2.4 roleplay structure: table name -> raw rows (column -> value). */
    val roleplayTables: Map<String, List<Map<String, Any?>>>,
    /** §2.5 images carried in the archive. */
    val images: List<CompanionBackupImage>
) {
    fun hasRoleplayRecords(): Boolean = roleplayTables.values.any { it.isNotEmpty() }
}

/**
 * One companion profile with every stored field (§2.1). Lorebook links are
 * carried as ids (reference only); the names captured at export time exist
 * purely so a restore report can name a lorebook that is missing on the
 * destination device.
 */
data class CompanionProfileEntry(
    val id: String,
    val label: String,
    val prompt: String,
    val activationPromptId: String,
    val coreLoreBookId: String,
    /** Core lorebook's name at export time, or null when it was unknown. */
    val coreLoreBookName: String?,
    val additionalLoreBookIds: List<String>,
    /** id -> name captured at export time for the additional links. */
    val additionalLoreBookNames: Map<String, String>,
    val autoLoadLastLoreBooks: Boolean,
    /** Last-used additional-lorebook bookkeeping ids (invisible bookkeeping). */
    val lastUsedLoreBookIds: List<String>,
    /** Profile Images hash, or "" for no picture. */
    val avatarRef: String
)

data class ActivationPromptEntry(
    val id: String,
    val label: String,
    val prompt: String
)

data class SystemPromptEntry(
    val id: String,
    val title: String,
    val body: String
)

data class CompanionBackupImage(
    /** Content hash (the Profile Images identity). */
    val hash: String,
    /** ZIP entry path, e.g. `images/profile_<hash>.jpg`. */
    val file: String
)

/** One removed companion -> lorebook connection for the §9 restore report. */
data class RemovedLorebookLink(
    val companionLabel: String,
    /** The lorebook's name captured at export time, or its id when no name
     *  was available (a link that was already dangling on the source device). */
    val lorebookName: String
)
