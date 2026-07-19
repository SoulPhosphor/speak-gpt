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

/**
 * Pure filename/timing rules for Profile Images. Kept free of Android and
 * file-system APIs so every decision here is unit-testable without a device.
 */
object ProfileImageFileNaming {

    private val PERMANENT_FILENAME_REGEX = Regex("^profile_([0-9a-f]{64})\\.jpg$")

    /** The permanent filename contract: profile_<hash>.jpg. */
    fun permanentFileName(hash: String): String = "profile_$hash.jpg"

    /**
     * Extracts the hash from a permanent filename, requiring an exact
     * "profile_<64 lowercase hex characters>.jpg" match (RECONCILIATION,
     * step 1). Anything else - wrong prefix, wrong length, uppercase hex,
     * wrong extension - returns null and must never be treated as valid.
     */
    fun hashFromPermanentFilename(name: String): String? =
        PERMANENT_FILENAME_REGEX.matchEntire(name)?.groupValues?.get(1)

    /**
     * Whether a framing session directory last modified at
     * [dirLastModifiedMillis] should be swept away, given the current time
     * and the stale threshold (TEMPORARY FILE CLEANUP: only stale sessions
     * are removed, and only when Profile Images opens - never indiscriminately).
     */
    fun isStaleFramingSession(dirLastModifiedMillis: Long, nowMillis: Long, staleThresholdMillis: Long): Boolean =
        nowMillis - dirLastModifiedMillis > staleThresholdMillis
}
