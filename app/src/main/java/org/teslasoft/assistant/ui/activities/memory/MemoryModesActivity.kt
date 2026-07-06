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

package org.teslasoft.assistant.ui.activities.memory

import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.ModeRecord
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.ui.adapters.memory.MemoryRow
import org.teslasoft.assistant.ui.fragments.dialogs.EditModeDialogFragment

/**
 * Phase 5 memory-manager admin editor for `modes` (the enforcer's Phase 4
 * conversational modes: signals/respond/avoid). Operating-default modes
 * provisioned with `origin != "user"` are tagged with a "system" badge but
 * remain editable/deletable here — this is a hand-editing surface, not a
 * read-only view. Search filters in memory on name/purpose; add/edit go
 * through [EditModeDialogFragment]; delete gets a Material confirm. Every
 * store read/write runs off the main thread via the base class's
 * `runOffThread`/`reload` — failures degrade to a toast, never a crash.
 */
class MemoryModesActivity : MemoryScreenActivity() {

    override fun screenTitle(): String = getString(R.string.mem_admin_title_modes)
    override fun showSearch(): Boolean = true
    override fun addButtonText(): String = getString(R.string.mem_admin_btn_new_mode)

    /* ------------------------------ data ------------------------------ */

    override fun loadRows(query: String): List<MemoryRow> {
        if (!MemoryStore.isProvisioned(this)) return emptyList()
        val store = MemoryStore.getInstance(this)
        val all = store.getModes()
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) all else all.filter {
            it.name.lowercase().contains(q) || (it.purpose ?: "").lowercase().contains(q)
        }
        return filtered.map { rowFor(it) }
    }

    private fun rowFor(m: ModeRecord): MemoryRow {
        val badge = if (m.origin != "user") getString(R.string.mem_admin_badge_system) else null
        return MemoryRow(id = m.modeId, title = m.name, subtitle = m.purpose ?: "", badge = badge, hasAction = true)
    }

    /* ------------------------------ toolbar ------------------------------ */

    override fun onAddClick() {
        if (!MemoryStore.isProvisioned(this)) {
            Toast.makeText(this, R.string.memory_not_provisioned_toast, Toast.LENGTH_SHORT).show()
            return
        }
        openEditor(null)
    }

    /* ------------------------------ rows ------------------------------ */

    override fun onClick(row: MemoryRow) {
        openEditor(row.id)
    }

    override fun onAction(row: MemoryRow, anchor: View) {
        runOffThread {
            val store = MemoryStore.getInstance(this)
            val m = store.getMode(row.id) ?: return@runOffThread
            runOnUiThread { showRowMenu(anchor, m) }
        }
    }

    private fun showRowMenu(anchor: View, m: ModeRecord) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, getString(R.string.action_edit))
        menu.menu.add(0, 2, 0, getString(R.string.action_delete))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openEditor(m.modeId)
                2 -> confirmDelete(m)
            }
            true
        }
        menu.show()
    }

    /* ------------------------------ editor ------------------------------ */

    private fun openEditor(modeId: String?) {
        runOffThread {
            val store = MemoryStore.getInstance(this)
            val existing = modeId?.let { store.getMode(it) }
            runOnUiThread { showEditor(existing) }
        }
    }

    private fun showEditor(existing: ModeRecord?) {
        val dialog = EditModeDialogFragment.newInstance(
            modeId = existing?.modeId ?: "",
            name = existing?.name ?: "",
            purpose = existing?.purpose ?: "",
            signals = existing?.let { linesToText(it.signalsJson) } ?: "",
            respond = existing?.let { linesToText(it.respondJson) } ?: "",
            avoid = existing?.let { linesToText(it.avoidJson) } ?: ""
        )
        dialog.setListener(editorListener)
        dialog.show(supportFragmentManager, "EditModeDialogFragment")
    }

    private val editorListener = object : EditModeDialogFragment.Listener {
        override fun onSave(
            modeId: String, name: String, purpose: String, signals: String, respond: String, avoid: String
        ) {
            runOffThread {
                val store = MemoryStore.getInstance(this@MemoryModesActivity)
                // Preserve fields this screen doesn't edit — otherwise editing a
                // default (origin='system') mode's name would wipe its
                // overrides/scope and relabel it as user-authored.
                val prior = modeId.takeIf { it.isNotEmpty() }?.let { store.getMode(it) }
                val record = ModeRecord(
                    modeId = modeId.ifEmpty { MemoryStore.newId("mode-") },
                    name = name,
                    purpose = purpose.ifBlank { null },
                    signalsJson = textToJsonArray(signals),
                    respondJson = textToJsonArray(respond),
                    avoidJson = textToJsonArray(avoid),
                    transitionNote = prior?.transitionNote,
                    overridesJson = prior?.overridesJson ?: "[]",
                    scope = prior?.scope ?: "global",
                    companionIdsJson = prior?.companionIdsJson ?: "[]",
                    origin = prior?.origin ?: "user"
                )
                store.upsertMode(record)
                runOnUiThread {
                    Toast.makeText(this@MemoryModesActivity, R.string.mem_admin_saved, Toast.LENGTH_SHORT).show()
                    reload()
                }
            }
        }

        override fun onError(message: String) {
            Toast.makeText(this@MemoryModesActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    /* ------------------------------ delete ------------------------------ */

    private fun confirmDelete(m: ModeRecord) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_admin_delete_confirm_title)
            .setMessage(getString(R.string.mem_admin_delete_confirm_msg, m.name))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                runOffThread {
                    MemoryStore.getInstance(this).deleteMode(m.modeId)
                    runOnUiThread {
                        Toast.makeText(this, R.string.mem_admin_deleted, Toast.LENGTH_SHORT).show()
                        reload()
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    /* ------------------------------ json helpers ------------------------------ */

    /** Newline-separated text <-> JSON array of strings (one entry per line). */
    private fun linesToText(json: String?): String = try {
        if (json.isNullOrBlank()) "" else {
            val arr = JSONArray(json)
            (0 until arr.length()).joinToString("\n") { arr.getString(it) }
        }
    } catch (_: Exception) { "" }

    private fun textToJsonArray(text: String): String {
        val arr = JSONArray()
        text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { arr.put(it) }
        return arr.toString()
    }
}
