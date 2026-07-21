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

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Readable backup filenames (owner ruling, July 21 2026) — replaces the old
 * epoch-millisecond names. One shared minute-resolution timestamp per run; if
 * two runs land in the same minute a short sequence number is appended (never a
 * hash). Pure so the exact shapes are unit-pinned.
 *
 * Examples:
 *   SpeakGPT-Recovery-2026-07-21_1722.zip                 (single package)
 *   SpeakGPT-Memory-2026-07-21_1722.dbbackup              (separate artifact)
 *   SpeakGPT-Lorebooks-2026-07-21_1722.dbbackup
 *   SpeakGPT-User-Image-Database-2026-07-21_1722.dbbackup
 *   SpeakGPT-Chats-Recovery-2026-07-21_1722.zip
 *   SpeakGPT-Readable-Chats-Complete-2026-07-21_1722.zip
 *   SpeakGPT-Readable-Chats-Incremental-2026-07-21_1722.zip
 *
 * The minute-resolution timestamp sorts lexically == chronologically, so
 * rotation can order automatic packages by name.
 */
object RecoveryFileNaming {

    const val PACKAGE_PREFIX = "SpeakGPT-Recovery-"
    const val PACKAGE_EXT = ".zip"

    private val STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm", Locale.US)

    /** The shared per-run stamp, e.g. "2026-07-21_1722". */
    fun stamp(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        STAMP.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    private fun seqSuffix(seq: Int): String = if (seq <= 1) "" else "-$seq"

    /** The single complete recovery package (all artifacts + manifest). */
    fun recoveryPackage(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault(), seq: Int = 1): String =
        "$PACKAGE_PREFIX${stamp(epochMillis, zone)}${seqSuffix(seq)}$PACKAGE_EXT"

    /** A separate recovery artifact file (Separate Recovery Files mode). */
    fun artifact(type: BackupType, epochMillis: Long, zone: ZoneId = ZoneId.systemDefault(), seq: Int = 1): String {
        val stamp = stamp(epochMillis, zone)
        val suffix = seqSuffix(seq)
        return when (type) {
            BackupType.MEMORY -> "SpeakGPT-Memory-$stamp$suffix.dbbackup"
            BackupType.LOREBOOK -> "SpeakGPT-Lorebooks-$stamp$suffix.dbbackup"
            BackupType.USER_IMAGE -> "SpeakGPT-User-Image-Database-$stamp$suffix.dbbackup"
            BackupType.CHATS -> "SpeakGPT-Chats-Recovery-$stamp$suffix.zip"
        }
    }

    /** A human-readable chat backup ZIP. */
    fun readableChats(complete: Boolean, epochMillis: Long, zone: ZoneId = ZoneId.systemDefault(), seq: Int = 1): String {
        val kind = if (complete) "Complete" else "Incremental"
        return "SpeakGPT-Readable-Chats-$kind-${stamp(epochMillis, zone)}${seqSuffix(seq)}$PACKAGE_EXT"
    }

    /** True for a name produced by [recoveryPackage] — the set rotation walks. */
    fun isRecoveryPackage(name: String): Boolean =
        name.startsWith(PACKAGE_PREFIX) && name.endsWith(PACKAGE_EXT)
}
