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
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.assistant.MemoryAssistantRunner
import org.teslasoft.assistant.theme.ThemeManager

/**
 * The Memory Assistant page (Phase 6, task 6.2 — the owner-approved words in
 * phase6_memory_assistant_work_order.md, verbatim): the Analyze History
 * control triggers one manual run over the pending conversations, and a
 * plain Advanced Settings row (a row, not a button) opens the knobs screen.
 * Suggestions land in the existing memory browser/Pending screen — this page
 * deliberately has no review or report surface of its own.
 */
class MemoryAssistantActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var textStatus: TextView? = null
    private var btnAnalyze: MaterialButton? = null
    private var progress: LinearProgressIndicator? = null
    private var textResult: TextView? = null
    private var rowAdvanced: LinearLayout? = null

    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_memory_assistant)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        preferences = Preferences.getPreferences(this, chatId)

        bindViews()
        applyTheme()
        initLogic()
    }

    override fun onResume() {
        super.onResume()
        if (!running) refreshQueueCount()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        textStatus = findViewById(R.id.text_assistant_status)
        btnAnalyze = findViewById(R.id.btn_analyze)
        progress = findViewById(R.id.analyze_progress)
        textResult = findViewById(R.id.text_assistant_result)
        rowAdvanced = findViewById(R.id.row_advanced_settings)
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
        btnAnalyze?.setOnClickListener { analyze() }
        rowAdvanced?.setOnClickListener {
            startActivity(Intent(this, MemoryAssistantAdvancedActivity::class.java).putExtra("chatId", chatId))
        }
    }

    private fun refreshQueueCount() {
        Thread {
            val count = try {
                if (MemoryStore.isProvisioned(this)) MemoryStore.getInstance(this).listPendingTranscripts().size else 0
            } catch (_: Exception) { 0 }
            runOnUiThread { textStatus?.text = getString(R.string.memory_assistant_status_fmt, count) }
        }.start()
    }

    /** One manual run. Everything the runner needs to guard (config, empty
     *  queue, safety laws) is guarded in the runner itself — this just
     *  reports honestly what happened. */
    private fun analyze() {
        if (running) return
        running = true
        btnAnalyze?.isEnabled = false
        progress?.visibility = android.view.View.VISIBLE
        progress?.isIndeterminate = true
        textResult?.text = ""

        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                MemoryAssistantRunner.run(this@MemoryAssistantActivity) { done, total ->
                    runOnUiThread {
                        progress?.isIndeterminate = false
                        progress?.max = total
                        progress?.setProgressCompat(done, true)
                        textResult?.text = getString(R.string.memory_assistant_progress_fmt, done, total)
                    }
                }
            } catch (e: Exception) {
                MemoryAssistantRunner.RunResult(0, 0, 0, e.message ?: e.javaClass.simpleName)
            }
            withContext(Dispatchers.Main) {
                running = false
                btnAnalyze?.isEnabled = true
                progress?.visibility = android.view.View.GONE
                textResult?.text = renderResult(result)
                refreshQueueCount()
            }
        }
    }

    private fun renderResult(r: MemoryAssistantRunner.RunResult): String {
        if (r.error == "not_configured" || r.error == "store_not_provisioned") {
            Toast.makeText(this, R.string.memory_assistant_not_configured, Toast.LENGTH_LONG).show()
            return getString(R.string.memory_assistant_not_configured)
        }
        val lines = ArrayList<String>()
        when {
            r.conversationsAnalyzed == 0 && r.failures == 0 && r.error == null ->
                lines.add(getString(R.string.memory_assistant_nothing_pending))
            r.suggestionsFiled == 0 && r.error == null && r.failures == 0 ->
                lines.add(getString(R.string.memory_assistant_done_none))
            else ->
                lines.add(getString(R.string.memory_assistant_done_fmt, r.suggestionsFiled, r.conversationsAnalyzed))
        }
        if (r.failures > 0) lines.add(getString(R.string.memory_assistant_done_failures_fmt, r.failures))
        if (r.error != null) lines.add(getString(R.string.memory_assistant_error_fmt, r.error))
        return lines.joinToString("\n")
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
