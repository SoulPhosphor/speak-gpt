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

/**
 * Keep-N rotation per type (Build Phase 2 item 3). Pure so the last-known-good
 * survival invariant is unit-pinned.
 *
 * Rotation runs ONLY after the new backup has been copied to the destination,
 * reopened and verified (item 4d) — so by the time [toDelete] is consulted the
 * newest file is already known-good and is never in the returned set. This
 * planner just decides which of the oldest files fall outside the keep window.
 */
object BackupRotationPlanner {

    const val KEEP = 5

    /**
     * @param existingOldestFirst every finalized backup of ONE type, oldest
     *        first (newest last), including the just-finalized one.
     * @param keep how many of the newest to retain (default [KEEP]).
     * @return the oldest files to delete — a prefix of [existingOldestFirst];
     *         empty when the set is already within [keep]. The newest [keep]
     *         files (the last-known-good among them) are never returned.
     */
    fun toDelete(existingOldestFirst: List<String>, keep: Int = KEEP): List<String> {
        require(keep >= 1) { "keep must be >= 1" }
        if (existingOldestFirst.size <= keep) return emptyList()
        return existingOldestFirst.dropLast(keep)
    }
}
