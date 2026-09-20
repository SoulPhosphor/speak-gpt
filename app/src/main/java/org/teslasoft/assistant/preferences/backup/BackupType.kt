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

package org.teslasoft.assistant.preferences.backup

/**
 * The four independent recovery-backup artifacts (Database Health & Backups,
 * Build Phase 2 item 3). Each is snapshotted, verified, written and rotated on
 * its OWN — one type failing never touches another's files or status.
 *
 * [key] is the stable identifier used in the controller-state prefs and in
 * artifact/manifest names; it must never change once shipped. [displayOrder]
 * is the owner-fixed row order for the Backup Status section (July 21 2026):
 * Memory, Lorebooks, Chats, User Image Database — Chats sits between Lorebooks
 * and User Image Database.
 *
 * USER_IMAGE is the profile-image CATALOG only (`profile_images.db`); the JPEG
 * files themselves are deliberately NOT a backup artifact (owner ruling: the
 * catalog is the record and can also be rebuilt from the files). No label may
 * imply the pictures are protected.
 */
enum class BackupType(val key: String) {
    MEMORY("memory"),
    LOREBOOK("lorebook"),
    CHATS("chats"),
    USER_IMAGE("user_image");

    companion object {
        val displayOrder: List<BackupType> = listOf(MEMORY, LOREBOOK, CHATS, USER_IMAGE)

        fun fromKey(key: String?): BackupType? = values().firstOrNull { it.key == key }
    }
}
