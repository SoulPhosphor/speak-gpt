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

package org.teslasoft.assistant.preferences

import org.json.JSONArray
import org.json.JSONObject

/**
 * Merges data written during an encryption outage back into the encrypted
 * store once the Keystore recovers (silent-failure audit, Round 4).
 *
 * While a file is LOCKED (see ChatStorageHealth) every read and write is
 * redirected to a separate `outage.<name>` file, so the outage side starts
 * empty and holds ONLY what the user did during the outage. That isolation
 * is what makes most reconciliation provably safe: a chat that exists only
 * on the outage side was created during the outage (append it); a key the
 * encrypted side has never held carries no conflict (copy it). Only a key
 * that is non-empty on BOTH sides with different values is a real conflict,
 * and those are never resolved automatically — the whole outage file is
 * preserved as a snapshot the owner can act on later, and the encrypted
 * side stays presented. Neither copy is ever silently discarded.
 *
 * Discipline (the same copy → verify → only-then-clear order the rest of
 * the storage layer uses, restartable and idempotent at every boundary):
 *
 *  1. If the file has conflicts and no snapshot is recorded yet, snapshot
 *     the WHOLE outage file and record the marker durably. A death between
 *     snapshot and marker at worst duplicates the snapshot on the next run
 *     — never skips it.
 *  2. Copy the conflict-free keys (committed). Copy-if-absent semantics
 *     make a re-run after a partial copy converge instead of clobbering.
 *  3. Verify every outage key is now either present-and-equal on the
 *     encrypted side, empty (nothing to preserve), or covered by the
 *     recorded snapshot. Anything else defers the file to the next start.
 *  4. Clear the marker, then delete the outage file. The marker is cleared
 *     FIRST: a stale marker surviving into a future outage of the same
 *     file would silently skip that outage's snapshot (loss); the reverse
 *     failure order merely duplicates a snapshot (noise).
 *
 * The chat list gets a structural merge instead of the generic key rules
 * (a whole-JSON blob under one key would otherwise always "conflict"):
 * outage-only chats are appended; a chat id present on both sides keeps
 * the encrypted entry and the outage copy rides in the snapshot. The
 * chat list is processed LAST so histories and settings are already in
 * place when their list entries appear — a death mid-run can leave an
 * orphan history file (invisible, merged next start) but never a listed
 * chat whose content is still missing. The rename journal gets a union
 * merge (its entries are independent facts; RenameJournal.record dedups
 * and its reconcile step discards stale entries safely).
 *
 * Pure logic against [Files] so every state and interruption boundary is
 * unit-tested in app/src/test (OutageReconcilerTest). The Android-backed
 * implementation lives in SecurePrefs.reconcileOutageAtStartup, which runs
 * on the startup thread BEFORE RenameJournal.reconcile — rename recovery
 * consults the live chat list, so the list must be merged first. No orphan
 * pruning may run against outage or snapshot files while any outage file
 * exists (same rule as RenameJournal.hasPending).
 */
object OutageReconciler {

    const val OUTAGE_PREFIX = "outage."
    const val CHAT_LIST_FILE = "chat_list"
    const val CHAT_LIST_KEY = "data"
    const val RENAME_JOURNAL_FILE = "rename_journal"
    const val RENAME_JOURNAL_KEY = "pending"

    /**
     * Storage surface the reconciler needs. Every mutation must be a
     * SYNCHRONOUS commit (return false / null on failure) — the ordering
     * guarantees only hold if nothing is apply()-deferred. Markers must be
     * durable across process death (ChatStorageHealth's journal in prod).
     */
    interface Files {
        /** Logical names that currently have an outage file. */
        fun outageNames(): List<String>

        /** Whether the encrypted side of [name] can be opened right now. */
        fun encryptedOpens(name: String): Boolean

        /** All entries of the encrypted file, or null when unreadable. */
        fun readEncrypted(name: String): Map<String, Any?>?

