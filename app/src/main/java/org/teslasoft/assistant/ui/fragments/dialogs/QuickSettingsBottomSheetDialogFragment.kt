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

package org.teslasoft.assistant.ui.fragments.dialogs

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.widget.addTextChangedListener
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ActivationPromptPreferences
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences
import org.teslasoft.assistant.preferences.LogitBiasConfigPreferences
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.SystemPromptsPreferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.preferences.dto.PersonaObject
import org.teslasoft.assistant.preferences.lorebook.LoreBookStore
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.UserPersonaRecord
import org.teslasoft.assistant.ui.activities.memory.MemoryUserPersonasActivity
import org.teslasoft.assistant.preferences.memory.CampaignRecord
import org.teslasoft.assistant.preferences.memory.CardEntryRecord
import org.teslasoft.assistant.preferences.memory.CardSections
import org.teslasoft.assistant.preferences.memory.CardType
import org.teslasoft.assistant.preferences.memory.ProjectRecord
import org.teslasoft.assistant.preferences.memory.RoleplayCharacterRecord
import org.teslasoft.assistant.preferences.memory.WorldRecord
import org.teslasoft.assistant.ui.activities.ActivationPromptsListActivity
import org.teslasoft.assistant.ui.activities.ApiEndpointsListActivity
import org.teslasoft.assistant.ui.activities.ChooseProviderActivity
import org.teslasoft.assistant.ui.activities.EditPersonaActivity
import org.teslasoft.assistant.ui.activities.LogitBiasConfigListActivity
import org.teslasoft.assistant.ui.activities.LoreBookEntriesActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.ui.activities.PersonasListActivity
import org.teslasoft.assistant.ui.activities.SystemPromptsListActivity
import org.teslasoft.assistant.ui.widgets.AppDropdown
import org.teslasoft.core.api.network.RequestNetwork

class QuickSettingsBottomSheetDialogFragment : BottomSheetDialogFragment() {
    companion object {
        fun newInstance(chatId: String, usageIn: Int, usageOut: Int, priceIn: Float, priceOut: Float): QuickSettingsBottomSheetDialogFragment {
            val quickSettingsBottomSheetDialogFragment = QuickSettingsBottomSheetDialogFragment()

            val args = Bundle()
            args.putString("chatId", chatId)
            args.putInt("usageIn", usageIn)
            args.putInt("usageOut", usageOut)
            args.putFloat("priceIn", priceIn)
            args.putFloat("priceOut", priceOut)
            quickSettingsBottomSheetDialogFragment.arguments = args

            return quickSettingsBottomSheetDialogFragment
        }
    }

    private var btnSelectModel: ConstraintLayout? = null
    private var btnSelectProvider: ConstraintLayout? = null
    private var textProvider: TextView? = null
    private var providerModeTile: ConstraintLayout? = null
    private var dropdownProviderMode: TextView? = null
    private var btnSelectSystemPrompt: ConstraintLayout? = null
    private var textSystemPrompt: TextView? = null
    private var rowReasoning: View? = null
    private var textReasoningEffort: TextView? = null
    private var systemPromptsPreferences: SystemPromptsPreferences? = null
    private var btnSelectLogitBias: ConstraintLayout? = null
    private var btnSelectApiEndpoint: ConstraintLayout? = null
    private var btnSelectPersona: ConstraintLayout? = null
    private var btnSelectActivation: ConstraintLayout? = null
    private var btnSelectLoreBook: ConstraintLayout? = null
    private var bgTemperature: ConstraintLayout? = null
    private var bgTopP: ConstraintLayout? = null
    private var bgFrequencyPenalty: ConstraintLayout? = null
    private var bgPresencePenalty: ConstraintLayout? = null
    private var apiEndpointPreferences: ApiEndpointPreferences? = null
    private var apiEndpoint: ApiEndpointObject? = null

    private var logitBiasConfigPreferences: LogitBiasConfigPreferences? = null

    private var personaPreferences: PersonaPreferences? = null
    private var textPersona: TextView? = null

    // Summoning Circle tiles (owner ruling, July 21 2026): each tile's value is
    // an inline dropdown; the edit button opens that category's manager. Glamour
    // is the new square middle tile (the chat's user persona). cachedUserPersonas
    // backs the Glamour dropdown - loaded off-main since it comes from the store.
    private var btnSelectGlamour: ConstraintLayout? = null
    private var textGlamour: TextView? = null
    private var btnEditPersona: ImageView? = null
    private var btnEditGlamour: ImageView? = null
    private var btnEditActivation: ImageView? = null
    private var btnEditSystemPrompt: ImageView? = null
    private var cachedUserPersonas: List<UserPersonaRecord> = emptyList()

    private var activationPromptPreferences: ActivationPromptPreferences? = null
    private var textActivation: TextView? = null

    private var textLoreBook: TextView? = null
    private var lorebookCheckList: LinearLayout? = null

    private var temperatureSeekbar: com.google.android.material.slider.Slider? = null
    private var topPSeekbar: com.google.android.material.slider.Slider? = null
    private var frequencyPenaltySeekbar: com.google.android.material.slider.Slider? = null
    private var presencePenaltySeekbar: com.google.android.material.slider.Slider? = null
    private var fieldSeed: TextInputEditText? = null
    private var btnSaveToProfile: MaterialButton? = null
    private var switchChatMemory: com.google.android.material.materialswitch.MaterialSwitch? = null
    private var switchChatExcluded: com.google.android.material.materialswitch.MaterialSwitch? = null
    // Per-chat lore books on/off, independent of the memory switch. QUICK
    // SETTINGS IS AUTHORITATIVE (owner ruling, July 10 2026): these two
    // switches decide what this chat injects; the global Memory engine picker
    // only supplies defaults for chats that never touched them.
    private var switchChatLoreBooks: com.google.android.material.materialswitch.MaterialSwitch? = null

    // Memory system Phase 4: per-chat scene (world / roleplay character / user
    // persona). Only shown once the chat's memory switch is on and the store
    // exists — before that there is nothing meaningful to pick from.
    private var containerMemoryScene: LinearLayout? = null
    private var textChatWorld: TextView? = null
    private var textChatCampaign: TextView? = null
    private var textChatRoleplayCharacter: TextView? = null
    private var textChatProject: TextView? = null

    // Model rules (owner_approved_rules §11 Revision 6): the per-chat
    // "Apply Model Rules" toggle. NOT part of the memory-scene container —
    // model rules are their own prompt layer and apply at any memory-engine
    // tier. Follows the global "Automatically Apply Model Rules" default (on);
    // flipping it overrides that for this chat only.
    private var rowChatModelRules: LinearLayout? = null
    private var switchChatModelRules: com.google.android.material.materialswitch.MaterialSwitch? = null

    // Conversation summarizer (conversation-summary-plan.md decisions 3 + 8):
    // the whole card stays hidden until a summarizer endpoint/model exists, so
    // the feature can never be switched on in a broken state. The toggle IS
    // the pause control; the number box appears only while it is on.
    private var cardSummarizer: ConstraintLayout? = null
    private var rowSummarizerWindow: LinearLayout? = null
    private var switchUseSummarizer: com.google.android.material.materialswitch.MaterialSwitch? = null
    private var fieldSummarizerWindow: TextInputEditText? = null
    private var suppressSummarizerWindowWatcher = false

    private var textUsage: TextView? = null
    private var textCost: TextView? = null
    private var btnCostInfo: MaterialButton? = null
    private var textModel: TextView? = null
    private var textHost: TextView? = null
    private var textLogitBiasesConfig: TextView? = null
    private var favoriteModelsPreferences: FavoriteModelsPreferences? = null
    private var usageCost: ConstraintLayout? = null

    private var preferences: Preferences? = null
    private var chatId: String = ""

    private var updateListener: OnUpdateListener? = null
    private var shouldForceUpdate: Boolean = false

    private var priceIn = 0.0f
    private var priceOut = 0.0f
    private var usageIn = 0
    private var usageOut = 0

    private var isAttached = false

    // Cached from a background load (see loadMemorySceneLists) so the picker
    // dialogs don't touch the encrypted store from the click handler.
    private var cachedWorlds: List<WorldRecord> = emptyList()
    private var cachedCampaigns: List<CampaignRecord> = emptyList()
    private var cachedRoleplayCharacters: List<RoleplayCharacterRecord> = emptyList()
    private var cachedProjects: List<ProjectRecord> = emptyList()

    // Name lookups over ALL worlds/characters (not just active ones) so a
    // selected campaign's links still display when the linked card is
    // dormant or archived (3.6c).
    private var worldNames: Map<String, String> = emptyMap()
    private var roleplayCharacterNames: Map<String, String> = emptyMap()

    private var requestNetwork: RequestNetwork? = null

