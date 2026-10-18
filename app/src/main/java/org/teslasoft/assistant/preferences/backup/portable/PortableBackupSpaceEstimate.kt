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

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import java.io.File
import org.teslasoft.assistant.preferences.includes.PdfAttachmentStore

/**
 * How much free space creating a Recovery Backup needs, checked before any
 * file is written so a full device fails with a clear reason instead of part
 * way through (chat-storage-and-backup-plan.md §4.5).
 *
 * Creation keeps, in the app's private cache: copies of the chats artifact
 * and the database snapshots, the package contents (every artifact,
 * including image and PDF files, which are read from where they live), and
 * the finished package. So the estimate is three times the preference and
 * database data plus twice the image and PDF files, plus a fixed reserve.
 * It deliberately errs high.
 */
internal object PortableBackupSpaceEstimate {

    /** Kept free beyond the estimate, so the device is never filled to zero. */
    const val RESERVE_BYTES = 64L * 1024L * 1024L

    data class Check(val requiredBytes: Long, val availableBytes: Long) {
        /** Additional free space needed, or 0 when there is enough. */
        val shortfallBytes: Long get() = (requiredBytes - availableBytes).coerceAtLeast(0L)
    }

    fun check(context: Context, stagingDir: File): Check {
        val app = context.applicationContext
        val stagedSources = treeBytes(File(app.dataDir, "shared_prefs")) +
            treeBytes(app.getDatabasePath("x").parentFile)
        val readInPlace = treeBytes(app.getExternalFilesDir("images")) +
            treeBytes(app.getExternalFilesDir("profile_images")) +
            treeBytes(
                app.getExternalFilesDir(PdfAttachmentStore.ROOT) ?: File(app.filesDir, PdfAttachmentStore.ROOT)
            )
        return Check(
            requiredBytes = required(stagedSources, readInPlace),
            availableBytes = stagingDir.usableSpace
        )
    }

    /** Pure arithmetic, separated for tests. */
    // Peak during verification: staged artifacts, the first inner zip, the
    // finished package, the decoded inner zip and the re-extracted artifacts
    // coexist (five copies of staged data; four of files read in place).
    fun required(stagedSourceBytes: Long, readInPlaceBytes: Long): Long =
        5L * stagedSourceBytes + 4L * readInPlaceBytes + RESERVE_BYTES

    private fun treeBytes(root: File?): Long {
        if (root == null || !root.exists()) return 0L
        return root.walkTopDown().filter(File::isFile).sumOf { it.length() }
    }
}
