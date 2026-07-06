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
import org.teslasoft.assistant.preferences.memory.EntityRecord
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.ui.adapters.memory.MemoryRow
import org.teslasoft.assistant.ui.fragments.dialogs.EditEntityDialogFragment

/**
 * Phase 5 memory-manager admin editor for `entities` (people/places/projects
 * the store tracks). Search filters in memory on name/summary; add/edit go
 * through [EditEntityDialogFragment]; delete gets a Material confirm. Every
 * store read/write runs off the main thread via the base class's
 * `runOffThread`/`reload` — failures degrade to a toast, never a crash.
 */
class MemoryEntitiesActivity : MemoryScreenActivity() {

    override fun screenTitle(): String = getString(R.string.mem_admin_title_entities)
    override fun showSearch(): Boolean = true
    override fun addButtonText(): String = getString(R.string.mem_admin_btn_new_entity)

    /* ------------------------------ data ------------------------------ */

    override fun loadRows(query: String): List<MemoryRow> {
        if (!MemoryStore.isProvisioned(this)) return emptyList()
        val store = MemoryStore.getInstance(this)
        val all = store.getEntities()
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) all else all.filter {
            it.name.lowercase().contains(q) || it.summary.lowercase().contains(q)
        }
        return filtered.map { rowFor(it) }
    }

    private fun rowFor(e: EntityRecord): MemoryRow {
        val subtitle = getString(R.string.mem_admin_entity_subtitle_fmt, e.kind, e.summary)
        return MemoryRow(id = e.entityId, title = e.name, subtitle = subtitle, badge = null, hasAction = true)
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
            val e = store.getEntity(row.id) ?: return@runOffThread
            runOnUiThread { showRowMenu(anchor, e) }
        }
    }

    private fun showRowMenu(anchor: View, e: EntityRecord) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, getString(R.string.action_edit))
        menu.menu.add(0, 2, 0, getString(R.string.action_delete))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openEditor(e.entityId)
                2 -> confirmDelete(e)
            }
            true
        }
        menu.show()
    }

    /* ------------------------------ editor ------------------------------ */

    private fun openEditor(entityId: String?) {
        runOffThread {
            val store = MemoryStore.getInstance(this)
            val existing = entityId?.let { store.getEntity(it) }
            runOnUiThread { showEditor(existing) }
        }
    }

    private fun showEditor(existing: EntityRecord?) {
        val dialog = EditEntityDialogFragment.newInstance(
            entityId = existing?.entityId ?: "",
            name = existing?.name ?: "",
            kind = existing?.kind ?: "",
            summary = existing?.summary ?: "",
            aliases = existing?.let { aliasesToText(it.aliasesJson) } ?: "",
            importance = existing?.importance ?: 3
        )
        dialog.setListener(editorListener)
        dialog.show(supportFragmentManager, "EditEntityDialogFragment")
    }

    private val editorListener = object : EditEntityDialogFragment.Listener {
        override fun onSave(
            entityId: String, name: String, kind: String, summary: String, aliases: String, importance: Int
        ) {
            runOffThread {
                val store = MemoryStore.getInstance(this@MemoryEntitiesActivity)
                val record = EntityRecord(
                    entityId = entityId.ifEmpty { MemoryStore.newId("e-") },
                    kind = kind,
                    name = name,
                    aliasesJson = textToJsonArray(aliases),
                    summary = summary,
                    status = "active",
                    importance = importance,
                    lastTouched = MemoryStore.nowIso(),
                    origin = "user"
                )
                store.upsertEntity(record)
                runOnUiThread {
                    Toast.makeText(this@MemoryEntitiesActivity, R.string.mem_admin_saved, Toast.LENGTH_SHORT).show()
                    reload()
                }
            }
        }

        override fun onError(message: String) {
            Toast.makeText(this@MemoryEntitiesActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    /* ------------------------------ delete ------------------------------ */

    private fun confirmDelete(e: EntityRecord) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_admin_delete_confirm_title)
            .setMessage(getString(R.string.mem_admin_delete_confirm_msg, e.name))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                runOffThread {
                    MemoryStore.getInstance(this).deleteEntity(e.entityId)
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

    private fun aliasesToText(json: String?): String = try {
        if (json.isNullOrBlank()) "" else {
            val arr = JSONArray(json)
            (0 until arr.length()).joinToString(", ") { arr.getString(it) }
        }
    } catch (_: Exception) { "" }

    private fun textToJsonArray(text: String): String {
        val arr = JSONArray()
        text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { arr.put(it) }
        return arr.toString()
    }
}
