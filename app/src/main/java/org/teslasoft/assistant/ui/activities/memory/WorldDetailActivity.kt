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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.CardEntryRecord
import org.teslasoft.assistant.preferences.memory.CardSections
import org.teslasoft.assistant.preferences.memory.CardType
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.RpTagRecord
import org.teslasoft.assistant.preferences.memory.RpTagTargetType
import org.teslasoft.assistant.preferences.memory.WorldRecord
import org.teslasoft.assistant.theme.ThemeManager

/**
 * The two-zone world card (roleplay_cards_and_tags_spec §6c). Zone 1 is the
 * world core — Name, Premise/Vibe, Cosmology, Magic Rules, in fresh v8
 * storage; the dormant pre-card premise/rules text is preserved untouched and
 * never shown (spec §8a). Zone 2 renders the seventeen spec sections under
 * their six group headers (headers are visual organization ONLY — sections
 * are the trigger units) plus the owner-added Notes section. Geography
 * entries show their parent; notable-NPC entries promoted to a party-member
 * card open that card on tap (the card is the source of truth — long-press
 * still opens the lore entry). The world-scoped Memories door and the
 * teardown flow carry over; the teardown gets the §5 link-warning rework in
 * the 3.6f slice.
 */
class WorldDetailActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId: String = ""

    /** Null until the world is saved once; then the persisted id. */
    private var worldId: String? = null
    /** The last loaded/saved record — the source of the fields we preserve
     *  (status, companion links, dormant pre-card text, createdAt). */
    private var existing: WorldRecord? = null

    /** Guards Save until an existing record has loaded. */
    private var ready = false

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var titleView: TextView? = null
    private var fieldName: TextInputEditText? = null
    private var fieldPremiseVibe: TextInputEditText? = null
    private var fieldCosmology: TextInputEditText? = null
    private var fieldMagicRules: TextInputEditText? = null
    private var btnSave: MaterialButton? = null
    private var btnMemories: MaterialButton? = null
    private var btnTeardown: MaterialButton? = null
    private var textSaveFirst: TextView? = null
    private var sectionsContainer: LinearLayout? = null

    companion object {
        // Zone 2 groups (spec §6c): header string -> its sections, in spec
        // order. Headers never trigger and are never stored.
        private val GROUPS = listOf(
            R.string.card_group_geography to listOf(
                CardSections.REGIONS, CardSections.SETTLEMENTS, CardSections.POINTS_OF_INTEREST
            ),
            R.string.card_group_species_culture to listOf(
                CardSections.RACES_SPECIES, CardSections.LANGUAGES_SCRIPTS
            ),
            R.string.card_group_history_lore to listOf(
                CardSections.HISTORICAL_EVENTS, CardSections.ARCANE_KNOWLEDGE
            ),
            R.string.card_group_organized_groups to listOf(
                CardSections.ORGANIZATIONS_GUILDS, CardSections.BANDS_THREATS
            ),
            R.string.card_group_religions_pantheons to listOf(
                CardSections.DEITIES, CardSections.FAITHS, CardSections.SACRED_ARTIFACTS
            ),
            R.string.card_group_notable_npcs to listOf(
                CardSections.HISTORICAL_FIGURES, CardSections.AUTHORITY_FIGURES,
                CardSections.SERVICE_NPCS, CardSections.ALLIES, CardSections.ANTAGONISTS
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_world_detail)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        worldId = intent.extras?.getString("worldId")
        preferences = Preferences.getPreferences(this, chatId)

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        titleView = findViewById(R.id.activity_title)
        fieldName = findViewById(R.id.field_world_name)
        fieldPremiseVibe = findViewById(R.id.field_world_premise_vibe)
        fieldCosmology = findViewById(R.id.field_world_cosmology)
        fieldMagicRules = findViewById(R.id.field_world_magic_rules)
        btnSave = findViewById(R.id.btn_world_save)
        btnMemories = findViewById(R.id.btn_world_memories)
        btnTeardown = findViewById(R.id.btn_world_teardown)
        textSaveFirst = findViewById(R.id.text_save_first)
        sectionsContainer = findViewById(R.id.sections_container)

        titleView?.setText(
            if (worldId == null) R.string.mem_world_detail_new_title else R.string.mem_world_detail_title
        )

        applyTheme()

        btnBack?.setOnClickListener { finish() }
        btnSave?.setOnClickListener { save() }
        btnMemories?.setOnClickListener { openMemories() }
        btnTeardown?.setOnClickListener { showTeardownDialog() }

        CardZoneUi.attachWordCount(
            this,
            listOf(fieldName, fieldPremiseVibe, fieldCosmology, fieldMagicRules),
            findViewById(R.id.text_card_word_count),
            findViewById(R.id.text_card_warning)
        )

        updateExtraButtons()
        loadIfExisting()
    }

    override fun onResume() {
        super.onResume()
        renderSections()
    }

    /* ------------------------------ load ------------------------------ */

    private fun loadIfExisting() {
        val id = worldId
        if (id == null) {
            ready = true
            return
        }
        runOffThread {
            if (!MemoryStore.isProvisioned(this)) return@runOffThread
            val w = MemoryStore.getInstance(this).getWorld(id)
            runOnUiThread {
                existing = w
                if (w != null) {
                    fieldName?.setText(w.name)
                    fieldPremiseVibe?.setText(w.premiseVibe ?: "")
                    fieldCosmology?.setText(w.cosmology ?: "")
                    fieldMagicRules?.setText(w.magicRules ?: "")
                }
                ready = true
                updateExtraButtons()
            }
        }
    }

    /** Memories/teardown only make sense once the world exists in the store. */
    private fun updateExtraButtons() {
        val saved = worldId != null
        btnMemories?.visibility = if (saved) View.VISIBLE else View.GONE
        btnTeardown?.visibility = if (saved) View.VISIBLE else View.GONE
    }

    /* ------------------------------ save ------------------------------ */

    private fun save() {
        if (!ready) return
        val name = fieldName?.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.card_name_required, Toast.LENGTH_SHORT).show()
            return
        }

        fun text(f: TextInputEditText?): String? =
            f?.text?.toString()?.trim()?.ifEmpty { null }

        val prior = existing
        val id = worldId ?: MemoryStore.newId("w-")
        val record = WorldRecord(
            worldId = id,
            name = name,
            // Dormant pre-card columns (spec §8a): preserved untouched, never
            // shown, never reused. premise is NOT NULL in the schema, so a
            // brand-new card stores an empty string there.
            premise = prior?.premise ?: "",
            rules = prior?.rules,
            cosmology = text(fieldCosmology),
            premiseVibe = text(fieldPremiseVibe),
            magicRules = text(fieldMagicRules),
            companionIdsJson = prior?.companionIdsJson ?: "[]",
            status = prior?.status ?: "active",
            createdAt = prior?.createdAt ?: MemoryStore.nowIso()
        )

        runOffThread {
            MemoryStore.getInstance(this).upsertWorld(record)
            runOnUiThread {
                worldId = id
                existing = record
                titleView?.setText(R.string.mem_world_detail_title)
                updateExtraButtons()
                Toast.makeText(this, R.string.mem_world_saved, Toast.LENGTH_SHORT).show()
                renderSections()
            }
        }
    }

    /* ------------------------------ Zone 2 sections ------------------------------ */

    private fun renderSections() {
        val container = sectionsContainer ?: return
        // Zone 2 always shows — never hidden, even before the card is saved
        // (owner ruling, July 10 2026).
        textSaveFirst?.visibility = View.GONE
        val id = worldId

        runOffThread {
            val store = MemoryStore.getInstance(this)
            val entries = if (id != null) store.entriesForCard(CardType.WORLD, id) else emptyList()
            val bySection = entries.groupBy { it.section }
            val nameById = entries.associate { it.entryId to it.name }
            // Each entry's own tags, read off-thread so the row can show them as
            // tappable links without a DB hit on the UI thread.
            val tagsByEntry = entries.associate {
                it.entryId to store.tagsForTarget(RpTagTargetType.CARD_ENTRY, it.entryId)
            }
            // §5 (3.6f): promotion pointers to a gone party-member card must
            // say so — collect the living roster ids once.
            val partyIds = try {
                store.getPartyMembers(includeArchived = true).map { it.partyMemberId }.toHashSet()
            } catch (_: Exception) { HashSet() }
            runOnUiThread {
                container.removeAllViews()
                val inflater = LayoutInflater.from(this)

                for ((groupRes, sections) in GROUPS) {
                    // Group headers are visual organization only (spec §6c).
                    val header = TextView(this).apply {
                        setText(groupRes)
                        setTextColor(ResourcesCompat.getColor(resources, R.color.text_title, theme))
                        textSize = 17f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setPadding(0, (24 * resources.displayMetrics.density).toInt(), 0, 0)
                    }
                    container.addView(header)
                    for (section in sections) {
                        container.addView(sectionBlock(inflater, container, section, bySection, nameById, partyIds, tagsByEntry))
                    }
                }
                // The owner-added Notes section (spec §8a), no group.
                container.addView(
                    sectionBlock(inflater, container, CardSections.NOTES, bySection, nameById, partyIds, tagsByEntry)
                )
            }
        }
    }

    private fun sectionBlock(
        inflater: LayoutInflater,
        container: LinearLayout,
        section: String,
        bySection: Map<String, List<CardEntryRecord>>,
        nameById: Map<String, String>,
        partyIds: Set<String>,
        tagsByEntry: Map<String, List<RpTagRecord>>
    ): View {
        val block = inflater.inflate(R.layout.view_card_section, container, false)
        block.findViewById<TextView>(R.id.section_title)
            .setText(CardEntryEditorActivity.sectionLabelRes(section))

        val list = block.findViewById<LinearLayout>(R.id.section_entries)
        for (entry in bySection[section].orEmpty()) list.addView(entryRow(inflater, list, entry, nameById, partyIds, tagsByEntry[entry.entryId].orEmpty()))

        block.findViewById<MaterialButton>(R.id.btn_add_entry).setOnClickListener {
            openEntryEditor(section, null)
        }
        return block
    }

    private fun entryRow(
        inflater: LayoutInflater,
        parent: LinearLayout,
        entry: CardEntryRecord,
        nameById: Map<String, String>,
        partyIds: Set<String>,
        tags: List<RpTagRecord>
    ): View {
        val row = inflater.inflate(R.layout.view_card_entry, parent, false)
        row.findViewById<TextView>(R.id.entry_name).text = entry.name

        // Geography shows its parent (the chain is self-locating, spec §6c/§8);
        // otherwise the first description line.
        val descView = row.findViewById<TextView>(R.id.entry_desc)
        val subtitle = entry.parentEntryId?.let { nameById[it] }
            ?: entry.description?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        if (subtitle == null) {
            descView.visibility = View.GONE
        } else {
            descView.visibility = View.VISIBLE
            descView.text = subtitle
        }

        // A promoted NPC entry: the party-member card is the source of truth —
        // tap opens the card (owner ruling, spec §8a); long-press still opens
        // the lightweight lore entry. Badge marks the promoted state.
        val badge = row.findViewById<TextView>(R.id.entry_badge)
        val promotedTo = entry.partyMemberId
        val promotedExists = promotedTo != null && promotedTo in partyIds
        if (promotedTo != null) {
            badge.visibility = View.VISIBLE
            // §5: a pointer to a deleted card says so instead of vanishing.
            badge.setText(if (promotedExists) R.string.card_title_party_member else R.string.rp_ref_deleted)
        } else {
            badge.visibility = View.GONE
        }

        CardEntryTags.render(this, row.findViewById(R.id.entry_tags), tags, chatId)

        row.setOnClickListener {
            if (promotedExists) openPartyMemberCard(promotedTo!!)
            else openEntryEditor(entry.section, entry.entryId)
        }
        row.setOnLongClickListener {
            openEntryEditor(entry.section, entry.entryId)
            true
        }
        return row
    }

    private fun openEntryEditor(section: String, entryId: String?) {
        val id = worldId ?: run {
            Toast.makeText(this, R.string.card_save_first, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, CardEntryEditorActivity::class.java)
                .putExtra("chatId", chatId)
                .putExtra("cardType", CardType.WORLD)
                .putExtra("cardId", id)
                .putExtra("section", section)
                .putExtra("entryId", entryId ?: "")
        )
    }

    private fun openPartyMemberCard(partyMemberId: String) {
        startActivity(
            Intent(this, CharacterCardActivity::class.java)
                .putExtra("chatId", chatId)
                .putExtra("cardType", CardType.PARTY_MEMBER)
                .putExtra("cardId", partyMemberId)
        )
    }

    /* ------------------------------ memories ------------------------------ */

    private fun openMemories() {
        val id = worldId
        if (id == null) {
            Toast.makeText(this, R.string.mem_world_save_first, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, MemoryBrowserActivity::class.java)
                .putExtra("chatId", chatId)
                .putExtra("worldId", id)
                .putExtra("screenTitle", fieldName?.text?.toString()?.trim().orEmpty())
        )
    }

    /* ------------------------------ teardown ------------------------------ */

    /** §5 (3.6f): deleting anything linked to a campaign warns, NAMES the
     *  campaign(s), and offers archive instead. Archive is status-only —
     *  links and memories stay; restorable from the worlds list. */
    private fun showTeardownDialog() {
        val id = worldId ?: return
        runOffThread {
            val linked = MemoryStore.getInstance(this).getCampaigns()
                .filter { it.worldId == id }.map { it.name }
            runOnUiThread {
                val message =
                    if (linked.isEmpty()) getString(R.string.mem_world_teardown_msg)
                    else getString(R.string.rp_delete_linked_msg, linked.joinToString(", "))
                MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                    .setTitle(R.string.mem_world_teardown_title)
                    .setMessage(message)
                    .setPositiveButton(R.string.mem_world_teardown_delete) { _, _ -> showDeleteScopeDialog(id) }
                    .setNeutralButton(R.string.mem_world_teardown_archive) { _, _ -> archive(id) }
                    .setNegativeButton(R.string.mem_world_cancel) { _, _ -> }
                    .show()
            }
        }
    }

    private fun archive(id: String) {
        runOffThread {
            MemoryStore.getInstance(this).archiveWorld(id)
            runOnUiThread {
                Toast.makeText(this, R.string.mem_world_archived, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /** True delete asks per-deletion whether the memories go too (§5).
     *  Memories that also belong to a character walk away clean either way —
     *  cards and memories are separate. */
    private fun showDeleteScopeDialog(id: String) {
        val name = fieldName?.text?.toString()?.trim().orEmpty()
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.rp_delete_scope_title)
            .setMessage(getString(R.string.rp_delete_scope_msg, name))
            .setPositiveButton(R.string.mem_pers_delete_character_with_memories) { _, _ ->
                delete(id, deleteMemories = true)
            }
            .setNegativeButton(R.string.mem_pers_delete_character_keep_memories) { _, _ ->
                delete(id, deleteMemories = false)
            }
            .show()
    }

    private fun delete(id: String, deleteMemories: Boolean) {
        runOffThread {
            MemoryStore.getInstance(this).deleteWorld(id, deleteMemories, keepCharacterMemories = true)
            runOnUiThread {
                Toast.makeText(this, R.string.mem_world_deleted, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
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
