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

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.ModelRuleTagRecord
import org.teslasoft.assistant.ui.adapters.memory.MemoryRow

/**
 * The model-rule tag index (§11 Revision 5): every tag in the model-rule tag
 * pool, with how many rules carry it. Tap a tag to see those rules (the
 * "tap a tag → everything" cross view); the row menu renames or deletes a tag.
 * Tags are created inline in the rule editor, so there is no add button here —
 * this screen is for organizing (rename) and cleanup (delete). Deleting a tag
 * only scrubs its links; the rules themselves are untouched.
 */
class ModelRuleTagsActivity : MemoryScreenActivity() {

    override fun screenTitle(): String = getString(R.string.model_rule_tags_title)
    override fun showSearch(): Boolean = true

    override fun loadRows(query: String): List<MemoryRow> {
        if (!MemoryStore.isProvisioned(this)) return emptyList()
        val store = MemoryStore.getInstance(this)
        val q = query.trim().lowercase()
        return store.getModelRuleTags()
            .filter { q.isEmpty() || it.name.lowercase().contains(q) }
            .map { tag ->
                val count = store.getModelRulesForTag(tag.tagId).size
                MemoryRow(
                    id = tag.tagId,
                    title = tag.name,
                    subtitle = resources.getQuantityString(R.plurals.model_rule_tag_rule_count, count, count),
                    hasAction = true
                )
            }
    }

    override fun onClick(row: MemoryRow) {
        startActivity(
            Intent(this, ModelRuleTagViewActivity::class.java)
                .putExtra("chatId", chatId)
                .putExtra("tagId", row.id)
                .putExtra("tagName", row.title)
        )
    }

    override fun onAction(row: MemoryRow, anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, getString(R.string.model_rule_tag_rename))
        menu.menu.add(0, 2, 0, getString(R.string.action_delete))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showRenameDialog(row.id, row.title)
                2 -> confirmDelete(row)
            }
            true
        }
        menu.show()
    }

    private fun showRenameDialog(tagId: String, currentName: String) {
        val field = EditText(this).apply {
            setText(currentName)
            setSelection(text.length)
            setSingleLine(true)
            gravity = Gravity.START
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(field)
        }
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.model_rule_tag_rename)
            .setView(container)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val name = field.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                runOffThread {
                    val store = MemoryStore.getInstance(this)
                    val existing = store.getModelRuleTags().firstOrNull { it.tagId == tagId }
                    if (existing != null) {
                        store.upsertModelRuleTag(existing.copy(name = name))
                    } else {
                        store.upsertModelRuleTag(
                            ModelRuleTagRecord(tagId = tagId, name = name, createdAt = MemoryStore.nowIso())
                        )
                    }
                    runOnUiThread { reload() }
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun confirmDelete(row: MemoryRow) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.model_rule_tag_delete_confirm_title)
            .setMessage(getString(R.string.model_rule_tag_delete_confirm_msg, row.title))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                runOffThread {
                    MemoryStore.getInstance(this).deleteModelRuleTag(row.id)
                    runOnUiThread {
                        Toast.makeText(this, R.string.memory_deleted, Toast.LENGTH_SHORT).show()
                        reload()
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }
}
