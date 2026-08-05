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
 * The Companion & Roleplay Backup file contract
 * (companion-roleplay-backup-plan.md §3): one ZIP, a human-readable
 * `backup.json` manifest, and the referenced `profile_<hash>.jpg` files under
 * `images/`. Pure constants — no Android APIs — so the codec and validator
 * built on top stay unit-testable on the JVM.
 */
object CompanionBackupFormat {

    /** `format` marker inside backup.json — the file-type check. */
    const val FORMAT_MARKER = "companion-roleplay-backup"

    /** Highest `format_version` this build can read (and the one it writes). */
    const val FORMAT_VERSION = 1

    /** The manifest entry inside the ZIP. */
    const val MANIFEST_ENTRY = "backup.json"

    /** Directory prefix for image entries inside the ZIP. */
    const val IMAGES_DIR = "images/"

    /**
     * The §2.4 record sets carried by the backup, parents before children.
     * [org.teslasoft.assistant.preferences.memory.MemoryStore] inserts in this
     * order and deletes in reverse. `rp_tag_links` rows whose target is a
     * memory are never exported (memories are not in this backup).
     */
    val ROLEPLAY_TABLES = listOf(
        "companions", "companion_name_history", "user_personas",
        "roleplay_characters", "worlds", "campaigns", "party_members",
        "campaign_party_members", "card_entries", "rp_tags", "rp_tag_links"
    )

    /** ZIP entry name for one profile image. */
    fun imageEntryName(hash: String): String = IMAGES_DIR + "profile_" + hash + ".jpg"

    /** Suggested export file name: `companion-backup-YYYY-MM-DD.zip` (§9). */
    fun defaultFileName(isoDate: String): String = "companion-backup-$isoDate.zip"
}
