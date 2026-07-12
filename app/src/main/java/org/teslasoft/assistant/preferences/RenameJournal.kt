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
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.memory.MemoryStore

/**
 * A tiny durable journal that bridges the gap between the two stores a chat
 * rename touches. The SharedPreferences rename (history + settings + the
 * chat-list pointer) and the memory database re-point (`MemoryStore.repointChat`,
 * which moves the chat's transcripts, cooldowns and turn-counter) are SEPARATE
 * stores and cannot share one atomic transaction. A process death or a
 * SQLCipher failure between "prefs pointer flipped" and "memory rows moved"
 * would otherwise leave the pre-rename transcripts stranded under the old id,
 * where the Archivist — which filters against the live chat list — can no
 * longer see them.
 *
 * The journal makes that window recoverable without pretending the two stores
 * are atomic:
 *
 *  - `editChat` writes a pending entry (old id → new id) BEFORE it touches any
 *    prefs, so a death anywhere after the pointer flip still has a record to
 *    recover from. The entry is committed synchronously to an encrypted prefs
 *    file — deliberately OUTSIDE the memory database, so it is writable even
 *    when the database is the thing failing.
 *  - The prefs pointer flip is the authoritative moment: once the chat list
 *    names the new id, the rename "happened". So recovery does not trust the
 *    journal blindly — it consults the live chat list ([planReconcile]) to
 *    decide whether the rename actually became authoritative:
 *      • new id live, old id gone  → the rename completed; finish the memory
 *        re-point (idempotent) and drop the entry.
 *      • old id still live         → the rename never took (it failed or died
 *        before the pointer flip); the transcripts correctly belong to the
 *        still-live old chat, so drop the entry WITHOUT re-pointing.
 *      • neither live              → the chat was deleted after the rename; the
 *        re-point is moot, drop the entry.
 *      • both live                 → the old id was reused by a new chat; a
 *        re-point would STEAL that chat's rows, so never re-point — drop it.
 *  - `repointChat` is one atomic DB transaction and is idempotent
 *    (`WHERE chat_id = old`), so re-running recovery any number of times cannot
 *    duplicate or destroy transcript data.
 *
 * Startup calls [reconcile] off the main thread. Any future orphaned-row
 * pruning MUST call [hasPending] first and refuse to prune while an entry is
 * outstanding — a pending entry means transcripts under an old id are not
 * abandoned, they are waiting to be re-pointed.
 */
object RenameJournal {

    private const val FILE = "rename_journal"
    private const val KEY = "pending"

    data class Pending(val old: String, val new: String)

    sealed class Action {
        /** The rename became authoritative; move the memory rows old → new. */
        data class Repoint(val old: String, val new: String) : Action()
        /** The re-point is unsafe or moot; forget the entry without moving rows. */
        data class Discard(val old: String, val new: String) : Action()
    }

    /**
     * Pure recovery decision — no I/O, unit-tested at every boundary. Given the
     * pending entries, the set of currently-live chat ids and whether the memory
     * store exists, decide what to do with each entry. A re-point is chosen ONLY
     * when the new id is live, the old id is not, and the store exists; every
     * other shape is a discard (see the class doc for why each is unsafe or moot).
     */
    fun planReconcile(pending: List<Pending>, liveIds: Set<String>, provisioned: Boolean): List<Action> =
        pending.map { e ->
            val newLive = e.new in liveIds
            val oldLive = e.old in liveIds
            if (newLive && !oldLive && provisioned) Action.Repoint(e.old, e.new)
            else Action.Discard(e.old, e.new)
        }

    @Synchronized
    fun record(context: Context, oldId: String, newId: String) {
        if (oldId.isBlank() || newId.isBlank() || oldId == newId) return
        val list = read(context).toMutableList()
        if (list.none { it.old == oldId && it.new == newId }) list.add(Pending(oldId, newId))
        write(context, list)
    }

    @Synchronized
    fun clear(context: Context, oldId: String, newId: String) {
        val list = read(context).toMutableList()
        if (list.removeAll { it.old == oldId && it.new == newId }) write(context, list)
    }

    fun pending(context: Context): List<Pending> = read(context)

    fun hasPending(context: Context): Boolean = read(context).isNotEmpty()

    /**
     * Pure gate for authority decisions (Round 4 correction): the plan may
     * only run against an AUTHORITATIVE chat-list view. A LOCKED, CORRUPT or
     * otherwise masked list looks empty, and planReconcile would read
     * "neither id live" as "chat deleted" and discard entries that are
     * actually still valid — a silent, wrong, permanent decision. Deferring
     * costs nothing: entries keep retrying every start until the list is
     * readable, and repointChat stays idempotent.
     */
    fun canDecideAuthority(state: ChatStorageHealth.ReadState): Boolean =
        ChatStorageHealth.isAuthoritative(state)

    /**
     * Finish (or discard) every pending rename. Runs the memory re-point for
     * entries whose rename became authoritative, then drops them; drops the
     * rest. A re-point that throws (database still failing) leaves its entry in
     * place for the next start — never dropped on failure, so transcripts are
     * never abandoned. MUST be called off the main thread (SQLCipher).
     */
    @Synchronized
    fun reconcile(context: Context) {
        val entries = read(context)
        if (entries.isEmpty()) return

        val listResult = try {
            ChatPreferences.getChatPreferences().getChatListResult(context)
        } catch (_: Exception) {
            return // can't establish authority without the chat list; retry next start
        }
        if (!canDecideAuthority(listResult.state)) {
            Logger.log(
                context, "crash", "RenameJournal", "warning",
                "Rename recovery deferred: the chat list is currently unavailable (${listResult.state}), so no rename can be judged completed or abandoned. Entries are kept for the next start."
            )
            return
        }
        val liveIds = listResult.chats.mapNotNull { it["id"] }.toSet()
        val provisioned = try {
            MemoryStore.isProvisioned(context)
        } catch (_: Exception) {
            false
        }

        for (action in planReconcile(entries, liveIds, provisioned)) {
            when (action) {
                is Action.Repoint -> {
                    try {
                        MemoryStore.getInstance(context).repointChat(action.old, action.new)
                        clear(context, action.old, action.new)
                        Logger.log(
                            context, "crash", "RenameJournal", "info",
                            "Recovered an interrupted chat rename: the chat's memory records were re-pointed to its new name."
                        )
                    } catch (e: Exception) {
                        // Keep the entry — the next start retries. Ungated,
                        // persistent (Error Log), because this is recovery
                        // information, not optional diagnostics.
                        Logger.log(
                            context, "crash", "RenameJournal", "error",
                            "Could not finish moving a renamed chat's memory records (${e.message}); it stays queued and will retry next start. Past messages remain preserved under the old name meanwhile."
                        )
                    }
                }
                is Action.Discard -> clear(context, action.old, action.new)
            }
        }
    }

    private fun read(context: Context): List<Pending> = try {
        val json = SecurePrefs.get(context, FILE).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val old = o.optString("old", "")
            val new = o.optString("new", "")
            if (old.isNotBlank() && new.isNotBlank()) Pending(old, new) else null
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun write(context: Context, list: List<Pending>) {
        val arr = JSONArray()
        for (p in list) arr.put(JSONObject().put("old", p.old).put("new", p.new))
        SecurePrefs.get(context, FILE).edit(commit = true) { putString(KEY, arr.toString()) }
    }
}