        /** All entries of the outage file (empty map when none). */
        fun readOutage(name: String): Map<String, Any?>

        /** Additive committed put into the encrypted file (no clear). */
        fun putEncrypted(name: String, entries: Map<String, Any?>): Boolean

        /**
         * Preserve [entries] as a durable recoverable copy tied to [name]
         * (an encrypted snapshot file in prod). Returns an identifier for
         * the copy, or null on failure — a failed snapshot must defer the
         * file, never proceed.
         */
        fun snapshot(name: String, entries: Map<String, Any?>): String?

        fun deleteOutage(name: String): Boolean

        fun snapshotMarker(name: String): String?
        fun setSnapshotMarker(name: String, snapshotId: String?)

        fun log(message: String)
    }

    data class Result(
        /** Files fully merged back; their outage copies are gone. */
        val merged: List<String>,
        /** Files left untouched for the next start (still locked, or a step failed). */
        val deferred: List<String>,
        /** Files whose conflicting outage copy was preserved as a snapshot. */
        val conflictsPreserved: List<String>
    )

    /**
     * A value that carries no information worth preserving: absent, the
     * empty string, or an empty JSON array (the storage layer's "no
     * messages yet" representation). Used on the ENCRYPTED side to decide
     * a key is safe to fill, and on the outage side to decide a key needs
     * no preservation.
     */
    fun isEmptyValue(value: Any?): Boolean =
        value == null || value == "" || value == "[]"

    fun reconcile(files: Files): Result {
        val names = files.outageNames()
        if (names.isEmpty()) return Result(emptyList(), emptyList(), emptyList())

        val merged = mutableListOf<String>()
        val deferred = mutableListOf<String>()
        val conflicts = mutableListOf<String>()

        // Chat list last — see the class doc for the ordering argument.
        val ordered = names.filter { it != CHAT_LIST_FILE } +
            names.filter { it == CHAT_LIST_FILE }

        for (name in ordered) {
            val outcome = try {
                reconcileOne(files, name)
            } catch (e: Exception) {
                files.log("Reconciling '$name' threw ${e.message}; deferred to the next start.")
                Outcome.DEFERRED
            }
            when (outcome) {
                Outcome.MERGED -> merged.add(name)
                Outcome.MERGED_WITH_CONFLICT -> { merged.add(name); conflicts.add(name) }
                Outcome.DEFERRED -> deferred.add(name)
            }
        }
        return Result(merged, deferred, conflicts)
    }

    private enum class Outcome { MERGED, MERGED_WITH_CONFLICT, DEFERRED }

    private fun reconcileOne(files: Files, name: String): Outcome {
        if (!files.encryptedOpens(name)) return Outcome.DEFERRED
        val enc = files.readEncrypted(name) ?: return Outcome.DEFERRED
        val out = files.readOutage(name)

        if (out.isEmpty() || out.values.all { isEmptyValue(it) }) {
            // Nothing was written during the outage worth carrying over.
            return if (files.deleteOutage(name)) Outcome.MERGED else Outcome.DEFERRED
        }

        return when (name) {
            RENAME_JOURNAL_FILE -> mergeRenameJournal(files, enc, out)
            CHAT_LIST_FILE -> mergeChatList(files, enc, out)
            else -> mergeGeneric(files, name, enc, out)
        }
    }

    // ----- Generic per-key merge (chat histories, per-chat settings, …) ---

