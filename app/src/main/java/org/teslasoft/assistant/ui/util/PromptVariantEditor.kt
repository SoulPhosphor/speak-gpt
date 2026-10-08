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

package org.teslasoft.assistant.ui.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.dto.CompanionPromptVariant

/**
 * The shared multiple-prompt editor (layout/view_prompt_variant_editor.xml):
 * wrapping prompt tabs, the add button, and the three-dot menu with Make
 * Default, Rename, Copy From…, Duplicate, Copy, Clear, Revert, Original
 * Prompt (built-in prompts only) and Delete.
 * Used by Edit Companion and Summarizer Prompts. Edits stay in memory until
 * the host screen saves; Revert returns the open prompt's text to its text at
 * the last save ([markSaved]), or to empty for a prompt added since then.
 */
class PromptVariantEditor(
    private val activity: Activity,
    root: View,
    hintRes: Int,
    /** Dialog title shown when Delete is used on the only remaining prompt;
     *  null when protected prompts make that impossible. */
    private val lastPromptTitleRes: Int?,
    /** A built-in prompt's shipped text, or null for the user's own prompts.
     *  Built-in prompts cannot be renamed or deleted, and their menu offers
     *  Original Prompt to restore the shipped text. */
    private val builtInOriginal: (CompanionPromptVariant) -> String? = { null }
) {
    private val tabRow: LinearLayout = root.findViewById(R.id.prompt_tab_row)
    private val tabName: TextView = root.findViewById(R.id.prompt_tab_name)
    private val btnAdd: ImageButton = root.findViewById(R.id.btn_add_prompt)
    private val btnMenu: ImageButton = root.findViewById(R.id.btn_prompt_menu)
    val field: TextInputEditText = root.findViewById(R.id.field_prompt)

    private var variants = ArrayList<CompanionPromptVariant>()
    private var activeIndex = 0
    private var savedTextById: Map<String, String> = emptyMap()

    // Manual pan-tracking state for the unfocused prompt field (see
    // installPanTouchListener): whether the current gesture has crossed touch
    // slop and become a drag, and the finger position it's tracked from.
    private var fieldDragging = false
    private var fieldTouchStartY = 0f
    private var fieldLastY = 0f

    init {
        field.setHint(hintRes)
        btnAdd.setOnClickListener { addPrompt() }
        btnMenu.setOnClickListener { showMenu(it) }
        installPanTouchListener()
    }

    /** Loads [list] for editing, opening [index]. [saved] is the last-saved
     *  state Revert returns to. */
    fun load(
        list: List<CompanionPromptVariant>,
        index: Int,
        saved: List<CompanionPromptVariant> = list
    ) {
        variants = ArrayList(list.map { it.copy() })
        activeIndex = if (variants.isEmpty()) 0 else index.coerceIn(variants.indices)
        savedTextById = saved.associate { it.id to it.text }
        loadActivePrompt()
        renderTabs()
    }

    /** Current prompts, including unsaved edits in the open prompt. */
    fun variants(): List<CompanionPromptVariant> {
        flushActivePrompt()
        return variants.map { it.copy() }
    }

    fun activeIndex(): Int = activeIndex

    fun toJson(): String = CompanionPromptVariant.toJson(variants())

    /** The current prompts become the state Revert returns to. */
    fun markSaved() {
        savedTextById = variants().associate { it.id to it.text }
    }

    /** JSON of the last-saved texts, for instance-state restore. */
    fun savedStateJson(): String = CompanionPromptVariant.toJson(
        savedTextById.map { (id, text) ->
            CompanionPromptVariant(id = id, name = "", text = text, isDefault = false)
        }
    )

    /* ------------------------------ tabs ------------------------------ */

    private fun flushActivePrompt() {
        if (activeIndex in variants.indices) {
            variants[activeIndex].text = field.text?.toString() ?: ""
        }
    }

    private fun loadActivePrompt() {
        if (activeIndex !in variants.indices) return
        val variant = variants[activeIndex]
        field.setText(variant.text)
        tabName.text = if (variant.isDefault) defaultLabel(variant.name) else variant.name
    }

    private fun defaultLabel(name: String): CharSequence {
        val dot = android.text.SpannableString("●  $name")
        dot.setSpan(
            android.text.style.ForegroundColorSpan(
                ResourcesCompat.getColor(activity.resources, R.color.light_green, activity.theme)
            ),
            0, 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return dot
    }

    private fun switchTo(index: Int) {
        if (index == activeIndex) return
        flushActivePrompt()
        activeIndex = index.coerceIn(variants.indices)
        loadActivePrompt()
        renderTabs()
    }

    private fun renderTabs() {
        val container = tabRow
        container.removeAllViews()

        val slantWidthPx = activity.resources.getDimension(R.dimen.prompt_tab_slant_width)
        val strokeWidthPx = activity.resources.getDimension(R.dimen.prompt_tab_stroke_width)
        val outlineColor = com.google.android.material.color.MaterialColors.getColor(
            container, com.google.android.material.R.attr.colorOutline
        )
        val activeFillColor = com.google.android.material.color.MaterialColors.getColor(
            container, com.google.android.material.R.attr.colorSurfaceContainerHigh
        )

        val tabs = mutableListOf<TextView>()
        for (i in variants.indices) {
            val variant = variants[i]
            val isActive = i == activeIndex
            val styleRes = if (isActive) R.style.Widget_App_PromptTab_Active else R.style.Widget_App_PromptTab
            val tab = TextView(activity, null, 0, styleRes)
            tab.text = if (variant.isDefault) defaultLabel(variant.name) else variant.name
            tab.setOnClickListener { switchTo(i) }
            tabs.add(tab)
        }

        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        for (tab in tabs) { tab.measure(unspec, unspec) }

        val availWidth = if (container.width > 0) container.width
        else activity.resources.displayMetrics.widthPixels -
                2 * (24 * activity.resources.displayMetrics.density).toInt()

        // Pack tabs into rows (zero spacing — adjacent tabs butt up).
        val rows = mutableListOf<List<Int>>()
        var rowIndices = mutableListOf<Int>()
        var rowWidth = 0
        for (i in tabs.indices) {
            val tw = tabs[i].measuredWidth
            if (rowIndices.isNotEmpty() && rowWidth + tw > availWidth) {
                rows.add(rowIndices.toList())
                rowIndices = mutableListOf()
                rowWidth = 0
            }
            rowIndices.add(i)
            rowWidth += tw
        }
        if (rowIndices.isNotEmpty()) rows.add(rowIndices.toList())

        // Move the row containing the active tab to the end so it sits
        // flush against the prompt frame.
        var activeRowIdx = rows.indexOfFirst { activeIndex in it }
        if (activeRowIdx < 0) activeRowIdx = rows.size - 1
        val reordered = rows.toMutableList()
        if (activeRowIdx in reordered.indices) {
            val active = reordered.removeAt(activeRowIdx)
            reordered.add(active)
        }

        for ((rowPos, row) in reordered.withIndex()) {
            val isBottomRow = rowPos == reordered.size - 1
            val rowLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val lastPosInRow = row.size - 1
            for ((posInRow, tabIdx) in row.withIndex()) {
                val tab = tabs[tabIdx]
                val isActive = tabIdx == activeIndex
                tab.background = PromptTabBackground(
                    fillColor = if (isActive) activeFillColor else android.graphics.Color.TRANSPARENT,
                    strokeColor = outlineColor,
                    strokeWidthPx = strokeWidthPx,
                    slantWidthPx = slantWidthPx,
                    isFirstInRow = posInRow == 0,
                    isLastInRow = posInRow == lastPosInRow,
                    drawBottomEdge = !(isActive && isBottomRow)
                )
                tab.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                rowLayout.addView(tab)
            }
            container.addView(rowLayout)
        }
    }

    private fun addPrompt() {
        flushActivePrompt()
        val name = CompanionPromptVariant.nextPromptName(variants)
        variants.add(CompanionPromptVariant(name = name, text = "", isDefault = false))
        activeIndex = variants.size - 1
        loadActivePrompt()
        renderTabs()
    }

    /* ------------------------------ menu ------------------------------ */

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(activity, anchor)
        val builtIn = variants.getOrNull(activeIndex)?.let { builtInOriginal(it) } != null
        menu.menu.add(0, MENU_MAKE_DEFAULT, 0, activity.getString(R.string.prompt_menu_make_default))
        if (!builtIn) menu.menu.add(0, MENU_RENAME, 0, activity.getString(R.string.prompt_menu_rename))
        menu.menu.add(0, MENU_COPY_FROM, 0, activity.getString(R.string.prompt_menu_copy_from))
        menu.menu.add(0, MENU_DUPLICATE, 0, activity.getString(R.string.prompt_menu_duplicate))
        menu.menu.add(0, MENU_COPY, 0, activity.getString(R.string.prompt_menu_copy))
        menu.menu.add(0, MENU_CLEAR, 0, activity.getString(R.string.prompt_menu_clear))
        menu.menu.add(0, MENU_REVERT, 0, activity.getString(R.string.prompt_menu_revert))
        if (builtIn) {
            menu.menu.add(0, MENU_ORIGINAL, 0, activity.getString(R.string.prompt_menu_original))
        }
        val delete = menu.menu.add(0, MENU_DELETE, 0, activity.getString(R.string.prompt_menu_delete))
        delete.isEnabled = activeIndex in variants.indices && !builtIn
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_MAKE_DEFAULT -> makeCurrentDefault()
                MENU_RENAME -> renameCurrent()
                MENU_COPY_FROM -> showCopyFromDialog()
                MENU_DUPLICATE -> duplicateCurrent()
                MENU_COPY -> copyCurrentToClipboard()
                MENU_CLEAR -> clearCurrent()
                MENU_REVERT -> revertCurrent()
                MENU_ORIGINAL -> restoreOriginalCurrent()
                MENU_DELETE -> deleteCurrent()
            }
            true
        }
        menu.show()
    }

    private fun copyCurrentToClipboard() {
        val text = field.text?.toString() ?: return
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("prompt", text))
    }

    private fun makeCurrentDefault() {
        for (i in variants.indices) {
            variants[i].isDefault = (i == activeIndex)
        }
        flushActivePrompt()
        renderTabs()
        loadActivePrompt()
    }

    private fun renameCurrent() {
        if (activeIndex !in variants.indices) return
        val current = variants[activeIndex]

        val input = EditText(activity)
        input.setText(current.name)
        input.setSelection(current.name.length)
        input.setPadding(dpToPx(24), dpToPx(16), dpToPx(24), dpToPx(8))

        val actionsView = activity.layoutInflater.inflate(R.layout.dialog_two_actions, null)
        val wrapper = LinearLayout(activity)
        wrapper.orientation = LinearLayout.VERTICAL
        wrapper.addView(input)
        wrapper.addView(actionsView)

        val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.prompt_rename_title)
            .setView(wrapper)
            .create()

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.btn_ok)
            setOnClickListener {
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    flushActivePrompt()
                    current.name = newName
                    renderTabs()
                    loadActivePrompt()
                }
                dialog.dismiss()
            }
        }

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
            setText(R.string.btn_cancel)
            setOnClickListener { dialog.dismiss() }
        }

        dialog.show()
    }

    private fun showCopyFromDialog() {
        if (activeIndex !in variants.indices) return
        flushActivePrompt()

        val otherVariants = variants.filterIndexed { i, _ -> i != activeIndex }
        if (otherVariants.isEmpty()) return

        val names = otherVariants.map { v ->
            val prefix = if (v.isDefault) "● " else ""
            val preview = if (v.text.isBlank()) activity.getString(R.string.prompt_empty_marker) else {
                v.text.take(60).replace('\n', ' ')
                    .let { if (v.text.length > 60) "$it…" else it }
            }
            "$prefix${v.name}\n$preview"
        }.toTypedArray()

        val currentHasText = field.text?.toString()?.isNotBlank() == true

        val performCopy = { sourceIndex: Int ->
            val source = otherVariants[sourceIndex]
            field.setText(source.text)
            flushActivePrompt()
        }

        MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.prompt_copy_from_header)
            .setItems(names) { _, which ->
                if (currentHasText) {
                    val actionsView = activity.layoutInflater.inflate(R.layout.dialog_two_actions, null)
                    val confirmDialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
                        .setTitle(R.string.prompt_copy_replace_title)
                        .setView(actionsView)
                        .create()

                    actionsView.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
                        setText(R.string.prompt_copy_replace_btn_ok)
                        setOnClickListener {
                            performCopy(which)
                            confirmDialog.dismiss()
                        }
                    }
                    actionsView.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
                        setText(R.string.btn_cancel)
                        setOnClickListener { confirmDialog.dismiss() }
                    }
                    confirmDialog.show()
                } else {
                    performCopy(which)
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun duplicateCurrent() {
        if (activeIndex !in variants.indices) return
        flushActivePrompt()
        val current = variants[activeIndex]
        val newName = CompanionPromptVariant.nextPromptName(variants)
        variants.add(CompanionPromptVariant(name = newName, text = current.text, isDefault = false))
        activeIndex = variants.size - 1
        loadActivePrompt()
        renderTabs()
    }

    private fun clearCurrent() {
        if (activeIndex !in variants.indices) return
        val currentText = field.text?.toString() ?: ""
        if (currentText.isBlank()) return

        val actionsView = activity.layoutInflater.inflate(R.layout.dialog_two_actions, null)
        val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.prompt_clear_title)
            .setView(actionsView)
            .create()

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.prompt_clear_btn_ok)
            setOnClickListener {
                field.setText("")
                flushActivePrompt()
                dialog.dismiss()
            }
        }
        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
            setText(R.string.btn_cancel)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
    }

    /** Returns only the open prompt's text to its last-saved text. */
    private fun revertCurrent() {
        if (activeIndex !in variants.indices) return
        val savedText = savedTextById[variants[activeIndex].id] ?: ""
        if ((field.text?.toString() ?: "") == savedText) return

        val actionsView = activity.layoutInflater.inflate(R.layout.dialog_two_actions_cancel_first, null)
        val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.prompt_revert_title)
            .setView(actionsView)
            .create()

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.btn_ok)
            setOnClickListener {
                field.setText(savedText)
                flushActivePrompt()
                dialog.dismiss()
            }
        }
        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
            setText(R.string.btn_cancel)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
    }

    /** Returns a built-in prompt's box to its shipped text (kept on Save). */
    private fun restoreOriginalCurrent() {
        if (activeIndex !in variants.indices) return
        val original = builtInOriginal(variants[activeIndex]) ?: return
        if ((field.text?.toString() ?: "") == original) return

        val actionsView = activity.layoutInflater.inflate(R.layout.dialog_two_actions_cancel_first, null)
        val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.prompt_original_title)
            .setView(actionsView)
            .create()

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.btn_ok)
            setOnClickListener {
                field.setText(original)
                flushActivePrompt()
                dialog.dismiss()
            }
        }
        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
            setText(R.string.btn_cancel)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
    }

    private fun deleteCurrent() {
        if (activeIndex !in variants.indices) return
        if (builtInOriginal(variants[activeIndex]) != null) return
        if (variants.size <= 1) {
            if (lastPromptTitleRes == null) return
            val actionsView = activity.layoutInflater.inflate(R.layout.dialog_single_action, null)
            val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
                .setTitle(lastPromptTitleRes)
                .setView(actionsView)
                .create()

            actionsView.findViewById<MaterialButton>(R.id.btn_dialog_action).apply {
                setText(R.string.btn_ok)
                setOnClickListener { dialog.dismiss() }
            }
            dialog.show()
            return
        }

        val actionsView = activity.layoutInflater.inflate(R.layout.dialog_two_actions, null)
        val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.prompt_delete_title)
            .setView(actionsView)
            .create()

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.prompt_delete_btn_ok)
            setOnClickListener {
                val wasDefault = variants[activeIndex].isDefault
                variants.removeAt(activeIndex)
                if (wasDefault && variants.isNotEmpty()) {
                    variants[0].isDefault = true
                }
                activeIndex = activeIndex.coerceAtMost(variants.size - 1)
                loadActivePrompt()
                renderTabs()
                dialog.dismiss()
            }
        }
        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
            setText(R.string.btn_cancel)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
    }

    /* --------------------------- field panning --------------------------- */

    /**
     * Lets the prompt field be panned by dragging while it is NOT focused,
     * without opening the keyboard, while a plain tap still focuses it and
     * opens the keyboard as normal (owner request, Aug 16 2026). Claims the
     * gesture from the parent scroll container up front, then either consumes
     * it as a manual, 1:1 finger-tracked scroll of the field's own text, or -
     * once the field has no more room to pan, or the gesture turns out to be a
     * tap rather than a drag, or the field is already focused - releases the
     * parent so it scrolls, or lets the field's own click/focus handling run.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun installPanTouchListener() {
        val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
        field.setOnTouchListener { v, event ->
            val tv = v as? TextView ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fieldDragging = false
                    fieldTouchStartY = event.y
                    fieldLastY = event.y
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (tv.hasFocus()) {
                        false
                    } else {
                        if (!fieldDragging && kotlin.math.abs(event.y - fieldTouchStartY) > touchSlop) {
                            fieldDragging = true
                        }
                        if (fieldDragging) {
                            val visibleHeight = tv.height - tv.paddingTop - tv.paddingBottom
                            val contentHeight = tv.layout?.height ?: 0
                            val maxScroll = (contentHeight - visibleHeight).coerceAtLeast(0)
                            if (maxScroll > 0) {
                                val delta = fieldLastY - event.y
                                val newScroll = (tv.scrollY + delta).coerceIn(0f, maxScroll.toFloat())
                                tv.scrollTo(0, newScroll.toInt())
                                fieldLastY = event.y
                                true
                            } else {
                                v.parent.requestDisallowInterceptTouchEvent(false)
                                false
                            }
                        } else {
                            false
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                    val consumed = !tv.hasFocus() && fieldDragging
                    fieldDragging = false
                    consumed
                }
                else -> false
            }
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val MENU_MAKE_DEFAULT = 1
        const val MENU_RENAME = 2
        const val MENU_COPY_FROM = 3
        const val MENU_DUPLICATE = 4
        const val MENU_CLEAR = 5
        const val MENU_DELETE = 6
        const val MENU_COPY = 7
        const val MENU_REVERT = 8
        const val MENU_ORIGINAL = 9
    }
}
