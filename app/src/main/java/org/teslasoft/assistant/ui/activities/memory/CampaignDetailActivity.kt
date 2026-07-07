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
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.CampaignRecord
import org.teslasoft.assistant.preferences.memory.CardEntryRecord
import org.teslasoft.assistant.preferences.memory.CardSections
import org.teslasoft.assistant.preferences.memory.CardType
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.PartyMemberRecord
import org.teslasoft.assistant.theme.ThemeManager

/**
 * The two-zone campaign card (roleplay_cards_and_tags_spec §6d). Zone 1 is
 * "the bookmark" — written at session end, read at session start: Name,
 * Quest Anchor (main objective + optional short side-objective lines, one
 * multi-line field), Active Scene, the world / user-character / GM-companion
 * links, and the Active Party Members linked from the roster (join, not
 * ownership — §4). Per the no-mid-conversation-writes law these fields only
 * ever change here, by the user's hand. Zone 2 renders Campaign Cast (world
 * NPC overlays + campaign-native NPCs), Campaign Locations (scene-state
 * overlays), the Plot Ledger, the Reliquary and Notes. The dormant pre-card
 * story_so_far text is preserved untouched and never shown (spec §8a).
 * Teardown gets its §5 link-warning rework in the 3.6f slice.
 */
class CampaignDetailActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId: String = ""

    private var campaignId: String? = null
    private var existing: CampaignRecord? = null

    /** Guards Save until an existing record has loaded. */
    private var ready = false

    // Working scope selections (nullable ids).
    private var status: String = "active"
    private var selWorldId: String? = null
    private var selCharacterId: String? = null
    private var selCompanionId: String? = null

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var titleView: TextView? = null
    private var fieldName: TextInputEditText? = null
    private var fieldQuestAnchor: TextInputEditText? = null
    private var fieldActiveScene: TextInputEditText? = null
    private var rowStatus: LinearLayout? = null
    private var textStatus: TextView? = null
    private var rowWorld: LinearLayout? = null
    private var textWorld: TextView? = null
    private var rowCharacter: LinearLayout? = null
    private var textCharacter: TextView? = null
    private var rowCompanion: LinearLayout? = null
    private var textCompanion: TextView? = null
    private var textPartyEmpty: TextView? = null
    private var partyContainer: LinearLayout? = null
    private var btnPartyAdd: MaterialButton? = null
    private var btnSave: MaterialButton? = null
    private var btnMemories: MaterialButton? = null
    private var btnTeardown: MaterialButton? = null
    private var textSaveFirst: TextView? = null
    private var sectionsContainer: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_campaign_detail)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        campaignId = intent.extras?.getString("campaignId")
        preferences = Preferences.getPreferences(this, chatId)

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        titleView = findViewById(R.id.activity_title)
        fieldName = findViewById(R.id.field_campaign_name)
        fieldQuestAnchor = findViewById(R.id.field_campaign_quest_anchor)
        fieldActiveScene = findViewById(R.id.field_campaign_active_scene)
        rowStatus = findViewById(R.id.row_campaign_status)
        textStatus = findViewById(R.id.text_campaign_status_value)
        rowWorld = findViewById(R.id.row_campaign_world)
        textWorld = findViewById(R.id.text_campaign_world_value)
        rowCharacter = findViewById(R.id.row_campaign_character)
        textCharacter = findViewById(R.id.text_campaign_character_value)
        rowCompanion = findViewById(R.id.row_campaign_companion)
        textCompanion = findViewById(R.id.text_campaign_companion_value)
        textPartyEmpty = findViewById(R.id.text_party_empty)
        partyContainer = findViewById(R.id.party_container)
        btnPartyAdd = findViewById(R.id.btn_party_add)
        btnSave = findViewById(R.id.btn_campaign_save)
        btnMemories = findViewById(R.id.btn_campaign_memories)
        btnTeardown = findViewById(R.id.btn_campaign_teardown)
        textSaveFirst = findViewById(R.id.text_save_first)
        sectionsContainer = findViewById(R.id.sections_container)

        titleView?.setText(
            if (campaignId == null) R.string.mem_world_campaign_detail_new_title
            else R.string.mem_world_campaign_detail_title
        )

        applyTheme()

        btnBack?.setOnClickListener { finish() }
        rowStatus?.setOnClickListener { showStatusPicker() }
        rowWorld?.setOnClickListener { showWorldPicker() }
        rowCharacter?.setOnClickListener { showCharacterPicker() }
        rowCompanion?.setOnClickListener { showCompanionPicker() }
        btnPartyAdd?.setOnClickListener { showPartyPicker() }
        btnSave?.setOnClickListener { save() }
        btnMemories?.setOnClickListener { openMemories() }
        btnTeardown?.setOnClickListener { showTeardownDialog() }

        CardZoneUi.attachWordCount(
            this,
            listOf(fieldName, fieldQuestAnchor, fieldActiveScene),
            findViewById(R.id.text_card_word_count),
            findViewById(R.id.text_card_warning)
        )

        refreshStatusLabel()
        updateExtraButtons()
        loadIfExisting()
    }

    override fun onResume() {
        super.onResume()
        renderParty()
        renderSections()
    }

    /* ------------------------------ load ------------------------------ */

    private fun loadIfExisting() {
        val id = campaignId
        if (id == null) {
            ready = true
            return
        }
        runOffThread {
            if (!MemoryStore.isProvisioned(this)) return@runOffThread
            val store = MemoryStore.getInstance(this)
            val c = store.getCampaign(id)
            // Resolve the display labels for the three scope selections up front
            // so the UI thread just paints strings (no store calls on main).
            // §5 (3.6f): a surviving reference to a gone card shows it
            // explicitly — "(archived card)" / "(deleted card)" — never a
            // silent hole or a fake "None".
            val worldName = c?.worldId?.let { wid ->
                store.getWorld(wid)?.let { w ->
                    if (w.status == "archived") getString(R.string.rp_ref_archived_fmt, w.name) else w.name
                } ?: getString(R.string.rp_ref_deleted)
            }
            val characterName = c?.roleplayCharacterId?.let { rid ->
                store.getRoleplayCharacter(rid)?.let { r ->
                    if (r.status == "archived") getString(R.string.rp_ref_archived_fmt, r.name) else r.name
                } ?: getString(R.string.rp_ref_deleted)
            }
            val companionName = c?.companionId?.let { cid ->
                store.getCompanions().firstOrNull { it.companionId == cid }?.currentName
                    ?: getString(R.string.rp_ref_deleted)
            }
            runOnUiThread {
                existing = c
                if (c != null) {
                    fieldName?.setText(c.name)
                    fieldQuestAnchor?.setText(c.questAnchor ?: "")
                    fieldActiveScene?.setText(c.activeScene ?: "")
                    status = c.status
                    selWorldId = c.worldId
                    selCharacterId = c.roleplayCharacterId
                    selCompanionId = c.companionId
                    refreshStatusLabel()
                    textWorld?.text = worldName ?: getString(R.string.mem_world_campaign_none)
                    textCharacter?.text = characterName ?: getString(R.string.mem_world_campaign_none)
                    textCompanion?.text = companionName ?: getString(R.string.mem_world_campaign_none)
                }
                ready = true
                updateExtraButtons()
            }
        }
    }

    private fun updateExtraButtons() {
        val saved = campaignId != null
        btnMemories?.visibility = if (saved) View.VISIBLE else View.GONE
        btnTeardown?.visibility = if (saved) View.VISIBLE else View.GONE
    }

    /* ------------------------------ party members (spec §4/§6d) ------------------------------ */

    private fun renderParty() {
        val container = partyContainer ?: return
        val id = campaignId
        if (id == null) {
            textPartyEmpty?.visibility = View.VISIBLE
            container.removeAllViews()
            return
        }
        runOffThread {
            val members = MemoryStore.getInstance(this).partyMembersForCampaign(id)
            runOnUiThread {
                container.removeAllViews()
                textPartyEmpty?.visibility = if (members.isEmpty()) View.VISIBLE else View.GONE
                val inflater = LayoutInflater.from(this)
                for (m in members) container.addView(partyRow(inflater, container, m))
            }
        }
    }

    private fun partyRow(inflater: LayoutInflater, parent: LinearLayout, m: PartyMemberRecord): View {
        val row = inflater.inflate(R.layout.view_memory_row, parent, false)
        row.findViewById<TextView>(R.id.row_title).text = m.name

        val subtitleView = row.findViewById<TextView>(R.id.row_subtitle)
        val subtitle = listOfNotNull(m.species, m.charClass)
            .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")
        if (subtitle.isEmpty()) subtitleView.visibility = View.GONE
        else { subtitleView.visibility = View.VISIBLE; subtitleView.text = subtitle }

        // The fiction status decides per-turn injection (§6b gating), so it's
        // always visible here.
        val badge = row.findViewById<TextView>(R.id.row_badge)
        badge.visibility = View.VISIBLE
        badge.setText(CharacterCardActivity.statusLabelRes(m.status))

        row.findViewById<ImageButton>(R.id.btn_row_action).visibility = View.GONE
        val ui = row.findViewById<View>(R.id.ui)
        ui.setOnClickListener {
            startActivity(
                Intent(this, CharacterCardActivity::class.java)
                    .putExtra("chatId", chatId)
                    .putExtra("cardType", CardType.PARTY_MEMBER)
                    .putExtra("cardId", m.partyMemberId)
            )
        }
        ui.setOnLongClickListener {
            val menu = PopupMenu(this, it)
            menu.menu.add(0, 1, 0, getString(R.string.card_campaign_party_unlink))
            menu.setOnMenuItemClickListener { item ->
                if (item.itemId == 1) unlinkPartyMember(m.partyMemberId)
                true
            }
            menu.show()
            true
        }
        return row
    }

    /** Unlinking is NOT deletion (§4 join, not ownership): the roster card
     *  survives untouched, it just leaves this campaign. */
    private fun unlinkPartyMember(partyMemberId: String) {
        val id = campaignId ?: return
        runOffThread {
            MemoryStore.getInstance(this).unlinkPartyMemberFromCampaign(id, partyMemberId)
            runOnUiThread { renderParty() }
        }
    }

    private fun showPartyPicker() {
        val id = campaignId
        if (id == null) {
            Toast.makeText(this, R.string.mem_world_campaign_save_first, Toast.LENGTH_SHORT).show()
            return
        }
        runOffThread {
            val store = MemoryStore.getInstance(this)
            val linked = store.partyMembersForCampaign(id).map { it.partyMemberId }.toSet()
            // Archived cards are hidden from active selection (§5).
            val candidates = store.getPartyMembers(includeArchived = false)
                .filter { it.partyMemberId !in linked }
            runOnUiThread {
                if (candidates.isEmpty()) {
                    Toast.makeText(this, R.string.card_campaign_party_picker_none, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val names = candidates.map { it.name }.toTypedArray()
                MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                    .setTitle(R.string.card_campaign_party_add)
                    .setItems(names) { _, which ->
                        runOffThread {
                            MemoryStore.getInstance(this)
                                .linkPartyMemberToCampaign(id, candidates[which].partyMemberId)
                            runOnUiThread { renderParty() }
                        }
                    }
                    .setNegativeButton(R.string.btn_cancel) { _, _ -> }
                    .show()
            }
        }
    }

    /* ------------------------------ Zone 2 sections (spec §6d) ------------------------------ */

    private fun renderSections() {
        val container = sectionsContainer ?: return
        val id = campaignId
        if (id == null) {
            textSaveFirst?.visibility = View.VISIBLE
            container.removeAllViews()
            return
        }
        textSaveFirst?.visibility = View.GONE

        runOffThread {
            val entries = MemoryStore.getInstance(this).entriesForCard(CardType.CAMPAIGN, id)
            val bySection = entries.groupBy { it.section }
            runOnUiThread {
                container.removeAllViews()
                val inflater = LayoutInflater.from(this)
                for (section in CardSections.CAMPAIGN_SECTIONS) {
                    val block = inflater.inflate(R.layout.view_card_section, container, false)
                    block.findViewById<TextView>(R.id.section_title)
                        .setText(CardEntryEditorActivity.sectionLabelRes(section))

                    val rows = bySection[section].orEmpty()
                    block.findViewById<TextView>(R.id.section_empty).visibility =
                        if (rows.isEmpty()) View.VISIBLE else View.GONE

                    val list = block.findViewById<LinearLayout>(R.id.section_entries)
                    for (entry in rows) list.addView(entryRow(inflater, list, entry))

                    block.findViewById<MaterialButton>(R.id.btn_add_entry).setOnClickListener {
                        openEntryEditor(section, null)
                    }
                    container.addView(block)
                }
            }
        }
    }

    private fun entryRow(inflater: LayoutInflater, parent: LinearLayout, entry: CardEntryRecord): View {
        val row = inflater.inflate(R.layout.view_memory_row, parent, false)
        row.findViewById<TextView>(R.id.row_title).text = entry.name

        // State-shaped sections show their state line; the rest show the
        // first description line.
        val subtitleView = row.findViewById<TextView>(R.id.row_subtitle)
        val subtitle = when (entry.section) {
            CardSections.CAMPAIGN_CAST ->
                listOfNotNull(entry.castDisposition, entry.castStatus)
                    .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null }
            CardSections.CAMPAIGN_LOCATIONS -> entry.locationCondition?.trim()?.takeIf { it.isNotEmpty() }
            CardSections.RELIQUARY -> entry.holder?.trim()?.takeIf { it.isNotEmpty() }
            else -> entry.description?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }
        if (subtitle == null) subtitleView.visibility = View.GONE
        else { subtitleView.visibility = View.VISIBLE; subtitleView.text = subtitle }

        row.findViewById<TextView>(R.id.row_badge).visibility = View.GONE
        row.findViewById<ImageButton>(R.id.btn_row_action).visibility = View.GONE
        row.findViewById<View>(R.id.ui).setOnClickListener {
            openEntryEditor(entry.section, entry.entryId)
        }
        return row
    }

    private fun openEntryEditor(section: String, entryId: String?) {
        val id = campaignId ?: run {
            Toast.makeText(this, R.string.mem_world_campaign_save_first, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, CardEntryEditorActivity::class.java)
                .putExtra("chatId", chatId)
                .putExtra("cardType", CardType.CAMPAIGN)
                .putExtra("cardId", id)
                .putExtra("section", section)
                .putExtra("entryId", entryId ?: "")
        )
    }

    /* ------------------------------ pickers ------------------------------ */

    private fun statusLabel(s: String): String = when (s) {
        "active" -> getString(R.string.mem_world_campaign_status_active)
        "paused" -> getString(R.string.mem_world_campaign_status_paused)
        "ended" -> getString(R.string.mem_world_campaign_status_ended)
        "archived" -> getString(R.string.mem_world_campaign_status_archived)
        else -> s
    }

    private fun refreshStatusLabel() {
        textStatus?.text = statusLabel(status)
    }

    private fun showStatusPicker() {
        val values = arrayOf("active", "paused", "ended", "archived")
        val labels = values.map { statusLabel(it) }.toTypedArray()
        val current = values.indexOf(status).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_world_campaign_status_picker_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                status = values[which]
                refreshStatusLabel()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.mem_world_campaign_cancel) { _, _ -> }
            .show()
    }

    /** Generic nullable single-choice picker: a leading "None" then the ids. */
    private fun showIdPicker(
        titleRes: Int,
        ids: List<String?>,
        labels: List<String>,
        currentId: String?,
        onPick: (String?) -> Unit
    ) {
        val current = ids.indexOf(currentId).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(titleRes)
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                onPick(ids[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.mem_world_campaign_cancel) { _, _ -> }
            .show()
    }

    private fun showWorldPicker() {
        runOffThread {
            if (!MemoryStore.isProvisioned(this)) {
                showEmptyPicker()
                return@runOffThread
            }
            val worlds = MemoryStore.getInstance(this).getAllWorlds()
            val ids = ArrayList<String?>().apply { add(null); addAll(worlds.map { it.worldId }) }
            val labels = ArrayList<String>().apply {
                add(getString(R.string.mem_world_campaign_none)); addAll(worlds.map { it.name })
            }
            runOnUiThread {
                showIdPicker(R.string.mem_world_campaign_world_picker_title, ids, labels, selWorldId) { picked ->
                    selWorldId = picked
                    textWorld?.text = labels[ids.indexOf(picked).coerceAtLeast(0)]
                }
            }
        }
    }

    private fun showCharacterPicker() {
        runOffThread {
            if (!MemoryStore.isProvisioned(this)) {
                showEmptyPicker()
                return@runOffThread
            }
            val chars = MemoryStore.getInstance(this).getActiveRoleplayCharacters()
            val ids = ArrayList<String?>().apply { add(null); addAll(chars.map { it.roleplayCharacterId }) }
            val labels = ArrayList<String>().apply {
                add(getString(R.string.mem_world_campaign_none)); addAll(chars.map { it.name })
            }
            runOnUiThread {
                showIdPicker(R.string.mem_world_campaign_character_picker_title, ids, labels, selCharacterId) { picked ->
                    selCharacterId = picked
                    textCharacter?.text = labels[ids.indexOf(picked).coerceAtLeast(0)]
                }
            }
        }
    }

    private fun showCompanionPicker() {
        runOffThread {
            if (!MemoryStore.isProvisioned(this)) {
                showEmptyPicker()
                return@runOffThread
            }
            // DM companion must be a real, past-draft companion.
            val companions = MemoryStore.getInstance(this).getCompanions().filter { it.status != "draft" }
            val ids = ArrayList<String?>().apply { add(null); addAll(companions.map { it.companionId }) }
            val labels = ArrayList<String>().apply {
                add(getString(R.string.mem_world_campaign_none)); addAll(companions.map { it.currentName })
            }
            runOnUiThread {
                showIdPicker(R.string.mem_world_campaign_companion_picker_title, ids, labels, selCompanionId) { picked ->
                    selCompanionId = picked
                    textCompanion?.text = labels[ids.indexOf(picked).coerceAtLeast(0)]
                }
            }
        }
    }

    private fun showEmptyPicker() {
        runOnUiThread {
            Toast.makeText(this, R.string.mem_world_campaign_none, Toast.LENGTH_SHORT).show()
        }
    }

    /* ------------------------------ save ------------------------------ */

    private fun save() {
        if (!ready) return
        val name = fieldName?.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.mem_world_campaign_required, Toast.LENGTH_SHORT).show()
            return
        }

        val prior = existing
        val id = campaignId ?: MemoryStore.newId("camp-")
        val record = CampaignRecord(
            campaignId = id,
            name = name,
            worldId = selWorldId,
            roleplayCharacterId = selCharacterId,
            companionId = selCompanionId,
            status = status,
            // Dormant pre-card text (spec §8a): preserved untouched, never
            // shown. The Plot Ledger section is its structured replacement.
            storySoFar = prior?.storySoFar,
            createdAt = prior?.createdAt ?: MemoryStore.nowIso(),
            questAnchor = fieldQuestAnchor?.text?.toString()?.trim()?.ifEmpty { null },
            activeScene = fieldActiveScene?.text?.toString()?.trim()?.ifEmpty { null }
        )

        runOffThread {
            MemoryStore.getInstance(this).upsertCampaign(record)
            runOnUiThread {
                campaignId = id
                existing = record
                titleView?.setText(R.string.mem_world_campaign_detail_title)
                updateExtraButtons()
                Toast.makeText(this, R.string.mem_world_campaign_saved, Toast.LENGTH_SHORT).show()
                renderParty()
                renderSections()
            }
        }
    }

    /* ------------------------------ memories ------------------------------ */

    private fun openMemories() {
        val id = campaignId
        if (id == null) {
            Toast.makeText(this, R.string.mem_world_campaign_save_first, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, MemoryBrowserActivity::class.java)
                .putExtra("chatId", chatId)
                .putExtra("campaignId", id)
                .putExtra("screenTitle", fieldName?.text?.toString()?.trim().orEmpty())
        )
    }

    /* ------------------------------ teardown ------------------------------ */

    private fun showTeardownDialog() {
        val id = campaignId ?: return
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_world_campaign_teardown_title)
            .setMessage(R.string.mem_world_campaign_teardown_msg)
            .setPositiveButton(R.string.mem_world_campaign_teardown_delete) { _, _ -> showDeleteDialog(id) }
            .setNeutralButton(R.string.mem_world_campaign_teardown_archive) { _, _ -> archive(id) }
            .setNegativeButton(R.string.mem_world_campaign_cancel) { _, _ -> }
            .show()
    }

    private fun archive(id: String) {
        runOffThread {
            MemoryStore.getInstance(this).archiveCampaign(id)
            runOnUiThread {
                Toast.makeText(this, R.string.mem_world_campaign_archived, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun showDeleteDialog(id: String) {
        val delBox = MaterialCheckBox(this).apply {
            setText(R.string.mem_world_campaign_delete_memories)
            isChecked = false
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(delBox)
        }
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_world_campaign_delete_title)
            .setMessage(R.string.mem_world_campaign_delete_msg)
            .setView(container)
            .setPositiveButton(R.string.mem_world_campaign_delete_confirm) { _, _ ->
                val deleteMemories = delBox.isChecked
                runOffThread {
                    MemoryStore.getInstance(this).deleteCampaign(id, deleteMemories = deleteMemories)
                    runOnUiThread {
                        Toast.makeText(this, R.string.mem_world_campaign_deleted, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
            .setNegativeButton(R.string.mem_world_campaign_cancel) { _, _ -> }
            .show()
    }

    /* ------------------------------ off-thread ------------------------------ */

    private fun runOffThread(work: () -> Unit) {
        Thread {
            try {
                work()
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.mem_world_op_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    /* ------------------------------ theme + insets ------------------------------ */

    @Suppress("DEPRECATION")
    private fun applyTheme() {
        val amoled = isDarkThemeEnabled() && preferences?.getAmoledPitchBlack() == true
        ThemeManager.getThemeManager().applyTheme(this, amoled)

        if (amoled) {
            window.setBackgroundDrawableResource(R.color.amoled_window_background)
            if (Build.VERSION.SDK_INT <= 34) {
                window.navigationBarColor = ResourcesCompat.getColor(resources, R.color.amoled_window_background, theme)
                window.statusBarColor = ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme)
            }
            actionBar?.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
            btnBack?.backgroundTintList = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
        } else {
            window.setBackgroundDrawable(SurfaceColors.SURFACE_0.getColor(this).toDrawable())
            if (Build.VERSION.SDK_INT <= 34) {
                window.navigationBarColor = SurfaceColors.SURFACE_0.getColor(this)
                window.statusBarColor = SurfaceColors.SURFACE_4.getColor(this)
            }
            actionBar?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
            btnBack?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
        }
    }

    private fun isDarkThemeEnabled(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        adjustPaddings()
    }

    private fun adjustPaddings() {
        if (Build.VERSION.SDK_INT < 35) return
        try {
            actionBar?.setPadding(
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.statusBars()).top,
                0,
                0
            )
            findViewById<ScrollView>(R.id.scroll)?.setPadding(
                0,
                0,
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom + pxToDp(24)
            )
        } catch (_: Exception) { /* unused */ }
    }

    private fun pxToDp(px: Int): Int = (px * resources.displayMetrics.density).toInt()
}
