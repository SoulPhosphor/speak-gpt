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

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.MemoryCompanionSync
import org.teslasoft.assistant.preferences.memory.MemoryExporter
import org.teslasoft.assistant.preferences.memory.MemorySeedCodec
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModelDownloader
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModelStorage
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModels
import org.teslasoft.assistant.preferences.memory.librarian.Librarian
import org.teslasoft.assistant.theme.ThemeManager

/**
 * "Memory (experimental)" — Phase 1 surface of the companion memory system:
 * store status (row counts + integrity), seed import (bundled starter template
 * or any schema-shaped JSON via SAF), manual backup export, the persona ->
 * companion bootstrap, and the automatic-backup toggle. Reached from a tile in
 * the Characters hub. Later phases grow the real memory editor; this screen
 * stays the store's plumbing/status page.
 *
 * The store is created lazily: nothing provisions the encrypted database until
 * the user takes an action here (or a later phase enables tier 2). All DB work
 * runs off the main thread; SQLCipher opens are not cheap.
 */
class MemorySettingsActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null

    private var textStatus: TextView? = null
    private var btnLoadTemplate: MaterialButton? = null
    private var btnImport: MaterialButton? = null
    private var btnExport: MaterialButton? = null
    private var btnBootstrap: MaterialButton? = null
    private var switchAutoBackup: MaterialSwitch? = null
    private var switchDefaultMemory: MaterialSwitch? = null
    private var switchSeedTesting: MaterialSwitch? = null
    private var btnPurgeSeed: MaterialButton? = null

    private var librarianModels: LinearLayout? = null
    private var btnRebuildIndex: MaterialButton? = null
    private var textIndexStatus: TextView? = null
    private var fieldDebugSearch: TextInputEditText? = null
    private var btnDebugSearch: MaterialButton? = null
    private var textDebugSearchResults: TextView? = null

    // Per-model download jobs so a second tap cancels an in-flight download.
    private val downloadJobs = HashMap<String, Job>()

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
        setContentView(R.layout.activity_memory_settings)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        preferences = Preferences.getPreferences(this, chatId)

        bindViews()
        applyTheme()
        initLogic()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        textStatus = findViewById(R.id.text_memory_status)
        btnLoadTemplate = findViewById(R.id.btn_memory_load_template)
        btnImport = findViewById(R.id.btn_memory_import)
        btnExport = findViewById(R.id.btn_memory_export)
        btnBootstrap = findViewById(R.id.btn_memory_bootstrap)
        switchAutoBackup = findViewById(R.id.switch_auto_backup)
        switchDefaultMemory = findViewById(R.id.switch_default_memory)
        switchSeedTesting = findViewById(R.id.switch_seed_testing)
        btnPurgeSeed = findViewById(R.id.btn_memory_purge_seed)
        librarianModels = findViewById(R.id.librarian_models)
        btnRebuildIndex = findViewById(R.id.btn_rebuild_index)
        textIndexStatus = findViewById(R.id.text_index_status)
        fieldDebugSearch = findViewById(R.id.field_debug_search)
        btnDebugSearch = findViewById(R.id.btn_debug_search)
        textDebugSearchResults = findViewById(R.id.text_debug_search_results)
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

        btnLoadTemplate?.setOnClickListener { importBundledTemplate() }

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

        btnBootstrap?.setOnClickListener {
            runOffThread {
                val created = MemoryCompanionSync.bootstrapFromPersonas(this)
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.memory_bootstrap_done, created), Toast.LENGTH_LONG).show()
                    refreshStatus()
                }
            }
        }

        switchAutoBackup?.setOnCheckedChangeListener { _, checked ->
            if (!MemoryStore.isProvisioned(this)) return@setOnCheckedChangeListener
            runOffThread {
                MemoryStore.getInstance(this).setMeta(
                    MemoryStore.META_AUTO_EXPORT_ENABLED, if (checked) "1" else "0"
                )
            }
        }

        // Global default for the per-chat memory kill switch (a plain global
        // pref — usable before the store is even provisioned).
        switchDefaultMemory?.isChecked = preferences?.getDefaultMemoryEnabled() ?: true
        switchDefaultMemory?.setOnCheckedChangeListener { _, checked ->
            preferences?.setDefaultMemoryEnabled(checked)
        }

        // Seed-safety controls (July 2026 audit): testing mode lets
        // seed/example records into retrieval (default OFF — they can never
        // reach a prompt otherwise), purge removes them for good. Both need a
        // provisioned store.
        if (MemoryStore.isProvisioned(this)) {
            runOffThread {
                val on = MemoryStore.getInstance(this).seedTestingModeEnabled()
                runOnUiThread { switchSeedTesting?.isChecked = on }
            }
        }
        switchSeedTesting?.setOnCheckedChangeListener { _, checked ->
            if (!MemoryStore.isProvisioned(this)) return@setOnCheckedChangeListener
            runOffThread {
                MemoryStore.getInstance(this).setMeta(
                    MemoryStore.META_SEED_TESTING_MODE, if (checked) "1" else "0"
                )
            }
        }

        btnPurgeSeed?.setOnClickListener {
            if (!MemoryStore.isProvisioned(this)) {
                Toast.makeText(this, R.string.memory_not_provisioned_toast, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.btn_memory_purge_seed)
                .setMessage(R.string.memory_purge_seed_confirm)
                .setPositiveButton(R.string.memory_purge_seed_go) { _, _ -> purgeSeedRecords() }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }

        buildLibrarianRows()

        btnRebuildIndex?.setOnClickListener { rebuildIndex() }
        btnDebugSearch?.setOnClickListener { runDebugSearch() }
    }

    /* ------------------------------ librarian ------------------------------ */

    private fun buildLibrarianRows() {
        val container = librarianModels ?: return
        val inflater = LayoutInflater.from(this)
        container.removeAllViews()
        for (model in EmbeddingModels.ALL) {
            val row = inflater.inflate(R.layout.view_embedding_model_row, container, false)
            row.tag = model.id
            container.addView(row)
            refreshModelRow(row, model)
        }
        refreshIndexStatus()
    }

    private fun refreshModelRow(row: View, model: EmbeddingModels.Model) {
        val title = row.findViewById<TextView>(R.id.model_row_title)
        val subtitle = row.findViewById<TextView>(R.id.model_row_subtitle)
        val action = row.findViewById<MaterialButton>(R.id.model_row_action)
        title.text = model.displayName
        subtitle.text = getString(R.string.memory_model_progress_placeholder, model.sizeMb, model.description)

        val installed = EmbeddingModelStorage.isInstalled(this, model)
        val downloading = downloadJobs[model.id]?.isActive == true
        action.text = when {
            downloading -> getString(R.string.memory_model_cancel)
            installed -> getString(R.string.memory_model_delete)
            else -> getString(R.string.memory_model_download)
        }
        action.setOnClickListener {
            val job = downloadJobs[model.id]
            when {
                job != null && job.isActive -> { job.cancel(); downloadJobs.remove(model.id); refreshModelRow(row, model) }
                EmbeddingModelStorage.isInstalled(this, model) -> deleteModel(model, row)
                else -> downloadModel(model, row)
            }
        }
    }

    private fun downloadModel(model: EmbeddingModels.Model, row: View) {
        val progress = row.findViewById<LinearProgressIndicator>(R.id.model_row_progress)
        val progressLabel = row.findViewById<TextView>(R.id.model_row_progress_label)
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        progressLabel.visibility = View.VISIBLE
        progressLabel.text = ""

        val job = lifecycleScope.launch {
            val result = EmbeddingModelDownloader.download(this@MemorySettingsActivity, model) { bytes, total ->
                runOnUiThread {
                    if (total > 0) {
                        progress.isIndeterminate = false
                        progress.setProgressCompat((bytes * 100 / total).toInt().coerceIn(0, 100), true)
                        progressLabel.text = getString(
                            R.string.memory_model_progress_fmt,
                            (bytes / 1_000_000).toInt(), (total / 1_000_000).toInt()
                        )
                    } else progress.isIndeterminate = true
                }
            }
            downloadJobs.remove(model.id)
            runOnUiThread {
                progress.visibility = View.GONE
                progressLabel.visibility = View.GONE
                when (result) {
                    EmbeddingModelDownloader.Result.Success -> {
                        Librarian.getInstance(this@MemorySettingsActivity).invalidateModel()
                        Toast.makeText(this@MemorySettingsActivity,
                            getString(R.string.memory_model_downloaded, model.displayName), Toast.LENGTH_SHORT).show()
                    }
                    EmbeddingModelDownloader.Result.Canceled -> { }
                    is EmbeddingModelDownloader.Result.Failed ->
                        Toast.makeText(this@MemorySettingsActivity,
                            getString(R.string.memory_model_download_failed, result.reason), Toast.LENGTH_LONG).show()
                }
                refreshModelRow(row, model)
                refreshIndexStatus()
            }
        }
        downloadJobs[model.id] = job
        refreshModelRow(row, model)
    }

    private fun deleteModel(model: EmbeddingModels.Model, row: View) {
        EmbeddingModelStorage.delete(this, model)
        Librarian.getInstance(this).invalidateModel()
        refreshModelRow(row, model)
        refreshIndexStatus()
    }

    private fun rebuildIndex() {
        textIndexStatus?.text = getString(R.string.memory_index_building, 0, 0)
        Thread {
            try {
                val count = Librarian.getInstance(this).rebuildIndex { done, total ->
                    runOnUiThread { textIndexStatus?.text = getString(R.string.memory_index_building, done, total) }
                }
                runOnUiThread {
                    textIndexStatus?.text =
                        if (count < 0) getString(R.string.memory_index_failed)
                        else getString(R.string.memory_index_done, count)
                }
            } catch (e: Exception) {
                runOnUiThread { textIndexStatus?.text = getString(R.string.memory_index_failed) }
            }
        }.start()
    }

    private fun refreshIndexStatus() {
        Thread {
            val text = try {
                val librarian = Librarian.getInstance(this)
                when {
                    librarian.activeTag() == null -> getString(R.string.memory_index_none)
                    librarian.indexNeedsRebuild() -> getString(R.string.memory_index_stale)
                    !MemoryStore.isProvisioned(this) -> getString(R.string.memory_index_ok, 0)
                    else -> getString(R.string.memory_index_ok,
                        MemoryStore.getInstance(this).counts()["memories"] ?: 0)
                }
            } catch (_: Exception) { getString(R.string.memory_index_none) }
            runOnUiThread { textIndexStatus?.text = text }
        }.start()
    }

    private fun runDebugSearch() {
        val query = fieldDebugSearch?.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) return
        textDebugSearchResults?.text = ""
        Thread {
            val rendered = try {
                if (!MemoryStore.isProvisioned(this)) getString(R.string.memory_debug_search_empty)
                else {
                    val store = MemoryStore.getInstance(this)
                    val sb = StringBuilder()

                    // Semantic memory ranking when a model is installed — this is
                    // what the enforcer will actually inject. Keyword-only until
                    // then, so it may be empty; the broad inspector below always
                    // runs so the user can still find anything they stored.
                    val librarian = Librarian.getInstance(this)
                    if (librarian.hasUsableModel()) {
                        val hits = librarian.search(null, null, query, 10)
                        if (hits.isNotEmpty()) {
                            sb.append(getString(R.string.memory_debug_semantic_header)).append("\n")
                            hits.forEach {
                                sb.append(getString(
                                    R.string.memory_debug_search_result_fmt, it.score, it.memory.title, it.memory.content
                                )).append("\n\n")
                            }
                        }
                    }

                    // Broad inspector across every record type (not just
                    // memories) — finds companions like "Storyteller", entities,
                    // characters and worlds too.
                    val all = store.debugSearchAll(query, 20)
                    if (all.isNotEmpty()) {
                        sb.append(getString(R.string.memory_debug_all_header)).append("\n")
                        all.forEach { (label, snippet) ->
                            sb.append(label).append("\n").append(snippet).append("\n\n")
                        }
                    }

                    if (sb.isBlank()) getString(R.string.memory_debug_search_empty) else sb.toString().trim()
                }
            } catch (e: Exception) {
                getString(R.string.memory_operation_failed, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread { textDebugSearchResults?.text = rendered }
        }.start()
    }

    private fun purgeSeedRecords() {
        runOffThread {
            val result = try {
                val counts = MemoryStore.getInstance(this).purgeSeedRecords()
                getString(
                    R.string.memory_purge_seed_done,
                    counts["memories"] ?: 0, counts["companions"] ?: 0, counts["entities"] ?: 0
                )
            } catch (e: Exception) {
                getString(R.string.memory_operation_failed, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                refreshStatus()
                refreshIndexStatus()
            }
        }
    }

    private fun importBundledTemplate() {
        runOffThread {
            val json = assets.open("memory_seed_template.json").bufferedReader().use { it.readText() }
            importSeedJson(json)
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
     *  singletons (owner profile, archivist settings, retrieval policy). */
    private fun importSeedJson(json: String) {
        val data = MemorySeedCodec.parse(json)
        val store = MemoryStore.getInstance(this)
        val firstSeed = store.getMeta(MemoryStore.META_SEED_IMPORTED_AT) == null
        val report = store.importData(data, overwriteSingletons = firstSeed)
        if (firstSeed) store.setMeta(MemoryStore.META_SEED_IMPORTED_AT, MemoryStore.nowIso())
        runOnUiThread {
            Toast.makeText(this, report.summary(), Toast.LENGTH_LONG).show()
            refreshStatus()
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
                refreshStatus()
            }
        }
    }

    private fun refreshStatus() {
        runOffThread {
            val status: String
            var autoBackupOn = true
            var provisioned = false
            if (!MemoryStore.isProvisioned(this)) {
                status = getString(R.string.memory_status_not_provisioned)
            } else {
                provisioned = true
                val store = MemoryStore.getInstance(this)
                val counts = store.counts()
                val problem = store.integrityCheck()
                autoBackupOn = store.getMeta(MemoryStore.META_AUTO_EXPORT_ENABLED) != "0"
                val lastBackup = store.getMeta(MemoryStore.META_LAST_AUTO_EXPORT_AT)
                status = buildString {
                    appendLine(
                        if (problem == null) getString(R.string.memory_status_integrity_ok)
                        else getString(R.string.memory_status_integrity_bad, problem)
                    )
                    appendLine(
                        getString(
                            R.string.memory_status_counts,
                            counts["companions"] ?: 0, counts["memories"] ?: 0, counts["entities"] ?: 0,
                            counts["modes"] ?: 0, counts["directives"] ?: 0
                        )
                    )
                    appendLine(
                        getString(
                            R.string.memory_status_counts2,
                            counts["worlds"] ?: 0, counts["user_personas"] ?: 0,
                            counts["roleplay_characters"] ?: 0, counts["proposals"] ?: 0
                        )
                    )
                    appendLine(
                        getString(R.string.memory_status_chats_since_backup, store.chatsSinceLastBackup())
                    )
                    appendLine(
                        getString(R.string.memory_status_pending_review, store.pendingReviewCount())
                    )
                    appendLine(
                        if (store.getMeta(MemoryStore.META_SEED_IMPORTED_AT) != null)
                            getString(R.string.memory_status_seed_yes) else getString(R.string.memory_status_seed_no)
                    )
                    appendLine(
                        if (store.getMeta(MemoryStore.META_BOOTSTRAP_DONE) == "1")
                            getString(R.string.memory_status_bootstrap_yes) else getString(R.string.memory_status_bootstrap_no)
                    )
                    append(
                        if (lastBackup != null) getString(R.string.memory_status_last_backup, lastBackup)
                        else getString(R.string.memory_status_last_backup_never)
                    )
                }
            }
            runOnUiThread {
                textStatus?.text = status
                switchAutoBackup?.isEnabled = provisioned
                switchAutoBackup?.isChecked = autoBackupOn
            }
        }
    }

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
}
