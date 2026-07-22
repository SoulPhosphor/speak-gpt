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

import android.content.Context
import org.teslasoft.assistant.R

/**
 * The user-visible backup filename BRAND (owner filename architecture, July 22
 * 2026). Three deliberately separate concepts:
 *
 *  1. The displayed application name (the Android app label) — may change in
 *     a future release; NEVER read for filenames.
 *  2. THIS: the filename brand — one centralized configurable resource
 *     (`backup_filename_brand`), sanitized here, with a stable fallback when
 *     empty or invalid. The current resource value is a PLACEHOLDER; the
 *     owner has not selected the final brand.
 *  3. Stable package identity — filename text is NOT identity. Packages are
 *     recognized by magic + format version + protection metadata +
 *     authenticated contents; renaming the app or changing this brand must
 *     never make older backups unreadable, and no filename prefix ever
 *     authorizes deletion.
 *
 * [sanitize] is pure and unit-tested; [resolve] is the one Android touchpoint.
 */
object BackupBrand {

    /** Proposed stable fallback when the configured brand is empty or fully
     *  invalid after sanitization (owner approval pending). */
    const val FALLBACK = "Backup"

    /** Filename-safe cap; generous for a brand, hostile to abuse. */
    const val MAX_LENGTH = 32

    /**
     * Make a configured brand filename-safe across Android storage providers:
     * trim; whitespace runs become single hyphens; only ASCII letters, digits
     * and hyphens survive; hyphen runs collapse; leading/trailing hyphens are
     * dropped; length capped at [MAX_LENGTH]; empty result -> [FALLBACK].
     */
    fun sanitize(raw: String?): String {
        if (raw == null) return FALLBACK
        val hyphenated = raw.trim().replace(Regex("\\s+"), "-")
        val filtered = hyphenated.filter { (it in 'A'..'Z') || (it in 'a'..'z') || (it in '0'..'9') || it == '-' }
        val collapsed = filtered.replace(Regex("-{2,}"), "-").trim('-')
        val capped = collapsed.take(MAX_LENGTH).trim('-')
        return capped.ifEmpty { FALLBACK }
    }

    /** The sanitized brand from the centralized resource. */
    fun resolve(context: Context): String = sanitize(
        try {
            context.getString(R.string.backup_filename_brand)
        } catch (_: Exception) {
            null
        }
    )
}
