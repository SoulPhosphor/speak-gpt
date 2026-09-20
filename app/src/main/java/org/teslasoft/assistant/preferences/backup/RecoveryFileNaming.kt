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
 * Backup filename shapes (owner filename architecture, July 22 2026). No app
 * name is hardcoded here: the brand arrives as a parameter (callers resolve
 * it via [BackupBrand]) and is re-sanitized defensively, so a future app
 * rename is a one-resource change, never a format redesign.
 *
 * Approved shapes ({Brand} = the configured backup filename brand):
 *   {Brand}-Recovery-Protected-2026-07-21_1722.zip
 *   {Brand}-Recovery-Unencrypted-2026-07-21_1722.zip
 *   {Brand}-Automatic-Recovery-Protected-2026-07-21_1722.zip
 *   {Brand}-Automatic-Recovery-Unencrypted-2026-07-21_1722.zip
 *   {Brand}-Readable-Chats-Complete-2026-07-21_1722.zip
 *   {Brand}-Readable-Chats-Incremental-2026-07-21_1722.zip
 * Same-minute collisions append -2, -3, ... (never hashes).
 *
 * ⚠️ FILENAMES ARE NOT IDENTITY AND NEVER AUTHORIZE DELETION (owner ruling):
 * packages are recognized by their magic/format/protection metadata and
 * authenticated contents; [hasAutomaticRecoveryShape] is deliberately
 * BRAND-AGNOSTIC (old- and new-brand files both match after a rename) and is
 * only ONE of the conjunctive automatic-rotation ownership conditions — a
 * file may be deleted by rotation only when it ALSO was created by the
 * automatic writer, is recorded in the durable automatic-backup ownership
 * index, sits in the configured automatic destination, and passed
 * reopen-validation into the retained set. Manual packages, readable chat
 * exports, portable data copies and user-saved files are NEVER rotated, even
 * inside the automatic folder. No deletion logic exists in this helper.
 */
object RecoveryFileNaming {

    const val EXT = ".zip"

    private val STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm", Locale.US)

    /** The shared per-run stamp, e.g. "2026-07-21_1722" — minute resolution,
     *  lexically sortable == chronologically sortable. */
    fun stamp(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        STAMP.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    private fun seqSuffix(seq: Int): String {
        require(seq >= 1) { "sequence starts at 1" }
        return if (seq == 1) "" else "-$seq"
    }

    /** A MANUAL portable recovery package. */
    fun manualRecoveryPackage(
        brand: String,
        protected: Boolean,
        epochMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        seq: Int = 1
    ): String = "${BackupBrand.sanitize(brand)}-Recovery-" +
        (if (protected) "Protected" else "Unencrypted") +
        "-${stamp(epochMillis, zone)}${seqSuffix(seq)}$EXT"

    /** An AUTOMATIC portable recovery package. (Naming only — the automatic
     *  writer itself is not authorized yet, owner ruling July 22 2026.) */
    fun automaticRecoveryPackage(
        brand: String,
        protected: Boolean,
        epochMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        seq: Int = 1
    ): String = "${BackupBrand.sanitize(brand)}-Automatic-Recovery-" +
        (if (protected) "Protected" else "Unencrypted") +
        "-${stamp(epochMillis, zone)}${seqSuffix(seq)}$EXT"

    /** A human-readable chat backup ZIP. */
    fun readableChats(
        brand: String,
        complete: Boolean,
        epochMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        seq: Int = 1
    ): String = "${BackupBrand.sanitize(brand)}-Readable-Chats-" +
        (if (complete) "Complete" else "Incremental") +
        "-${stamp(epochMillis, zone)}${seqSuffix(seq)}$EXT"

    /**
     * True when [name] merely LOOKS like an automatic recovery package, under
     * ANY brand (brand-agnostic by design: an app rename must not orphan or
     * misclassify older files). Necessary-but-never-sufficient for rotation —
     * see the class doc; this function must never be the sole basis for
     * touching a file.
     */
    fun hasAutomaticRecoveryShape(name: String): Boolean =
        AUTOMATIC_SHAPE.matches(name)

    private val AUTOMATIC_SHAPE = Regex(
        "^[A-Za-z0-9][A-Za-z0-9-]{0,31}-Automatic-Recovery-(Protected|Unencrypted)-" +
            "\\d{4}-\\d{2}-\\d{2}_\\d{4}(-\\d+)?\\.zip$"
    )

    /**
     * DORMANT — Separate Recovery Files naming (owner ruling, July 22 2026):
     * the v2 architecture defines ONE complete enveloped package as the
     * portable recovery unit. These per-artifact names stay dormant until
     * separate-artifact protection/portability/restore behavior receives its
     * own owner decision. Nothing may call this in production paths.
     */
    fun dormantSeparateArtifact(
        brand: String,
        type: BackupType,
        epochMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        seq: Int = 1
    ): String {
        val b = BackupBrand.sanitize(brand)
        val s = stamp(epochMillis, zone)
        val suffix = seqSuffix(seq)
        return when (type) {
            BackupType.MEMORY -> "$b-Memory-$s$suffix.dbbackup"
            BackupType.LOREBOOK -> "$b-Lorebooks-$s$suffix.dbbackup"
            BackupType.USER_IMAGE -> "$b-User-Image-Database-$s$suffix.dbbackup"
            BackupType.CHATS -> "$b-Chats-Recovery-$s$suffix.zip"
        }
    }
}
