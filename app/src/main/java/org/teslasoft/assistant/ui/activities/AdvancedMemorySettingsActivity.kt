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

import org.teslasoft.assistant.ui.util.ScreenChrome

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
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
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import com.google.android.material.button.MaterialButton
import com.google.android.material.elevation.SurfaceColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.librarian.Librarian
import org.teslasoft.assistant.theme.ThemeManager

/**
 * "Advanced Memory Settings" — the diagnostics/repair surface from the Memory
 * Settings reorganization (`Memory System/memory_settings_reorg_spec.md` §3,
 * July 9 2026). Technical tools only: System Status (store health + row
 * counts) and Rebuild Memory Index; shown to the user as "Memory Repair Settings". Reached from
 * the Memory Manager hub. Embedding model download/remove moved to Embedding
 * Model in AI Settings, and Memory Search Test to RAG Active Memory Settings
 * (the Memory Controls screen) (Nov 16 2026).
 *
 * Reset Memories deliberately does NOT live here — it stays on Memory Controls
 * as the single home for that destructive action (placement ruling 5).
 */
class AdvancedMemorySettingsActivity : SettingsPageActivity() {

    private var preferences: Preferences? = null
    private var chatId = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null

    private var textStatus: TextView? = null

    private var btnRebuildIndex: MaterialButton? = null
    private var textIndexStatus: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_advanced_memory_settings)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        preferences = Preferences.getPreferences(this, chatId)

        bindViews()
        applyTheme()
        initLogic()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshIndexStatus()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        textStatus = findViewById(R.id.text_memory_status)
        btnRebuildIndex = findViewById(R.id.btn_rebuild_index)
        textIndexStatus = findViewById(R.id.text_index_status)
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

        btnRebuildIndex?.setOnClickListener { rebuildIndex() }
    }

    /* ------------------------------ status ------------------------------ */

    private fun refreshStatus() {
        runOffThread {
            val status: String
            if (!MemoryStore.isProvisioned(this)) {
                status = getString(R.string.memory_status_not_provisioned)
            } else {
                val store = MemoryStore.getInstance(this)
                val counts = store.counts()
                val problem = store.integrityCheck()
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
            runOnUiThread { textStatus?.text = status }
        }
    }

    /* ------------------------------ index ------------------------------ */

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

    /** The no-model line moved to the Embedding Model screen; with no model
     *  installed this line stays empty. */
    private fun refreshIndexStatus() {
        Thread {
            val text = try {
                val librarian = Librarian.getInstance(this)
                when {
                    librarian.activeTag() == null -> ""
                    librarian.indexNeedsRebuild() -> getString(R.string.memory_index_stale)
                    !MemoryStore.isProvisioned(this) -> getString(R.string.memory_index_ok, 0)
                    else -> getString(R.string.memory_index_ok,
                        MemoryStore.getInstance(this).counts()["memories"] ?: 0)
                }
            } catch (_: Exception) { "" }
            runOnUiThread { textIndexStatus?.text = text }
        }.start()
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
        ScreenChrome.fitSystemBarsAndKeyboard(
            findViewById<android.view.ViewGroup>(android.R.id.content)?.getChildAt(0),
            actionBar,
            findViewById<android.view.View>(R.id.scroll)
        )
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
