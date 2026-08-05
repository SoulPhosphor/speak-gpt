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

import org.teslasoft.assistant.util.Hash
import java.io.File
import java.util.zip.ZipFile

/**
 * The §6.1 validation ladder (companion-roleplay-backup-plan.md): everything
 * is checked BEFORE anything on the device is touched, and any failure maps
 * to exactly one approved error dialog. Pure JVM (java.util.zip + the codec),
 * so every rejection cause is unit-testable.
 *
 * Ladder -> verdict:
 *  - file does not open as a ZIP, or has no backup.json  -> [Verdict.WrongFile]
 *    (nothing establishes it ever was a companion backup)
 *  - backup.json present but not parseable JSON          -> [Verdict.Damaged]
 *  - `format` marker is something else                   -> [Verdict.WrongFile]
 *  - `format_version` above this build's                 -> [Verdict.NewerFormat]
 *  - sections structurally unsound                       -> [Verdict.Damaged]
 *  - a manifest-listed image missing from the archive,
 *    or its bytes do not hash to the manifest's hash     -> [Verdict.Damaged]
 *
 * The image byte-hash check exists because restore stores images under their
 * CONTENT hash: an entry whose bytes no longer match its declared hash would
 * otherwise restore into a file no reference points at — a silently missing
 * picture. Rejecting the file as damaged tells the truth instead.
 */
object CompanionBackupValidator {

    sealed class Verdict {
        data class Valid(val manifest: CompanionBackupManifest) : Verdict()
        object WrongFile : Verdict()
        object NewerFormat : Verdict()
        object Damaged : Verdict()
    }

    fun validate(file: File): Verdict {
        val zip = try {
            ZipFile(file)
        } catch (_: Exception) {
            return Verdict.WrongFile
        }
        zip.use {
            val manifestEntry = zip.getEntry(CompanionBackupFormat.MANIFEST_ENTRY)
                ?: return Verdict.WrongFile
            val manifestText = try {
                zip.getInputStream(manifestEntry).use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                }
            } catch (_: Exception) {
                return Verdict.Damaged
            }

            val manifest = when (val parsed = CompanionBackupCodec.parse(manifestText)) {
                is CompanionBackupCodec.ParseResult.Ok -> parsed.manifest
                CompanionBackupCodec.ParseResult.WrongFile -> return Verdict.WrongFile
                CompanionBackupCodec.ParseResult.NewerFormat -> return Verdict.NewerFormat
                CompanionBackupCodec.ParseResult.Damaged -> return Verdict.Damaged
            }

            for (image in manifest.images) {
                val entry = zip.getEntry(image.file) ?: return Verdict.Damaged
                val bytes = try {
                    zip.getInputStream(entry).use { it.readBytes() }
                } catch (_: Exception) {
                    return Verdict.Damaged
                }
                if (Hash.hash(bytes) != image.hash) return Verdict.Damaged
            }

            return Verdict.Valid(manifest)
        }
    }

    /** Reads one validated image's bytes back out of the archive. */
    fun readImageBytes(file: File, image: CompanionBackupImage): ByteArray =
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(image.file)
                ?: throw IllegalStateException("validated image entry disappeared: ${image.file}")
            zip.getInputStream(entry).use { it.readBytes() }
        }
}
