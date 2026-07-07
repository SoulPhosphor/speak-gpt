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

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.CardEntryRecord
import org.teslasoft.assistant.preferences.memory.CardSections
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.RpTagTargetType
import org.teslasoft.assistant.theme.ThemeManager

/**
 * The Zone 2 entry editor (roleplay_cards_and_tags_spec §6): one full-screen
 * form for every card section. The section key (intent extra) decides which
 * rows show and which spec rules apply — the per-section Type/Relationship
 * dropdown lists, the Inventory quantity (required), Backstory's required
 * description, and the Title-vs-Name hint for the title-shaped sections.
 * World/campaign-only rows (geography parents, overlays, reliquary fields)
 * arrive with those cards' build slices. Tags per spec §3, roleplay realm
 * only. No length caps anywhere — multi-line is the rule (spec §6).
 */
class CardEntryEditorActivity : FragmentActivity() {

    private var preferences: Preferences? = null

    private var cardType: String = ""
    private var cardId: String = ""
    private var section: String = ""
    private var entryId: String? = null
    private var existing: CardEntryRecord? = null

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var titleView: TextView? = null
    private var sectionLabel: TextView? = null
    private var layoutName: TextInputLayout? = null
    private var fieldName: TextInputEditText? = null
    private var rowKind: View? = null
    private var labelKind: TextView? = null
    private var btnKind: MaterialButton? = null
    private var layoutQuantity: TextInputLayout? = null
    private var fieldQuantity: TextInputEditText? = null
    private var layoutDescription: TextInputLayout? = null
    private var fieldDescription: TextInputEditText? = null
    private var btnSave: MaterialButton? = null
    private var btnDelete: MaterialButton? = null
    private var tagChips: CardTagChips? = null

    private var currentKind: String? = null

    companion object {
        // Per-section Type/Relationship value lists — the spec §6a/§6b words,
        // stored as snake_case keys, shown via the entry_kind_* strings.
        private val KIND_OPTIONS = mapOf(
            CardSections.ABILITIES to listOf("innate", "trained", "class_feature", "spell", "other"),
            CardSections.INVENTORY to listOf("mundane", "magical", "quest", "weapon", "armor", "other"),
            CardSections.RELATIONSHIPS to listOf("ally", "enemy", "family", "mentor", "rival", "member", "other"),
            CardSections.TRAITS to listOf("fear", "like", "dislike")
        )

        // Sections whose entry name is a Title (Backstory, Plot Ledger, Notes).
        private val TITLE_SECTIONS = setOf(CardSections.BACKSTORY, CardSections.PLOT_LEDGER, CardSections.NOTES)

        /** The user-facing section names — the spec §6 words. */
        fun sectionLabelRes(section: String): Int = when (section) {
            CardSections.ABILITIES -> R.string.card_section_abilities
            CardSections.INVENTORY -> R.string.card_section_inventory
            CardSections.RELATIONSHIPS -> R.string.card_section_relationships
            CardSections.TRAITS -> R.string.card_section_traits
            CardSections.BACKSTORY -> R.string.card_section_backstory
            CardSections.LANGUAGES -> R.string.card_section_languages
            else -> R.string.card_section_notes
        }

        fun kindLabelRes(kind: String): Int = when (kind) {
            "innate" -> R.string.entry_kind_innate
            "trained" -> R.string.entry_kind_trained
            "class_feature" -> R.string.entry_kind_class_feature
            "spell" -> R.string.entry_kind_spell
            "mundane" -> R.string.entry_kind_mundane
            "magical" -> R.string.entry_kind_magical
            "quest" -> R.string.entry_kind_quest
            "weapon" -> R.string.entry_kind_weapon
            "armor" -> R.string.entry_kind_armor
            "ally" -> R.string.entry_kind_ally
            "enemy" -> R.string.entry_kind_enemy
            "family" -> R.string.entry_kind_family
            "mentor" -> R.string.entry_kind_mentor
            "rival" -> R.string.entry_kind_rival
            "member" -> R.string.entry_kind_member
            "fear" -> R.string.entry_kind_fear
            "like" -> R.string.entry_kind_like
            "dislike" -> R.string.entry_kind_dislike
            else -> R.string.entry_kind_other
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_card_entry_editor)

        val chatId = intent.extras?.getString("chatId", "") ?: ""
        preferences = Preferences.getPreferences(this, chatId)

        cardType = intent.extras?.getString("cardType", "") ?: ""
        cardId = intent.extras?.getString("cardId", "") ?: ""
        section = intent.extras?.getString("section", "") ?: ""
        entryId = intent.extras?.getString("entryId")?.takeIf { it.isNotEmpty() }

        if (cardType.isEmpty() || cardId.isEmpty() || section.isEmpty()) {
            finish()
            return
        }

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        titleView = findViewById(R.id.activity_title)
        sectionLabel = findViewById(R.id.text_entry_section)
        layoutName = findViewById(R.id.layout_entry_name)
        fieldName = findViewById(R.id.field_entry_name)
        rowKind = findViewById(R.id.row_entry_kind)
        labelKind = findViewById(R.id.label_entry_kind)
        btnKind = findViewById(R.id.btn_entry_kind)
        layoutQuantity = findViewById(R.id.layout_entry_quantity)
        fieldQuantity = findViewById(R.id.field_entry_quantity)
        layoutDescription = findViewById(R.id.layout_entry_description)
        fieldDescription = findViewById(R.id.field_entry_description)
        btnSave = findViewById(R.id.btn_entry_save)
        btnDelete = findViewById(R.id.btn_entry_delete)

        tagChips = CardTagChips(
            this,
            findViewById<ChipGroup>(R.id.chips_entry_tags),
            findViewById<AutoCompleteTextView>(R.id.field_entry_tag_input)
        )

        titleView?.setText(if (entryId == null) R.string.entry_title_new else R.string.entry_title_edit)
        sectionLabel?.setText(sectionLabelRes(section))

        applyTheme()
        configureForSection()

        btnBack?.setOnClickListener { finish() }
        btnKind?.setOnClickListener { showKindPicker() }
        btnSave?.setOnClickListener { save() }
        btnDelete?.setOnClickListener { confirmDelete() }

        loadExisting()
    }

