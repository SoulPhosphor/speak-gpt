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
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.UserPersonaRecord
import org.teslasoft.assistant.ui.adapters.memory.MemoryRow

/**
 * "My Personas" (Phase 5, app_adaptation_notes.md §Tab structure): ALL
 * presentation variants of the primary user ("casual me", "ball gown me")
 * live here, in one place — same person, same memories, only the appearance
 * text handed to the model differs. Separate from Roleplay Characters, which
 * are user-played fictional characters, not the user themself.
 */
class MemoryUserPersonasActivity : MemoryScreenActivity() {

    override fun contentLayoutRes(): Int = R.layout.activity_memory_list_simple
    override fun screenTitle(): String = getString(R.string.mem_pers_title_user_personas)
    override fun showSearch(): Boolean = true
    override fun addButtonText(): String = getString(R.string.mem_pers_fab_add_persona)

    /* ------------------------------ data ------------------------------ */

    override fun loadRows(query: String): List<MemoryRow> {
        if (!MemoryStore.isProvisioned(this)) return emptyList()
        val store = MemoryStore.getInstance(this)
        val records = store.getAllUserPersonas()

        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) records else records.filter {
            it.name.lowercase().contains(q) || it.presentation.lowercase().contains(q)
        }

        return filtered.map { rowFor(it) }
    }

    private fun rowFor(p: UserPersonaRecord): MemoryRow {
        val badge = if (p.status == "archived") getString(R.string.memory_badge_archived) else null
        return MemoryRow(
            id = p.personaId,
            title = p.name,
            subtitle = p.presentation.trim(),
            badge = badge,
            hasAction = true
        )
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
            val p = store.getUserPersona(row.id) ?: return@runOffThread
            runOnUiThread { showRowMenu(anchor, p) }
        }
    }

    private fun showRowMenu(anchor: View, p: UserPersonaRecord) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, getString(R.string.action_edit))
        if (p.status == "active") {
            menu.menu.add(0, 2, 0, getString(R.string.action_archive))
        } else {
            menu.menu.add(0, 3, 0, getString(R.string.action_restore))
        }
        menu.menu.add(0, 4, 0, getString(R.string.action_delete))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openEditor(p.personaId)
                2 -> setStatus(p.personaId, "archived")
                3 -> setStatus(p.personaId, "active")
                4 -> confirmDelete(p)
            }
            true
        }
        menu.show()
    }

    /* ------------------------------ editor ------------------------------ */

    // Applies the full-screen editor's result the same way EditPersonaActivity's
    // caller (PersonasListActivity) does: the editor owns no persistence, this
    // activity does the store work and reloads the list. No save/delete toast -
    // matches the Companion editor, which has none either (the refreshed list
    // row is the confirmation).
    private val editUserPersonaLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        when (data.getStringExtra(EditUserPersonaActivity.EXTRA_RESULT_ACTION)) {
            EditUserPersonaActivity.ACTION_SAVE -> {
                val personaId = data.getStringExtra(EditUserPersonaActivity.EXTRA_PERSONA_ID) ?: ""
                val name = data.getStringExtra(EditUserPersonaActivity.EXTRA_RESULT_NAME) ?: ""
                val presentation = data.getStringExtra(EditUserPersonaActivity.EXTRA_RESULT_PRESENTATION) ?: ""
                runOffThread {
                    val store = MemoryStore.getInstance(this)
                    val prior = if (personaId.isNotEmpty()) store.getUserPersona(personaId) else null
                    val record = UserPersonaRecord(
                        personaId = prior?.personaId ?: MemoryStore.newId("up-"),
                        name = name,
                        presentation = presentation,
                        status = prior?.status ?: "active",
                        createdAt = prior?.createdAt ?: MemoryStore.nowIso(),
                        imageRef = prior?.imageRef
                    )
                    store.upsertUserPersona(record)
                    runOnUiThread { reload() }
                }
            }
            EditUserPersonaActivity.ACTION_DELETE -> {
                val personaId = data.getStringExtra(EditUserPersonaActivity.EXTRA_PERSONA_ID) ?: ""
                if (personaId.isNotEmpty()) {
                    runOffThread {
                        MemoryStore.getInstance(this).deleteUserPersona(personaId)
                        runOnUiThread { reload() }
                    }
                }
            }
        }
    }

    private fun openEditor(personaId: String?) {
        if (personaId == null) {
            editUserPersonaLauncher.launch(EditUserPersonaActivity.createIntent(this, "", "", ""))
            return
        }
        runOffThread {
            val existing = MemoryStore.getInstance(this).getUserPersona(personaId)
            runOnUiThread {
                editUserPersonaLauncher.launch(
                    EditUserPersonaActivity.createIntent(
                        this,
                        existing?.personaId ?: "",
                        existing?.name ?: "",
                        existing?.presentation ?: ""
                    )
                )
            }
        }
    }

    /* ------------------------------ actions ------------------------------ */

    private fun setStatus(personaId: String, status: String) {
        runOffThread {
            MemoryStore.getInstance(this).setUserPersonaStatus(personaId, status)
            runOnUiThread { reload() }
        }
    }

    private fun confirmDelete(p: UserPersonaRecord) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_pers_delete_persona_title)
            .setMessage(getString(R.string.mem_pers_delete_persona_msg, p.name))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                runOffThread {
                    MemoryStore.getInstance(this).deleteUserPersona(p.personaId)
                    runOnUiThread {
                        Toast.makeText(this, R.string.mem_pers_persona_deleted, Toast.LENGTH_SHORT).show()
                        reload()
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }
}
