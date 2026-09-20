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
import androidx.activity.addCallback
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
import org.teslasoft.assistant.preferences.memory.PartyMemberRecord
import org.teslasoft.assistant.preferences.memory.RoleplayCharacterRecord
import org.teslasoft.assistant.preferences.memory.RpTagRecord
import org.teslasoft.assistant.preferences.memory.RpTagTargetType
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.DiscardChangesDialog

/**
 * The two-zone character card (roleplay_cards_and_tags_spec §6a/§6b): one
 * screen for the user RP character AND the NPC party member, which adds two
 * Zone 1 fields (Speech Style, the four-state fiction Status). Zone 1 is
 * visibly labeled with the owner's approved words; the word count sits
 * right-aligned under the Zone 1 box with the 300-word warning and the
 * 500-word red-count escalation (spec §8a). Zone 2 renders the six spec
 * sections plus Notes; entries open the shared entry editor. The pre-card
 * description/arc/played_by columns are dormant and never shown (spec §8a —
 * the cards only carry what the spec lists).
 */
class CharacterCardActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId: String = ""

    private var cardType: String = CardType.RP_CHARACTER
    private var cardId: String? = null

    private var priorCharacter: RoleplayCharacterRecord? = null
    private var priorParty: PartyMemberRecord? = null

    /** Guards Save until an existing record has loaded, so an edit can't be
     *  overwritten with the empty form. */
    private var ready = false

    /** Snapshot of the fields as last loaded/saved, for the discard-changes
     *  confirmation on back-out (see DiscardChangesDialog). */
    private var initialSnapshot: String = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var titleView: TextView? = null
    private var fieldName: TextInputEditText? = null
    private var fieldSpecies: TextInputEditText? = null
    private var fieldClass: TextInputEditText? = null
    private var fieldCorePersonality: TextInputEditText? = null
    private var fieldPhysicalDescription: TextInputEditText? = null
    private var fieldGoalsDrives: TextInputEditText? = null
    private var layoutSpeechStyle: View? = null
    private var fieldSpeechStyle: TextInputEditText? = null
    private var rowStatus: View? = null
    private var btnStatus: MaterialButton? = null
    private var textWarning: TextView? = null
    private var textWordCount: TextView? = null
    private var btnSave: ImageButton? = null
    private var btnMemories: MaterialButton? = null
    private var textSaveFirst: TextView? = null
    private var sectionsContainer: LinearLayout? = null

    private var currentStatus: String = "alive"

    companion object {
        private val STATUS_KEYS = listOf("alive", "incapacitated", "dead", "enemy")

        fun statusLabelRes(status: String): Int = when (status) {
            "incapacitated" -> R.string.card_status_incapacitated
            "dead" -> R.string.card_status_dead
            "enemy" -> R.string.card_status_enemy
            else -> R.string.card_status_alive
        }
    }

    private val isParty: Boolean get() = cardType == CardType.PARTY_MEMBER

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_character_card)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        preferences = Preferences.getPreferences(this, chatId)

        cardType = intent.extras?.getString("cardType", CardType.RP_CHARACTER) ?: CardType.RP_CHARACTER
        cardId = intent.extras?.getString("cardId")?.takeIf { it.isNotEmpty() }

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        titleView = findViewById(R.id.activity_title)
        fieldName = findViewById(R.id.field_card_name)
        fieldSpecies = findViewById(R.id.field_card_species)
        fieldClass = findViewById(R.id.field_card_class)
        fieldCorePersonality = findViewById(R.id.field_card_core_personality)
        fieldPhysicalDescription = findViewById(R.id.field_card_physical_description)
        fieldGoalsDrives = findViewById(R.id.field_card_goals_drives)
        layoutSpeechStyle = findViewById(R.id.layout_card_speech_style)
        fieldSpeechStyle = findViewById(R.id.field_card_speech_style)
        rowStatus = findViewById(R.id.row_card_status)
        btnStatus = findViewById(R.id.btn_card_status)
        textWarning = findViewById(R.id.text_card_warning)
        textWordCount = findViewById(R.id.text_card_word_count)
        btnSave = findViewById(R.id.btn_card_save)
        btnMemories = findViewById(R.id.btn_card_memories)
        textSaveFirst = findViewById(R.id.text_save_first)
        sectionsContainer = findViewById(R.id.sections_container)

        titleView?.setText(if (isParty) R.string.card_title_party_member else R.string.card_title_character)
        if (isParty) {
            layoutSpeechStyle?.visibility = View.VISIBLE
            rowStatus?.visibility = View.VISIBLE
        }

        applyTheme()

        onBackPressedDispatcher.addCallback(this) { attemptExit() }

        btnBack?.setOnClickListener { attemptExit() }
        btnSave?.setOnClickListener { save() }
        btnStatus?.setOnClickListener { showStatusPicker() }
        btnMemories?.setOnClickListener { openMemories() }

        CardZoneUi.attachWordCount(this, zone1Fields(), textWordCount, textWarning)
        refreshStatus()
        refreshMemoriesButton()
        loadExisting()
    }

    /** One browser, many doors (§8): pre-filtered to this character. Party
     *  members aren't a memory scope, so their card has no memories door. */
    private fun refreshMemoriesButton() {
        btnMemories?.visibility =
            if (!isParty && cardId != null) View.VISIBLE else View.GONE
    }

    private fun openMemories() {
        val id = cardId ?: return
        startActivity(
            Intent(this, MemoryBrowserActivity::class.java)
                .putExtra("roleplayCharacterId", id)
                .putExtra("screenTitle", fieldName?.text?.toString()?.trim().orEmpty())
                .putExtra("chatId", chatId)
        )
    }

    override fun onResume() {
        super.onResume()
        renderSections()
    }

    /* ------------------------------ load ------------------------------ */

    private fun loadExisting() {
        val id = cardId
        if (id == null) {
            ready = true
            initialSnapshot = snapshot()
            return
        }
        runOffThread {
            val store = MemoryStore.getInstance(this)
            if (isParty) {
                val record = store.getPartyMember(id)
                runOnUiThread {
                    priorParty = record
                    record?.let {
                        fieldName?.setText(it.name)
                        fieldSpecies?.setText(it.species ?: "")
                        fieldClass?.setText(it.charClass ?: "")
                        fieldCorePersonality?.setText(it.corePersonality ?: "")
                        fieldPhysicalDescription?.setText(it.physicalDescription ?: "")
                        fieldGoalsDrives?.setText(it.goalsDrives ?: "")
                        fieldSpeechStyle?.setText(it.speechStyle ?: "")
                        currentStatus = it.status
                        refreshStatus()
                    }
                    ready = true
                    initialSnapshot = snapshot()
                }
            } else {
                val record = store.getRoleplayCharacter(id)
                runOnUiThread {
                    priorCharacter = record
                    record?.let {
                        fieldName?.setText(it.name)
                        fieldSpecies?.setText(it.species ?: "")
                        fieldClass?.setText(it.charClass ?: "")
                        fieldCorePersonality?.setText(it.corePersonality ?: "")
                        fieldPhysicalDescription?.setText(it.physicalDescription ?: "")
                        fieldGoalsDrives?.setText(it.goalsDrives ?: "")
                    }
                    ready = true
                    initialSnapshot = snapshot()
                }
            }
        }
    }

    /* ------------------------------ word count (spec §8a) ------------------------------ */

    // Speech Style is included unconditionally: hidden on the user card, it
    // is always empty there and counts zero.
    private fun zone1Fields(): List<TextInputEditText?> = listOf(
        fieldName, fieldSpecies, fieldClass, fieldCorePersonality,
        fieldPhysicalDescription, fieldGoalsDrives, fieldSpeechStyle
    )

    /* ------------------------------ status (party members, spec §4) ------------------------------ */

    private fun refreshStatus() {
        btnStatus?.setText(statusLabelRes(currentStatus))
    }

    private fun showStatusPicker() {
        val labels = STATUS_KEYS.map { getString(statusLabelRes(it)) }.toTypedArray()
        val current = STATUS_KEYS.indexOf(currentStatus).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.card_field_status)
            .setSingleChoiceItems(labels, current) { d, which ->
                currentStatus = STATUS_KEYS[which]
                refreshStatus()
                d.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    /* ------------------------------ exit / discard ------------------------------ */

    /** Serialised form of the Zone 1 fields, used only for change detection
     *  against initialSnapshot. speechStyle/currentStatus are party-only
     *  fields but are harmless to include unconditionally — they never
     *  change on the (hidden) user-character form. */
    private fun snapshot(): String = listOf(
        fieldName?.text?.toString().orEmpty(),
        fieldSpecies?.text?.toString().orEmpty(),
        fieldClass?.text?.toString().orEmpty(),
        fieldCorePersonality?.text?.toString().orEmpty(),
        fieldPhysicalDescription?.text?.toString().orEmpty(),
        fieldGoalsDrives?.text?.toString().orEmpty(),
        fieldSpeechStyle?.text?.toString().orEmpty(),
        currentStatus
    ).joinToString("")

    /** Back / cancel. Confirms first if anything changed since the last load
     *  or save (DiscardChangesDialog — the app's standard unsaved-changes
     *  confirmation). */
    private fun attemptExit() {
        if (ready && snapshot() != initialSnapshot) {
            DiscardChangesDialog.show(this) { finish() }
        } else {
            finish()
        }
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

        val id = cardId ?: MemoryStore.newId(if (isParty) "pm-" else "rc-")

        runOffThread {
            val store = MemoryStore.getInstance(this)
            if (isParty) {
                store.upsertPartyMember(
                    PartyMemberRecord(
                        partyMemberId = id,
                        name = name,
                        species = text(fieldSpecies),
                        charClass = text(fieldClass),
                        corePersonality = text(fieldCorePersonality),
                        physicalDescription = text(fieldPhysicalDescription),
                        goalsDrives = text(fieldGoalsDrives),
                        speechStyle = text(fieldSpeechStyle),
                        status = currentStatus,
                        archived = priorParty?.archived ?: false,
                        createdAt = priorParty?.createdAt ?: MemoryStore.nowIso(),
                        updatedAt = if (priorParty != null) MemoryStore.nowIso() else null
                    ).also { runOnUiThread { priorParty = it } }
                )
            } else {
                store.upsertRoleplayCharacter(
                    RoleplayCharacterRecord(
                        roleplayCharacterId = id,
                        name = name,
                        // Dormant pre-card columns (spec §8a): preserved
                        // untouched, never shown, never reused.
                        playedBy = priorCharacter?.playedBy ?: "user",
                        description = priorCharacter?.description ?: "",
                        arc = priorCharacter?.arc,
                        worldsPlayedJson = priorCharacter?.worldsPlayedJson ?: "[]",
                        status = priorCharacter?.status ?: "active",
                        createdAt = priorCharacter?.createdAt ?: MemoryStore.nowIso(),
                        species = text(fieldSpecies),
                        charClass = text(fieldClass),
                        corePersonality = text(fieldCorePersonality),
                        physicalDescription = text(fieldPhysicalDescription),
                        goalsDrives = text(fieldGoalsDrives),
                        // Preserve the assigned picture: this card has no
                        // picture control, so a save must carry the stored
                        // image_ref through rather than default it to null and
                        // wipe an image the record already had (e.g. imported).
                        imageRef = priorCharacter?.imageRef
                    ).also { runOnUiThread { priorCharacter = it } }
                )
            }
            runOnUiThread {
                cardId = id
                initialSnapshot = snapshot()
                Toast.makeText(this, R.string.card_saved, Toast.LENGTH_SHORT).show()
                refreshMemoriesButton()
                renderSections()
            }
        }
    }

    /* ------------------------------ Zone 2 sections ------------------------------ */

    private fun sectionKeys(): List<String> =
        CardSections.CHARACTER_SECTIONS + CardSections.NOTES

    private fun renderSections() {
        val container = sectionsContainer ?: return
        // Zone 2 always shows — the structure is never hidden, even before the
        // card is saved (owner ruling, July 10 2026).
        textSaveFirst?.visibility = View.GONE
        val id = cardId

        runOffThread {
            val store = MemoryStore.getInstance(this)
            val bySection = if (id != null)
                store.entriesForCard(cardType, id).groupBy { it.section }
            else emptyMap()
            // Each entry's own tags, read off-thread so the row can show them as
            // tappable links without a DB hit on the UI thread.
            val tagsByEntry = bySection.values.flatten()
                .associate { it.entryId to store.tagsForTarget(RpTagTargetType.CARD_ENTRY, it.entryId) }
            runOnUiThread {
                container.removeAllViews()
                val inflater = LayoutInflater.from(this)
                for (section in sectionKeys()) {
                    val block = inflater.inflate(R.layout.view_card_section, container, false)
                    block.findViewById<TextView>(R.id.section_title)
                        .setText(CardEntryEditorActivity.sectionLabelRes(section))

                    val list = block.findViewById<LinearLayout>(R.id.section_entries)
                    for (entry in bySection[section].orEmpty()) {
                        list.addView(entryRow(inflater, list, entry, tagsByEntry[entry.entryId].orEmpty()))
                    }

                    block.findViewById<MaterialButton>(R.id.btn_add_entry).setOnClickListener {
                        openEntryEditor(section, null)
                    }
                    container.addView(block)
                }
            }
        }
    }

    private fun entryRow(
        inflater: LayoutInflater,
        parent: LinearLayout,
        entry: CardEntryRecord,
        tags: List<RpTagRecord>
    ): View {
        val row = inflater.inflate(R.layout.view_card_entry, parent, false)
        row.findViewById<TextView>(R.id.entry_name).text = entry.name

        val descView = row.findViewById<TextView>(R.id.entry_desc)
        val parts = ArrayList<String>()
        entry.entryKind?.let { parts.add(getString(CardEntryEditorActivity.kindLabelRes(it))) }
        entry.quantity?.let { parts.add(getString(R.string.entry_quantity_fmt, it)) }
        if (parts.isEmpty()) entry.description?.lineSequence()?.firstOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        if (parts.isEmpty()) {
            descView.visibility = View.GONE
        } else {
            descView.visibility = View.VISIBLE
            descView.text = parts.joinToString(" · ")
        }

        CardEntryTags.render(this, row.findViewById(R.id.entry_tags), tags, chatId)

        row.setOnClickListener {
            openEntryEditor(entry.section, entry.entryId)
        }
        return row
    }

    private fun openEntryEditor(section: String, entryId: String?) {
        val id = cardId ?: run {
            Toast.makeText(this, R.string.card_save_first, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, CardEntryEditorActivity::class.java)
                .putExtra("chatId", chatId)
                .putExtra("cardType", cardType)
                .putExtra("cardId", id)
                .putExtra("section", section)
                .putExtra("entryId", entryId ?: "")
        )
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
            btnSave?.backgroundTintList = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
        } else {
            window.setBackgroundDrawable(SurfaceColors.SURFACE_0.getColor(this).toDrawable())
            if (Build.VERSION.SDK_INT <= 34) {
                window.navigationBarColor = SurfaceColors.SURFACE_0.getColor(this)
                window.statusBarColor = SurfaceColors.SURFACE_4.getColor(this)
            }
            actionBar?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
            btnBack?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
            btnSave?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
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
