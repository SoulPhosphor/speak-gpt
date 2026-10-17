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

package org.teslasoft.assistant.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Write-to-temp → fsync → rename → read-back-verify. For files whose absence
 * is recoverable but whose silent truncation is not (memory backups): a
 * crash mid-write must never leave a half-written file under the final name,
 * and a caller about to do something destructive (Reset memories with
 * "back up first") must be able to trust that `true` means the bytes are
 * really on disk.
 */
object AtomicFileWriter {

    enum class ByteWriteResult {
        WRITTEN,
        EXISTING_MATCH,
        FAILED
    }

    fun writeAndVerify(target: File, content: String): Boolean {
        val tmp = File(target.parentFile, target.name + ".tmp")
        return try {
            // Encode once and verify against these bytes. Reading the file
            // back as a String would need several times the backup's size in
            // heap at once, which ran a large memory export out of memory.
            val bytes = content.toByteArray(Charsets.UTF_8)
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                // Force the bytes to disk before the rename makes them the
                // real file — otherwise a power loss could publish an empty
                // file under the final name.
                out.fd.sync()
            }
            if (!fileMatches(tmp, bytes)) {
                tmp.delete()
                return false
            }
            // Timestamped names normally make the target fresh; if it does
            // exist (clock collision), replacing it is the intended outcome.
            val renamed = tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))
            if (!renamed) {
                tmp.delete()
                return false
            }
            fileMatches(target, bytes)
        } catch (_: Exception) {
            try {
                tmp.delete()
            } catch (_: Exception) {
                // nothing left to clean
            }
            false
        }
    }

    /** Streams [file] in small chunks and compares it with [expected]. */
    private fun fileMatches(file: File, expected: ByteArray): Boolean {
        if (file.length() != expected.size.toLong()) return false
        val buffer = ByteArray(64 * 1024)
        var offset = 0
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (offset + read > expected.size) return false
                for (i in 0 until read) {
                    if (buffer[i] != expected[offset + i]) return false
                }
                offset += read
            }
        }
        return offset == expected.size
    }

    /**
     * Binary counterpart used by generated-image registration. The target is
     * never removed or replaced: an existing matching file makes a retried
     * registration idempotent, while an existing different file is a hard
     * failure. Only the dedicated `.catalogtmp` file is cleaned on failure,
     * so this helper cannot damage a legacy/shared asset.
     */
    fun writeBytesAndVerify(target: File, content: ByteArray): ByteWriteResult {
        val parent = target.parentFile ?: return ByteWriteResult.FAILED
        if (!parent.exists() && !parent.mkdirs()) return ByteWriteResult.FAILED
        if (target.exists()) {
            return try {
                if (target.readBytes().contentEquals(content)) {
                    ByteWriteResult.EXISTING_MATCH
                } else {
                    ByteWriteResult.FAILED
                }
            } catch (_: Exception) {
                ByteWriteResult.FAILED
            }
        }

        val tmp = File(parent, target.name + ".catalogtmp")
        return try {
            FileOutputStream(tmp).use { out ->
                out.write(content)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.readBytes().contentEquals(content)) {
                tmp.delete()
                return ByteWriteResult.FAILED
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return ByteWriteResult.FAILED
            }
            if (target.readBytes().contentEquals(content)) {
                ByteWriteResult.WRITTEN
            } else {
                // The published file is ours and failed read-back verification.
                target.delete()
                ByteWriteResult.FAILED
            }
        } catch (_: Exception) {
            try { tmp.delete() } catch (_: Exception) { }
            ByteWriteResult.FAILED
        }
    }
}
