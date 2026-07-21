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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.UserPersonaRecord
import org.teslasoft.assistant.ui.adapters.memory.MemoryRow
import org.teslasoft.assistant.ui.adapters.memory.ProfileImageRowAdapter

/**
 * "My Personas" (Phase 5, app_adaptation_notes.md §Tab structure): ALL
 * presentation variants of the primary user ("casual me", "ball gown me")
 * live here, in one place — same person, same memories, only the appearance
 * text handed to the model differs. Separate from Roleplay Characters, which
 * are user-played fictional characters, not the user themself.
 */
class MemoryUserPersonasActivity : MemoryScreenActivity() {

    companion object {
        /** Launched from Quick Settings to CHOOSE the chat's persona: a
         *  whole-row tap selects and returns; the chevron edits. Absent/false
         *  (from Characters) = managing, where a whole-row tap edits. */
        const val EXTRA_PICK_MODE = "pickMode"

        /** RESULT_OK in pick mode carries the chosen persona id here. */
        const val EXTRA_SELECTED_PERSONA_ID = "selectedPersonaId"
    }

    private val pickMode: Boolean by lazy { intent.getBooleanExtra(EXTRA_PICK_MODE, false) }

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
            it.name.lowercase().contains(q) ||
                it.presentation.lowercase().contains(q) ||
                (it.shortDescription?.lowercase()?.contains(q) == true)
        }

        return filtered.map { rowFor(it) }
    }

    // Only the row's look changed (owner ruling, July 21 2026): the shared
    // picture row style - Image + Title + Subtitle, the subtitle being the
    // persona's own short description (blank -> no subtitle line), the leading
    // picture resolved by ProfileImageRowAdapter from imageRef. The list order
    // and everything else are unchanged from before.
    private fun rowFor(p: UserPersonaRecord): MemoryRow = MemoryRow(
        id = p.personaId,
        title = p.name,
        subtitle = p.shortDescription?.trim()?.ifEmpty { null },
        hasAction = true,
        imageRef = p.imageRef
    )

    override fun buildListAdapter(rows: List<MemoryRow>): android.widget.ListAdapter {
        val adapter = ProfileImageRowAdapter(
            rows, this, R.layout.view_user_persona_row,
            chevronOpensMenu = false, pickMode = pickMode
        )
        adapter.setOnRowListener(this)
        return adapter
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

    // Whole-row tap. Managing (from Characters): open the editor - everything,
    // including delete, is done there; there is no popup menu. Pick mode (from
    // Quick Settings): select this persona for the chat and return.
    override fun onClick(row: MemoryRow) {
        if (pickMode) selectAndFinish(row.id) else openEditor(row.id)
    }

    // Pick mode only (chevron tap): edit this persona without selecting it yet.
    override fun onEditClick(row: MemoryRow) {
        openEditor(row.id)
    }

    /** Returns the chosen persona to Quick Settings (pick mode). */
    private fun selectAndFinish(personaId: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SELECTED_PERSONA_ID, personaId))
        finish()
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
                val shortDescription = data.getStringExtra(EditUserPersonaActivity.EXTRA_RESULT_SHORT_DESCRIPTION) ?: ""
                val imageRef = data.getStringExtra(EditUserPersonaActivity.EXTRA_RESULT_IMAGE_REF) ?: ""
                runOffThread {
                    val store = MemoryStore.getInstance(this)
                    val prior = if (personaId.isNotEmpty()) store.getUserPersona(personaId) else null
                    val record = UserPersonaRecord(
                        personaId = prior?.personaId ?: MemoryStore.newId("up-"),
                        name = name,
                        presentation = presentation,
                        status = prior?.status ?: "active",
                        createdAt = prior?.createdAt ?: MemoryStore.nowIso(),
                        // The editor is the source of truth for both - it was
                        // handed the prior values and returns them unchanged
                        // unless the user edited them (blank = intentionally none).
                        imageRef = imageRef.ifEmpty { null },
                        shortDescription = shortDescription.ifEmpty { null }
                    )
                    store.upsertUserPersona(record)
                    // Pick mode: editing then saving from Quick Settings also
                    // selects the persona and returns (same as the Companion
                    // edit-then-Save path). Managing: just refresh the list.
                    runOnUiThread {
                        if (pickMode) selectAndFinish(record.personaId) else reload()
                    }
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
            editUserPersonaLauncher.launch(EditUserPersonaActivity.createIntent(this, "", "", "", "", ""))
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
                        existing?.presentation ?: "",
                        existing?.shortDescription ?: "",
                        existing?.imageRef ?: ""
                    )
                )
            }
        }
    }

}
