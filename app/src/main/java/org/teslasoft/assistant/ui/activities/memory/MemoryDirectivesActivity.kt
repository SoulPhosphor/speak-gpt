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
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.DirectiveRecord
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.ui.adapters.memory.MemoryRow
import org.teslasoft.assistant.ui.fragments.dialogs.EditDirectiveDialogFragment

/**
 * Phase 5 memory-manager admin editor for `directives` (the enforcer's
 * companion hard limits / standing instructions, priority-ranked). Operating
 * defaults with `origin != "user"` get a "system" badge but stay editable and
 * deletable here. Search filters in memory on text/rationale; add/edit go
 * through [EditDirectiveDialogFragment]; delete gets a Material confirm.
 * Every store read/write runs off the main thread via the base class's
 * `runOffThread`/`reload` — failures degrade to a toast, never a crash.
 */
class MemoryDirectivesActivity : MemoryScreenActivity() {

    override fun screenTitle(): String = getString(R.string.mem_admin_title_directives)
    override fun showSearch(): Boolean = true
    override fun addButtonText(): String = getString(R.string.mem_admin_btn_new_directive)

    /* ------------------------------ data ------------------------------ */

    override fun loadRows(query: String): List<MemoryRow> {
        if (!MemoryStore.isProvisioned(this)) return emptyList()
        val store = MemoryStore.getInstance(this)
        val all = store.getDirectives()
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) all else all.filter {
            it.text.lowercase().contains(q) || (it.rationale ?: "").lowercase().contains(q)
        }
        return filtered.map { rowFor(it) }
    }

    private fun rowFor(d: DirectiveRecord): MemoryRow {
        val title = if (d.text.length > 80) d.text.take(80) + "…" else d.text
        val subtitle = if (d.rationale.isNullOrBlank()) {
            getString(R.string.mem_admin_directive_subtitle_priority, d.priority)
        } else {
            getString(R.string.mem_admin_directive_subtitle_with_rationale, d.priority, d.rationale)
        }
        val badge = if (d.origin != "user") getString(R.string.mem_admin_badge_system) else null
        return MemoryRow(id = d.directiveId, title = title, subtitle = subtitle, badge = badge, hasAction = true)
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
            val d = store.getDirective(row.id) ?: return@runOffThread
            runOnUiThread { showRowMenu(anchor, d) }
        }
    }

    private fun showRowMenu(anchor: View, d: DirectiveRecord) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, getString(R.string.action_edit))
        menu.menu.add(0, 2, 0, getString(R.string.action_delete))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openEditor(d.directiveId)
                2 -> confirmDelete(d)
            }
            true
        }
        menu.show()
    }

    /* ------------------------------ editor ------------------------------ */

    private fun openEditor(directiveId: String?) {
        runOffThread {
            val store = MemoryStore.getInstance(this)
            val existing = directiveId?.let { store.getDirective(it) }
            runOnUiThread { showEditor(existing) }
        }
    }

    private fun showEditor(existing: DirectiveRecord?) {
        val dialog = EditDirectiveDialogFragment.newInstance(
            directiveId = existing?.directiveId ?: "",
            text = existing?.text ?: "",
            rationale = existing?.rationale ?: "",
            priority = existing?.priority ?: 3
        )
        dialog.setListener(editorListener)
        dialog.show(supportFragmentManager, "EditDirectiveDialogFragment")
    }

    private val editorListener = object : EditDirectiveDialogFragment.Listener {
        override fun onSave(directiveId: String, text: String, rationale: String, priority: Int) {
            runOffThread {
                val store = MemoryStore.getInstance(this@MemoryDirectivesActivity)
                // Preserve applies_to / origin the screen doesn't edit, so
                // editing a default directive can't wipe its scope or relabel it.
                val prior = directiveId.takeIf { it.isNotEmpty() }?.let { store.getDirective(it) }
                val record = DirectiveRecord(
                    directiveId = directiveId.ifEmpty { MemoryStore.newId("dir-") },
                    text = text,
                    rationale = rationale.ifBlank { null },
                    appliesToJson = prior?.appliesToJson ?: "[]",
                    priority = priority,
                    origin = prior?.origin ?: "user"
                )
                store.upsertDirective(record)
                runOnUiThread {
                    Toast.makeText(this@MemoryDirectivesActivity, R.string.mem_admin_saved, Toast.LENGTH_SHORT).show()
                    reload()
                }
            }
        }

        override fun onError(message: String) {
            Toast.makeText(this@MemoryDirectivesActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    /* ------------------------------ delete ------------------------------ */

    private fun confirmDelete(d: DirectiveRecord) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_admin_delete_confirm_title)
            .setMessage(getString(R.string.mem_admin_delete_confirm_msg, d.text))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                runOffThread {
                    MemoryStore.getInstance(this).deleteDirective(d.directiveId)
                    runOnUiThread {
                        Toast.makeText(this, R.string.mem_admin_deleted, Toast.LENGTH_SHORT).show()
                        reload()
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }
}
