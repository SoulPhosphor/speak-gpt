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
import android.view.Gravity
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
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.MemoryRecord
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.theme.ThemeManager

/**
 * The Pending screen (Phase 5 Stage 2.4, owner_approved_rules §14): draft
 * memories awaiting the user's judgment, grouped under collapsible
 * destination-scope headers (each with its own select-all), with checkboxes,
 * a Select all / Select none top bar, and Accept / Delete on the checked set
 * (with a count confirm). Tapping a draft opens the normal editor pre-filled;
 * saving there keeps it a draft, and the editor's own Accept activates it.
 *
 * No Archivist exists yet, so this screen is usually empty; it is built against
 * status='draft' rows and can be exercised by hand-setting a memory to draft.
 * A scoped entry (from a companion/world/campaign/RP browser) pre-filters to
 * that scope. All store work runs off the main thread; failures degrade to a
 * toast.
 */
class MemoryPendingActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId: String = ""

    private var presetCompanionId: String? = null
    private var presetWorldId: String? = null
    private var presetCampaignId: String? = null
    private var presetRoleplayCharacterId: String? = null

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var container: LinearLayout? = null
    private var emptyView: TextView? = null

    private val checked = LinkedHashSet<String>()
    /** Collapsed group scope keys (persist across a reload). */
    private val collapsed = HashSet<String>()

    private companion object {
        val GROUP_ORDER = listOf("global", "real_life", "companion", "project", "world", "campaign", "rp_character")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_memory_pending)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        presetCompanionId = intent.getStringExtra("companionId")
        presetWorldId = intent.getStringExtra("worldId")
        presetCampaignId = intent.getStringExtra("campaignId")
        presetRoleplayCharacterId = intent.getStringExtra("roleplayCharacterId")
        preferences = Preferences.getPreferences(this, chatId)

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        container = findViewById(R.id.pending_container)
        emptyView = findViewById(R.id.pending_empty)

        applyTheme()

        btnBack?.setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btn_select_all)?.setOnClickListener { selectAll(true) }
        findViewById<MaterialButton>(R.id.btn_select_none)?.setOnClickListener { selectAll(false) }
        findViewById<MaterialButton>(R.id.btn_accept)?.setOnClickListener { confirmAccept() }
        findViewById<MaterialButton>(R.id.btn_delete)?.setOnClickListener { confirmDelete() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    /* ------------------------------ data ------------------------------ */

    private fun scoped(): Boolean =
        presetCompanionId != null || presetWorldId != null ||
            presetCampaignId != null || presetRoleplayCharacterId != null

    private fun reload() {
        Thread {
            val drafts: List<MemoryRecord> = try {
                if (!MemoryStore.isProvisioned(this)) emptyList()
                else {
                    val store = MemoryStore.getInstance(this)
                    when {
                        presetCompanionId != null -> store.memoriesForCompanion(presetCompanionId!!, true)
                        presetWorldId != null -> store.memoriesForWorld(presetWorldId!!, true)
                        presetCampaignId != null -> store.memoriesForCampaign(presetCampaignId!!, true)
                        presetRoleplayCharacterId != null -> store.memoriesForRoleplayCharacter(presetRoleplayCharacterId!!, true)
                        else -> store.draftMemories()
                    }.filter { it.status == "draft" }
                }
            } catch (_: Exception) { emptyList() }
            runOnUiThread { render(drafts) }
        }.start()
    }

    private fun render(drafts: List<MemoryRecord>) {
        val root = container ?: return
        root.removeAllViews()
        checked.retainAll(drafts.map { it.memoryId }.toSet())

        if (drafts.isEmpty()) {
            emptyView?.visibility = View.VISIBLE
            return
        }
        emptyView?.visibility = View.GONE

        val groups = LinkedHashMap<String, MutableList<MemoryRecord>>()
        for (key in GROUP_ORDER) groups[key] = ArrayList()
        for (d in drafts) groups.getOrPut(d.scope) { ArrayList() }.add(d)

        for ((scope, rows) in groups) {
            if (rows.isEmpty()) continue
            root.addView(buildGroup(scope, rows))
        }
    }

    /* ------------------------------ view building ------------------------------ */

    private fun buildGroup(scope: String, rows: List<MemoryRecord>): View {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val groupBox = MaterialCheckBox(this).apply {
            isChecked = rows.all { checked.contains(it.memoryId) }
            setOnClickListener {
                val on = isChecked
                rows.forEach { if (on) checked.add(it.memoryId) else checked.remove(it.memoryId) }
                reRenderChildChecks(this@apply)
            }
        }
        val label = TextView(this).apply {
            text = getString(R.string.mem_pending_group, scopeLabel(scope), rows.size)
            textSize = 16f
            setTextColor(resources.getColor(R.color.text_title, theme))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(groupBox)
        header.addView(label)

        val childBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (collapsed.contains(scope)) View.GONE else View.VISIBLE
        }
        // Tapping the label (not the checkbox) collapses/expands the group.
        label.setOnClickListener {
            if (collapsed.contains(scope)) collapsed.remove(scope) else collapsed.add(scope)
            childBox.visibility = if (collapsed.contains(scope)) View.GONE else View.VISIBLE
        }

        for (m in rows) childBox.addView(buildRow(m, groupBox, rows))

        group.addView(header)
        group.addView(childBox)
        return group
    }

    private fun buildRow(m: MemoryRecord, groupBox: MaterialCheckBox, groupRows: List<MemoryRecord>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val box = MaterialCheckBox(this).apply {
            isChecked = checked.contains(m.memoryId)
            tag = m.memoryId
            setOnClickListener {
                if (isChecked) checked.add(m.memoryId) else checked.remove(m.memoryId)
                groupBox.isChecked = groupRows.all { checked.contains(it.memoryId) }
            }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { openEditor(m.memoryId) }
        }
        col.addView(TextView(this).apply {
            text = m.title
            textSize = 16f
            setTextColor(resources.getColor(R.color.text_title, theme))
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.mem_pending_dest, scopeLabel(m.scope), typeLabel(m.kind))
            textSize = 12f
            setTextColor(resources.getColor(R.color.text_subtitle, theme))
        })
        col.addView(TextView(this).apply {
            text = m.content.substringBefore('\n').trim()
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(resources.getColor(R.color.text_subtitle, theme))
        })
        row.addView(box)
        row.addView(col)
        return row
    }

    /** Sync child checkboxes after a group select-all toggle. */
    private fun reRenderChildChecks(groupBox: View) {
        val parent = groupBox.parent?.parent as? LinearLayout ?: return
        // parent = group; child 1 = childBox
        val childBox = parent.getChildAt(1) as? LinearLayout ?: return
        for (i in 0 until childBox.childCount) {
            val rowLayout = childBox.getChildAt(i) as? LinearLayout ?: continue
            val box = rowLayout.getChildAt(0) as? MaterialCheckBox ?: continue
            box.isChecked = checked.contains(box.tag)
        }
    }

    private fun selectAll(on: Boolean) {
        Thread {
            if (!MemoryStore.isProvisioned(this)) return@Thread
            val store = MemoryStore.getInstance(this)
            val ids = when {
                presetCompanionId != null -> store.memoriesForCompanion(presetCompanionId!!, true)
                presetWorldId != null -> store.memoriesForWorld(presetWorldId!!, true)
                presetCampaignId != null -> store.memoriesForCampaign(presetCampaignId!!, true)
                presetRoleplayCharacterId != null -> store.memoriesForRoleplayCharacter(presetRoleplayCharacterId!!, true)
                else -> store.draftMemories()
            }.filter { it.status == "draft" }.map { it.memoryId }
            runOnUiThread {
                checked.clear()
                if (on) checked.addAll(ids)
                reload()
            }
        }.start()
    }

    /* ------------------------------ actions ------------------------------ */

    private fun confirmAccept() {
        val ids = checked.toList()
        if (ids.isEmpty()) {
            Toast.makeText(this, R.string.mem_pending_none_checked, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_pending_accept_title)
            .setMessage(getString(R.string.mem_pending_accept_confirm, ids.size))
            .setPositiveButton(R.string.mem_pending_accept) { _, _ -> applyAccept(ids) }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun confirmDelete() {
        val ids = checked.toList()
        if (ids.isEmpty()) {
            Toast.makeText(this, R.string.mem_pending_none_checked, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_pending_delete_title)
            .setMessage(getString(R.string.mem_pending_delete_confirm, ids.size))
            .setPositiveButton(R.string.mem_pending_delete) { _, _ -> applyDelete(ids) }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun applyAccept(ids: List<String>) {
        runOffThread {
            val store = MemoryStore.getInstance(this)
            ids.forEach { store.setMemoryStatus(it, "active", getString(R.string.mem_pending_accepted_note)) }
            runOnUiThread {
                checked.clear()
                Toast.makeText(this, R.string.mem_pending_done, Toast.LENGTH_SHORT).show()
                reload()
            }
        }
    }

    private fun applyDelete(ids: List<String>) {
        runOffThread {
            val store = MemoryStore.getInstance(this)
            ids.forEach { store.deleteMemory(it) }
            runOnUiThread {
                checked.clear()
                Toast.makeText(this, R.string.mem_pending_done, Toast.LENGTH_SHORT).show()
                reload()
            }
        }
    }

    private fun openEditor(memoryId: String) {
        startActivity(
            Intent(this, MemoryEditorActivity::class.java)
                .putExtra("chatId", chatId)
                .putExtra("memoryId", memoryId)
        )
    }

    /* ------------------------------ labels ------------------------------ */

    private fun scopeLabel(key: String) = getString(
        when (key) {
            "global" -> R.string.mem_scope_global
            "real_life" -> R.string.mem_scope_real_life
            "companion" -> R.string.mem_scope_companion
            "project" -> R.string.mem_scope_project
            "world" -> R.string.mem_scope_world
            "campaign" -> R.string.mem_scope_campaign
            "rp_character" -> R.string.mem_scope_rp_character
            else -> R.string.mem_scope_global
        }
    )

    private fun typeLabel(key: String) = getString(
        when (key) {
            "fact" -> R.string.mem_type_fact
            "preference" -> R.string.mem_type_preference
            "event" -> R.string.mem_type_event
            "status" -> R.string.mem_type_status
            "instruction" -> R.string.mem_type_instruction
            else -> R.string.mem_type_lore
        }
    )

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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
                0, 0, 0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
            )
        } catch (_: Exception) { /* unused */ }
    }
}
