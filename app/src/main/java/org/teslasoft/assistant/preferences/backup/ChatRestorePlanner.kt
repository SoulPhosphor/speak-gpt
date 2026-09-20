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
 * Pure decisions for the same-device chat recovery restore (Build Phase 3
 * item 5) — the journaled-swap recovery logic and the archive-entry
 * validation, unit-tested in app/src/test.
 *
 * The swap journal records two phases:
 *  - [PHASE_STAGED]: the archive was validated and extracted into staging;
 *    the live chat files are UNTOUCHED.
 *  - [PHASE_SWAPPING]: quarantine of the current files completed and the
 *    replacement copy began; the live set may be part old / part new.
 *
 * A process death is recovered at the next startup by [planRecovery]: an
 * interrupted swap is FINISHED from staging (the staged set is verified
 * bytes), never rolled back half-way; an interrupted stage is discarded (the
 * live files were never touched).
 */
object ChatRestorePlanner {

    const val PHASE_STAGED = "staged"
    const val PHASE_SWAPPING = "swapping"

    enum class Recovery {
        /** No pending journal — nothing to do. */
        NOTHING,

        /** Journal says the swap started and staging is complete: redo the
         *  copy (idempotent — staged bytes are the verified source of truth)
         *  and clear the journal. */
        RESUME_SWAP,

        /** Journal exists but the swap never started: discard staging and
         *  clear the journal; the live files were never touched. */
        DISCARD_STAGING,

        /** Journal says swapping but the staging files are gone/incomplete:
         *  the copy cannot be finished OR undone automatically. Preserved
         *  pre-restore quarantine copies remain the recovery source; log
         *  loudly and clear the journal. */
        UNRECOVERABLE
    }

    fun planRecovery(phase: String?, stagingComplete: Boolean): Recovery = when (phase) {
        null, "" -> Recovery.NOTHING
        PHASE_SWAPPING -> if (stagingComplete) Recovery.RESUME_SWAP else Recovery.UNRECOVERABLE
        PHASE_STAGED -> Recovery.DISCARD_STAGING
        // An unknown phase written by a future build: touch nothing beyond
        // clearing — the conservative read is "stage never completed".
        else -> Recovery.DISCARD_STAGING
    }

    /**
     * The ONLY archive entry names a chat restore may write, exactly the
     * names the chats snapshot produces: the encrypted chat list, per-chat
     * histories, and per-chat settings. Anything else — other prefs files, a
     * path separator, a traversal — is rejected, so a crafted archive cannot
     * plant arbitrary files in shared_prefs.
     */
    private val ALLOWED_ENTRY = Regex("^enc\\.(chat_list|chat_[A-Za-z0-9_-]+|settings\\.[A-Za-z0-9_-]+)\\.xml$")

    fun isAllowedEntryName(name: String): Boolean =
        !name.contains('/') && !name.contains('\\') && ALLOWED_ENTRY.matches(name)

    /** Whether an EXISTING shared_prefs file belongs to chat storage and is
     *  therefore replaced (quarantined first) by a wholesale restore. Same
     *  shape as [isAllowedEntryName] — other tenants of shared_prefs are
     *  never touched. */
    fun isChatStorageFileName(name: String): Boolean = isAllowedEntryName(name)
}
