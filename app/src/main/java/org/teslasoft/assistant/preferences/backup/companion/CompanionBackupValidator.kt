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

import org.teslasoft.assistant.preferences.backup.portable.PortableRecoveryLimits
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
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

    fun validate(file: File): Verdict = validateWithDetail(file).first

    /**
     * [validate] plus, for any rejection, a restore-diagnostics reason naming
     * the ladder step that failed. It never includes file content, a name, or
     * an image hash.
     */
    internal fun validateWithDetail(file: File): Pair<Verdict, String?> {
        if (!file.isFile) return Verdict.Damaged to "archive file is missing"
        if (file.length() > PortableRecoveryLimits.COMPANION_ROLEPLAY_ARCHIVE_BYTES) {
            return Verdict.Damaged to "archive is larger than the size limit"
        }
        val zip = try {
            ZipFile(file)
        } catch (_: Exception) {
            return Verdict.WrongFile to "archive does not open as a ZIP"
        }
        zip.use {
            val manifestEntry = zip.getEntry(CompanionBackupFormat.MANIFEST_ENTRY)
                ?: return Verdict.WrongFile to "archive has no manifest entry"
            val manifestText = try {
                zip.getInputStream(manifestEntry).use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var count = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > PortableRecoveryLimits.COMPANION_ROLEPLAY_ARCHIVE_BYTES) {
                            return Verdict.Damaged to "manifest is larger than the size limit"
                        }
                        out.write(buffer, 0, read)
                    }
                    out.toString(Charsets.UTF_8.name())
                }
            } catch (e: Exception) {
                return Verdict.Damaged to "manifest could not be read (${e.javaClass.simpleName})"
            }

            val (parsed, parseDetail) = CompanionBackupCodec.parseWithDetail(manifestText)
            val manifest = when (parsed) {
                is CompanionBackupCodec.ParseResult.Ok -> parsed.manifest
                CompanionBackupCodec.ParseResult.WrongFile -> return Verdict.WrongFile to parseDetail
                CompanionBackupCodec.ParseResult.NewerFormat -> return Verdict.NewerFormat to parseDetail
                CompanionBackupCodec.ParseResult.Damaged -> return Verdict.Damaged to parseDetail
            }

            for (image in manifest.images) {
                val entry = zip.getEntry(image.file)
                    ?: return Verdict.Damaged to "a listed profile image is missing from the archive"
                if (entry.size > PortableRecoveryLimits.IMAGE_ASSET_BYTES) {
                    return Verdict.Damaged to "a profile image is larger than the size limit"
                }
                val actualHash = try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            if (count > PortableRecoveryLimits.IMAGE_ASSET_BYTES) {
                                return Verdict.Damaged to "a profile image is larger than the size limit"
                            }
                            digest.update(buffer, 0, read)
                        }
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                } catch (e: Exception) {
                    return Verdict.Damaged to "a profile image could not be read (${e.javaClass.simpleName})"
                }
                if (actualHash != image.hash) {
                    return Verdict.Damaged to "a profile image's contents do not match its recorded hash"
                }
            }

            return Verdict.Valid(manifest) to null
        }
    }

    /** Reads one validated image's bytes back out of the archive. */
    fun readImageBytes(file: File, image: CompanionBackupImage): ByteArray =
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(image.file)
                ?: throw IllegalStateException("validated image entry disappeared: ${image.file}")
            if (entry.size > PortableRecoveryLimits.IMAGE_ASSET_BYTES) {
                throw IllegalStateException("validated image exceeds the decoded-size policy")
            }
            zip.getInputStream(entry).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var count = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    if (count > PortableRecoveryLimits.IMAGE_ASSET_BYTES) {
                        throw IllegalStateException("validated image exceeds the decoded-size policy")
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }
}
