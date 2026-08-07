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
import android.widget.ImageView
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
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONArray
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.MemoryRecord
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.PossibleMatchFinder
import org.teslasoft.assistant.preferences.memory.librarian.Librarian
import org.teslasoft.assistant.theme.ThemeManager

/**
 * The Possible Match Review screen (Step 1.5): a dedicated full-page comparison
 * where a proposed Associative Memory is weighed against the existing memories
 * it may overlap, and one of the three atomic resolutions is applied.
 *
 * The proposed memory is the first card and scrolls normally (never pinned);
 * each existing possible match follows in the normal memory-card format with a
 * checkbox (top-left, pre-checked) and an Info control (top-right). Resolutions
 * live in their own section after the last match:
 *   - Save & Edit Old Memory — one selection only; activates the proposal, keeps
 *     the old memory active, and applies inline title/content edits to it;
 *   - Save & Supersede — activates the proposal, marks the checked memories
 *     superseded (history preserved);
 *   - Save & Replace — activates the proposal, permanently deletes the checked
 *     memories.
 *
 * Every resolution is revalidated against the live store and applied through the
 * store's atomic operations — never partially. If the proposal or a selected
 * memory changed or vanished, nothing is applied and the user stays here.
 */
class MemoryPossibleMatchReviewActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId: String = ""
    private var draftId: String = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var container: LinearLayout? = null
    private var resolution: View? = null
    private var selectHint: TextView? = null
    private var btnEdit: MaterialButton? = null
    private var btnSupersede: MaterialButton? = null
    private var btnReplace: MaterialButton? = null

    /** One existing possible-match card and its controls. */
    private class MatchHolder(
        val record: MemoryRecord,
        val check: MaterialCheckBox,
        val editSection: View,
        val editTitle: TextInputEditText,
        val editContent: TextInputEditText
    )

    private val matchHolders = ArrayList<MatchHolder>()

    /** The card currently in inline-edit mode (Save & Edit Old Memory), or null. */
    private var editingCardId: String? = null

    /** True while a resolution is being applied, to block double-taps. */
    private var applying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_memory_possible_match_review)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        draftId = intent.extras?.getString("draftId", "") ?: ""
        preferences = Preferences.getPreferences(this, chatId)

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        container = findViewById(R.id.review_container)
        resolution = findViewById(R.id.review_resolution)
        selectHint = findViewById(R.id.review_select_hint)
        btnEdit = findViewById(R.id.btn_review_edit)
        btnSupersede = findViewById(R.id.btn_review_supersede)
        btnReplace = findViewById(R.id.btn_review_replace)

        applyTheme()
        btnBack?.setOnClickListener { finish() }
        btnEdit?.setOnClickListener { onEditClick() }
        btnSupersede?.setOnClickListener { applyResolution(ResolutionKind.SUPERSEDE) }
        btnReplace?.setOnClickListener { applyResolution(ResolutionKind.REPLACE) }

        load()
    }

    /* ------------------------------ load ------------------------------ */

    private fun load() {
        editingCardId = null
        runOffThread {
            if (!MemoryStore.isProvisioned(this)) {
                runOnUiThread { finish() }
                return@runOffThread
            }
            val store = MemoryStore.getInstance(this)
            val proposal = store.getMemory(draftId)?.takeIf { it.status == "draft" }
            if (proposal == null) {
                runOnUiThread {
                    Toast.makeText(this, R.string.mem_review_gone, Toast.LENGTH_LONG).show()
                    finish()
                }
                return@runOffThread
            }
            // Fresh detection — the same finder the browser used, recomputed here
            // so Review always reflects the current library.
            val matchIds = PossibleMatchFinder.find(this, draftId).matches.map { it.memoryId }
            val matches = matchIds.mapNotNull { store.getMemory(it) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (matches.isEmpty()) {
                    // The overlap is gone; nothing to resolve. The browser will
                    // show this suggestion as conflict-free after it re-checks.
                    Toast.makeText(this, R.string.mem_review_no_matches, Toast.LENGTH_LONG).show()
                    finish()
                    return@runOnUiThread
                }
                buildUi(proposal, matches)
            }
        }
    }

    private fun buildUi(proposal: MemoryRecord, matches: List<MemoryRecord>) {
        val c = container ?: return
        c.removeAllViews()
        matchHolders.clear()

        addIntro(c)
        addHeader(c, getString(R.string.mem_review_proposed_header))
        addMemoryCard(c, proposal, withCheckbox = false)
        addHeader(c, getString(R.string.mem_review_existing_header))
        for (m in matches) matchHolders.add(addMemoryCard(c, m, withCheckbox = true))

        resolution?.visibility = View.VISIBLE
        refreshResolutionButtons()
    }

    private fun addIntro(parent: LinearLayout) {
        val tv = TextView(this).apply {
            text = getString(R.string.mem_review_intro)
            setTextColor(ResourcesCompat.getColor(resources, R.color.text_subtitle, theme))
            textSize = 14f
            val h = dp(20); setPadding(h, dp(4), h, dp(12))
        }
        parent.addView(tv)
    }

    private fun addHeader(parent: LinearLayout, text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        parent.addView(tv)
    }

    private fun addMemoryCard(parent: LinearLayout, record: MemoryRecord, withCheckbox: Boolean): MatchHolder {
        val card = layoutInflater.inflate(R.layout.view_review_memory_card, parent, false)
        card.findViewById<ImageView>(R.id.review_icon).setImageResource(iconForScope(record.scope))
        // Titles are retired (§3.1): the review card shows content, not a title.
        card.findViewById<TextView>(R.id.review_title).visibility = View.GONE
        val tags = parseTags(record.tagsJson)
        card.findViewById<TextView>(R.id.review_tags).apply {
            if (tags.isEmpty()) visibility = View.GONE
            else { visibility = View.VISIBLE; text = formatTags(tags) }
        }
        card.findViewById<TextView>(R.id.review_content).text = record.content
        card.findViewById<ImageButton>(R.id.review_info).setOnClickListener {
            MemoryInfoDialog.show(this, record)
        }
        val check = card.findViewById<MaterialCheckBox>(R.id.review_check)
        if (withCheckbox) {
            check.visibility = View.VISIBLE
            check.isChecked = true
            check.setOnCheckedChangeListener { _, _ -> onSelectionChanged() }
        } else {
            check.visibility = View.GONE
        }
        parent.addView(card)
        return MatchHolder(
            record, check,
            card.findViewById(R.id.review_edit_section),
            card.findViewById(R.id.review_edit_title),
            card.findViewById(R.id.review_edit_content)
        )
    }

    /* ------------------------------ selection ------------------------------ */

    private fun checkedHolders(): List<MatchHolder> = matchHolders.filter { it.check.isChecked }

    private fun onSelectionChanged() {
        // Leaving a single selection cancels an in-progress inline edit.
        val editing = editingCardId
        if (editing != null && checkedHolders().singleOrNull()?.record?.memoryId != editing) {
            matchHolders.firstOrNull { it.record.memoryId == editing }?.editSection?.visibility = View.GONE
            editingCardId = null
        }
        refreshResolutionButtons()
    }

    private fun refreshResolutionButtons() {
        if (applying) return
        val count = checkedHolders().size
        val editing = editingCardId != null
        btnSupersede?.isEnabled = count >= 1 && !editing
        btnReplace?.isEnabled = count >= 1 && !editing
        // Save & Edit Old Memory: exactly one selection (or already editing it).
        btnEdit?.isEnabled = count == 1 || editing
        btnEdit?.text = getString(if (editing) R.string.mem_review_edit_save else R.string.mem_review_save_edit)
        selectHint?.visibility = if (count == 0) View.VISIBLE else View.GONE
    }

    /* ------------------------------ edit-old ------------------------------ */

    private fun onEditClick() {
        if (applying) return
        val editing = editingCardId
        if (editing == null) {
            // First tap: reveal the single selected card's inline editor. Titles
            // are retired (§3.1): only the content is editable.
            val holder = checkedHolders().singleOrNull() ?: return
            holder.editContent.setText(holder.record.content)
            holder.editSection.visibility = View.VISIBLE
            editingCardId = holder.record.memoryId
            refreshResolutionButtons()
        } else {
            // Second tap: apply the edit.
            applyResolution(ResolutionKind.EDIT_OLD)
        }
    }

    /* ------------------------------ apply ------------------------------ */

    private enum class ResolutionKind { EDIT_OLD, SUPERSEDE, REPLACE }

    private fun applyResolution(kind: ResolutionKind) {
        if (applying) return
        val checked = checkedHolders()
        if (checked.isEmpty()) return  // buttons are disabled here, belt-and-braces

        // For Edit Old, gather and validate the inline fields up front.
        var editedOld: MemoryRecord? = null
        if (kind == ResolutionKind.EDIT_OLD) {
            val holder = checked.singleOrNull() ?: return
            val content = holder.editContent.text?.toString()?.trim().orEmpty()
            if (content.isEmpty()) {
                Toast.makeText(this, R.string.mem_edit_required, Toast.LENGTH_SHORT).show()
                return
            }
            editedOld = holder.record.copy(content = content)
        }
        val checkedIds = checked.map { it.record.memoryId }

        applying = true
        setButtonsEnabled(false)
        runOffThread {
            val store = MemoryStore.getInstance(this)
            // Pre-apply revalidation: the proposal must still be a pending draft
            // and every selected memory must still exist. Otherwise apply
            // nothing and keep the user on this screen (owner rule).
            val proposal = store.getMemory(draftId)
            if (proposal == null || proposal.status != "draft") {
                runOnUiThread { applying = false; toastAndReload(R.string.mem_review_gone, reload = false); finishGone() }
                return@runOffThread
            }
            if (checkedIds.any { store.getMemory(it) == null }) {
                runOnUiThread { applying = false; toastAndReload(R.string.mem_review_stale, reload = true) }
                return@runOffThread
            }

            val result = when (kind) {
                ResolutionKind.EDIT_OLD -> store.resolveSaveAndEditOld(draftId, editedOld)
                ResolutionKind.SUPERSEDE -> store.resolveSupersede(draftId, checkedIds)
                ResolutionKind.REPLACE -> store.resolveReplace(draftId, checkedIds)
            }
            when (result) {
                is MemoryStore.ResolutionResult.Applied -> {
                    // Re-index the now-active memories (the proposal, and an
                    // edited old memory that stayed active).
                    val lib = Librarian.getInstance(this)
                    result.reindexMemoryIds.forEach { lib.reindexMemory(it) }
                    runOnUiThread {
                        Toast.makeText(this, R.string.memory_saved, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                is MemoryStore.ResolutionResult.StaleProposal -> {
                    runOnUiThread { applying = false; toastAndReload(R.string.mem_review_gone, reload = false); finishGone() }
                }
            }
        }
    }

    private fun finishGone() {
        if (!isFinishing) finish()
    }

    private fun toastAndReload(msgRes: Int, reload: Boolean) {
        Toast.makeText(this, msgRes, Toast.LENGTH_LONG).show()
        if (reload) {
            setButtonsEnabled(true)
            load()
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnEdit?.isEnabled = enabled
        btnSupersede?.isEnabled = enabled
        btnReplace?.isEnabled = enabled
        if (enabled) refreshResolutionButtons()
    }

    /* ------------------------------ helpers ------------------------------ */

    private fun iconForScope(scope: String?): Int = when (scope) {
        "real_life" -> R.drawable.ic_mem_person
        "global" -> R.drawable.ic_mem_global
        "companion" -> R.drawable.ic_mem_companion
        "project" -> R.drawable.ic_mem_draft
        "rp_character" -> R.drawable.ic_mem_theater
        else -> R.drawable.ic_mem_public
    }

    private fun formatTags(tags: List<String>): String =
        tags.joinToString("  ·  ") { t ->
            t.trim().split(" ").joinToString(" ") { w ->
                if (w.isEmpty()) w else w[0].uppercaseChar() + w.substring(1)
            }
        }

    private fun parseTags(tagsJson: String?): List<String> = try {
        if (tagsJson.isNullOrBlank()) emptyList() else {
            val arr = JSONArray(tagsJson)
            (0 until arr.length()).map { arr.getString(it) }
        }
    } catch (_: Exception) { emptyList() }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun runOffThread(work: () -> Unit) {
        Thread {
            try {
                work()
            } catch (e: org.teslasoft.assistant.preferences.backup.DatabaseDegradedException) {
                runOnUiThread {
                    if (!isFinishing) {
                        org.teslasoft.assistant.ui.DatabaseRecoveryFlows.showBlockedScreenDialog(this, e.type)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    applying = false
                    setButtonsEnabled(true)
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
            findViewById<ScrollView>(R.id.scroll)?.setPadding(
                0, 0, 0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom + dp(24)
            )
        } catch (_: Exception) { /* unused */ }
    }
}