    private fun mergeGeneric(
        files: Files,
        name: String,
        enc: Map<String, Any?>,
        out: Map<String, Any?>
    ): Outcome {
        val copy = out.filter { (k, v) -> isEmptyValue(enc[k]) && !isEmptyValue(v) }
        val conflicting = out.filter { (k, v) -> !isEmptyValue(enc[k]) && enc[k] != v }

        var hadConflict = false
        if (conflicting.isNotEmpty()) {
            hadConflict = true
            if (files.snapshotMarker(name) == null) {
                val snap = files.snapshot(name, out) ?: return Outcome.DEFERRED
                files.setSnapshotMarker(name, snap)
                files.log(
                    "'$name' was changed both before and during a storage outage; the outage copy was preserved as '$snap' and the encrypted copy stays in place. Neither was deleted."
                )
            }
        }

        if (copy.isNotEmpty() && !files.putEncrypted(name, copy)) return Outcome.DEFERRED

        // Verify before anything is cleared: every outage key must be
        // present-and-equal, empty, or covered by the recorded snapshot.
        val after = files.readEncrypted(name) ?: return Outcome.DEFERRED
        val safe = out.all { (k, v) ->
            after[k] == v || isEmptyValue(v) ||
                (conflicting.containsKey(k) && files.snapshotMarker(name) != null)
        }
        if (!safe) return Outcome.DEFERRED

        files.setSnapshotMarker(name, null)
        if (!files.deleteOutage(name)) return Outcome.DEFERRED
        return if (hadConflict) Outcome.MERGED_WITH_CONFLICT else Outcome.MERGED
    }

    // ----- Chat-list structural merge -------------------------------------

    private fun mergeChatList(
        files: Files,
        enc: Map<String, Any?>,
        out: Map<String, Any?>
    ): Outcome {
        val outJson = out[CHAT_LIST_KEY] as? String
        if (outJson.isNullOrBlank() || outJson == "[]") {
            return if (files.deleteOutage(CHAT_LIST_FILE)) Outcome.MERGED else Outcome.DEFERRED
        }

        val encJson = (enc[CHAT_LIST_KEY] as? String).let { if (it.isNullOrBlank()) "[]" else it }

        // A corrupt ENCRYPTED list is not this code's problem to fix — the
        // read path (preserveCorruptData) owns that; defer so the merge
        // lands in a repaired list on a later start, never inside garbage.
        val encList = parseChatList(encJson) ?: return Outcome.DEFERRED

        val outList = parseChatList(outJson)
        if (outList == null) {
            // The OUTAGE list is corrupt: preserve it, then stand down —
            // per-chat history files were already handled independently.
            if (files.snapshotMarker(CHAT_LIST_FILE) == null) {
                val snap = files.snapshot(CHAT_LIST_FILE, out) ?: return Outcome.DEFERRED
                files.setSnapshotMarker(CHAT_LIST_FILE, snap)
            }
            files.log("The chat list written during a storage outage did not parse; it was preserved as a snapshot instead of being merged.")
            files.setSnapshotMarker(CHAT_LIST_FILE, null)
            return if (files.deleteOutage(CHAT_LIST_FILE)) Outcome.MERGED_WITH_CONFLICT else Outcome.DEFERRED
        }

        val encIds = encList.mapNotNull { it.optString("id").ifBlank { null } }.toSet()
        val toAppend = mutableListOf<JSONObject>()
        val skipped = mutableListOf<JSONObject>()
        for (entry in outList) {
            val id = entry.optString("id")
            if (id.isBlank() || id in encIds) skipped.add(entry) else toAppend.add(entry)
        }

        var hadConflict = false
        if (skipped.isNotEmpty()) {
            // Same chat id on both sides (the same name existed before and
            // during the outage), or an entry with no id. The encrypted
            // entry stays presented; the outage copy is preserved whole.
            hadConflict = true
            if (files.snapshotMarker(CHAT_LIST_FILE) == null) {
                val snap = files.snapshot(CHAT_LIST_FILE, out) ?: return Outcome.DEFERRED
                files.setSnapshotMarker(CHAT_LIST_FILE, snap)
                files.log(
                    "${skipped.size} chat-list entr${if (skipped.size == 1) "y" else "ies"} from a storage outage collided with existing chats; the outage list was preserved as '$snap'."
                )
            }
        }

        if (toAppend.isNotEmpty()) {
            val mergedArr = JSONArray()
            encList.forEach { mergedArr.put(it) }
            toAppend.forEach { mergedArr.put(it) }
            if (!files.putEncrypted(CHAT_LIST_FILE, mapOf(CHAT_LIST_KEY to mergedArr.toString()))) {
                return Outcome.DEFERRED
            }
        }

        // Verify: the encrypted list must now contain every id it held
        // before PLUS every appended id; skipped entries must be covered by
        // the snapshot marker.
        val after = files.readEncrypted(CHAT_LIST_FILE) ?: return Outcome.DEFERRED
        val afterList = parseChatList((after[CHAT_LIST_KEY] as? String) ?: "[]") ?: return Outcome.DEFERRED
        val afterIds = afterList.mapNotNull { it.optString("id").ifBlank { null } }.toSet()
        val appendOk = toAppend.all { it.optString("id") in afterIds }
        val keepOk = encIds.all { it in afterIds }
        val skipOk = skipped.isEmpty() || files.snapshotMarker(CHAT_LIST_FILE) != null
        if (!appendOk || !keepOk || !skipOk) return Outcome.DEFERRED

        files.setSnapshotMarker(CHAT_LIST_FILE, null)
        if (!files.deleteOutage(CHAT_LIST_FILE)) return Outcome.DEFERRED
        if (toAppend.isNotEmpty()) {
            files.log("${toAppend.size} chat${if (toAppend.size == 1) "" else "s"} created during a storage outage ${if (toAppend.size == 1) "was" else "were"} restored into the chat list.")
        }
        return if (hadConflict) Outcome.MERGED_WITH_CONFLICT else Outcome.MERGED
    }