    private var requestListener: RequestNetwork.RequestListener = object : RequestNetwork.RequestListener {
        override fun onResponse(tag: String, message: String) {
            val gson = com.google.gson.Gson()

            try {
                val models: Map<String, Any> = gson.fromJson(message, Map::class.java) as Map<String, Any>

                var modelsList: List<Map<String, Any>> = models["data"] as ArrayList<Map<String, Any>>

                if (modelsList == null) modelsList = arrayListOf()

                for (model in modelsList) {
                    val m = model.toMap()
                    if (preferences?.getModel() == m["id"]) {
                        priceIn = (m["pricing"] as Map<String, Any>)["prompt"].toString().toFloat()
                        priceOut = (m["pricing"] as Map<String, Any>)["completion"].toString().toFloat()
                        val costIn = priceIn * usageIn
                        val costOut = priceOut * usageOut
                        val costTotal = costIn + costOut
                        textCost?.text = String.format(getString(R.string.cost_template), costIn, costOut, costTotal, priceIn * 1000000, priceOut * 1000000)
                        break
                    }
                }
            } catch (_: Exception) {
                performStaticCostParse(preferences?.getModel()!!)
            }
        }

        override fun onErrorResponse(tag: String, message: String) {
            textCost?.text = getString(R.string.msg_error_calculating_cost)
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)

        isAttached = true
    }

    override fun onDetach() {
        super.onDetach()

        isAttached = false
    }

    private var logitBiasesActivityResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data: Intent? = result.data
            val configId = data?.getStringExtra("configId")

