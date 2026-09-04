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

    // ---- manifest cross-check (Phase 9.2) -----------------------------------

    /** The single manifest version this reader understands. Must equal the
     *  producer's [ChatSnapshotManifest.MANIFEST_VERSION]; a drift guard in
     *  ChatRestorePlannerTest fails the build if they diverge. A future
     *  producer that bumps the format is rejected here rather than silently
     *  mis-read. */
    const val SUPPORTED_MANIFEST_VERSION = 1

    /** The one non-per-chat archive entry: the encrypted chat list. */
    const val CHAT_LIST_ENTRY = "enc.chat_list.xml"

    /** A stored chat id: a historical title hash or a UUID, path-safe. The same
     *  character class the per-chat entry names allow. */
    private val SAFE_CHAT_ID = Regex("^[A-Za-z0-9_-]+$")

    /** What is wrong with a manifest's declared chat set relative to the actual
     *  archive entries. Each is a distinct, reportable cause — nothing collapses
     *  into a generic "invalid archive". */
    enum class ManifestDefect {
        /** Missing `manifest_version`, or a version this build does not read. */
        UNSUPPORTED_VERSION,

        /** No `enc.chat_list.xml` entry. Restoring a chat set with no list would
         *  leave the destination with orphan history/settings and no chats. */
        MISSING_CHAT_LIST,

        /** The `chats` array names the same chat id twice. */
        DUPLICATE_CHAT_ID,

        /** A declared chat id is not path-safe. */
        UNSAFE_CHAT_ID,

        /** A declared chat has no `enc.chat_<id>.xml` history entry. */
        CHAT_MISSING_HISTORY,

        /** A declared chat has no `enc.settings.<id>.xml` entry. */
        CHAT_MISSING_SETTINGS,

        /** A per-chat archive entry belongs to no declared chat id. The archive
         *  carries a chat file the manifest never listed. */
        UNLISTED_CHAT_FILE
    }

    /**
     * Cross-checks the manifest's declared chat set against the exact set of
     * hashed archive entries (Phase 9.2). Returns null when the shape is
     * coherent, or the first defect found.
     *
     * [manifestVersion] is the archive's declared version (null if absent).
     * [chatIds] are the `chat_id`s from the manifest `chats` array, in order.
     * [hashedEntryNames] are the keys of the manifest `file_hashes` block — the
     * encrypted files the archive claims to carry.
     *
     * The rule: exactly the chat list plus, for every declared chat, its
     * history and settings, and nothing else. A declared chat missing either
     * file, or a per-chat file for an undeclared chat, is a mismatch and stops
     * the restore before anything is touched.
     */
    fun manifestDefect(
        manifestVersion: Int?,
        chatIds: List<String>,
        hashedEntryNames: Set<String>
    ): ManifestDefect? {
        if (manifestVersion != SUPPORTED_MANIFEST_VERSION) return ManifestDefect.UNSUPPORTED_VERSION
        if (CHAT_LIST_ENTRY !in hashedEntryNames) return ManifestDefect.MISSING_CHAT_LIST

        val declared = HashSet<String>()
        declared.add(CHAT_LIST_ENTRY)
        val seen = HashSet<String>()
        for (id in chatIds) {
            if (!SAFE_CHAT_ID.matches(id)) return ManifestDefect.UNSAFE_CHAT_ID
            if (!seen.add(id)) return ManifestDefect.DUPLICATE_CHAT_ID
            if ("enc.chat_$id.xml" !in hashedEntryNames) return ManifestDefect.CHAT_MISSING_HISTORY
            if ("enc.settings.$id.xml" !in hashedEntryNames) return ManifestDefect.CHAT_MISSING_SETTINGS
            declared.add("enc.chat_$id.xml")
            declared.add("enc.settings.$id.xml")
        }
        for (name in hashedEntryNames) {
            if (name !in declared) return ManifestDefect.UNLISTED_CHAT_FILE
        }
        return null
    }
}
