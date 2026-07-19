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
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.stt.LocalWhisperDownloader
import org.teslasoft.assistant.stt.LocalWhisperEngine
import org.teslasoft.assistant.stt.LocalWhisperModels
import org.teslasoft.assistant.stt.LocalWhisperStorage
import org.teslasoft.assistant.theme.ThemeManager

/**
 * Pick which on-device Whisper model is active, and trigger downloads for
 * any not yet on disk. Per-row state:
 *
 *   - not installed  → "Download" button starts a streaming download with
 *                      progress shown inline and the button switching to
 *                      "Cancel" while in flight.
 *   - installed/inactive → "Make active" button.
 *   - installed/active   → button hidden; row shows the Active label.
 */
class LocalWhisperModelsActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var root: ConstraintLayout? = null
    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var btnManage: MaterialButton? = null
    private var activeModelLabel: TextView? = null
    private var storageUsedLabel: TextView? = null
    private var modelsContainer: LinearLayout? = null

    private val rowsByModelId = mutableMapOf<String, ModelRow>()
    private val downloadJobs = mutableMapOf<String, Job>()

    private data class ModelRow(
        val root: View,
        val name: TextView,
        val meta: TextView,
        val description: TextView,
        val actionButton: MaterialButton,
        val uninstallButton: MaterialButton,
        val progressContainer: View,
        val progress: LinearProgressIndicator,
        val progressLabel: TextView
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_local_whisper_models)

        preferences = Preferences.getPreferences(this, "")

        root = findViewById(R.id.root)
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        btnManage = findViewById(R.id.btn_manage_models)
        activeModelLabel = findViewById(R.id.active_model_label)
        storageUsedLabel = findViewById(R.id.storage_used_label)
        modelsContainer = findViewById(R.id.models_container)

        btnBack?.setOnClickListener { finish() }
        btnManage?.setOnClickListener {
            startActivity(Intent(this, LocalWhisperManageActivity::class.java))
        }

        buildModelRows()
        refreshAll()
        reloadAmoled()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
        reloadAmoled()
    }

    override fun onDestroy() {
        // Cancel any in-flight downloads if the user leaves; partial files
        // are wiped by the downloader on cancel.
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
        super.onDestroy()
    }

    private fun buildModelRows() {
        val inflater = LayoutInflater.from(this)
        modelsContainer?.removeAllViews()
        rowsByModelId.clear()

        for (model in LocalWhisperModels.ALL) {
            val rowView = inflater.inflate(R.layout.item_local_whisper_model, modelsContainer, false)
            modelsContainer?.addView(rowView)

            val row = ModelRow(
                root = rowView,
                name = rowView.findViewById(R.id.model_name),
                meta = rowView.findViewById(R.id.model_meta),
                description = rowView.findViewById(R.id.model_description),
                actionButton = rowView.findViewById(R.id.model_action_button),
                uninstallButton = rowView.findViewById(R.id.model_uninstall_button),
                progressContainer = rowView.findViewById(R.id.download_progress_container),
                progress = rowView.findViewById(R.id.download_progress),
                progressLabel = rowView.findViewById(R.id.download_progress_label)
            )

            row.name.text = model.displayName
            row.description.text = model.description

            row.root.setOnClickListener {
                handleRowTap(model)
            }
            row.actionButton.setOnClickListener {
                handleRowTap(model)
            }
            row.uninstallButton.setOnClickListener {
                confirmUninstall(model)
            }

            rowsByModelId[model.id] = row
        }
    }

    private fun handleRowTap(model: LocalWhisperModels.Model) {
        val activeJob = downloadJobs[model.id]
        if (activeJob != null && activeJob.isActive) {
            // Mid-download → tap acts as cancel.
            activeJob.cancel()
            downloadJobs.remove(model.id)
            refreshRow(model)
            return
        }

        if (LocalWhisperStorage.isInstalled(this, model)) {
            preferences?.setActiveLocalWhisperModel(model.id)
            refreshAll()
        } else {
            startDownload(model)
        }
    }

    private fun startDownload(model: LocalWhisperModels.Model) {
        val row = rowsByModelId[model.id] ?: return

        row.progressContainer.visibility = View.VISIBLE
        row.progress.isIndeterminate = true
        row.progressLabel.text = ""
        row.actionButton.text = getString(R.string.btn_cancel_download)

        val job = lifecycleScope.launch {
            val result = LocalWhisperDownloader.download(this@LocalWhisperModelsActivity, model) { bytes, total ->
                runOnUiThread {
                    if (total > 0) {
                        row.progress.isIndeterminate = false
                        val pct = (bytes * 100 / total).toInt().coerceIn(0, 100)
                        row.progress.setProgressCompat(pct, true)
                        val downloadedMb = (bytes / 1_000_000).toInt()
                        val totalMb = (total / 1_000_000).toInt()
                        row.progressLabel.text = getString(
                            R.string.local_whisper_progress_fmt, downloadedMb, totalMb
                        )
                    } else {
                        // Unknown content length — keep spinner going.
                        row.progress.isIndeterminate = true
                    }
                }
            }
            downloadJobs.remove(model.id)
            runOnUiThread {
                row.progressContainer.visibility = View.GONE
                when (result) {
                    LocalWhisperDownloader.Result.Success -> {
                        // First-installed model auto-becomes active.
                        if (preferences?.getActiveLocalWhisperModel().isNullOrEmpty()) {
                            preferences?.setActiveLocalWhisperModel(model.id)
                        }
                        refreshAll()
                    }
                    LocalWhisperDownloader.Result.Canceled -> {
                        refreshRow(model)
                    }
                    is LocalWhisperDownloader.Result.Failed -> {
                        MaterialAlertDialogBuilder(
                            this@LocalWhisperModelsActivity,
                            R.style.App_MaterialAlertDialog
                        )
                            .setTitle(model.displayName)
                            .setMessage(getString(R.string.local_whisper_download_failed_fmt, result.reason))
                            .setPositiveButton(android.R.string.ok) { _, _ -> }
                            .show()
                        refreshRow(model)
                    }
                }
            }
        }
        downloadJobs[model.id] = job
    }

    private fun refreshAll() {
        val activeId = preferences?.getActiveLocalWhisperModel() ?: ""
        if (activeId.isNotEmpty() && LocalWhisperModels.byId(activeId) != null) {
            activeModelLabel?.text = getString(R.string.local_whisper_active_model_fmt, activeId)
        } else {
            activeModelLabel?.text = getString(R.string.local_whisper_no_active_model)
        }

        for (model in LocalWhisperModels.ALL) refreshRow(model)

        val totalMb = (LocalWhisperStorage.totalUsedBytes(this) / 1_000_000).toInt()
        storageUsedLabel?.text = getString(R.string.local_whisper_storage_used_fmt, totalMb)
    }

    private fun refreshRow(model: LocalWhisperModels.Model) {
        val row = rowsByModelId[model.id] ?: return
        val installed = LocalWhisperStorage.isInstalled(this, model)
        val active = installed && preferences?.getActiveLocalWhisperModel() == model.id

        row.meta.text = when {
            active -> getString(R.string.local_whisper_meta_active_fmt, model.sizeMb)
            installed -> getString(R.string.local_whisper_meta_installed_fmt, model.sizeMb)
            else -> getString(R.string.local_whisper_meta_not_installed_fmt, model.sizeMb)
        }

        // The uninstall button is offered whenever the model is on disk,
        // including the active one — deleting the active model just clears
        // the active selection. It's hidden mid-download.
        val downloading = downloadJobs[model.id]?.isActive == true
        row.uninstallButton.visibility = if (installed && !downloading) View.VISIBLE else View.GONE

        when {
            !installed -> {
                row.actionButton.visibility = View.VISIBLE
                row.actionButton.text = getString(R.string.btn_download)
            }
            active -> {
                row.actionButton.visibility = View.GONE
            }
            else -> {
                row.actionButton.visibility = View.VISIBLE
                row.actionButton.text = getString(R.string.btn_make_active)
            }
        }
    }

    private fun confirmUninstall(model: LocalWhisperModels.Model) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.local_whisper_delete_confirm_title)
            .setMessage(getString(R.string.local_whisper_delete_confirm_msg_fmt, model.displayName))
            .setPositiveButton(R.string.btn_uninstall) { _, _ ->
                val wasActive = preferences?.getActiveLocalWhisperModel() == model.id
                LocalWhisperStorage.delete(this, model)
                if (wasActive) {
                    // Drop the selection and free the native context so the
                    // next transcription doesn't point at a deleted file.
                    preferences?.setActiveLocalWhisperModel("")
                    LocalWhisperEngine.get().release()
                }
                refreshAll()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun reloadAmoled() {
        try {
            if (isDarkThemeEnabled() && preferences?.getAmoledPitchBlack() == true) {
                window.setBackgroundDrawableResource(R.color.amoled_window_background)
                root?.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.amoled_window_background, theme))
                actionBar?.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
                btnBack?.backgroundTintList = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
            } else {
                window.setBackgroundDrawableResource(R.color.window_background)
                root?.setBackgroundColor(SurfaceColors.SURFACE_0.getColor(this))
                actionBar?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
                btnBack?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
            }
        } catch (_: Exception) {
            window.setBackgroundDrawableResource(R.color.window_background)
            root?.setBackgroundColor(SurfaceColors.SURFACE_0.getColor(this))
        }
    }

    private fun isDarkThemeEnabled(): Boolean = when (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) {
        Configuration.UI_MODE_NIGHT_YES -> true
        else -> false
    }
}
