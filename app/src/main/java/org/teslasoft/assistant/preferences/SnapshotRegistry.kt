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

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * Durable index of every preserved storage-recovery artifact (Round 4
 * correction): conflict snapshots from the legacy plaintext migration,
 * outage-reconciliation snapshots, corrupt-JSON backups, and the
 * byte-for-byte ciphertext copies under files/storage_recovery/.
 * Preserving a file the app cannot find again is entombment, not recovery —
 * the registry is what a future restore surface will enumerate.
 *
 * Entries live in the plaintext `storage_health` prefs file under
 * `registry.<id>` keys, and are METADATA ONLY: an id, file names, the source
 * logical file, the chat id (a hash already visible in file names on disk),
 * a sanitized reason category, origin, creation time, and recovery status.
 * Never chat text, prompts, keys, or any payload — [entryJson] is the single
 * builder and is unit-tested to emit exactly the allowed keys.
 *
 * Nothing here is ever deleted automatically. Entries whose files predate
 * the registry are discovered at startup and recorded as
 * origin="legacy_unindexed". Duplicate preserved copies (a re-run after an
 * interrupted settlement) each get their own entry and are identifiable by
 * shared sourceName + distinct ids.
 */
object SnapshotRegistry {

    /** Where these artifacts came from. */
    const val ORIGIN_READ_FAILURE = "read_failure"
    const val ORIGIN_LEGACY_CONFLICT = "legacy_conflict"
    const val ORIGIN_OUTAGE_RECONCILIATION = "outage_reconciliation"
    const val ORIGIN_CORRUPT_JSON = "corrupt_json"
    const val ORIGIN_LEGACY_UNINDEXED = "legacy_unindexed"

    const val STATUS_PRESERVED = "preserved"

    private const val FILE = "storage_health" // shared with ChatStorageHealth (metadata-only file)
    private const val KEY_PREFIX = "registry."

    data class Entry(
        val id: String,
        val snapshotName: String,
        val sourceName: String,
        val chatId: String?,
        val reason: String,
        val origin: String,
        val createdAt: Long,
        val status: String
    )

    private val counter = AtomicInteger(0)
    private val random = SecureRandom()

    /**
     * Collision-proof suffix for snapshot names and registry ids: wall time
     * + a process-lifetime counter + random hex. Two snapshots created in
     * the same millisecond — same process or racing processes — cannot
     * collide. Unit-tested for uniqueness in bulk.
     */
    fun uniqueSuffix(): String {
        val rand = ByteArray(4).also { random.nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        return "${System.currentTimeMillis().toString(36)}_${counter.incrementAndGet()}_$rand"
    }

    /**
     * Pure entry serializer — the ONLY writer shape. Emits exactly the
     * allowed metadata keys; reason and origin are clamped to safe tokens
     * so no caller can smuggle payload text into the plaintext journal.
     */
    fun entryJson(
        id: String,
        snapshotName: String,
        sourceName: String,
        chatId: String?,
        reason: String,
        origin: String,
        createdAt: Long,
        status: String = STATUS_PRESERVED
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("snapshot", snapshotName.take(120))
        put("source", sourceName.take(120))
        if (chatId != null) put("chat_id", chatId.take(80))
        put("reason", StorageErrorSanitizer.sanitizeToken(reason))
        put("origin", StorageErrorSanitizer.sanitizeToken(origin))
        put("created", createdAt)
        put("status", StorageErrorSanitizer.sanitizeToken(status))
    }

    /** Record one preserved artifact. Best-effort: a registry failure must
     *  never block the preservation it describes. Returns the entry id. */
    fun record(
        context: Context,
        snapshotName: String,
        sourceName: String,
        origin: String,
        reason: String,
        chatId: String? = chatIdFrom(sourceName)
    ): String {
        val id = uniqueSuffix()
        try {
            prefs(context).edit(commit = true) {
                putString(
                    KEY_PREFIX + id,
                    entryJson(id, snapshotName, sourceName, chatId, reason, origin, System.currentTimeMillis()).toString()
                )
            }
        } catch (_: Exception) { /* preservation itself already happened */ }
        return id
    }

    fun entries(context: Context): List<Entry> = try {
        prefs(context).all.entries
            .filter { it.key.startsWith(KEY_PREFIX) }
            .mapNotNull { (_, v) ->
                try {
                    val o = JSONObject(v.toString())
                    Entry(
                        id = o.getString("id"),
                        snapshotName = o.getString("snapshot"),
                        sourceName = o.getString("source"),
                        chatId = if (o.has("chat_id")) o.getString("chat_id") else null,
                        reason = o.optString("reason"),
                        origin = o.optString("origin"),
                        createdAt = o.optLong("created"),
                        status = o.optString("status", STATUS_PRESERVED)
                    )
                } catch (_: Exception) {
                    null
                }
            }
    } catch (_: Exception) {
        emptyList()
    }

    /** The chat id embedded in a chat-file name, when there is one. The
     *  chat LIST is not a chat — no id is derived from it. */
    fun chatIdFrom(sourceName: String): String? = when {
        sourceName == "chat_list" -> null
        sourceName.startsWith("chat_") -> sourceName.removePrefix("chat_")
        sourceName.startsWith("settings.") -> sourceName.removePrefix("settings.")
        else -> null
    }

    /**
     * One-time-per-artifact discovery of preserved files that predate the
     * registry: `enc.*_recovered_*` / `enc.*_conflict_*` / `enc.*_corrupt_*`
     * prefs files and everything under files/storage_recovery/. Recorded as
     * origin="legacy_unindexed" so they become enumerable; nothing is
     * opened, moved, or deleted. Idempotent — files whose names are already
     * registered are skipped.
     */
    fun discoverLegacy(context: Context) {
        try {
            val known = entries(context).map { it.snapshotName }.toSet()

            val sharedPrefsDir = File(context.dataDir, "shared_prefs")
            (sharedPrefsDir.listFiles() ?: emptyArray())
                .filter { f ->
                    f.name.startsWith("enc.") && f.name.endsWith(".xml") &&
                        (f.name.contains("_recovered_") || f.name.contains("_conflict_") || f.name.contains("_corrupt_"))
                }
                .forEach { f ->
                    // Logical prefs name (strip enc. prefix + .xml suffix).
                    val logical = f.name.removePrefix("enc.").removeSuffix(".xml")
                    if (logical !in known) {
                        val source = logical.substringBefore("_recovered_")
                            .substringBefore("_conflict_").substringBefore("_corrupt_")
                        record(context, logical, source, ORIGIN_LEGACY_UNINDEXED, "discovered")
                    }
                }

            val recoveryDir = File(context.filesDir, "storage_recovery")
            (recoveryDir.listFiles() ?: emptyArray()).forEach { f ->
                if (f.name !in known) {
                    // Names look like enc.chat_<id>.<ts>.xml — recover the
                    // logical source from the middle segment.
                    val source = f.name.removePrefix("enc.").substringBeforeLast(".xml")
                        .substringBeforeLast(".")
                    record(context, f.name, source, ORIGIN_LEGACY_UNINDEXED, "discovered")
                }
            }
        } catch (_: Exception) { /* discovery is best-effort housekeeping */ }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
