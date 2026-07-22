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
 * The four automatic-backup frequency choices (owner directive, July 22
 * 2026): Every Day, Every Week, Every Two Weeks, Every Month. Stored by
 * [key]; an unknown/missing stored value falls back to [DAILY] — the most
 * protective choice is the safe default direction. "Every Month" is a fixed
 * 30-day interval (a due-check window, not a calendar anniversary — the
 * check runs when the app opens, so exact calendar arithmetic buys nothing).
 */
enum class AutoBackupFrequency(val key: String, val intervalMillis: Long) {
    DAILY("daily", 24L * 60L * 60L * 1000L),
    WEEKLY("weekly", 7L * 24L * 60L * 60L * 1000L),
    BIWEEKLY("biweekly", 14L * 24L * 60L * 60L * 1000L),
    MONTHLY("monthly", 30L * 24L * 60L * 60L * 1000L);

    companion object {
        fun fromKey(key: String?): AutoBackupFrequency =
            values().firstOrNull { it.key == key } ?: DAILY
    }
}