            if (configId != null) {
                preferences?.setLogitBiasesConfigId(configId)
                textLogitBiasesConfig?.text = if (configId != ""){
                    logitBiasConfigPreferences?.getConfigById(configId)?.get("label") ?: getString(R.string.label_tap_to_set)
                } else {
                    getString(R.string.label_tap_to_set)
                }
                shouldForceUpdate = true
            }
        }
    }

    private var apiEndpointActivityResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data: Intent? = result.data
            val apiEndpointId = data?.getStringExtra("apiEndpointId")

            if (apiEndpointId != null) {
                preferences?.setApiEndpointId(apiEndpointId)
                apiEndpoint = apiEndpointPreferences?.getApiEndpoint(requireContext(), apiEndpointId)
                textHost?.text = if (apiEndpoint?.label != "") apiEndpoint?.label ?: getString(R.string.label_tap_to_set) else getString(R.string.label_tap_to_set)
                shouldForceUpdate = true
                // Provider Mode is OpenRouter-only and reflects the active
                // model on this endpoint, so re-evaluate it when the endpoint
                // changes.
                refreshProviderModeTile()
                setupReasoningTile()
            }
        }
    }

    /** Return from the Choose Provider screen opened by the Provider Mode
     *  set-up dialog. That screen persists the favorite itself, so just
     *  re-sync the dropdown to whatever routing is now stored (a save applies
     *  the new mode; a cancel leaves the old one, reverting the dropdown). */
    private val chooseProviderLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!isAdded) return@registerForActivityResult
        // The provider screen may have switched to a different model. Adopt it as
        // the chat's model so the model AND its just-saved routing become active,
        // regardless of which model was selected when the screen was opened.
        val savedModel = result.data?.getStringExtra(ChooseProviderActivity.EXTRA_MODEL)
        if (!savedModel.isNullOrBlank() && savedModel != preferences?.getModel()) {
            preferences?.setModel(savedModel)
            textModel?.text = savedModel
        }
        updateListener?.onUpdate()
        shouldForceUpdate = true
        refreshProviderModeDisplay()
        setupReasoningTile()
    }

    private var personaActivityResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data: Intent? = result.data
            val personaId = data?.getStringExtra("personaId")

            if (personaId != null) {
                preferences?.setPersonaId(personaId)
                updatePersonaLabel(personaId)
                renderLoreBookList()
                shouldForceUpdate = true
            }
        }
    }

    private fun updatePersonaLabel(personaId: String) {
        // Empty id — or an id whose persona was deleted — reads as "none" rather
        // than "Tap to set", so a chat with no persona says so explicitly.
        textPersona?.text = if (personaId != "") {
            val label = personaPreferences?.getPersona(personaId)?.label ?: ""
            if (label != "") label else getString(R.string.label_persona_none)
        } else {
            getString(R.string.label_persona_none)
        }
    }

    private var activationActivityResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data: Intent? = result.data
            val activationPromptId = data?.getStringExtra("activationPromptId")

            if (activationPromptId != null) {
                preferences?.setActivationPromptId(activationPromptId)
                // Remember this as the global default for the next new chat
                // (recorded even when it's "none", so the latest choice wins).
                preferences?.setLastUsedActivationPromptId(activationPromptId)
                // Keep the existing chat-activation flow working: the selected
                // prompt text is what actually gets sent as the first message.
                val prompt = if (activationPromptId != "") {
                    activationPromptPreferences?.getActivationPrompt(activationPromptId)?.prompt ?: ""
                } else ""
                preferences?.setPrompt(prompt)
                updateActivationLabel(activationPromptId)
                shouldForceUpdate = true
            }
        }
    }

    private fun updateActivationLabel(activationPromptId: String) {
        // Empty id — or an id whose activation prompt was deleted — reads as
        // "None" rather than "Tap to set", so "no activation" is explicit.
        textActivation?.text = if (activationPromptId != "") {
            val label = activationPromptPreferences?.getActivationPrompt(activationPromptId)?.label ?: ""
            if (label != "") label else getString(R.string.label_activation_none)
        } else {
            getString(R.string.label_activation_none)
        }
    }

    /**
     * Rebuild the lorebook checklist for this chat. With a persona selected the
     * list offers the persona's linked additional lorebooks (the core book is
     * never listed — it is always active — but is named in the subtitle).
     * Without a persona the whole collection is offered. Checking/unchecking
     * persists immediately, so the selection can vary mid-conversation, and is
     * also recorded on the persona as its "last used" set for auto-load.
     */
    private fun renderLoreBookList() {
        val container = lorebookCheckList ?: return
        container.removeAllViews()

        // Build Phase 3 degraded gate: with the lorebook store refused
        // (confirmed damage), the checklist degrades to a persistent inline
        // line instead of crashing the whole sheet — the chat's other
        // settings stay reachable; the A2 banner owns the repair route.
        val store = try {
            LoreBookStore.getInstance(requireContext())
        } catch (_: Exception) {
            textLoreBook?.text = getString(R.string.health_screen_blocked_lorebook)
            return
        }
        val personaId = preferences?.getPersonaId() ?: ""
        val persona = if (personaId != "") personaPreferences?.getPersona(personaId) else null

        val coreBookName = persona?.coreLoreBookId?.takeIf { it.isNotEmpty() }?.let { store.getBook(it)?.name }
        textLoreBook?.text = if (coreBookName != null) {
            getString(R.string.lorebook_core_always_active, coreBookName)
        } else {
            getString(R.string.lorebook_subtitle)
        }

        val offeredBooks = if (persona != null) {
            // The core book is always active, so it never appears as a checkbox —
            // even if it was also linked as an additional book.
            persona.additionalLoreBookIdList()
                .filter { it != persona.coreLoreBookId }
                .mapNotNull { store.getBook(it) }
        } else {
            store.getAllBooks()
        }

        // Prune checked ids that are no longer offered (book deleted/unlinked).
        val offeredIds = offeredBooks.map { it.id }
        val activeIds = LinkedHashSet((preferences?.getActiveLoreBookIds() ?: arrayListOf()).filter { offeredIds.contains(it) })

        if (offeredBooks.isEmpty()) {
            val empty = TextView(requireContext())
            empty.text = getString(
                if (persona != null) R.string.lorebook_none_linked else R.string.lorebook_none_yet
            )
            empty.setTextColor(resources.getColor(R.color.text_subtitle, requireContext().theme))
            empty.textSize = 13f
            empty.setPadding(24, 8, 24, 16)
            container.addView(empty)
            return
        }

        for (book in offeredBooks) {
            val row = layoutInflater.inflate(R.layout.view_lorebook_check_row, container, false)

            row.findViewById<TextView>(R.id.row_name)?.text = book.name

            val description = row.findViewById<TextView>(R.id.row_description)
            if (book.description.isBlank()) {
                description?.visibility = View.GONE
            } else {
                description?.visibility = View.VISIBLE
                description?.text = book.description
            }

            val check = row.findViewById<MaterialCheckBox>(R.id.row_check)
            check?.isChecked = activeIds.contains(book.id)
            check?.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) activeIds.add(book.id) else activeIds.remove(book.id)
                preferences?.setActiveLoreBookIds(activeIds.toList())
                if (persona != null) {
                    // Remember for "auto-enable last-used lorebooks" on new chats.
                    personaPreferences?.setLastUsedLoreBookIds(personaId, PersonaObject.joinIds(activeIds.toList()))
                }
            }
            // Tapping anywhere on the row toggles, not just the small box.
            row.setOnClickListener { check?.isChecked = check?.isChecked != true }

            row.findViewById<ImageButton>(R.id.row_btn_edit)?.setOnClickListener {
                // Checks persist as they are made, so nothing is lost by leaving.
                val intent = Intent(requireContext(), LoreBookEntriesActivity::class.java)
                intent.putExtra("lorebookId", book.id)
                intent.putExtra("lorebookName", book.name)
                startActivity(intent)
            }

            container.addView(row)
        }
    }

    // Returning from the system prompt library (pick mode): the chosen prompt is
    // already recorded and mirrored into the global system message by the list
    // screen, so we just refresh the label and force the chat to reload.
    private var systemPromptActivityResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            updateSystemPromptLabel()
            shouldForceUpdate = true
            updateListener?.onUpdate()
        }
    }

    private fun updateSystemPromptLabel() {
        val effective = systemPromptsPreferences?.getEffectivePrompt()
        textSystemPrompt?.text = effective?.title ?: getString(R.string.system_prompt_none)
    }

    // The tile edit buttons open a category's manager screen (Companions /
    // Glamour Studio / Activation prompts / System prompts). Editing there can
    // rename/add/remove items, so on return every tile's label is re-resolved
    // and the Glamour list reloaded - cheaply, from prefs, plus one off-main
    // store read for glamours. Selection itself is done by the dropdowns, not
    // here, so this never changes what's chosen.
    private val managerRefreshLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!isAdded) return@registerForActivityResult
        updatePersonaLabel(preferences?.getPersonaId() ?: "")
        updateActivationLabel(preferences?.getActivationPromptId() ?: "")
        updateSystemPromptLabel()
        loadUserPersonasForGlamour()
    }

    // The Companion tile's edit button goes straight to the active companion's
    // own editor (owner ruling, Aug 16 2026 - it previously opened the general
    // Companions list instead). Save/delete handling mirrors
    // PersonasListActivity.editPersonaLauncher exactly, since this bypasses
    // that screen and must persist the result itself.
    private val editActivePersonaLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!isAdded) return@registerForActivityResult
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        when (data.getStringExtra(EditPersonaActivity.EXTRA_RESULT_ACTION)) {
            EditPersonaActivity.ACTION_SAVE -> {
                val persona = EditPersonaActivity.readResultPersona(data)
                personaPreferences?.setPersona(persona)
                updatePersonaLabel(preferences?.getPersonaId() ?: "")
            }
            EditPersonaActivity.ACTION_DELETE -> {
                val id = data.getStringExtra(EditPersonaActivity.EXTRA_RESULT_ID)
                if (id != null) {
                    personaPreferences?.deletePersona(id)
                    updatePersonaLabel(preferences?.getPersonaId() ?: "")
                    // Deleting from this shortcut always deletes the chat's
                    // own active companion (that's the one it was launched
                    // for), so the chat is now left pointing at an id that no
                    // longer exists - resolve that before leaving the user
                    // here with an invalid selection (owner spec, Aug 16 2026).
                    recoverFromMissingActiveCompanion()
                }
            }
        }
    }

    // Launches PersonasListActivity's createOnStart flow when no companions
    // remain to choose from - the exact same mechanism
    // ChatActivity.createFirstCompanionLauncher already uses for "a chat has
    // no companion yet", reused here rather than calling EditPersonaActivity
    // directly a second way. On success PersonasListActivity.finishWithActive
    // returns the new companion's id via the "personaId" extra.
    private val recoverCompanionCreationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!isAdded) return@registerForActivityResult
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val personaId = result.data?.getStringExtra("personaId")
        if (!personaId.isNullOrEmpty()) {
            preferences?.setPersonaId(personaId)
            updatePersonaLabel(personaId)
            renderLoreBookList()
            shouldForceUpdate = true
        }
    }

    /**
     * Required recovery when the chat's active companion no longer exists.
     * Never leaves that state unresolved: if other companions exist, a
     * non-dismissible picker forces an explicit choice; if none remain, the
     * app's existing "create the first companion" flow opens directly. Both
     * paths reuse the app's normal companion selection/creation plumbing -
     * this only detects the gap and forces it to be filled.
     */
    private fun recoverFromMissingActiveCompanion() {
        if (!isAdded) return
        val activeId = preferences?.getPersonaId() ?: ""
        val companions = personaPreferences?.getPersonasList() ?: emptyList()
        val activeStillExists = activeId != "" && companions.any { it.id == activeId }
        if (activeStillExists) return

        if (companions.isNotEmpty()) {
            showRequiredCompanionSelectionDialog(companions)
        } else {
            recoverCompanionCreationLauncher.launch(
                Intent(requireContext(), PersonasListActivity::class.java)
                    .putExtra("createOnStart", true)
            )
        }
    }

    /**
     * Non-dismissible companion picker (owner spec, Aug 16 2026): the chat
     * has no valid companion until one is explicitly chosen, so there is no
     * back/outside-tap/skip escape. Uses the same canonical dropdown control
     * and AppDropdown behavior as the inline Companion tile, just presented
     * modally since this choice is required rather than optional, and the
     * same companion list/selection plumbing as [showCompanionDropdown].
     */
    private fun showRequiredCompanionSelectionDialog(companions: List<PersonaObject>) {
        val context = requireContext()
        val labels = companions.map { it.label }
        val density = resources.displayMetrics.density

        val dropdown = TextView(context, null, 0, R.style.Widget_App_Dropdown_CanonicalValue)
        dropdown.text = getString(R.string.qs_companion_deleted_dropdown_placeholder)
        dropdown.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val padding = (24 * density).toInt()
        val wrapper = LinearLayout(context)
        wrapper.orientation = LinearLayout.VERTICAL
        wrapper.setPadding(padding, padding, padding, padding)
        wrapper.addView(dropdown)

        val dialog = MaterialAlertDialogBuilder(context, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.qs_companion_deleted_dialog_title)
            .setView(wrapper)
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)

        AppDropdown.sizeToOptions(dropdown, labels) {
            resources.displayMetrics.widthPixels - padding * 2 - (48 * density).toInt()
        }

        dropdown.setOnClickListener {
            AppDropdown.show(dropdown, labels, selectedIndex = -1) { position ->
                val chosen = companions[position]
                preferences?.setPersonaId(chosen.id)
                updatePersonaLabel(chosen.id)
                renderLoreBookList()
                shouldForceUpdate = true
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    /* ------------------------------ Summoning Circle dropdowns ------------------------------ */

    /** Canonical anchored dropdown shared with Provider Mode. */
    private fun showTileDropdown(anchor: View, labels: List<String>, onPick: (Int) -> Unit) {
        val dropdown = anchor as? TextView ?: return
        AppDropdown.show(dropdown, labels, onPick = onPick)
    }

    /** Companion has no None (a chat always has a companion). */
    private fun showCompanionDropdown(anchor: View) {
        val personas = personaPreferences?.getPersonasList() ?: return
        val labels = personas.map { it.label }
        showTileDropdown(anchor, labels) { position ->
            val id = personas[position].id
            preferences?.setPersonaId(id)
            updatePersonaLabel(id)
            renderLoreBookList()
            shouldForceUpdate = true
        }
    }

    /** Glamour = the chat's user persona; None is the first entry. */
    private fun showGlamourDropdown(anchor: View) {
        val labels = listOf(getString(R.string.label_user_persona_none)) + cachedUserPersonas.map { it.name }
        showTileDropdown(anchor, labels) { position ->
            val id = if (position == 0) "" else cachedUserPersonas[position - 1].personaId
            preferences?.setChatUserPersonaId(id)
            updateGlamourLabel()
            shouldForceUpdate = true
        }
    }

    private fun showActivationDropdown(anchor: View) {
        val prompts = activationPromptPreferences?.getActivationPromptsList() ?: return
        val labels = listOf(getString(R.string.label_activation_none)) + prompts.map { it.label }
        showTileDropdown(anchor, labels) { position ->
            val id = if (position == 0) "" else prompts[position - 1].id
            preferences?.setActivationPromptId(id)
            preferences?.setLastUsedActivationPromptId(id)
            val prompt = if (id != "") activationPromptPreferences?.getActivationPrompt(id)?.prompt ?: "" else ""
            preferences?.setPrompt(prompt)
            updateActivationLabel(id)
            shouldForceUpdate = true
        }
    }

    private fun showSystemPromptDropdown(anchor: View) {
        val prompts = systemPromptsPreferences?.getSystemPrompts() ?: return
        val labels = prompts.map { it.title }
        showTileDropdown(anchor, labels) { position ->
            systemPromptsPreferences?.setSelectedId(prompts[position].id)
            preferences?.let { systemPromptsPreferences?.applyEffectiveToGlobal(it) }
            updateSystemPromptLabel()
            shouldForceUpdate = true
            updateListener?.onUpdate()
        }
    }

    /**
     * The reasoning capability of the chat's current model/endpoint, resolved
     * through the §7.7 ladder over the endpoint's persisted capability store.
     */
    private fun currentReasoningCapability(): org.teslasoft.assistant.reasoning.ReasoningCapability {
        val endpointId = preferences?.getApiEndpointId().orEmpty()
        val model = preferences?.getModel().orEmpty()
        if (model.isBlank()) return org.teslasoft.assistant.reasoning.ReasoningCapability.UNKNOWN
        val endpoint = apiEndpointPreferences?.getApiEndpoint(requireContext(), endpointId)
        return org.teslasoft.assistant.reasoning.EndpointReasoningCapability.resolve(
            endpoint?.reasoningCapabilityByModel, model
        )
    }

    /**
     * Quick Settings Thinking control (chat-redesign-plan.md §7.5). Shown only
     * when the active model/provider exposes configurable reasoning; its value
     * is the conversation's effective effort, and picking a level persists a
     * per-conversation override without rewriting the favorite.
     */
    private fun setupReasoningTile() {
        val row = rowReasoning ?: return
        val capability = currentReasoningCapability()
        if (!capability.effortConfigurable) {
            row.visibility = View.GONE
            row.setOnClickListener(null)
            textReasoningEffort?.setOnClickListener(null)
            return
        }
        row.visibility = View.VISIBLE
        refreshReasoningEffortLabel(capability)
        row.setOnClickListener { showReasoningEffortDropdown(textReasoningEffort ?: it, capability) }
        textReasoningEffort?.setOnClickListener { showReasoningEffortDropdown(it, capability) }
    }

    /** The effort this conversation would send now: its own override, else the
     *  favorite default, else Auto — clamped to what the path supports (§7.9). */
    private fun effectiveReasoningEffort(
        capability: org.teslasoft.assistant.reasoning.ReasoningCapability
    ): org.teslasoft.assistant.reasoning.ReasoningEffort {
        val endpointId = preferences?.getApiEndpointId().orEmpty()
        val model = preferences?.getModel().orEmpty()
        val favorite = favoriteModelsPreferences?.getFavorite(model, endpointId)
        val favoriteEffort = favorite?.let {
            org.teslasoft.assistant.reasoning.ReasoningEffort.fromSerialized(it.reasoningEffort)
        }
        val override = org.teslasoft.assistant.reasoning.ReasoningEffort.fromSerialized(
            preferences?.getReasoningEffortOverride()
        )
        return org.teslasoft.assistant.reasoning.ReasoningSettingsResolver.resolve(
            override, favoriteEffort, favorite?.showReasoning, capability
        ).effort
    }

    private fun refreshReasoningEffortLabel(
        capability: org.teslasoft.assistant.reasoning.ReasoningCapability
    ) {
        textReasoningEffort?.text = org.teslasoft.assistant.ui.reasoning.ReasoningEffortLabels.label(
            requireContext(), effectiveReasoningEffort(capability)
        )
    }

    private fun showReasoningEffortDropdown(
        anchor: View,
        capability: org.teslasoft.assistant.reasoning.ReasoningCapability
    ) {
        val choices = capability.thinkingChoices()
        if (choices.isEmpty()) return
        val labels = choices.map {
            org.teslasoft.assistant.ui.reasoning.ReasoningEffortLabels.label(requireContext(), it)
        }
        showTileDropdown(anchor, labels) { position ->
            // The conversation owns and persists its override (§7.5); Auto is a
            // real persisted choice, not "inherit the favorite".
            preferences?.setReasoningEffortOverride(choices[position].serialized)
            refreshReasoningEffortLabel(capability)
            shouldForceUpdate = true
        }
    }

    private fun updateGlamourLabel() {
        val id = preferences?.getChatUserPersonaId().orEmpty()
        textGlamour?.text = if (id.isEmpty()) {
            getString(R.string.label_user_persona_none)
        } else {
            cachedUserPersonas.firstOrNull { it.personaId == id }?.name
                ?: getString(R.string.label_user_persona_none)
        }
    }

    /** Loads the user personas that back the Glamour dropdown off the main
     *  thread (they come from the SQLCipher store), then refreshes the label. */
    private fun loadUserPersonasForGlamour() {
        val appContext = context?.applicationContext ?: return
        Thread {
            val personas = try {
                if (MemoryStore.isProvisioned(appContext)) MemoryStore.getInstance(appContext).getActiveUserPersonas()
                else emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                cachedUserPersonas = personas
                updateGlamourLabel()
            }
        }.start()
    }

    private var modelSelectedListener: AdvancedModelSelectorDialogFragment.OnModelSelectedListener = AdvancedModelSelectorDialogFragment.OnModelSelectedListener { model ->
        preferences?.setModel(model)
        updateListener?.onUpdate()
        shouldForceUpdate = true
        textModel?.text = model
        // The Provider Mode dropdown reflects the active model's routing.
        refreshProviderModeDisplay()
        // Reasoning controls are keyed to the model/provider path (§7.9).
        setupReasoningTile()
    }

    private var modelSelectedListenerV2: AdvancedFavoriteModelSelectorDialogFragment.OnModelSelectedListener = AdvancedFavoriteModelSelectorDialogFragment.OnModelSelectedListener { model, endpointId ->
        preferences?.setApiEndpointId(endpointId)
        android.widget.Toast.makeText(requireContext(), R.string.msg_api_compatibility_change, android.widget.Toast.LENGTH_SHORT).show()
        preferences?.setModel(model)
        updateListener?.onUpdate()
        shouldForceUpdate = true
        textModel?.text = model
        // Model and endpoint may both have changed; re-evaluate the tile.
        refreshProviderModeTile()
        setupReasoningTile()
    }

    /* ------------------------------ Provider Mode (OpenRouter) ------------------------------ */

    private val providerModeOrder = arrayOf(
        FavoriteModelObject.ROUTING_AUTOMATIC,
        FavoriteModelObject.ROUTING_PREFERRED,
        FavoriteModelObject.ROUTING_ONLY
    )

    private fun providerModeLabel(mode: String): String = when (mode) {
        FavoriteModelObject.ROUTING_PREFERRED -> getString(R.string.choose_provider_routing_preferred)
        FavoriteModelObject.ROUTING_ONLY -> getString(R.string.choose_provider_routing_only)
        else -> getString(R.string.choose_provider_routing_automatic)
    }

    private fun setupProviderModeDropdown() {
        val dropdown = dropdownProviderMode ?: return
        val labels = providerModeOrder.map { providerModeLabel(it) }
        dropdown.setOnClickListener {
            AppDropdown.show(dropdown, labels) { position ->
                onProviderModePicked(providerModeOrder[position])
            }
        }
    }

    /** Show the Provider Mode tile only for an OpenRouter endpoint (host-based,
     *  never the profile name), and sync the dropdown to the active model. */
    private fun refreshProviderModeTile() {
        val endpointId = preferences?.getApiEndpointId() ?: ""
        val endpoint = if (endpointId.isBlank()) null
            else apiEndpointPreferences?.getApiEndpoint(requireContext(), endpointId)
        val isOpenRouter = endpoint != null && endpoint.isOpenRouterRouting()
        btnSelectProvider?.visibility = if (isOpenRouter) View.VISIBLE else View.GONE
        providerModeTile?.visibility = if (isOpenRouter) View.VISIBLE else View.GONE
        if (isOpenRouter) {
            refreshProviderModeDisplay()
        }
    }

    /** Set the dropdown to the active model's stored routing mode. setText with
     *  filter = false does not fire the selection handler, so this never loops. */
    private fun refreshProviderModeDisplay() {
        val endpointId = preferences?.getApiEndpointId() ?: return
        val model = preferences?.getModel() ?: return
        val mode = favoriteModelsPreferences?.getRoutingType(model, endpointId)
            ?: FavoriteModelObject.ROUTING_AUTOMATIC
        dropdownProviderMode?.text = providerModeLabel(mode)
        refreshProviderDisplay()
    }

    /** Show the active model's saved provider identity. The favorite store
     * keeps provider slugs, so this row uses that exact persisted identity and
     * does not invent a display name or make a second provider-discovery call. */
    private fun refreshProviderDisplay() {
        val endpointId = preferences?.getApiEndpointId() ?: return
        val model = preferences?.getModel() ?: return
        val favorite = favoriteModelsPreferences?.getFavorite(model, endpointId)
        textProvider?.text = QuickSettingsProviderDisplay.label(
            favorite,
            getString(R.string.choose_provider_routing_automatic)
        )
    }

    private fun onProviderModePicked(mode: String) {
        val endpointId = preferences?.getApiEndpointId() ?: return
        val model = preferences?.getModel() ?: return
        if (model.isBlank()) return
        val favorite = favoriteModelsPreferences?.getFavorite(model, endpointId)
        val needsSetup = when (mode) {
            FavoriteModelObject.ROUTING_PREFERRED -> favorite == null || favorite.providerOrder.isEmpty()
            FavoriteModelObject.ROUTING_ONLY -> favorite == null || favorite.selectedProvider.isBlank()
            else -> false
        }
        if (needsSetup) showProviderModeSetupDialog(mode) else applyProviderMode(mode)
    }

    /** Apply a mode that needs no new setup: Automatic always, or Preferred/Only
     *  when the model already has the providers it needs. Writes straight to the
     *  favorite (the single source of truth). Automatic on a model that isn't a
     *  favorite stays the default and stores nothing. */
    private fun applyProviderMode(mode: String) {
        val endpointId = preferences?.getApiEndpointId() ?: return
        val model = preferences?.getModel() ?: return
        if (model.isBlank()) return
        val existing = favoriteModelsPreferences?.getFavorite(model, endpointId)
        if (!(mode == FavoriteModelObject.ROUTING_AUTOMATIC && existing == null)) {
            val favorite = existing ?: FavoriteModelObject(model, endpointId)
            favorite.routingType = mode
            favoriteModelsPreferences?.addFavoriteModel(favorite)
        }
        updateListener?.onUpdate()
        shouldForceUpdate = true
        refreshProviderModeDisplay()
    }

    /** Preferred/Only picked but not set up yet: confirm before leaving Quick
     *  Settings. Set up opens the Choose Provider screen for the active model
     *  with the chosen mode preselected; Cancel or dismiss reverts the dropdown
     *  to the stored mode. */
    private fun showProviderModeSetupDialog(mode: String) {
        var launched = false
        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.App_MaterialAlertDialog)
            .setTitle(R.string.provider_mode_setup_title)
            .setMessage(R.string.provider_mode_setup_message)
            .setPositiveButton(R.string.provider_mode_setup_confirm) { _, _ ->
                val endpointId = preferences?.getApiEndpointId() ?: return@setPositiveButton
                val model = preferences?.getModel() ?: return@setPositiveButton
                val prefs = apiEndpointPreferences ?: return@setPositiveButton
                val favPrefs = favoriteModelsPreferences ?: return@setPositiveButton
                FavoriteRoutingActions.buildRoutingIntent(requireContext(), prefs, favPrefs, model, endpointId, mode, keepRoutingOnModelChange = true)?.let {
                    launched = true
                    chooseProviderLauncher.launch(it)
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
        dialog.setOnDismissListener { if (!launched) refreshProviderModeDisplay() }
        dialog.show()
    }

    @SuppressLint("SetTextI18n", "DefaultLocale")
    private fun performStaticCostParse(model: String): HashMap<String, Float> {
        var inPrice = 0.0
        var outPrice = 0.0

        when {

            model.contains("gpt-4o-audio-preview") || model.contains("gpt-4o-audio-preview-2024-12-17") || model.contains("gpt-4o-audio-preview-2024-10-01") -> {
                inPrice = 0.000025
                outPrice = 0.0001
            }
            model.contains("gpt-4o-realtime-preview") || model.contains("gpt-4o-realtime-preview-2024-12-17") || model.contains("gpt-4o-realtime-preview-2024-10-01") -> {
                inPrice = 0.00005
                outPrice = 0.0002
            }
            model.contains("gpt-4o-mini-audio-preview") || model.contains("gpt-4o-mini-audio-preview-2024-12-17") -> {
                inPrice = 0.0000015
                outPrice = 0.000006
            }
            model.contains("gpt-4o-mini-realtime-preview") || model.contains("gpt-4o-mini-realtime-preview-2024-12-17") -> {
                inPrice = 0.000006
                outPrice = 0.000024
            }
            model.contains("gpt-4o-mini") || model.contains("gpt-4o-mini-2024-07-18") -> {
                inPrice = 0.0000015
                outPrice = 0.000006
            }
            model.contains("gpt-4o") || model.contains("gpt-4o-2024-11-20") || model.contains("gpt-4o-2024-08-06") || model.contains("gpt-4o-2024-05-13") -> {
                inPrice = 0.000025
                outPrice = 0.0001
            }
            model.contains("o1-mini") || model.contains("o1-mini-2024-09-12") -> {
                inPrice = 0.000011
                outPrice = 0.000044
            }
            model.contains("o1") || model.contains("o1-2024-12-17") -> {
                inPrice = 0.00015
                outPrice = 0.0006
            }
            model.contains("o3-mini") || model.contains("o3-mini-2025-01-31") -> {
                inPrice = 0.000011
                outPrice = 0.000044
            }
        }

        if (isAttached) {
            if (inPrice == 0.0 && outPrice == 0.0) {
                textCost?.text = getString(R.string.msg_cost_not_enough_data)
            } else {
                val costIn = inPrice * usageIn
                val costOut = outPrice * usageOut
                val costTotal = costIn + costOut
                textCost?.text = String.format(getString(R.string.cost_template), costIn, costOut, costTotal, inPrice * 1000000, outPrice * 1000000)
            }
        }
        return hashMapOf()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

        if (shouldForceUpdate) {
            updateListener?.onForceUpdate()
        }
    }

    fun setOnUpdateListener(listener: OnUpdateListener) {
        updateListener = listener
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the gear (entries editor) or the persona screen may
        // have changed books, links, or names — rebuild the checklist.
        if (lorebookCheckList != null) renderLoreBookList()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_quick_settings, container, false)
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        chatId = requireArguments().getString("chatId")!!
        preferences = Preferences.getPreferences(requireContext(), chatId)
        apiEndpointPreferences = ApiEndpointPreferences.getApiEndpointPreferences(requireContext())
        apiEndpoint = apiEndpointPreferences?.getApiEndpoint(requireContext(), preferences?.getApiEndpointId()!!)
        logitBiasConfigPreferences = LogitBiasConfigPreferences.getLogitBiasConfigPreferences(requireContext())
        favoriteModelsPreferences = FavoriteModelsPreferences.getPreferences(requireContext())
        personaPreferences = PersonaPreferences.getPersonaPreferences(requireContext())
        activationPromptPreferences = ActivationPromptPreferences.getActivationPromptPreferences(requireContext())
        systemPromptsPreferences = SystemPromptsPreferences.getSystemPromptsPreferences(requireContext())

        btnSelectModel = view.findViewById(R.id.btn_select_model)
        btnSelectProvider = view.findViewById(R.id.btn_select_provider)
        textProvider = view.findViewById(R.id.text_provider)
        providerModeTile = view.findViewById(R.id.provider_mode_tile)
        dropdownProviderMode = view.findViewById(R.id.dropdown_provider_mode)
        btnSelectSystemPrompt = view.findViewById(R.id.btn_select_system_prompt)
        textSystemPrompt = view.findViewById(R.id.text_system_prompt)
        rowReasoning = view.findViewById(R.id.row_reasoning)
        textReasoningEffort = view.findViewById(R.id.text_reasoning_effort)
        btnSelectLogitBias = view.findViewById(R.id.btn_set_logit_biases)
        btnSelectApiEndpoint = view.findViewById(R.id.btn_select_api_endpoint)
        btnSelectPersona = view.findViewById(R.id.btn_select_persona)
        textPersona = view.findViewById(R.id.text_persona)
        btnSelectGlamour = view.findViewById(R.id.btn_select_glamour)
        textGlamour = view.findViewById(R.id.text_glamour)
        btnSelectActivation = view.findViewById(R.id.btn_select_activation)
        textActivation = view.findViewById(R.id.text_activation)
        btnEditPersona = view.findViewById(R.id.btn_edit_persona)
        btnEditGlamour = view.findViewById(R.id.btn_edit_glamour)
        btnEditActivation = view.findViewById(R.id.btn_edit_activation)
        btnEditSystemPrompt = view.findViewById(R.id.btn_edit_system_prompt)
        btnSelectLoreBook = view.findViewById(R.id.btn_select_lorebook)
        textLoreBook = view.findViewById(R.id.text_lorebook)
        lorebookCheckList = view.findViewById(R.id.lorebook_check_list)
        bgTemperature = view.findViewById(R.id.bg_temperature)
        bgTopP = view.findViewById(R.id.bg_top_p)
        bgFrequencyPenalty = view.findViewById(R.id.bg_frequency_penalty)
        bgPresencePenalty = view.findViewById(R.id.bg_presence_penalty)

        temperatureSeekbar = view.findViewById(R.id.temperature_slider)
        frequencyPenaltySeekbar = view.findViewById(R.id.frequency_penalty_slider)
        presencePenaltySeekbar = view.findViewById(R.id.presence_penalty_slider)
        topPSeekbar = view.findViewById(R.id.top_p_slider)
        fieldSeed = view.findViewById(R.id.field_seed)

        // Memory system (Phase 2): per-chat injection switch + reversible
        // Archive pause. The durable bookmark hides a paused chat without
        // consuming its already-queued or subsequently captured turns.
        switchChatMemory = view.findViewById(R.id.switch_chat_memory)
        switchChatExcluded = view.findViewById(R.id.switch_chat_excluded)
        containerMemoryScene = view.findViewById(R.id.container_memory_scene)
        textChatWorld = view.findViewById(R.id.text_chat_world)
        textChatCampaign = view.findViewById(R.id.text_chat_campaign)
        textChatRoleplayCharacter = view.findViewById(R.id.text_chat_roleplay_character)
        textChatProject = view.findViewById(R.id.text_chat_project)
        rowChatModelRules = view.findViewById(R.id.row_chat_model_rules)
        switchChatModelRules = view.findViewById(R.id.switch_chat_model_rules)
        cardSummarizer = view.findViewById(R.id.card_summarizer)
        rowSummarizerWindow = view.findViewById(R.id.row_summarizer_window)
        switchUseSummarizer = view.findViewById(R.id.switch_use_summarizer)
        fieldSummarizerWindow = view.findViewById(R.id.field_summarizer_window)
        setupModelRulesRow()
        setupSummarizerControls()
        switchChatMemory?.isChecked = preferences?.getChatMemoryEnabled() ?: true
        // "Archive this chat": positive framing. Checked = archive (capture on).
        // The stored pref is still "excluded" (the inverse), so flip both ways.
        switchChatExcluded?.isChecked = !(preferences?.isChatExcludedFromMemory() ?: false)
        switchChatMemory?.setOnCheckedChangeListener { _, checked ->
            preferences?.setChatMemoryEnabled(checked)
            // The scene rows follow this switch (not the global engine), so
            // they appear/disappear the moment it's flipped.
            setupMemorySceneRows()
        }
        switchChatLoreBooks = view.findViewById(R.id.switch_chat_lorebooks)
        switchChatLoreBooks?.isChecked = preferences?.getChatLoreBooksEnabled() ?: true
        switchChatLoreBooks?.setOnCheckedChangeListener { _, checked ->
            preferences?.setChatLoreBooksEnabled(checked)
        }
        switchChatExcluded?.setOnCheckedChangeListener { _, archive ->
            val excluded = !archive
            preferences?.setChatExcludedFromMemory(excluded)
            val appContext = context?.applicationContext ?: return@setOnCheckedChangeListener
            Thread {
                try {
                    if (MemoryStore.isProvisioned(appContext)) {
                        MemoryStore.getInstance(appContext).setChatTranscriptsExcluded(chatId, excluded)
                    }
                } catch (_: Exception) { /* capture also reconciles the durable pause bit on the next turn */ }
            }.start()
        }
        setupMemorySceneRows()
        btnSaveToProfile = view.findViewById(R.id.btn_save_to_profile)
        textModel = view.findViewById(R.id.text_model)
        textHost = view.findViewById(R.id.text_host)
        textLogitBiasesConfig = view.findViewById(R.id.text_logit_biases_config)
        usageCost = view.findViewById(R.id.usage_cost)

        usageCost?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(activity ?: return))
        btnSelectModel?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(activity ?: return))
        btnSelectProvider?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(activity ?: return))
        providerModeTile?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(activity ?: return))
        btnSelectLogitBias?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(activity ?: return))
        btnSelectApiEndpoint?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(activity ?: return))
        btnSelectPersona?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(activity ?: return))
        btnSelectGlamour?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(activity ?: return))
        btnSelectActivation?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(activity ?: return))
        btnSelectSystemPrompt?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(activity ?: return))
        btnSelectLoreBook?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(activity ?: return))
        bgTemperature?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(activity ?: return))
        bgTopP?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(activity ?: return))
        bgFrequencyPenalty?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(activity ?: return))
        bgPresencePenalty?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(activity ?: return))


        textUsage = view.findViewById(R.id.text_usage)
        textCost = view.findViewById(R.id.text_cost)
        btnCostInfo = view.findViewById(R.id.btn_cost_info)

        textHost?.text = if (apiEndpoint?.label != "") apiEndpoint?.label ?: getString(R.string.label_tap_to_set) else getString(R.string.label_tap_to_set)
        updatePersonaLabel(preferences?.getPersonaId() ?: "")
        updateActivationLabel(preferences?.getActivationPromptId() ?: "")
        // Glamour (user persona) tile: label shows None until the store read
        // returns and fills cachedUserPersonas for the dropdown.
        updateGlamourLabel()
        loadUserPersonasForGlamour()
        // Fold any pre-library system message into the library so the label names
        // it, then show the effective (chosen or top) prompt's title.
        preferences?.let { systemPromptsPreferences?.migrateExistingSystemMessage(it) }
        updateSystemPromptLabel()
        renderLoreBookList()
        textLogitBiasesConfig?.text = if (preferences?.getLogitBiasesConfigId() != "") {
            logitBiasConfigPreferences?.getConfigById(preferences?.getLogitBiasesConfigId()!!)?.get("label") ?: getString(R.string.label_tap_to_set)
        } else {
            getString(R.string.label_tap_to_set)
        }

        // The cost-info page was hosted by the upstream Teslasoft service and
        // is gone in this independent fork.
        btnCostInfo?.visibility = View.GONE

        usageIn = requireArguments().getInt("usageIn")
        usageOut = requireArguments().getInt("usageOut")

        textUsage?.text = getString(R.string.cost_counter_usage).format(usageIn.toString(), usageOut.toString())
        textCost?.text = getString(R.string.cost_loading)

        if (usageIn >= 0) {
            requestNetwork = RequestNetwork(requireActivity())
            requestNetwork?.setHeaders(hashMapOf("Authorization" to "Bearer " + apiEndpoint?.apiKey))
            requestNetwork?.startRequestNetwork("GET", apiEndpoint?.host + "models", "A", requestListener)
        } else {
            textUsage?.text = "Usage: <Usage is not available in playground>"
            textCost?.text = "Cost: <Cost is not available in playground>"
            usageCost?.visibility = View.GONE
        }

        temperatureSeekbar?.value = preferences?.getTemperature()!! * 10
        topPSeekbar?.value = preferences?.getTopP()!! * 10
        frequencyPenaltySeekbar?.value = preferences?.getFrequencyPenalty()!! * 10
        presencePenaltySeekbar?.value = preferences?.getPresencePenalty()!! * 10
        fieldSeed?.setText(preferences?.getSeed())

        val model = preferences?.getModel()

        if (model != null) {
            textModel?.text = model
        }

        btnSelectModel?.setOnClickListener {
            // One full-screen selector: it opens on this endpoint's favorites and
            // offers "View all". Favorites are scoped to the chat's active
            // endpoint, so a picked favorite always belongs to it.
            val dialog = AdvancedModelSelectorDialogFragment.newInstance(model!!, chatId)
            dialog.setModelSelectedListener(modelSelectedListener)
            dialog.show(parentFragmentManager, "AdvancedModelSelectorDialogFragment")
        }

        btnSelectProvider?.setOnClickListener {
            val endpointId = preferences?.getApiEndpointId() ?: return@setOnClickListener
            val selectedModel = preferences?.getModel() ?: return@setOnClickListener
            if (selectedModel.isBlank()) return@setOnClickListener
            val endpointPrefs = apiEndpointPreferences ?: return@setOnClickListener
            val favoritePrefs = favoriteModelsPreferences ?: return@setOnClickListener
            FavoriteRoutingActions.buildRoutingIntent(
                requireContext(),
                endpointPrefs,
                favoritePrefs,
                selectedModel,
                endpointId
            )?.let { chooseProviderLauncher.launch(it) }
        }

        setupProviderModeDropdown()
        refreshProviderModeTile()

        // System Prompt tile: the value is an inline dropdown of saved prompts;
        // the edit button opens the library. NOTE: a "None" (send no system
        // prompt) isn't offered here yet - the prompt library always keeps the
        // selected-or-first prompt effective, so a real None needs a small
        // per-chat mechanism that doesn't exist; deferred rather than guessed.
        textSystemPrompt?.setOnClickListener { showSystemPromptDropdown(it) }
        setupReasoningTile()
        btnEditSystemPrompt?.setOnClickListener {
            managerRefreshLauncher.launch(Intent(requireContext(), SystemPromptsListActivity::class.java))
        }

        fieldSeed?.addTextChangedListener { text ->
            preferences?.setSeed(text.toString())
        }

        temperatureSeekbar?.addOnChangeListener { _, value, _ ->
            preferences?.setTemperature(value / 10.0f)
        }

        temperatureSeekbar?.setLabelFormatter {
            return@setLabelFormatter "${it/10.0}"
        }

        topPSeekbar?.addOnChangeListener { _, value, _ ->
            preferences?.setTopP(value / 10.0f)
        }

        topPSeekbar?.setLabelFormatter {
            return@setLabelFormatter "${it/10.0}"
        }

        frequencyPenaltySeekbar?.addOnChangeListener { _, value, _ ->
            preferences?.setFrequencyPenalty(value / 10.0f)
        }

        frequencyPenaltySeekbar?.setLabelFormatter {
            return@setLabelFormatter "${it/10.0}"
        }

        presencePenaltySeekbar?.addOnChangeListener { _, value, _ ->
            preferences?.setPresencePenalty(value / 10.0f)
        }

        presencePenaltySeekbar?.setLabelFormatter {
            return@setLabelFormatter "${it/10.0}"
        }

        btnSelectLogitBias?.setOnClickListener {
            logitBiasesActivityResultLauncher.launch(Intent(requireContext(), LogitBiasConfigListActivity::class.java))
        }

        btnSelectApiEndpoint?.setOnClickListener {
            apiEndpointActivityResultLauncher.launch(Intent(requireContext(), ApiEndpointsListActivity::class.java))
        }

        // Companion / Glamour / Activation tiles (owner ruling, July 21 2026):
        // the value is an inline dropdown; the edit button opens that category's
        // manager. Companion has no None (a chat always has one); Glamour and
        // Activation include None.
        textPersona?.setOnClickListener { showCompanionDropdown(it) }
        btnEditPersona?.setOnClickListener {
            val activeId = preferences?.getPersonaId() ?: ""
            val activePersona = if (activeId != "") personaPreferences?.getPersona(activeId) else null
            if (activePersona != null) {
                editActivePersonaLauncher.launch(EditPersonaActivity.createIntent(requireContext(), activePersona, 0))
            } else {
                // No active companion to edit directly (shouldn't normally
                // happen - a chat always has one) - fall back to the manager.
                managerRefreshLauncher.launch(Intent(requireContext(), PersonasListActivity::class.java))
            }
        }

        textGlamour?.setOnClickListener { showGlamourDropdown(it) }
        btnEditGlamour?.setOnClickListener {
            managerRefreshLauncher.launch(Intent(requireContext(), MemoryUserPersonasActivity::class.java))
        }

        textActivation?.setOnClickListener { showActivationDropdown(it) }
        btnEditActivation?.setOnClickListener {
            managerRefreshLauncher.launch(Intent(requireContext(), ActivationPromptsListActivity::class.java))
        }

        btnSaveToProfile?.setOnClickListener {
            saveCurrentSettingsToProfile()
        }
    }

    /* ------------------------------ memory scene (Phase 4) ------------------------------ */

    /**
     * The world / roleplay-character / user-persona rows only make sense once
     * the full memory engine is selected and the store has actually been
     * created — otherwise there is nothing to pick from. Values are read
     * per-turn by the enforcer, so persisting them here needs no
     * shouldForceUpdate/restart.
     */
    private fun setupMemorySceneRows() {
        // Follows the per-chat "Use memory" switch, not the global engine tier
        // (Quick Settings is God — owner ruling, July 10 2026): a chat with
        // memory switched on gets its scene selectors regardless of the
        // global default.
        val memoryOn = preferences?.getChatMemoryEnabled() == true
        val provisioned = MemoryStore.isProvisioned(requireContext())
        val visible = memoryOn && provisioned
        containerMemoryScene?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return

        updateChatWorldLabel()
        updateChatCampaignLabel()
        updateChatRoleplayCharacterLabel()
        updateChatProjectLabel()

        textChatWorld?.setOnClickListener { showWorldPicker() }
        textChatCampaign?.setOnClickListener { showCampaignPicker() }
        textChatRoleplayCharacter?.setOnClickListener { showRoleplayCharacterPicker() }
        textChatProject?.setOnClickListener { showProjectPicker() }

        loadMemorySceneLists()
    }

    private fun loadMemorySceneLists() {
        val appContext = context?.applicationContext ?: return
        Thread {
            try {
                val store = MemoryStore.getInstance(appContext)
                val worlds = store.getActiveWorlds()
                val campaigns = store.getActiveCampaigns()
                // Only characters played by the user belong here — companion-played
                // characters are the Storyteller/companion's own cast, not a chat scene pick.
                val roleplayCharacters = store.getActiveRoleplayCharacters().filter { it.playedBy == "user" }
                val projects = store.getActiveProjects()
                val allWorldNames = store.getAllWorlds().associate { it.worldId to it.name }
                val allCharacterNames = store.getAllRoleplayCharacters()
                    .associate { it.roleplayCharacterId to it.name }
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    cachedWorlds = worlds
                    cachedCampaigns = campaigns
                    cachedRoleplayCharacters = roleplayCharacters
                    cachedProjects = projects
                    worldNames = allWorldNames
                    roleplayCharacterNames = allCharacterNames
                    updateChatWorldLabel()
                    updateChatCampaignLabel()
                    updateChatRoleplayCharacterLabel()
                    updateChatProjectLabel()
                }
            } catch (_: Exception) { /* the rows keep working, just empty until the store is reachable */ }
        }.start()
    }

    /** The chat's selected campaign, resolved from the active-campaign cache. */
    private fun selectedCampaign(): CampaignRecord? {
        val id = preferences?.getChatCampaignId().orEmpty()
        if (id.isEmpty()) return null
        return cachedCampaigns.firstOrNull { it.campaignId == id }
    }

    private fun updateChatWorldLabel() {
        // While a campaign is selected the world slot displays the CAMPAIGN's
        // world — auto-filled from its links, visibly (spec §2/§8b).
        val campaignWorldId = selectedCampaign()?.worldId
        if (campaignWorldId != null) {
            // §5: a campaign link to a gone world says so — never a fake "None".
            textChatWorld?.text = worldNames[campaignWorldId] ?: getString(R.string.rp_ref_deleted)
            return
        }
        val id = preferences?.getChatWorldId().orEmpty()
        textChatWorld?.text = if (id.isEmpty()) {
            getString(R.string.label_world_none)
        } else {
            cachedWorlds.firstOrNull { it.worldId == id }?.name ?: getString(R.string.label_world_none)
        }
    }

    private fun updateChatCampaignLabel() {
        val id = preferences?.getChatCampaignId().orEmpty()
        textChatCampaign?.text = if (id.isEmpty()) {
            getString(R.string.label_campaign_none)
        } else {
            cachedCampaigns.firstOrNull { it.campaignId == id }?.name ?: getString(R.string.label_campaign_none)
        }
    }

    // Stage 3.0 (owner_approved_rules §3/§12 rev 3): selecting a campaign is
    // the explicit "this chat is inside that playthrough" signal the retrieval
    // engine reads — campaign memories join, and the campaign's GM companion
    // defines the narrator path for companion memories in roleplay.
    private fun showCampaignPicker() {
        val dropdown = textChatCampaign ?: return
        val ids = listOf("") + cachedCampaigns.map { it.campaignId }
        val labels = listOf(getString(R.string.label_campaign_none)) + cachedCampaigns.map { it.name }
        val current = ids.indexOf(preferences?.getChatCampaignId().orEmpty()).coerceAtLeast(0)

        AppDropdown.show(dropdown, labels, current) { which ->
            preferences?.setChatCampaignId(ids[which])
            updateChatCampaignLabel()
            // Auto-fill the world/character slots from the campaign's links,
            // visibly (3.6c, spec §2/§8b).
            updateChatWorldLabel()
            updateChatRoleplayCharacterLabel()
        }
    }

    private fun updateChatRoleplayCharacterLabel() {
        // A campaign's character fills the slot as a display (spec §2/§8b).
        val campaignCharacterId = selectedCampaign()?.roleplayCharacterId
        if (campaignCharacterId != null) {
            // §5: a campaign link to a gone character says so.
            textChatRoleplayCharacter?.text =
                roleplayCharacterNames[campaignCharacterId] ?: getString(R.string.rp_ref_deleted)
            return
        }
        val id = preferences?.getChatRoleplayCharacterId().orEmpty()
        textChatRoleplayCharacter?.text = if (id.isEmpty()) {
            getString(R.string.label_roleplay_character_none)
        } else {
            cachedRoleplayCharacters.firstOrNull { it.roleplayCharacterId == id }?.name
                ?: getString(R.string.label_roleplay_character_none)
        }
    }

    private fun updateChatProjectLabel() {
        val id = preferences?.getChatProjectId().orEmpty()
        textChatProject?.text = if (id.isEmpty()) {
            getString(R.string.label_project_none)
        } else {
            cachedProjects.firstOrNull { it.projectId == id }?.name ?: getString(R.string.label_project_none)
        }
    }

    // §4 rev 3 (Stage 3.5): selecting a project BOOSTS its memories in
    // ranking; with none selected, project memories still retrieve on
    // relevance in ordinary chats. Never an eligibility gate.
    private fun showProjectPicker() {
        val dropdown = textChatProject ?: return
        val ids = listOf("") + cachedProjects.map { it.projectId }
        val labels = listOf(getString(R.string.label_project_none)) + cachedProjects.map { it.name }
        val current = ids.indexOf(preferences?.getChatProjectId().orEmpty()).coerceAtLeast(0)

        AppDropdown.show(dropdown, labels, current) { which ->
            preferences?.setChatProjectId(ids[which])
            updateChatProjectLabel()
        }
    }

    /* ------------------------------ conversation summarizer ------------------------------ */

    /**
     * Decision 3: "Use Summarizer" turns the summarizer on or off for this
     * chat (turning it off IS the pause action). When on, the "Complete
     * Messages" number box appears, prefilled from the Summarizer Settings
     * default; changing it affects only this chat. The whole card is hidden
     * until a summarizer endpoint/model is configured (decision 8).
     */
    private fun setupSummarizerControls() {
        val configured = try {
            org.teslasoft.assistant.util.summarizer.SummarizerController.isConfigured(requireContext())
        } catch (_: Exception) {
            false
        }
        cardSummarizer?.visibility = if (configured) View.VISIBLE else View.GONE
        if (!configured) return

        val on = preferences?.getChatUseSummarizer() == true
        switchUseSummarizer?.isChecked = on
        rowSummarizerWindow?.visibility = if (on) View.VISIBLE else View.GONE
        setSummarizerWindowFieldText(preferences?.getChatSummarizerWindow()?.toString() ?: "")

        switchUseSummarizer?.setOnCheckedChangeListener { _, checked ->
            preferences?.setChatUseSummarizer(checked)
            rowSummarizerWindow?.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked) {
                setSummarizerWindowFieldText(preferences?.getChatSummarizerWindow()?.toString() ?: "")
            }
            // The chat screen re-reads the toggle, refreshes its icons, and
            // runs catch-up (or cancels an in-flight fold-in) on reload.
            shouldForceUpdate = true
        }

        fieldSummarizerWindow?.addTextChangedListener { text ->
            if (suppressSummarizerWindowWatcher) return@addTextChangedListener
            val parsed = text?.toString()?.trim()?.toIntOrNull()
            if (parsed != null && parsed >= 1) {
                preferences?.setChatSummarizerWindow(parsed)
                shouldForceUpdate = true
            }
        }
    }

    private fun setSummarizerWindowFieldText(text: String) {
        suppressSummarizerWindowWatcher = true
        fieldSummarizerWindow?.setText(text)
        fieldSummarizerWindow?.setSelection(text.length)
        suppressSummarizerWindowWatcher = false
    }

    /* ------------------------------ model rules (Stage 4, §11) ------------------------------ */

    /**
     * The per-chat "Apply Model Rules" toggle (§11 Revision 6). Unlike the
     * scene rows it is not gated on the full memory engine — model rules are
     * their own prompt layer at any tier. Follows the global "Automatically
     * Apply Model Rules" default (on); flipping it overrides for this chat.
     */
    private fun setupModelRulesRow() {
        switchChatModelRules?.isChecked = preferences?.getChatApplyModelRules() ?: true
        switchChatModelRules?.setOnCheckedChangeListener { _, checked ->
            preferences?.setChatApplyModelRules(checked)
        }
    }

    private fun showWorldPicker() {
        val dropdown = textChatWorld ?: return
        // 3.6c (spec §2/§8b): while a campaign is selected there is no
        // per-chat world override — the slot is the campaign's fact. Picking
        // a different world asks the owner-worded continue-in-new-world
        // question and, on confirm, edits the campaign itself.
        val campaign = selectedCampaign()
        if (campaign != null) {
            val ids = cachedWorlds.map { it.worldId }
            val labels = cachedWorlds.map { it.name }
            if (ids.isEmpty()) return
            AppDropdown.show(dropdown, labels, ids.indexOf(campaign.worldId)) { which ->
                val picked = ids[which]
                if (picked != campaign.worldId) confirmWorldMove(campaign, picked)
            }
            return
        }

        val ids = listOf("") + cachedWorlds.map { it.worldId }
        val labels = listOf(getString(R.string.label_world_none)) + cachedWorlds.map { it.name }
        val current = ids.indexOf(preferences?.getChatWorldId().orEmpty()).coerceAtLeast(0)

        AppDropdown.show(dropdown, labels, current) { which ->
            preferences?.setChatWorldId(ids[which])
            updateChatWorldLabel()
        }
    }

    /** The owner-worded 3.6c dialog (spec §8b): optional "what happened?"
     *  reason, and the prompted choice of whether the move is told to the AI
     *  every turn (by updating the campaign's Active Scene). */
    private fun confirmWorldMove(campaign: CampaignRecord, newWorldId: String) {
        val ctx = context ?: return
        val reasonInput = TextInputEditText(ctx).apply {
            hint = getString(R.string.rp_continue_reason_hint)
        }
        val sceneBox = MaterialCheckBox(ctx).apply {
            text = getString(R.string.rp_continue_scene_check)
            isChecked = true
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(reasonInput)
            addView(sceneBox)
        }
        MaterialAlertDialogBuilder(ctx, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.rp_continue_title)
            .setMessage(R.string.rp_continue_msg)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                applyWorldMove(
                    campaign, newWorldId,
                    reasonInput.text?.toString()?.trim().orEmpty(),
                    sceneBox.isChecked
                )
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    /** The user just confirmed the dialog, so this is an explicit user edit —
     *  the no-mid-conversation-writes law is satisfied (spec §2/§8b). The
     *  permanent history note is a Plot Ledger entry ON the campaign card
     *  (started in X → moved to Y → …), per the owner's ruling. */
    private fun applyWorldMove(campaign: CampaignRecord, newWorldId: String, reason: String, updateScene: Boolean) {
        val appContext = context?.applicationContext ?: return
        val newName = worldNames[newWorldId] ?: newWorldId
        val oldName = campaign.worldId?.let { worldNames[it] }
        Thread {
            try {
                val store = MemoryStore.getInstance(appContext)
                val descParts = ArrayList<String>()
                if (oldName != null) descParts.add(appContext.getString(R.string.rp_move_ledger_from, oldName))
                if (reason.isNotEmpty()) descParts.add(reason)
                store.upsertCardEntry(
                    CardEntryRecord(
                        entryId = MemoryStore.newId("ce-"),
                        cardType = CardType.CAMPAIGN,
                        cardId = campaign.campaignId,
                        section = CardSections.PLOT_LEDGER,
                        // The owner's progression reads "started in X → moved
                        // to Y → …": the campaign's first world is a start,
                        // every later change a move.
                        name = if (oldName == null) {
                            appContext.getString(R.string.rp_move_ledger_started_title, newName)
                        } else {
                            appContext.getString(R.string.rp_move_ledger_title, newName)
                        },
                        description = descParts.joinToString(" ").ifEmpty { null },
                        createdAt = MemoryStore.nowIso()
                    )
                )
                store.upsertCampaign(
                    campaign.copy(
                        worldId = newWorldId,
                        // The prompted "send to the AI every turn" choice: the
                        // Active Scene is the bookmark line that already rides
                        // every message.
                        activeScene = if (updateScene) {
                            if (reason.isNotEmpty()) appContext.getString(R.string.rp_scene_fmt, newName, reason)
                            else newName
                        } else campaign.activeScene
                    )
                )
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    loadMemorySceneLists()
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    // A failed move must be READABLE — dialog, not toast
                    // (owner ruling, spec §8b).
                    MaterialAlertDialogBuilder(requireContext(), R.style.App_MaterialAlertDialog)
                        .setMessage(getString(R.string.memory_operation_failed, e.message ?: e.javaClass.simpleName))
                        .setPositiveButton(android.R.string.ok) { _, _ -> }
                        .show()
                }
            }
        }.start()
    }

    private fun showRoleplayCharacterPicker() {
        // 3.6c (spec §8b): a campaign's character is not changeable from the
        // chat — the owner-worded explanation shows as a DIALOG (readable),
        // never a toast.
        if (selectedCampaign()?.roleplayCharacterId != null) {
            MaterialAlertDialogBuilder(requireContext(), R.style.App_MaterialAlertDialog)
                .setMessage(R.string.rp_character_locked_msg)
                .setPositiveButton(android.R.string.ok) { _, _ -> }
                .show()
            return
        }

        val dropdown = textChatRoleplayCharacter ?: return
        val ids = listOf("") + cachedRoleplayCharacters.map { it.roleplayCharacterId }
        val labels = listOf(getString(R.string.label_roleplay_character_none)) + cachedRoleplayCharacters.map { it.name }
        val current = ids.indexOf(preferences?.getChatRoleplayCharacterId().orEmpty()).coerceAtLeast(0)

        AppDropdown.show(dropdown, labels, current) { which ->
            preferences?.setChatRoleplayCharacterId(ids[which])
            updateChatRoleplayCharacterLabel()
        }
    }

    private fun saveCurrentSettingsToProfile() {
        val endpointId = preferences?.getApiEndpointId().orEmpty()
        if (endpointId.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), R.string.msg_no_active_profile, android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val currentProfile = apiEndpointPreferences?.getApiEndpoint(requireContext(), endpointId) ?: return
        val updated = ApiEndpointObject(
            label = currentProfile.label,
            host = currentProfile.host,
            apiKey = currentProfile.apiKey,
            chatEndpoint = currentProfile.chatEndpoint,
            authType = currentProfile.authType,
            model = preferences?.getModel() ?: currentProfile.model,
            temperature = preferences?.getTemperature() ?: currentProfile.temperature,
            topP = preferences?.getTopP() ?: currentProfile.topP,
            frequencyPenalty = preferences?.getFrequencyPenalty() ?: currentProfile.frequencyPenalty,
            presencePenalty = preferences?.getPresencePenalty() ?: currentProfile.presencePenalty,
            maxTokens = preferences?.getMaxTokens() ?: currentProfile.maxTokens,
            endSeparator = preferences?.getEndSeparator() ?: currentProfile.endSeparator,
            prefix = preferences?.getPrefix() ?: currentProfile.prefix,
            provider = currentProfile.provider,
            connectTimeoutSeconds = currentProfile.connectTimeoutSeconds,
            responseTimeoutSeconds = currentProfile.responseTimeoutSeconds,
            providerDiscoveryPath = currentProfile.providerDiscoveryPath,
            // Carry the stable id so this UPDATES the same profile in place;
            // without it setApiEndpoint would mint a new id and duplicate it.
            id = currentProfile.id
        )
        apiEndpointPreferences?.setApiEndpoint(requireContext(), updated)
        android.widget.Toast.makeText(requireContext(), R.string.msg_saved_to_profile, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), R.style.ThemeOverlay_App_BottomSheetDialog)
    }

    interface OnUpdateListener {
        fun onUpdate()
        fun onForceUpdate()
    }
}
