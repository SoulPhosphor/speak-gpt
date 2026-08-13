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

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.models.EndpointCatalogCheck
import org.teslasoft.assistant.preferences.models.ModelCleanupPolicy
import org.teslasoft.assistant.preferences.models.ModelCleanupReferences
import org.teslasoft.assistant.preferences.models.ModelCleanupReferencesLoader
import org.teslasoft.assistant.preferences.models.ModelCleanupReport
import org.teslasoft.assistant.preferences.models.ModelCleanupReportStore
import org.teslasoft.assistant.preferences.models.ModelIdentity
import org.teslasoft.assistant.providers.ModelCatalogAvailabilityClient
import org.teslasoft.assistant.theme.ThemeManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Saved, user-triggered availability report for Favorite Models and Model Rules. */
class ModelCleanupActivity : FragmentActivity() {

    private data class ScreenData(
        val references: ModelCleanupReferences,
        val report: ModelCleanupReport,
        val endpointLabels: Map<String, String>
    )

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var textUnchecked: TextView? = null
    private var textGenerated: TextView? = null
    private var btnCheck: MaterialButton? = null
    private var progress: View? = null
    private var favoritesContainer: LinearLayout? = null
    private var rulesContainer: LinearLayout? = null
    private var noFavorites: TextView? = null
    private var noRules: TextView? = null
    private var btnDeleteFavorites: MaterialButton? = null
    private var btnDeleteRules: MaterialButton? = null

