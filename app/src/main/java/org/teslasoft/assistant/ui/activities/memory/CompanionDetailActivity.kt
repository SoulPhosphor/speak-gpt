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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.CompanionRecord
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.theme.ThemeManager

/**
 * Phase 5 companion detail/edit page (app_adaptation_notes §Characters area).
 * A companion IS the memory-system continuity file for one of the app's
 * personas: identity (the name) is app-owned and therefore READ-ONLY here — you
 * rename a companion by renaming its persona, never here.
 *
 * The page keeps only: the read-only name, the draft badge + Approve action,
 * and the memory-participation selector (Save persists that one field). The
 * essence / relationship-notes / hard-limits / model-adaptations sections were
 * removed (owner decision July 2026 — companion cards are author-only and the
 * relationship lives in the memories, not on the card). The underlying columns
 * stay in the store but nothing on this screen writes to them.
 *
 * A draft companion's memories never inject (the real draft gate lives in
 * `activeMemoriesForScope`); the approve button promotes draft -> active.
 *
 * All store work runs off the main thread; every failure degrades to a toast,
 * never a crash — the memory-UI contract.
 */
class CompanionDetailActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId = ""
    private var companionId = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null

    private var textName: TextView? = null
    private var draftContainer: LinearLayout? = null
    private var btnApprove: MaterialButton? = null
    private var rowParticipation: LinearLayout? = null
    private var textParticipationValue: TextView? = null
    private var btnSave: MaterialButton? = null

    // The loaded record; status drives the draft/approve section.
    private var record: CompanionRecord? = null

    // full | global_only | none — the participation currently selected in the UI.
    private var participation: String = "full"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_companion_detail)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        companionId = intent.extras?.getString("companionId", "") ?: ""
        preferences = Preferences.getPreferences(this, chatId)

        bindViews()
        applyTheme()

        btnBack?.setOnClickListener { finish() }
        rowParticipation?.setOnClickListener { showParticipationPicker() }
        btnApprove?.setOnClickListener { approve() }
        btnSave?.setOnClickListener { save() }

        load()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        textName = findViewById(R.id.text_companion_name)
        draftContainer = findViewById(R.id.draft_container)
        btnApprove = findViewById(R.id.btn_approve)
        rowParticipation = findViewById(R.id.row_participation)
        textParticipationValue = findViewById(R.id.text_participation_value)
        btnSave = findViewById(R.id.btn_save)
    }

    /* ------------------------------ data ------------------------------ */

    private fun load() {
        runOffThread {
            val loaded = if (MemoryStore.isProvisioned(this)) {
                MemoryStore.getInstance(this).getCompanion(companionId)
            } else null
            runOnUiThread {
                if (loaded == null) {
                    Toast.makeText(this, R.string.mem_comp_load_failed, Toast.LENGTH_LONG).show()
                    finish()
                    return@runOnUiThread
                }
                record = loaded
                bindRecord(loaded)
            }
        }
    }

    private fun bindRecord(c: CompanionRecord) {
        textName?.text = c.currentName
        participation = c.memoryParticipation
        refreshParticipationRow()

        draftContainer?.visibility = if (c.status == "draft") View.VISIBLE else View.GONE
    }

    /* ------------------------------ participation ------------------------------ */

    private fun participationLabel(value: String): String = when (value) {
        "global_only" -> getString(R.string.mem_comp_participation_global_only)
        "none" -> getString(R.string.mem_comp_participation_none)
        else -> getString(R.string.mem_comp_participation_full)
    }

    private fun refreshParticipationRow() {
        textParticipationValue?.text = participationLabel(participation)
    }

    private fun showParticipationPicker() {
        val values = arrayOf("full", "global_only", "none")
        val labels = arrayOf(
            getString(R.string.mem_comp_participation_full),
            getString(R.string.mem_comp_participation_global_only),
            getString(R.string.mem_comp_participation_none)
        )
        val current = values.indexOf(participation).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_comp_participation_picker_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                participation = values[which]
                refreshParticipationRow()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    /* ------------------------------ actions ------------------------------ */

    private fun approve() {
        val id = companionId
        runOffThread {
            MemoryStore.getInstance(this).setCompanionStatus(id, "active")
            runOnUiThread {
                Toast.makeText(this, R.string.mem_comp_approved_toast, Toast.LENGTH_SHORT).show()
                load()
            }
        }
    }

    private fun save() {
        // Participation is the only field this screen still writes; the
        // essence/relationship/limits/adaptations columns stay untouched.
        val id = companionId
        val part = participation

        runOffThread {
            MemoryStore.getInstance(this).updateCompanionParticipation(id, part)
            runOnUiThread {
                Toast.makeText(this, R.string.mem_comp_saved_toast, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
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
            val scroll = findViewById<ScrollView>(R.id.scroll)
            scroll?.setPadding(
                0,
                0,
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom + pxToDp(24)
            )
        } catch (_: Exception) { /* unused */ }
    }

    private fun pxToDp(px: Int): Int = (px * resources.displayMetrics.density).toInt()
}
