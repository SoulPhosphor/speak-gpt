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

    fun writeAndVerify(target: File, content: String): Boolean {
        val tmp = File(target.parentFile, target.name + ".tmp")
        return try {
            FileOutputStream(tmp).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
                // Force the bytes to disk before the rename makes them the
                // real file — otherwise a power loss could publish an empty
                // file under the final name.
                out.fd.sync()
            }
            if (tmp.readText(Charsets.UTF_8) != content) {
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
            target.readText(Charsets.UTF_8) == content
        } catch (_: Exception) {
            try {
                tmp.delete()
            } catch (_: Exception) {
                // nothing left to clean
            }
            false
        }
    }
}
