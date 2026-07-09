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
import android.widget.EditText
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
import org.json.JSONArray
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.CardSections
import org.teslasoft.assistant.preferences.memory.CardType
import org.teslasoft.assistant.preferences.memory.MemoryRecord
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.ProjectRecord
import org.teslasoft.assistant.preferences.memory.librarian.Librarian
import org.teslasoft.assistant.theme.ThemeManager

/**
 * The full-screen memory add/edit form (Phase 5 Stage 2.2). Replaces the old
 * pop-up editor — the owner's device mishandles large dialogs, so anything with
 * this many fields opens as its own screen. Fields, per owner_approved_rules
 * §§2/5/6/8: title (required) + content, a Type dropdown (six types with their
 * meanings shown as a hint line), an Importance dropdown (five steps), a primary
 * Scope category (seven), and — for the target-bearing scopes — a multi-select
 * target picker whose choices show as removable pills (§2). Protection/handling
 * editing is unchanged; it stays on the browser row menu.
 *
 * All store work runs off the main thread; failures degrade to a toast.
 */
class MemoryEditorActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId: String = ""

    private var memoryId: String? = null
    private var existing: MemoryRecord? = null

    /** Guards Save until the record + pick lists have loaded, so an edit is
     *  never overwritten with the form's defaults before it populates. */
    private var ready = false

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var titleView: TextView? = null
    private var fieldTitle: TextInputEditText? = null
    private var fieldContent: TextInputEditText? = null
    private var btnType: MaterialButton? = null
    private var typeHint: TextView? = null
    private var btnImportance: MaterialButton? = null
    private var btnScope: MaterialButton? = null
    private var sectionTargets: View? = null
    private var targetsHeading: TextView? = null
    private var chipsTargets: ChipGroup? = null
    private var dropdownTargets: TextView? = null
    private var btnSave: MaterialButton? = null
    private var btnAccept: MaterialButton? = null
    private var sectionLinkCard: View? = null
    private var dropdownLinkCard: TextView? = null
    private var dropdownLinkSection: TextView? = null

    // Current selections.
    private var currentType: String = "fact"
    private var currentImportance: Int = 3
    private var currentScope: String = "global"

    /** "Link to Lore Card:" pick (roleplay drafts only, owner design July 8
     *  2026 evening). When both are set, Approve All As Shown MOVES the
     *  memory onto the card instead of activating it. */
    private var linkCardType: String? = null
    private var linkCardId: String? = null
    private var linkSection: String? = null

    /** True while editing a roleplay-scoped DRAFT — drives the "Needs
     *  roleplay target." note and the card-link gate (owner ruling, July 9
     *  2026: an untargeted roleplay draft cannot be added to a card). */
    private var isRoleplayDraft = false

    /** Selected targets for the current scope category, id -> display name. */
    private val selectedTargets = LinkedHashMap<String, String>()

    // Loaded pick lists (id -> name), populated off-thread once.
    private val companionItems = LinkedHashMap<String, String>()
    /** ALL companions (drafts included) for id→name display only. */
    private val companionNames = LinkedHashMap<String, String>()
    private val projectItems = LinkedHashMap<String, String>()
    private val worldItems = LinkedHashMap<String, String>()
    private val campaignItems = LinkedHashMap<String, String>()
    private val rpItems = LinkedHashMap<String, String>()

    /** Live lore cards for the Link to Lore Card dropdown: (cardType, id, name). */
    private val loreCards = ArrayList<Triple<String, String, String>>()

    companion object {
        private val TYPE_KEYS = listOf("fact", "preference", "event", "status", "instruction", "lore")
        private val SCOPE_KEYS = listOf("global", "real_life", "companion", "project", "world", "campaign", "rp_character")
        private val TARGET_SCOPES = setOf("companion", "project", "world", "campaign", "rp_character")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_memory_editor)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        memoryId = intent.extras?.getString("memoryId")?.takeIf { it.isNotEmpty() }
        preferences = Preferences.getPreferences(this, chatId)

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        titleView = findViewById(R.id.activity_title)
        fieldTitle = findViewById(R.id.field_mem_title)
        fieldContent = findViewById(R.id.field_mem_content)
        btnType = findViewById(R.id.btn_mem_type)
        typeHint = findViewById(R.id.text_type_hint)
        btnImportance = findViewById(R.id.btn_mem_importance)
        btnScope = findViewById(R.id.btn_mem_scope)
        sectionTargets = findViewById(R.id.section_targets)
        targetsHeading = findViewById(R.id.text_targets_heading)
        chipsTargets = findViewById(R.id.chips_targets)
        dropdownTargets = findViewById(R.id.dropdown_targets)
        btnSave = findViewById(R.id.btn_mem_save)
        btnAccept = findViewById(R.id.btn_mem_accept)
        sectionLinkCard = findViewById(R.id.section_link_card)
        dropdownLinkCard = findViewById(R.id.dropdown_link_card)
        dropdownLinkSection = findViewById(R.id.dropdown_link_section)

        titleView?.setText(if (memoryId == null) R.string.mem_edit_title_new else R.string.mem_edit_title_edit)

        applyTheme()

        btnBack?.setOnClickListener { finish() }
        btnType?.setOnClickListener { showTypePicker() }
        btnImportance?.setOnClickListener { showImportancePicker() }
        btnScope?.setOnClickListener { showScopePicker() }
        dropdownTargets?.setOnClickListener { showTargetDropdown(it) }
        btnSave?.setOnClickListener { save(activate = false) }
        btnAccept?.setOnClickListener { save(activate = true) }

        // Preset scope/target for a new memory opened from a scoped browser door.
        intent.getStringExtra("presetScope")?.takeIf { it in SCOPE_KEYS }?.let { currentScope = it }

        refreshType()
        refreshImportance()
        refreshScope()

        loadEverything()
    }

    /* ------------------------------ load ------------------------------ */

    private fun loadEverything() {
        runOffThread {
            if (!MemoryStore.isProvisioned(this)) {
                runOnUiThread { Toast.makeText(this, R.string.memory_not_provisioned_toast, Toast.LENGTH_SHORT).show() }
                return@runOffThread
            }
            val store = MemoryStore.getInstance(this)
            // The PICKER offers approved companions only, but name display
            // must resolve every id — the owner's rule (July 8 2026): the
            // internal identifier is never shown; always the current name.
            // A memory can point at a draft companion (auto-created on first
            // contact), and its pill used to leak the raw id.
            store.getCompanions().forEach { companionNames[it.companionId] = it.currentName }
            store.getCompanions().filter { it.status != "draft" }.forEach { companionItems[it.companionId] = it.currentName }
            store.getProjects().forEach { projectItems[it.projectId] = it.name }
            store.getAllWorlds().forEach { worldItems[it.worldId] = it.name }
            store.getCampaigns().forEach { campaignItems[it.campaignId] = it.name }
            store.getAllRoleplayCharacters().forEach { rpItems[it.roleplayCharacterId] = it.name }
            // Live cards for Link to Lore Card (archived cards are shelved
            // and not offered as destinations).
            loreCards.clear()
            store.getAllWorlds().filter { it.status == "active" }
                .forEach { loreCards.add(Triple(CardType.WORLD, it.worldId, it.name)) }
            store.getActiveCampaigns()
                .forEach { loreCards.add(Triple(CardType.CAMPAIGN, it.campaignId, it.name)) }
            store.getAllRoleplayCharacters().filter { it.status == "active" }
                .forEach { loreCards.add(Triple(CardType.RP_CHARACTER, it.roleplayCharacterId, it.name)) }
            store.getPartyMembers(includeArchived = false)
                .forEach { loreCards.add(Triple(CardType.PARTY_MEMBER, it.partyMemberId, it.name)) }

            val record = memoryId?.let { store.getMemory(it) }
            runOnUiThread {
                existing = record
                if (record != null) {
                    fieldTitle?.setText(record.title)
                    fieldContent?.setText(record.content)
                    findViewById<TextInputEditText>(R.id.field_mem_tags)?.setText(tagsToText(record.tagsJson))
                    currentType = record.kind.takeIf { it in TYPE_KEYS } ?: "fact"
                    currentImportance = record.importance.coerceIn(1, 5)
                    currentScope = record.scope.takeIf { it in SCOPE_KEYS } ?: "global"
                    selectedTargets.clear()
                    targetsForScope(currentScope, record).forEach { id ->
                        selectedTargets[id] = nameFor(currentScope, id)
                    }
                    refreshType()
                    refreshImportance()
                    // Pending mode (owner design, July 8 2026 evening): the
                    // bottom button reads "Approve All As Shown" — approving
                    // the draft with everything as shown and returning to the
                    // pending screen. It replaces the old separate Accept
                    // button (which stays hidden as redundant).
                    if (record.status == "draft") {
                        btnSave?.setText(R.string.mem_approve_all_as_shown)
                        btnSave?.setOnClickListener { save(activate = true) }
                        btnAccept?.visibility = View.GONE
                        // Roleplay drafts get the Link to Lore Card spot:
                        // picking a card + section makes approval MOVE the
                        // memory onto the card (owner ruling — it leaves the
                        // browser and lives with the card).
                        if (record.scope in setOf("world", "campaign", "rp_character")) {
                            isRoleplayDraft = true
                            wireLinkCardDropdowns()
                            // A Memory Assistant placement suggestion
                            // pre-selects both dropdowns ("Select" otherwise);
                            // the user can change or ignore it.
                            val suggested = loreCards.firstOrNull {
                                it.first == record.suggestedCardType && it.second == record.suggestedCardId
                            }
                            if (suggested != null) {
                                linkCardType = suggested.first
                                linkCardId = suggested.second
                                dropdownLinkCard?.text = suggested.third
                                val section = record.suggestedSection
                                if (section != null && section in CardSections.sectionsFor(suggested.first)) {
                                    linkSection = section
                                    dropdownLinkSection?.text =
                                        getString(CardEntryEditorActivity.sectionLabelRes(section))
                                }
                            }
                        }
                    }
                } else {
                    // New memory: a single preset target from a scoped door.
                    val presetTarget = intent.getStringExtra("presetTargetId")
                    if (presetTarget != null && currentScope in TARGET_SCOPES) {
                        selectedTargets[presetTarget] = nameFor(currentScope, presetTarget)
                    }
                }
                refreshScope()
                ready = true
            }
        }
    }

    private fun targetsForScope(scope: String, r: MemoryRecord): List<String> = when (scope) {
        "companion" -> r.companionIds
        "project" -> r.projectIds
        "world" -> r.worldIds
        "campaign" -> r.campaignIds
        "rp_character" -> r.roleplayCharacterIds
        else -> emptyList()
    }

    private fun itemsForScope(scope: String): LinkedHashMap<String, String> = when (scope) {
        "companion" -> companionItems
        "project" -> projectItems
        "world" -> worldItems
        "campaign" -> campaignItems
        "rp_character" -> rpItems
        else -> LinkedHashMap()
    }

    private fun nameFor(scope: String, id: String): String =
        itemsForScope(scope)[id]
            ?: (if (scope == "companion") companionNames[id] else null)
            ?: id

    /* ------------------------------ type ------------------------------ */

    private fun typeLabel(key: String): String = getString(
        when (key) {
            "fact" -> R.string.mem_type_fact
            "preference" -> R.string.mem_type_preference
            "event" -> R.string.mem_type_event
            "status" -> R.string.mem_type_status
            "instruction" -> R.string.mem_type_instruction
            else -> R.string.mem_type_lore
        }
    )

    private fun typeHintText(key: String): String = getString(
        when (key) {
            "fact" -> R.string.mem_type_fact_hint
            "preference" -> R.string.mem_type_preference_hint
            "event" -> R.string.mem_type_event_hint
            "status" -> R.string.mem_type_status_hint
            "instruction" -> R.string.mem_type_instruction_hint
            else -> R.string.mem_type_lore_hint
        }
    )

    private fun refreshType() {
        btnType?.text = typeLabel(currentType)
        typeHint?.text = typeHintText(currentType)
    }

    private fun showTypePicker() {
        val labels = TYPE_KEYS.map { typeLabel(it) }.toTypedArray()
        val current = TYPE_KEYS.indexOf(currentType).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_edit_label_type)
            .setSingleChoiceItems(labels, current) { d, which ->
                currentType = TYPE_KEYS[which]
                refreshType()
                d.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    /* ------------------------------ importance ------------------------------ */

    private fun importanceLabel(i: Int): String = getString(
        when (i) {
            1 -> R.string.mem_importance_1
            2 -> R.string.mem_importance_2
            3 -> R.string.mem_importance_3
            4 -> R.string.mem_importance_4
            else -> R.string.mem_importance_5
        }
    )

    private fun refreshImportance() {
        btnImportance?.text = importanceLabel(currentImportance)
    }

    private fun showImportancePicker() {
        val labels = (1..5).map { importanceLabel(it) }.toTypedArray()
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_edit_label_importance)
            .setSingleChoiceItems(labels, currentImportance - 1) { d, which ->
                currentImportance = which + 1
                refreshImportance()
                d.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    /* ------------------------------ scope ------------------------------ */

    private fun scopeLabel(key: String): String = getString(
        when (key) {
            "global" -> R.string.mem_scope_global
            "real_life" -> R.string.mem_scope_real_life
            "companion" -> R.string.mem_scope_companion
            "project" -> R.string.mem_scope_project
            "world" -> R.string.mem_scope_world
            "campaign" -> R.string.mem_scope_campaign
            else -> R.string.mem_scope_rp_character
        }
    )

    private fun refreshScope() {
        btnScope?.text = scopeLabel(currentScope)
        val hasTargets = currentScope in TARGET_SCOPES
        sectionTargets?.visibility = if (hasTargets) View.VISIBLE else View.GONE
        // "Associated Companion" / "Associated World" / … — the owner's label
        // pattern applied to the approved scope names.
        if (hasTargets) {
            targetsHeading?.text = getString(R.string.mem_edit_associated_fmt, scopeLabel(currentScope))
        }
        renderChips()
    }

    private fun showScopePicker() {
        val labels = SCOPE_KEYS.map { scopeLabel(it) }.toTypedArray()
        val current = SCOPE_KEYS.indexOf(currentScope).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_edit_label_scope)
            .setSingleChoiceItems(labels, current) { d, which ->
                val picked = SCOPE_KEYS[which]
                if (picked != currentScope) {
                    currentScope = picked
                    // Targets belong to one category; switching clears them.
                    selectedTargets.clear()
                    refreshScope()
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    /* ------------------------------ targets ------------------------------ */

    // Selected targets render as small-curve boxes (owner style: 5dp
    // corners, never pills) with the × at the far right to remove; the
    // ChipGroup container is kept only as the wrapping flow layout.
    private fun renderChips() {
        val group = chipsTargets ?: return
        group.removeAllViews()
        val pad = (10 * resources.displayMetrics.density).toInt()
        for ((id, name) in selectedTargets) {
            val box = TextView(this).apply {
                text = name
                textSize = 15f
                setTextColor(ResourcesCompat.getColor(resources, R.color.text_title, theme))
                background = ResourcesCompat.getDrawable(resources, R.drawable.bg_dropdown_box, theme)
                setPadding(pad, pad, pad, pad)
                setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_close, 0)
                compoundDrawablePadding = pad / 2
                setOnClickListener {
                    selectedTargets.remove(id)
                    renderChips()
                }
            }
            group.addView(box)
        }
        refreshRoleplayDraftGate()
    }

    /** Owner ruling (July 9 2026): an untargeted roleplay draft shows the
     *  persistent "Needs roleplay target." note and cannot be linked to a
     *  card; assigning a target (via the picker) opens the Link section.
     *  Re-evaluated on every target change. */
    private fun refreshRoleplayDraftGate() {
        if (!isRoleplayDraft) return
        val needsTarget = selectedTargets.isEmpty()
        findViewById<TextView>(R.id.text_needs_target)?.visibility =
            if (needsTarget) View.VISIBLE else View.GONE
        sectionLinkCard?.visibility = if (needsTarget) View.GONE else View.VISIBLE
        if (needsTarget && linkCardId != null) {
            // Losing the last target also clears any pending card link —
            // approval then activates normally instead of converting.
            linkCardType = null
            linkCardId = null
            linkSection = null
            dropdownLinkCard?.setText(R.string.mem_dropdown_select)
            dropdownLinkSection?.setText(R.string.mem_dropdown_select)
        }
    }

    /** The boxed "Select" dropdown (owner rework): lists the not-yet-selected
     *  targets for the current scope; picking one adds it as a ×-removable
     *  box below. Projects keep their create-from-picker ability
     *  (owner_approved_rules §4) as the dropdown's last entry. */
    private fun showTargetDropdown(anchor: View) {
        val items = itemsForScope(currentScope)
        val available = items.entries.filter { it.key !in selectedTargets }
        val menu = android.widget.PopupMenu(this, anchor)
        available.forEachIndexed { i, entry -> menu.menu.add(0, i, i, entry.value) }
        val newProjectId = available.size
        if (currentScope == "project") {
            menu.menu.add(0, newProjectId, newProjectId, getString(R.string.mem_edit_new_project))
        }
        menu.setOnMenuItemClickListener { item ->
            if (currentScope == "project" && item.itemId == newProjectId) {
                showNewProjectDialog()
            } else {
                val entry = available[item.itemId]
                selectedTargets[entry.key] = entry.value
                renderChips()
            }
            true
        }
        menu.show()
    }

    private fun showNewProjectDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.mem_edit_new_project_hint)
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_edit_new_project)
            .setView(container)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) return@setPositiveButton
                val id = MemoryStore.newId("proj-")
                val record = ProjectRecord(id, name, "active", MemoryStore.nowIso(), MemoryStore.nowIso())
                runOffThread {
                    MemoryStore.getInstance(this).upsertProject(record)
                    runOnUiThread {
                        projectItems[id] = name
                        selectedTargets[id] = name
                        renderChips()
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    /* ------------------------------ link to lore card ------------------------------ */

    private fun wireLinkCardDropdowns() {
        dropdownLinkCard?.setOnClickListener { anchor ->
            val menu = android.widget.PopupMenu(this, anchor)
            loreCards.forEachIndexed { i, c -> menu.menu.add(0, i, i, c.third) }
            menu.setOnMenuItemClickListener { item ->
                val card = loreCards[item.itemId]
                linkCardType = card.first
                linkCardId = card.second
                dropdownLinkCard?.text = card.third
                // Sections belong to the picked card's type; reset the choice.
                linkSection = null
                dropdownLinkSection?.setText(R.string.mem_dropdown_select)
                true
            }
            menu.show()
        }
        dropdownLinkSection?.setOnClickListener { anchor ->
            val type = linkCardType ?: return@setOnClickListener
            val sections = CardSections.sectionsFor(type)
            val menu = android.widget.PopupMenu(this, anchor)
            sections.forEachIndexed { i, s ->
                menu.menu.add(0, i, i, getString(CardEntryEditorActivity.sectionLabelRes(s)))
            }
            menu.setOnMenuItemClickListener { item ->
                linkSection = sections[item.itemId]
                dropdownLinkSection?.text = getString(CardEntryEditorActivity.sectionLabelRes(linkSection!!))
                true
            }
            menu.show()
        }
    }

    /* ------------------------------ save ------------------------------ */

    private fun save(activate: Boolean) {
        if (!ready) {
            Toast.makeText(this, R.string.mem_edit_still_loading, Toast.LENGTH_SHORT).show()
            return
        }
        val title = fieldTitle?.text?.toString()?.trim().orEmpty()
        val content = fieldContent?.text?.toString()?.trim().orEmpty()
        if (title.isEmpty() || content.isEmpty()) {
            Toast.makeText(this, R.string.mem_edit_required, Toast.LENGTH_SHORT).show()
            return
        }
        val tagsJson = textToTagsJson(findViewById<TextInputEditText>(R.id.field_mem_tags)?.text?.toString().orEmpty())
        val targets = selectedTargets.keys.toList()

        val companionIds = if (currentScope == "companion") targets else emptyList()
        val projectIds = if (currentScope == "project") targets else emptyList()
        val worldIds = if (currentScope == "world") targets else emptyList()
        val campaignIds = if (currentScope == "campaign") targets else emptyList()
        val rpIds = if (currentScope == "rp_character") targets else emptyList()

        runOffThread {
            val store = MemoryStore.getInstance(this)
            val prior = existing ?: memoryId?.let { store.getMemory(it) }
            // Approving a roleplay draft with a Lore Card picked MOVES it onto
            // the card (owner ruling, July 8 2026 evening): the edited title/
            // content become the entry, and the memory row is gone for good.
            val cardType = linkCardType
            val cardId = linkCardId
            val section = linkSection
            if (activate && prior != null && cardType != null && cardId != null && section != null) {
                store.updateMemory(
                    prior.copy(title = title, content = content),
                    getString(R.string.memory_change_edited)
                )
                store.convertMemoryToCardEntry(prior.memoryId, cardType, cardId, section)
                runOnUiThread {
                    Toast.makeText(this, R.string.memory_saved, Toast.LENGTH_SHORT).show()
                    finish()
                }
                return@runOffThread
            }
            if (prior == null) {
                val record = MemoryRecord(
                    memoryId = MemoryStore.newId("m-"),
                    scope = currentScope, kind = currentType, title = title, content = content,
                    embeddingText = null, tagsJson = tagsJson, importance = currentImportance,
                    worldIds = worldIds, roleplayCharacterIds = rpIds, campaignIds = campaignIds,
                    projectIds = projectIds,
                    protectionJson = null, modeHintsJson = "[]",
                    provenanceSource = "user_entered", provenanceConfidence = null,
                    provenanceNotedOn = MemoryStore.nowIso(), provenanceContext = null,
                    createdAt = MemoryStore.nowIso(), updatedAt = null, status = "active",
                    supersedes = null, companionIds = companionIds, entityRefs = emptyList(),
                    changeLog = emptyList(), origin = "user"
                )
                store.insertMemory(record)
                Librarian.getInstance(this).reindexMemory(record.memoryId)
            } else {
                val updated = prior.copy(
                    scope = currentScope, kind = currentType, title = title, content = content,
                    importance = currentImportance, tagsJson = tagsJson,
                    worldIds = worldIds, roleplayCharacterIds = rpIds, campaignIds = campaignIds,
                    projectIds = projectIds, companionIds = companionIds,
                    // Save keeps a draft a draft (§14); Accept activates it.
                    status = if (activate) "active" else prior.status
                )
                store.updateMemory(updated, getString(R.string.memory_change_edited))
                Librarian.getInstance(this).reindexMemory(prior.memoryId)
            }
            runOnUiThread {
                Toast.makeText(this, R.string.memory_saved, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /* ------------------------------ tags ------------------------------ */

    private fun tagsToText(tagsJson: String): String = try {
        val arr = JSONArray(tagsJson)
        (0 until arr.length()).joinToString(", ") { arr.getString(it) }
    } catch (_: Exception) { "" }

    private fun textToTagsJson(text: String): String {
        val arr = JSONArray()
        text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { arr.put(it) }
        return arr.toString()
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
                        getString(R.string.mem_edit_op_failed, e.message ?: e.javaClass.simpleName),
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
