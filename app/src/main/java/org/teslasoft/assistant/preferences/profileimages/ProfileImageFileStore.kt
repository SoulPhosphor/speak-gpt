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

package org.teslasoft.assistant.preferences.profileimages

import org.teslasoft.assistant.util.Hash
import java.io.File
import java.io.IOException

/**
 * Atomic write/delete of permanent Profile Image files. Operates on plain
 * File and ByteArray only (no Context, no Bitmap) so the write-once/dedup/
 * no-partial-file behavior is unit-testable with real temp directories,
 * matching the plan's ATOMIC SAVE PROCESS: encode once, hash the encoded
 * bytes, write to a temp file, then rename into place - never expose a
 * partially-written permanent file.
 */
object ProfileImageFileStore {

    data class WriteResult(val file: File, val hash: String, val wasNewFile: Boolean)

    /**
     * Writes [encodedJpegBytes] into [storageDir] under its content hash.
     * If a file with that exact hash already exists, it is reused untouched
     * (dedup - "Avoid replacing an existing identical hash file") rather
     * than being overwritten. Returns null only if the directory could not
     * be created or the write/rename failed; callers must not assign an
     * image to a profile in that case.
     */
    fun writeEncodedImage(storageDir: File, encodedJpegBytes: ByteArray): WriteResult? {
        if (!storageDir.exists() && !storageDir.mkdirs()) return null

        val hash = Hash.hash(encodedJpegBytes)
        val target = File(storageDir, ProfileImageFileNaming.permanentFileName(hash))

        if (target.exists()) {
            return WriteResult(target, hash, wasNewFile = false)
        }

        val temp = File(storageDir, "${ProfileImageFileNaming.permanentFileName(hash)}.tmp-${System.nanoTime()}")
        return try {
            temp.writeBytes(encodedJpegBytes)

            if (target.exists()) {
                // Another writer raced us to the same content hash; the
                // existing file is byte-identical by construction (same
                // hash), so keep it and drop our temp copy.
                temp.delete()
                WriteResult(target, hash, wasNewFile = false)
            } else if (temp.renameTo(target)) {
                WriteResult(target, hash, wasNewFile = true)
            } else {
                temp.delete()
                null
            }
        } catch (e: IOException) {
            temp.delete()
            null
        }
    }

    /**
     * Deletes the permanent file for [hash] in [storageDir]. An already
     * missing file counts as success (PERMANENT DELETION: "Treat an already
     * missing file as success") - deleting the file first, then the catalog
     * record, is the safe order: a failed record delete leaves a retryable
     * unavailable placeholder, while deleting the record first could let a
     * failed file delete be rediscovered and registered again.
     */
    fun deleteImageFile(storageDir: File, hash: String): Boolean {
        val file = File(storageDir, ProfileImageFileNaming.permanentFileName(hash))
        return !file.exists() || file.delete()
    }
}