    private fun parseChatList(json: String): List<JSONObject>? = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getJSONObject(it) }
    } catch (_: Exception) {
        null
    }

    // ----- Rename-journal union merge --------------------------------------

    private fun mergeRenameJournal(
        files: Files,
        enc: Map<String, Any?>,
        out: Map<String, Any?>
    ): Outcome {
        val outEntries = parseJournal(out[RENAME_JOURNAL_KEY] as? String)
        if (outEntries.isEmpty()) {
            return if (files.deleteOutage(RENAME_JOURNAL_FILE)) Outcome.MERGED else Outcome.DEFERRED
        }
        val encEntries = parseJournal(enc[RENAME_JOURNAL_KEY] as? String)

        // Union: journal entries are independent pending facts, and
        // RenameJournal.reconcile safely discards any that turn out stale
        // (it re-derives authority from the live chat list). Losing one
        // strands transcripts; duplicating one is impossible (keyed pairs).
        val union = LinkedHashMap<String, JSONObject>()
        for (e in encEntries + outEntries) {
            union["${e.optString("old")}→${e.optString("new")}"] = e
        }
        if (union.size > encEntries.size) {
            val arr = JSONArray()
            union.values.forEach { arr.put(it) }
            if (!files.putEncrypted(RENAME_JOURNAL_FILE, mapOf(RENAME_JOURNAL_KEY to arr.toString()))) {
                return Outcome.DEFERRED
            }
        }

        val after = parseJournal(
            (files.readEncrypted(RENAME_JOURNAL_FILE) ?: return Outcome.DEFERRED)[RENAME_JOURNAL_KEY] as? String
        )
        val afterKeys = after.map { "${it.optString("old")}→${it.optString("new")}" }.toSet()
        if (!union.keys.all { it in afterKeys }) return Outcome.DEFERRED

        return if (files.deleteOutage(RENAME_JOURNAL_FILE)) Outcome.MERGED else Outcome.DEFERRED
    }

    private fun parseJournal(json: String?): List<JSONObject> = try {
        if (json.isNullOrBlank()) emptyList() else {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .filter { it.optString("old").isNotBlank() && it.optString("new").isNotBlank() }
        }
    } catch (_: Exception) {
        emptyList()
    }
}
