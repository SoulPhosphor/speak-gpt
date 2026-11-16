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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModelDownloader
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModelStorage
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModels
import org.teslasoft.assistant.preferences.memory.librarian.Librarian
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.ScreenChrome

/**
 * Embedding Model, opened from AI Settings (owner design, Nov 16 2026): the
 * on-device embedding models with their Download / Delete / Cancel buttons,
 * moved here unchanged from Advanced Memory Settings. Rebuild Memory Index
 * stays on Advanced Memory Settings. While no model is installed, the
 * keyword-search-only line is shown under the models.
 */
class EmbeddingModelActivity : SettingsPageActivity() {

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var models: LinearLayout? = null
    private var textNoModel: TextView? = null

    // Per-model download jobs so a second tap cancels an in-flight download.
    private val downloadJobs = HashMap<String, Job>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_embedding_model)

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        models = findViewById(R.id.embedding_models)
        textNoModel = findViewById(R.id.text_no_model)

        ScreenChrome.apply(this, actionBar, btnBack)
        ScreenChrome.fitSystemBars(findViewById(R.id.root), actionBar, findViewById(R.id.scroll))

        btnBack?.setOnClickListener { finish() }
        buildModelRows()
    }

    override fun onResume() {
        super.onResume()
        refreshNoModelLine()
    }

    private fun buildModelRows() {
        val container = models ?: return
        val inflater = LayoutInflater.from(this)
        container.removeAllViews()
        for (model in EmbeddingModels.ALL) {
            val row = inflater.inflate(R.layout.view_embedding_model_row, container, false)
            row.tag = model.id
            container.addView(row)
            refreshModelRow(row, model)
        }
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
            val result = EmbeddingModelDownloader.download(this@EmbeddingModelActivity, model) { bytes, total ->
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
                        Librarian.getInstance(this@EmbeddingModelActivity).invalidateModel()
                        Toast.makeText(this@EmbeddingModelActivity,
                            getString(R.string.memory_model_downloaded, model.displayName), Toast.LENGTH_SHORT).show()
                    }
                    EmbeddingModelDownloader.Result.Canceled -> { }
                    is EmbeddingModelDownloader.Result.Failed ->
                        Toast.makeText(this@EmbeddingModelActivity,
                            getString(R.string.memory_model_download_failed, result.reason), Toast.LENGTH_LONG).show()
                }
                refreshModelRow(row, model)
                refreshNoModelLine()
            }
        }
        downloadJobs[model.id] = job
        refreshModelRow(row, model)
    }

    private fun deleteModel(model: EmbeddingModels.Model, row: View) {
        EmbeddingModelStorage.delete(this, model)
        Librarian.getInstance(this).invalidateModel()
        refreshModelRow(row, model)
        refreshNoModelLine()
    }

    /** The keyword-search-only line, shown while no model is in use. */
    private fun refreshNoModelLine() {
        Thread {
            val none = try {
                Librarian.getInstance(this).activeTag() == null
            } catch (_: Exception) { true }
            runOnUiThread { textNoModel?.visibility = if (none) View.VISIBLE else View.GONE }
        }.start()
    }
}
