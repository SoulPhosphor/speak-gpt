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
 * How often Automatic Backups would run (owner ruling, July 22 2026) — the
 * option the Backup Frequency dropdown on the Memory Backup & Restore screen
 * lets the user pick and persist ahead of the portable automatic writer
 * actually existing. [key] is the stable identifier stored in
 * [RecoveryBackupState]; it must never change once shipped.
 */
enum class BackupFrequency(val key: String) {
    DAILY("daily"),
    WEEKLY("weekly"),
    BIWEEKLY("biweekly"),
    MONTHLY("monthly");

    companion object {
        /** Display order matches the dropdown's option order. */
        val displayOrder: List<BackupFrequency> = listOf(DAILY, WEEKLY, BIWEEKLY, MONTHLY)

        fun fromKey(key: String?): BackupFrequency? = values().firstOrNull { it.key == key }
    }
}
