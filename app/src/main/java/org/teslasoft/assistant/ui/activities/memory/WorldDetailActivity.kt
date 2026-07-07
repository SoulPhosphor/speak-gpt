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
import android.widget.ScrollView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.WorldRecord
import org.teslasoft.assistant.theme.ThemeManager

/**
 * The Phase 5 world page: editable name/premise/rules, a world-scoped memory
 * browser (launched at [MemoryBrowserActivity] with the "worldId" extra), and a
 * single "Tear down" action offering archive-all or delete-all (delete-all can
 * keep memories that also belong to a character). There are NO structured
 * worldbuilding fields — cities/cultures are world-scoped memories in prose
 * (app_adaptation_notes §Worlds UI).
 *
 * A brand-new (unsaved) world hides the memories/teardown controls until it has
 * an id to hang them on. All store work runs off the main thread; every failure
 * degrades to a toast, never a crash; destructive actions always confirm.
 */
class WorldDetailActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId: String = ""

    /** Null until the world is saved once; then the persisted id. */
    private var worldId: String? = null
    /** The last loaded/saved record — the source of the fields we preserve
     *  (status, companion links, createdAt) that this screen doesn't edit. */
    private var existing: WorldRecord? = null

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var titleView: android.widget.TextView? = null
    private var fieldName: TextInputEditText? = null
    private var fieldPremise: TextInputEditText? = null
    private var fieldRules: TextInputEditText? = null
    private var btnSave: MaterialButton? = null
    private var btnMemories: MaterialButton? = null
    private var btnTeardown: MaterialButton? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_world_detail)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        worldId = intent.extras?.getString("worldId")
        preferences = Preferences.getPreferences(this, chatId)

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        titleView = findViewById(R.id.activity_title)
        fieldName = findViewById(R.id.field_world_name)
        fieldPremise = findViewById(R.id.field_world_premise)
        fieldRules = findViewById(R.id.field_world_rules)
        btnSave = findViewById(R.id.btn_world_save)
        btnMemories = findViewById(R.id.btn_world_memories)
        btnTeardown = findViewById(R.id.btn_world_teardown)

        titleView?.setText(
            if (worldId == null) R.string.mem_world_detail_new_title else R.string.mem_world_detail_title
        )

        applyTheme()

        btnBack?.setOnClickListener { finish() }
        btnSave?.setOnClickListener { save() }
        btnMemories?.setOnClickListener { openMemories() }
        btnTeardown?.setOnClickListener { showTeardownDialog() }

        updateExtraButtons()
        loadIfExisting()
    }

    /* ------------------------------ load ------------------------------ */

    private fun loadIfExisting() {
        val id = worldId ?: return
        runOffThread {
            if (!MemoryStore.isProvisioned(this)) return@runOffThread
            val w = MemoryStore.getInstance(this).getWorld(id)
            runOnUiThread {
                existing = w
                if (w != null) {
                    fieldName?.setText(w.name)
                    fieldPremise?.setText(w.premise)
                    fieldRules?.setText(w.rules ?: "")
                }
                updateExtraButtons()
            }
        }
    }

    /** Memories/teardown only make sense once the world exists in the store. */
    private fun updateExtraButtons() {
        val saved = worldId != null
        btnMemories?.visibility = if (saved) android.view.View.VISIBLE else android.view.View.GONE
        btnTeardown?.visibility = if (saved) android.view.View.VISIBLE else android.view.View.GONE
    }

    /* ------------------------------ save ------------------------------ */

    private fun save() {
        val name = fieldName?.text?.toString()?.trim().orEmpty()
        val premise = fieldPremise?.text?.toString()?.trim().orEmpty()
        val rules = fieldRules?.text?.toString()?.trim().orEmpty()

        if (name.isEmpty() || premise.isEmpty()) {
            Toast.makeText(this, R.string.mem_world_required, Toast.LENGTH_SHORT).show()
            return
        }

        val prior = existing
        val id = worldId ?: MemoryStore.newId("w-")
        val record = WorldRecord(
            worldId = id,
            name = name,
            premise = premise,
            rules = rules.ifEmpty { null },
            // Card fields this screen doesn't edit yet (3.6b) — pass through so
            // saving here can't wipe them.
            cosmology = prior?.cosmology,
            premiseVibe = prior?.premiseVibe,
            magicRules = prior?.magicRules,
            companionIdsJson = prior?.companionIdsJson ?: "[]",
            status = prior?.status ?: "active",
            createdAt = prior?.createdAt ?: MemoryStore.nowIso()
        )

        runOffThread {
            MemoryStore.getInstance(this).upsertWorld(record)
            runOnUiThread {
                worldId = id
                existing = record
                titleView?.setText(R.string.mem_world_detail_title)
                updateExtraButtons()
                Toast.makeText(this, R.string.mem_world_saved, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /* ------------------------------ memories ------------------------------ */

    private fun openMemories() {
        val id = worldId
        if (id == null) {
            Toast.makeText(this, R.string.mem_world_save_first, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, MemoryBrowserActivity::class.java)
                .putExtra("chatId", chatId)
                .putExtra("worldId", id)
                .putExtra("screenTitle", fieldName?.text?.toString()?.trim().orEmpty())
        )
    }

    /* ------------------------------ teardown ------------------------------ */

    private fun showTeardownDialog() {
        val id = worldId ?: return
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_world_teardown_title)
            .setMessage(R.string.mem_world_teardown_msg)
            .setPositiveButton(R.string.mem_world_teardown_delete) { _, _ -> showDeleteDialog(id) }
            .setNeutralButton(R.string.mem_world_teardown_archive) { _, _ -> archive(id) }
            .setNegativeButton(R.string.mem_world_cancel) { _, _ -> }
            .show()
    }

    private fun archive(id: String) {
        runOffThread {
            MemoryStore.getInstance(this).archiveWorld(id)
            runOnUiThread {
                Toast.makeText(this, R.string.mem_world_archived, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun showDeleteDialog(id: String) {
        val keepBox = MaterialCheckBox(this).apply {
            setText(R.string.mem_world_delete_keep_characters)
            isChecked = true
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(keepBox)
        }
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_world_delete_title)
            .setMessage(R.string.mem_world_delete_msg)
            .setView(container)
            .setPositiveButton(R.string.mem_world_delete_confirm) { _, _ ->
                val keep = keepBox.isChecked
                runOffThread {
                    MemoryStore.getInstance(this).deleteWorld(id, deleteMemories = true, keepCharacterMemories = keep)
                    runOnUiThread {
                        Toast.makeText(this, R.string.mem_world_deleted, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
            .setNegativeButton(R.string.mem_world_cancel) { _, _ -> }
            .show()
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
                        getString(R.string.mem_world_op_failed, e.message ?: e.javaClass.simpleName),
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
            findViewById<ScrollView>(R.id.scroll)?.setPadding(
                0,
                0,
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom + pxToDp(24)
            )
        } catch (_: Exception) { /* unused */ }
    }

    private fun pxToDp(px: Int): Int = (px * resources.displayMetrics.density).toInt()
}