    private lateinit var reportStore: ModelCleanupReportStore
    private var screenData: ScreenData? = null
    private var scanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_model_cleanup)
        reportStore = ModelCleanupReportStore.get(this)
        bindViews()

        btnBack?.setOnClickListener { finish() }
        btnCheck?.setOnClickListener { runScan() }
        btnDeleteFavorites?.setOnClickListener { confirmDeleteFavorites() }
        btnDeleteRules?.setOnClickListener { confirmDeleteRuleTargets() }

        // Local-only reconciliation. This is intentionally not a scan.
        refreshFromLocal()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        textUnchecked = findViewById(R.id.text_unchecked_endpoints)
        textGenerated = findViewById(R.id.text_report_generated)
        btnCheck = findViewById(R.id.btn_check_models)
        progress = findViewById(R.id.model_cleanup_progress)
        favoritesContainer = findViewById(R.id.container_unavailable_favorites)
        rulesContainer = findViewById(R.id.container_unavailable_rules)
        noFavorites = findViewById(R.id.text_no_unavailable_favorites)
        noRules = findViewById(R.id.text_no_unavailable_rules)
        btnDeleteFavorites = findViewById(R.id.btn_delete_all_favorites)
        btnDeleteRules = findViewById(R.id.btn_delete_all_rules)
    }

    private fun refreshFromLocal() {
        lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    val references = ModelCleanupReferencesLoader.load(this@ModelCleanupActivity)
                    val saved = reportStore.load()
                    val pruned = if (references.isComplete) {
                        ModelCleanupPolicy.prune(saved, references.allTargets)
                    } else {
                        saved
                    }
                    if (references.isComplete) reportStore.save(pruned)
                    ScreenData(references, pruned, currentEndpointLabels())
                }
                screenData = data
                render(data)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showCheckFailed()
            }
        }
    }

    private fun runScan() {
        if (scanning) return
        scanning = true
        setScanningUi(true)
        lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    val references = ModelCleanupReferencesLoader.load(this@ModelCleanupActivity)
                    check(references.isComplete) { "Saved model references could not be read." }
                    val endpoints = ApiEndpointPreferences.getApiEndpointPreferences(this@ModelCleanupActivity)
                        .getApiEndpointsList(this@ModelCleanupActivity)
                        .associateBy { it.id }
                    val previous = reportStore.load()
                    val labels = LinkedHashMap(previous.endpointLabels)
                    endpoints.values.forEach { labels[it.id] = endpointDisplayLabel(it) }

                    val targetsByEndpoint = references.allTargets.groupBy { it.endpointId }
                    val checks = coroutineScope {
                        targetsByEndpoint.keys.map { endpointId ->
                            async {
                                endpointId to (endpoints[endpointId]?.let {
                                    ModelCatalogAvailabilityClient.check(
                                        it,
                                        targetsByEndpoint[endpointId]
                                            .orEmpty()
                                            .map { target -> target.modelId }
                                            .toSet()
                                    )
                                } ?: EndpointCatalogCheck.Unchecked)
                            }
                        }.awaitAll().toMap()
                    }
                    val report = ModelCleanupPolicy.update(
                        previous = previous,
                        currentTargets = references.allTargets,
                        checks = checks,
                        endpointLabels = labels,
                        generatedAtMillis = System.currentTimeMillis()
                    )
                    reportStore.save(report)
                    ScreenData(references, report, labels)
                }
                screenData = data
                render(data)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showCheckFailed()
            } finally {
                scanning = false
                setScanningUi(false)
            }
        }
    }

    private fun showCheckFailed() {
        Toast.makeText(this, R.string.model_cleanup_check_failed, Toast.LENGTH_LONG).show()
    }

    private fun render(data: ScreenData) {
        val report = data.report
        textGenerated?.text = if (report.hasReport) {
            getString(R.string.model_cleanup_report_generated, formatReportTime(report.generatedAtMillis))
        } else {
            getString(R.string.model_cleanup_no_report)
        }

        val uncheckedNotes = report.uncheckedEndpointIds.map { endpointId ->
            getString(
                R.string.model_cleanup_endpoint_unchecked,
                displayLabel(endpointId, data)
            )
        }
        textUnchecked?.apply {
            visibility = if (uncheckedNotes.isEmpty()) View.GONE else View.VISIBLE
            text = uncheckedNotes.joinToString("\n\n")
        }
        val unavailableFavorites = data.references.favorites.intersect(report.unavailable)
        val unavailableRules = data.references.ruleTargets.intersect(report.unavailable)
        renderGroups(favoritesContainer, unavailableFavorites, data)
        renderGroups(rulesContainer, unavailableRules, data)
        noFavorites?.visibility = if (unavailableFavorites.isEmpty()) View.VISIBLE else View.GONE
        noRules?.visibility = if (unavailableRules.isEmpty()) View.VISIBLE else View.GONE
        btnDeleteFavorites?.visibility = if (unavailableFavorites.isEmpty()) View.GONE else View.VISIBLE
        btnDeleteRules?.visibility = if (unavailableRules.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderGroups(
        container: LinearLayout?,
        targets: Set<ModelIdentity>,
        data: ScreenData
    ) {
        val parent = container ?: return
        parent.removeAllViews()
        targets.groupBy { it.endpointId }
            .toList()
            .sortedBy { (endpointId, _) -> displayLabel(endpointId, data).lowercase(Locale.getDefault()) }
            .forEach { (endpointId, models) ->
                parent.addView(TextView(this, null, 0, R.style.Widget_App_Row_Title).apply {
                    text = displayLabel(endpointId, data)
                    setPadding(0, dp(16), 0, dp(4))
                })
                models.sortedBy { it.modelId.lowercase(Locale.getDefault()) }.forEach { target ->
                    parent.addView(TextView(this, null, 0, R.style.Widget_App_Row_Subtitle).apply {
                        text = target.modelId
                        maxLines = 2
                        setPadding(dp(12), dp(5), 0, dp(5))
                    })
                }
            }
    }

    private fun confirmDeleteFavorites() {
        val data = screenData ?: return
        val targets = data.references.favorites.intersect(data.report.unavailable)
        if (targets.isEmpty()) return
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.model_cleanup_delete_favorites_confirm_title)
            .setMessage(R.string.model_cleanup_delete_favorites_confirm_message)
            .setPositiveButton(R.string.model_cleanup_delete_all) { _, _ ->
                lifecycleScope.launch {
                    val removed = withContext(Dispatchers.IO) {
                        FavoriteModelsPreferences.getPreferences(this@ModelCleanupActivity)
                            .removeFavoriteModels(targets)
                    }
                    Toast.makeText(
                        this@ModelCleanupActivity,
                        resources.getQuantityString(
                            R.plurals.model_cleanup_removed_favorites,
                            removed,
                            removed
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshFromLocal()
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun confirmDeleteRuleTargets() {
        val data = screenData ?: return
        val targets = data.references.ruleTargets.intersect(data.report.unavailable)
        if (targets.isEmpty()) return
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.model_cleanup_delete_rules_confirm_title)
            .setMessage(R.string.model_cleanup_delete_rules_confirm_message)
            .setPositiveButton(R.string.model_cleanup_delete_all) { _, _ ->
                lifecycleScope.launch {
                    val removed = withContext(Dispatchers.IO) {
                        if (!MemoryStore.isProvisioned(this@ModelCleanupActivity)) 0
                        else MemoryStore.getInstance(this@ModelCleanupActivity)
                            .removeModelTargets(targets)
                            .removedTargets
                    }
                    Toast.makeText(
                        this@ModelCleanupActivity,
                        resources.getQuantityString(
                            R.plurals.model_cleanup_removed_rule_targets,
                            removed,
                            removed
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshFromLocal()
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun setScanningUi(active: Boolean) {
        btnCheck?.isEnabled = !active
        btnDeleteFavorites?.isEnabled = !active
        btnDeleteRules?.isEnabled = !active
        progress?.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun currentEndpointLabels(): Map<String, String> = try {
        ApiEndpointPreferences.getApiEndpointPreferences(this)
            .getApiEndpointsList(this)
            .associate { it.id to endpointDisplayLabel(it) }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun endpointDisplayLabel(endpoint: ApiEndpointObject): String = when {
        endpoint.provider.isNotBlank() &&
            !endpoint.provider.equals(endpoint.label, ignoreCase = true) ->
            "${endpoint.provider} — ${endpoint.label}"
        endpoint.label.isNotBlank() -> endpoint.label
        endpoint.provider.isNotBlank() -> endpoint.provider
        else -> endpoint.id
    }

    private fun displayLabel(endpointId: String, data: ScreenData): String =
        data.endpointLabels[endpointId]
            ?: data.report.endpointLabels[endpointId]
            ?: getString(R.string.model_cleanup_unknown_endpoint)

    private fun formatReportTime(epochMillis: Long): String =
        DateTimeFormatter.ofPattern("MMMM d, yyyy h:mm a", Locale.getDefault())
            .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

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
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom + dp(24)
            )
        } catch (_: Exception) { }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

}
