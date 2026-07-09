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

package org.teslasoft.assistant.ui.activities

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.memory.MemoryExporter
import org.teslasoft.assistant.preferences.memory.MemorySeedCodec
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.MemoryCompanionSync
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModelStorage
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.util.Hash

/**
 * "Memory Controls" — the normal user-facing controls page from the Memory
 * Settings reorganization (`Memory System/memory_settings_reorg_spec.md` §1,
 * July 9 2026). Owner-sanctioned wording, used verbatim. It holds the memory
 * defaults, the Memory Assistant entry + suggestion cap, the memory engine, the
 * Memory Assistant model, backups, and the destructive Reset at the bottom.
 * Two doors lead deeper: "Memory Assistant Advanced Settings" (extraction
 * tuning) inside the Memory Assistant section, and "Advanced Memory Settings"
 * (diagnostics/repair) as the very last row.
 *
 * The user-facing name is "Memory Assistant" — never "Archivist" (the internal
 * `Preferences.getArchivist*` accessors keep the code name).
 */
class MemoryControlsActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var apiEndpointPreferences: ApiEndpointPreferences? = null
    private var chatId = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null

    private var switchDefaultMemory: MaterialSwitch? = null
    private var switchCompanionInRoleplay: MaterialSwitch? = null

    private var rowMemoryAssistant: LinearLayout? = null
    private var switchCardSuggestions: MaterialSwitch? = null
    private var switchMaxSuggestions: MaterialSwitch? = null
    private var layoutMaxSuggestions: TextInputLayout? = null
    private var fieldMaxSuggestions: TextInputEditText? = null
    private var rowAssistantAdvanced: LinearLayout? = null

    private var rowMemoryEngine: LinearLayout? = null
    private var textMemoryEngineValue: TextView? = null
    private var rowArchivistEndpoint: LinearLayout? = null
    private var textArchivistEndpointValue: TextView? = null
    private var rowArchivistModel: LinearLayout? = null
    private var textArchivistModelValue: TextView? = null

    private var switchAutoBackup: MaterialSwitch? = null
    private var btnImport: MaterialButton? = null
    private var btnExport: MaterialButton? = null
    private var textLastBackup: TextView? = null

    private var btnReset: MaterialButton? = null
    private var rowAdvancedMemory: LinearLayout? = null

    /** Guards the suggestion field's TextWatcher while we set text programmatically. */
    private var suppressSuggestionWatcher = false

    private val importSeedLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importSeedFromUri(uri)
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) exportToUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_memory_controls)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        preferences = Preferences.getPreferences(this, chatId)
        apiEndpointPreferences = ApiEndpointPreferences.getApiEndpointPreferences(this)

        bindViews()
        applyTheme()
        initLogic()
    }

    override fun onResume() {
        super.onResume()
        refreshEngineRow()
        refreshArchivistRows()
        refreshBackupStatus()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        switchDefaultMemory = findViewById(R.id.switch_default_memory)
        switchCompanionInRoleplay = findViewById(R.id.switch_companion_in_roleplay)
        rowMemoryAssistant = findViewById(R.id.row_memory_assistant)
        switchCardSuggestions = findViewById(R.id.switch_card_suggestions)
        switchMaxSuggestions = findViewById(R.id.switch_max_suggestions)
        layoutMaxSuggestions = findViewById(R.id.layout_max_suggestions)
        fieldMaxSuggestions = findViewById(R.id.field_max_suggestions)
        rowAssistantAdvanced = findViewById(R.id.row_assistant_advanced)
        rowMemoryEngine = findViewById(R.id.row_memory_engine)
        textMemoryEngineValue = findViewById(R.id.text_memory_engine_value)
        rowArchivistEndpoint = findViewById(R.id.row_archivist_endpoint)
        textArchivistEndpointValue = findViewById(R.id.text_archivist_endpoint_value)
        rowArchivistModel = findViewById(R.id.row_archivist_model)
        textArchivistModelValue = findViewById(R.id.text_archivist_model_value)
        switchAutoBackup = findViewById(R.id.switch_auto_backup)
        btnImport = findViewById(R.id.btn_memory_import)
        btnExport = findViewById(R.id.btn_memory_export)
        textLastBackup = findViewById(R.id.text_last_backup)
        btnReset = findViewById(R.id.btn_memory_reset)
        rowAdvancedMemory = findViewById(R.id.row_advanced_memory)
    }

    @Suppress("DEPRECATION")
    private fun applyTheme() {
        ThemeManager.getThemeManager().applyTheme(this, isDarkThemeEnabled() && preferences?.getAmoledPitchBlack() == true)

        if (isDarkThemeEnabled() && preferences?.getAmoledPitchBlack() == true) {
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

    private fun initLogic() {
        btnBack?.setOnClickListener { finish() }

        /* ---- Memory Defaults ---- */
        // Global default for the per-chat memory kill switch (a plain global
        // pref — usable before the store is even provisioned).
        switchDefaultMemory?.isChecked = preferences?.getDefaultMemoryEnabled() ?: true
        switchDefaultMemory?.setOnCheckedChangeListener { _, checked ->
            preferences?.setDefaultMemoryEnabled(checked)
        }

        // "Allow companion memories in roleplay" (owner_approved_rules §3;
        // global, default OFF). Retrieval participation only, never forced.
        switchCompanionInRoleplay?.isChecked = preferences?.getAllowCompanionMemoriesInRoleplay() ?: false
        switchCompanionInRoleplay?.setOnCheckedChangeListener { _, checked ->
            preferences?.setAllowCompanionMemoriesInRoleplay(checked)
        }

        /* ---- Memory Assistant ---- */
        rowMemoryAssistant?.setOnClickListener {
            startActivity(Intent(this, MemoryAssistantActivity::class.java).putExtra("chatId", chatId))
        }
        rowAssistantAdvanced?.setOnClickListener {
            startActivity(Intent(this, MemoryAssistantAdvancedSettingsActivity::class.java).putExtra("chatId", chatId))
        }
        // Card-suggestions "danger switch" (placement ruling 6; owner wording in
        // phase6_card_suggestions_and_icons_design.md §2). ON by default; the
        // pref already defaults true. Manual card assignment stays available
        // regardless.
        switchCardSuggestions?.isChecked = preferences?.getArchivistCardSuggestions() ?: true
        switchCardSuggestions?.setOnCheckedChangeListener { _, checked ->
            preferences?.setArchivistCardSuggestions(checked)
        }
        setupMaxSuggestions()

        /* ---- Memory Engine ---- */
        refreshEngineRow()
        rowMemoryEngine?.setOnClickListener { showMemoryEnginePicker() }

        /* ---- Memory Assistant Model ---- */
        refreshArchivistRows()
        rowArchivistEndpoint?.setOnClickListener { showArchivistEndpointPicker() }
        rowArchivistModel?.setOnClickListener { showArchivistModelDialog() }

        /* ---- Backups ---- */
        switchAutoBackup?.setOnCheckedChangeListener { _, checked ->
            if (!MemoryStore.isProvisioned(this)) return@setOnCheckedChangeListener
            runOffThread {
                MemoryStore.getInstance(this).setMeta(
                    MemoryStore.META_AUTO_EXPORT_ENABLED, if (checked) "1" else "0"
                )
            }
        }
        btnImport?.setOnClickListener {
            importSeedLauncher.launch(arrayOf("application/json", "text/*"))
        }
        btnExport?.setOnClickListener {
            if (!MemoryStore.isProvisioned(this)) {
                Toast.makeText(this, R.string.memory_not_provisioned_toast, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val stamp = MemoryStore.nowIso().substring(0, 10)
            exportLauncher.launch("memory-export-$stamp.json")
        }
        refreshBackupStatus()

        /* ---- Reset (bottom) ---- */
        btnReset?.setOnClickListener { showResetDialog() }

        /* ---- Advanced Memory Settings door ---- */
        rowAdvancedMemory?.setOnClickListener {
            startActivity(Intent(this, AdvancedMemorySettingsActivity::class.java).putExtra("chatId", chatId))
        }
    }

    /* ------------------------------ Maximum Suggestions Per Conversation ------------------------------ */

    // Off (switch off) == no cap (pref 0). Turning the switch ON defaults the
    // field to 10 (placement ruling 4). The cap itself is enforced in the
    // Archivist runner, not only in the extraction prompt.
    private fun setupMaxSuggestions() {
        val current = preferences?.getArchivistMaxSuggestions() ?: 0
        val on = current > 0
        switchMaxSuggestions?.isChecked = on
        layoutMaxSuggestions?.visibility = if (on) View.VISIBLE else View.GONE
        if (on) setSuggestionFieldText(current.toString())

        switchMaxSuggestions?.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                val value = (preferences?.getArchivistMaxSuggestions() ?: 0).let { if (it > 0) it else DEFAULT_MAX_SUGGESTIONS }
                preferences?.setArchivistMaxSuggestions(value)
                setSuggestionFieldText(value.toString())
                layoutMaxSuggestions?.visibility = View.VISIBLE
            } else {
                preferences?.setArchivistMaxSuggestions(0)
                layoutMaxSuggestions?.visibility = View.GONE
            }
        }

        fieldMaxSuggestions?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressSuggestionWatcher || switchMaxSuggestions?.isChecked != true) return
                val parsed = s?.toString()?.trim()?.toIntOrNull()
                // Blank/zero is left alone while typing; the switch is the way
                // to reach "no cap", so a live cap is always at least 1.
                if (parsed != null && parsed >= 1) preferences?.setArchivistMaxSuggestions(parsed)
            }
        })
    }

    private fun setSuggestionFieldText(text: String) {
        suppressSuggestionWatcher = true
        fieldMaxSuggestions?.setText(text)
        fieldMaxSuggestions?.setSelection(text.length)
        suppressSuggestionWatcher = false
    }

    /* ------------------------------ memory engine ------------------------------ */

    private fun engineLabel(engine: String): String = when (engine) {
        "none" -> getString(R.string.memory_controls_engine_none)
        "full" -> getString(R.string.memory_controls_engine_full)
        else -> getString(R.string.memory_controls_engine_lorebooks)
    }

    private fun refreshEngineRow() {
        textMemoryEngineValue?.text = engineLabel(preferences?.getMemoryEngine() ?: "lorebooks")
    }

    private fun showMemoryEnginePicker() {
        val engines = arrayOf("none", "lorebooks", "full")
        val labels = arrayOf(
            getString(R.string.memory_controls_engine_none_desc),
            getString(R.string.memory_controls_engine_lorebooks_desc),
            getString(R.string.memory_controls_engine_full_desc)
        )
        val current = engines.indexOf(preferences?.getMemoryEngine() ?: "lorebooks").coerceAtLeast(0)

        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.memory_engine_picker_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val picked = engines[which]
                if (picked == "full" && EmbeddingModelStorage.activeModel(this) == null) {
                    // The full engine needs semantic retrieval to be usable — refuse
                    // the switch rather than silently degrading to keyword-only.
                    // TODO(owner-wording): this moved string says "the Librarian
                    // section below", but the Librarian now lives on Advanced
                    // Memory Settings, not below on this screen. Kept verbatim
                    // (owner-approved words) pending an owner reword.
                    Toast.makeText(this, R.string.memory_engine_full_needs_model_toast, Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                    return@setSingleChoiceItems
                }
                preferences?.setMemoryEngine(picked)
                refreshEngineRow()
                if (picked == "full") {
                    // Enabling the full engine is the tier-2 opt-in that
                    // provisions the store and links a companion record to every
                    // existing persona (idempotent). Without it, chats capture
                    // as companion=none.
                    Thread {
                        try {
                            val created = MemoryCompanionSync.bootstrapFromPersonas(this)
                            org.teslasoft.assistant.preferences.memory.MemoryLog.log(
                                this, "MemorySync", "info",
                                "Tier-2 enable: bootstrap linked $created new companion(s)"
                            )
                        } catch (e: Exception) {
                            org.teslasoft.assistant.preferences.memory.MemoryLog.log(
                                this, "MemorySync", "error", "Tier-2 bootstrap failed: ${e.message}"
                            )
                        }
                    }.start()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    /* ------------------------------ Memory Assistant model ------------------------------ */

    private fun refreshArchivistRows() {
        val endpointId = preferences?.getArchivistEndpointId().orEmpty()
        textArchivistEndpointValue?.text = if (endpointId.isEmpty()) {
            getString(R.string.label_endpoint_none)
        } else {
            val endpoints = apiEndpointPreferences?.getApiEndpointsList(this) ?: arrayListOf()
            val label = endpoints.firstOrNull { Hash.hash(it.label) == endpointId }?.label
            if (!label.isNullOrEmpty()) label else getString(R.string.label_endpoint_none)
        }

        val model = preferences?.getArchivistModel().orEmpty()
        textArchivistModelValue?.text = model.ifEmpty { getString(R.string.label_archivist_model_none) }
    }

    private fun showArchivistEndpointPicker() {
        val endpoints = apiEndpointPreferences?.getApiEndpointsList(this) ?: arrayListOf()
        if (endpoints.isEmpty()) {
            Toast.makeText(this, R.string.memory_archivist_endpoint_none_toast, Toast.LENGTH_SHORT).show()
            return
        }

        val currentId = preferences?.getArchivistEndpointId().orEmpty()
        val labels = endpoints.map { it.label }.toTypedArray()
        val current = endpoints.indexOfFirst { Hash.hash(it.label) == currentId }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.memory_archivist_endpoint_picker_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val picked: ApiEndpointObject = endpoints[which]
                preferences?.setArchivistEndpointId(Hash.hash(picked.label))
                refreshArchivistRows()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun showArchivistModelDialog() {
        val field = android.widget.EditText(this)
        field.setText(preferences?.getArchivistModel().orEmpty())
        field.hint = getString(R.string.memory_archivist_model_hint)

        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(pad, pad, pad, 0)
        container.addView(field)

        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.memory_controls_row_assistant_model)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                preferences?.setArchivistModel(field.text?.toString()?.trim().orEmpty())
                refreshArchivistRows()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    /* ------------------------------ backups ------------------------------ */

    private fun refreshBackupStatus() {
        runOffThread {
            var autoBackupOn = true
            var provisioned = false
            val lastBackup: String?
            if (!MemoryStore.isProvisioned(this)) {
                lastBackup = null
            } else {
                provisioned = true
                val store = MemoryStore.getInstance(this)
                autoBackupOn = store.getMeta(MemoryStore.META_AUTO_EXPORT_ENABLED) != "0"
                lastBackup = store.getMeta(MemoryStore.META_LAST_AUTO_EXPORT_AT)
            }
            runOnUiThread {
                switchAutoBackup?.isEnabled = provisioned
                switchAutoBackup?.isChecked = autoBackupOn
                textLastBackup?.text = getString(
                    R.string.memory_controls_last_backup,
                    lastBackup ?: getString(R.string.memory_controls_never)
                )
            }
        }
    }

    private fun importSeedFromUri(uri: Uri) {
        runOffThread {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException(getString(R.string.memory_file_unreadable))
            importSeedJson(json)
        }
    }

    /** Runs on a worker thread. First seed into a fresh store also takes the
     *  singletons (archivist settings, retrieval policy). */
    private fun importSeedJson(json: String) {
        val data = MemorySeedCodec.parse(json)
        val store = MemoryStore.getInstance(this)
        val firstSeed = store.getMeta(MemoryStore.META_SEED_IMPORTED_AT) == null
        val report = store.importData(data, overwriteSingletons = firstSeed)
        if (firstSeed) store.setMeta(MemoryStore.META_SEED_IMPORTED_AT, MemoryStore.nowIso())
        runOnUiThread {
            Toast.makeText(this, report.summary(), Toast.LENGTH_LONG).show()
            refreshBackupStatus()
        }
    }

    private fun exportToUri(uri: Uri) {
        runOffThread {
            val json = MemoryExporter.buildExportJson(this)
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(json) }
                ?: throw IllegalStateException(getString(R.string.memory_file_unreadable))
            // A manual export counts as a backup, so "Chats since last back-up"
            // resets after it too (not just the automatic daily one).
            MemoryStore.getInstance(this).setMeta(MemoryStore.META_LAST_AUTO_EXPORT_AT, MemoryStore.nowIso())
            runOnUiThread {
                Toast.makeText(this, R.string.memory_export_done, Toast.LENGTH_SHORT).show()
                refreshBackupStatus()
            }
        }
    }

    /* ------------------------------ reset (destructive; Memory Controls only) ------------------------------ */

    private fun showResetDialog() {
        if (!MemoryStore.isProvisioned(this)) {
            Toast.makeText(this, R.string.memory_not_provisioned_toast, Toast.LENGTH_SHORT).show()
            return
        }
        // The user decides whether a backup is written first (starts checked); if
        // they decline, trust them (owner_approved_rules approved UI decisions).
        val backupBox = MaterialCheckBox(this).apply {
            setText(R.string.mem_reset_backup)
            isChecked = true
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(backupBox)
        }
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_reset_title)
            .setMessage(R.string.mem_reset_message)
            .setView(container)
            .setPositiveButton(R.string.mem_reset_confirm) { _, _ -> doReset(backupBox.isChecked) }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun doReset(backupFirst: Boolean) {
        runOffThread {
            val backupName = if (backupFirst) MemoryExporter.writeBackupNow(this) else null
            MemoryStore.getInstance(this).resetAllMemoryData()
            runOnUiThread {
                val msg = if (backupName != null) getString(R.string.mem_reset_done_backup, backupName)
                else getString(R.string.mem_reset_done)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refreshBackupStatus()
            }
        }
    }

    /* ------------------------------ helpers ------------------------------ */

    /** All store work off the main thread, all failures as a toast — never a crash. */
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
            val scroll = findViewById<ScrollView>(R.id.scroll)
            scroll?.setPadding(
                0,
                0,
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom + pxToDp(24)
            )
        } catch (_: Exception) { /* unused */ }
    }

    private fun pxToDp(px: Int): Int {
        val density = resources.displayMetrics.density
        return (px * density).toInt()
    }

    private fun isDarkThemeEnabled(): Boolean {
        return when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            else -> false
        }
    }

    companion object {
        /** Placement ruling 4: switching the cap ON defaults the field to 10. */
        private const val DEFAULT_MAX_SUGGESTIONS = 10
    }
}
