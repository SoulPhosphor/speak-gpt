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
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * The durable OWNERSHIP INDEX of automatic backup files (owner directive,
 * July 22 2026). An entry is written ONLY for a file the automatic runner
 * itself created in the user's configured folder — never adopted from a
 * directory listing, never inferred from a filename (filenames are not
 * identity and never authorize anything; standing owner ruling in
 * RecoveryFileNaming).
 *
 * This index is the future retention system's sole deletion authority:
 * a file may one day be pruned only when it is recorded here, verified,
 * AND still sits in the configured automatic destination.
 * ⚠️ DELETION IS NOT IMPLEMENTED — [retentionCandidates] only IDENTIFIES
 * which owned, verified files fall outside the keep window; nothing in the
 * codebase acts on that list (owner safety fence: actual pruning stays
 * disabled until the owner reviews it). After an uninstall or data clear
 * this index is gone, so older files become unmanaged — they are never
 * heuristically re-adopted or deleted.
 *
 * Storage: one JSON array in the raw `storage_health` prefs file (the same
 * outside-the-protected-databases home as RecoveryBackupState — a locked
 * database must never lock the backup bookkeeping). Contents are metadata
 * only: the document URI/identifier, filename, SHA-256, size, creation
 * time, verification state. Never chat content, prompts, or keys — and the
 * URI is for the future pruning logic, never for display (the UI shows
 * friendly labels only).
 *
 * The RECORD list is capped at [MAX_ENTRIES]: when full, the oldest RECORDS
 * are dropped. Dropping a record only makes its file unmanaged — the safe
 * direction (an unmanaged file can never be deleted) — and the cap keeps a
 * plaintext prefs value from growing without bound over years.
 */
object AutomaticBackupIndex {

    const val MAX_ENTRIES = 200

    private const val FILE = "storage_health"
    private const val KEY_INDEX = "backup.auto.index"

    data class Entry(
        /** The document URI string of the created file (access identity for
         *  the future pruning logic; NEVER shown to the user). */
        val uri: String,
        /** The tree URI of the configured automatic folder AT CREATION TIME —
         *  retention only ever considers entries whose folder matches the
         *  currently configured one. */
        val treeUri: String,
        /** The display filename the file was created with. */
        val fileName: String,
        /** Hex SHA-256 of the verified package contents. */
        val sha256: String,
        val createdAtMillis: Long,
        val sizeBytes: Long,
        /** True only when the destination copy was reopened and hash-verified.
         *  Unverified entries exist only to track a discard that could not be
         *  completed; they never count toward retention. */
        val verified: Boolean
    )

    // ---- pure codec + rules (unit-tested) -----------------------------------

    fun toJson(entries: List<Entry>): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("uri", e.uri)
                    .put("tree_uri", e.treeUri)
                    .put("name", e.fileName)
                    .put("sha256", e.sha256)
                    .put("created_at", e.createdAtMillis)
                    .put("size", e.sizeBytes)
                    .put("verified", e.verified)
            )
        }
        return arr.toString()
    }

    /** A missing/corrupt index reads as EMPTY — the safe direction: with no
     *  records, nothing is ever eligible for future pruning. */
    fun fromJson(json: String?): List<Entry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Entry(
                        uri = o.getString("uri"),
                        treeUri = o.optString("tree_uri", ""),
                        fileName = o.getString("name"),
                        sha256 = o.getString("sha256"),
                        createdAtMillis = o.getLong("created_at"),
                        sizeBytes = o.optLong("size", 0L),
                        verified = o.optBoolean("verified", false)
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Append keeping at most [maxEntries] NEWEST records (records, not
     *  files — see the class contract). */
    fun appendCapped(entries: List<Entry>, new: Entry, maxEntries: Int = MAX_ENTRIES): List<Entry> {
        val combined = entries + new
        return if (combined.size <= maxEntries) combined
        else combined.sortedBy { it.createdAtMillis }.takeLast(maxEntries)
    }

    /**
     * Which owned files WOULD be pruned under keep-[keep] retention:
     * verified entries in the CURRENTLY configured folder only, oldest
     * first, beyond the newest [keep]. Manual backups, readable chat
     * exports and anything else the runner did not create are structurally
     * absent (they are never indexed). IDENTIFICATION ONLY — no caller may
     * delete based on this until the owner enables pruning.
     */
    fun retentionCandidates(
        entries: List<Entry>,
        currentTreeUri: String,
        keep: Int = BackupRotationPlanner.KEEP
    ): List<Entry> {
        require(keep >= 1) { "keep must be >= 1" }
        val eligible = entries
            .filter { it.verified && it.treeUri == currentTreeUri }
            .sortedBy { it.createdAtMillis }
        if (eligible.size <= keep) return emptyList()
        return eligible.dropLast(keep)
    }

    // ---- Android-backed persistence ------------------------------------------

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): List<Entry> =
        try { fromJson(prefs(context).getString(KEY_INDEX, null)) } catch (_: Exception) { emptyList() }

    /** Record one file the runner just created. Synchronous commit — the
     *  ownership record must be durable before the run reports success. */
    fun record(context: Context, entry: Entry) {
        try {
            val updated = appendCapped(load(context), entry)
            prefs(context).edit(commit = true) { putString(KEY_INDEX, toJson(updated)) }
        } catch (_: Exception) { /* best-effort; an unrecorded file is merely unmanaged */ }
    }
}
