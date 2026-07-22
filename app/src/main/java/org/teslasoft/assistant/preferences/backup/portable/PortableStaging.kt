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

/**
 * Per-run staging directories for portable-package creation and restoration
 * (owner ruling 14): app-private cache only, one directory per run, deleted in
 * `finally` on success, failure, cancellation, and authentication error, and
 * swept wholesale at startup — anything present at process start is by
 * definition a dead run. "Deleted" means unlinked; no secure-erase claim is
 * made (flash storage makes that promise false).
 */
object PortableStaging {

    private const val ROOT = "portable_staging"

    /** A fresh per-run staging directory. The caller must delete it in a
     *  `finally` via [delete] on EVERY exit path. */
    fun newRunDir(context: Context): File {
        val dir = File(File(context.cacheDir, ROOT), "run_${System.nanoTime()}")
        check(dir.mkdirs() || dir.isDirectory) { "could not create staging directory" }
        return dir
    }

    /** Recursive best-effort delete of one run directory. */
    fun delete(dir: File?) {
        if (dir == null) return
        try {
            dir.walkBottomUp().forEach { runCatching { it.delete() } }
        } catch (_: Exception) { /* best-effort; the startup sweep is the net */ }
    }

    /** Startup sweep: remove EVERYTHING under the staging root. Runs from the
     *  housekeeping thread and whenever the backup feature is entered. */
    fun sweep(context: Context) {
        try {
            val root = File(context.cacheDir, ROOT)
            if (root.exists()) root.walkBottomUp().forEach { runCatching { it.delete() } }
        } catch (_: Exception) { /* best-effort */ }
    }
}