    /* ------------------------------ section shape ------------------------------ */

    private fun configureForSection() {
        layoutName?.hint = getString(
            if (section in TITLE_SECTIONS) R.string.entry_hint_title else R.string.entry_hint_name
        )

        val kinds = KIND_OPTIONS[section]
        if (kinds != null) {
            rowKind?.visibility = View.VISIBLE
            labelKind?.setText(
                if (section == CardSections.RELATIONSHIPS) R.string.entry_label_relationship
                else R.string.entry_label_type
            )
            currentKind = kinds.first()
            refreshKind()
        } else {
            rowKind?.visibility = View.GONE
        }

        layoutQuantity?.visibility =
            if (section == CardSections.INVENTORY) View.VISIBLE else View.GONE

        layoutDescription?.hint = getString(
            if (descriptionRequired()) R.string.entry_hint_description else R.string.entry_hint_description_optional
        )
    }

    /** Backstory requires a description (spec §6a); the world card's sections
     *  all do too (spec §6c) — ready for that card's slice. */
    private fun descriptionRequired(): Boolean =
        section == CardSections.BACKSTORY || section in CardSections.WORLD_SECTIONS

    private fun refreshKind() {
        btnKind?.setText(kindLabelRes(currentKind ?: "other"))
    }

    private fun showKindPicker() {
        val kinds = KIND_OPTIONS[section] ?: return
        val labels = kinds.map { getString(kindLabelRes(it)) }.toTypedArray()
        val current = kinds.indexOf(currentKind).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(
                if (section == CardSections.RELATIONSHIPS) R.string.entry_label_relationship
                else R.string.entry_label_type
            )
            .setSingleChoiceItems(labels, current) { d, which ->
                currentKind = kinds[which]
                refreshKind()
                d.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    /* ------------------------------ load ------------------------------ */

    private fun loadExisting() {
        val id = entryId ?: return
        runOffThread {
            val store = MemoryStore.getInstance(this)
            val record = store.getCardEntry(id) ?: return@runOffThread
            val tags = store.tagsForTarget(RpTagTargetType.CARD_ENTRY, id)
            runOnUiThread {
                existing = record
                fieldName?.setText(record.name)
                fieldDescription?.setText(record.description ?: "")
                record.quantity?.let { fieldQuantity?.setText(it.toString()) }
                if (KIND_OPTIONS[section]?.contains(record.entryKind) == true) {
                    currentKind = record.entryKind
                    refreshKind()
                }
                tagChips?.setInitial(tags)
                btnDelete?.visibility = View.VISIBLE
            }
        }
    }

    /* ------------------------------ save ------------------------------ */

    private fun save() {
        val name = fieldName?.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            Toast.makeText(
                this,
                if (section in TITLE_SECTIONS) R.string.entry_title_required else R.string.entry_name_required,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val description = fieldDescription?.text?.toString()?.trim().orEmpty()
        if (descriptionRequired() && description.isEmpty()) {
            Toast.makeText(this, R.string.entry_description_required, Toast.LENGTH_SHORT).show()
            return
        }
        var quantity: Int? = null
        if (section == CardSections.INVENTORY) {
            quantity = fieldQuantity?.text?.toString()?.trim()?.toIntOrNull()
            if (quantity == null) {
                Toast.makeText(this, R.string.entry_quantity_required, Toast.LENGTH_SHORT).show()
                return
            }
        }

        // A typed-but-unconfirmed tag counts as picked.
        tagChips?.confirmText()

        val prior = existing
        val id = prior?.entryId ?: MemoryStore.newId("ce-")
        val record = (prior ?: CardEntryRecord(
            entryId = id, cardType = cardType, cardId = cardId, section = section,
            name = name, createdAt = MemoryStore.nowIso()
        )).copy(
            name = name,
            description = description.ifEmpty { null },
            entryKind = if (KIND_OPTIONS.containsKey(section)) currentKind else null,
            quantity = quantity,
            updatedAt = if (prior != null) MemoryStore.nowIso() else null
        )

        runOffThread {
            val store = MemoryStore.getInstance(this)
            store.upsertCardEntry(record)

            // Reconcile tag links with the chips.
            val wanted = tagChips?.selectedTagIds()?.toSet() ?: emptySet()
            val current = store.tagsForTarget(RpTagTargetType.CARD_ENTRY, id).map { it.tagId }.toSet()
            (wanted - current).forEach { store.addTagLink(it, RpTagTargetType.CARD_ENTRY, id) }
            (current - wanted).forEach { store.removeTagLink(it, RpTagTargetType.CARD_ENTRY, id) }

            runOnUiThread {
                Toast.makeText(this, R.string.entry_saved, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /* ------------------------------ delete ------------------------------ */

    private fun confirmDelete() {
        val record = existing ?: return
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.entry_delete_title)
            .setMessage(getString(R.string.entry_delete_msg, record.name))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                runOffThread {
                    MemoryStore.getInstance(this).deleteCardEntry(record.entryId)
                    runOnUiThread {
                        Toast.makeText(this, R.string.entry_deleted, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
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
                        getString(R.string.memory_operation_failed, e.message ?: e.javaClass.simpleName),
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
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom +
                    (24 * resources.displayMetrics.density).toInt()
            )
        } catch (_: Exception) { /* unused */ }
    }
}
