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
 * The DISPLAY-oriented reason an automatic-backup pass failed (owner ruling,
 * July 23 2026). Distinct from [BackupFailureCategory], which is the shared
 * per-type engine classification that also drives the manual Backup Status
 * rows and the 3-strikes dialog — this enum is JUST for the automatic-backup
 * status line and carries the two extra distinctions that line needs:
 *  - [DESTINATION_FULL] — a destination write that specifically failed for
 *    lack of storage space (a [BackupFailureCategory.DESTINATION_WRITE] whose
 *    exception [BackupFailureClassifier.isInsufficientStorage] recognized), so
 *    the user sees an accurate "not enough space" message instead of a generic
 *    write failure;
 *  - [UNEXPECTED] — an unclassified/internal error (the controller's defensive
 *    catch), so the user sees "an unexpected internal error" rather than the
 *    source-data message.
 *
 * Persisted by name in [RecoveryBackupState]; the names of the four values it
 * shares with [BackupFailureCategory] are intentionally identical so an older
 * stored category value still parses. Never surfaced raw to the user — the UI
 * maps each value to owner-approved wording.
 */
enum class AutoBackupFailureReason {
    DESTINATION_PERMISSION,
    DESTINATION_WRITE,
    DESTINATION_FULL,
    SOURCE,
    VERIFY,
    UNEXPECTED;

    companion object {
        fun fromKey(key: String?): AutoBackupFailureReason? = values().firstOrNull { it.name == key }
    }
}
