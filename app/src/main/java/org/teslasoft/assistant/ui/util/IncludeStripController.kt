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

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.includes.ChatInclude
import org.teslasoft.assistant.preferences.includes.IncludeForm
import org.teslasoft.assistant.preferences.includes.IncludeKind
import org.teslasoft.assistant.preferences.includes.IncludeNotice
import java.text.NumberFormat

/**
 * Drives the Includes strip above the chat's message box.
 *
 * The owner's rule for this surface is the whole design: **if you can see it,
 * it is being sent.** There is no hidden "included / not included" state to
 * remember — a row's presence IS the state, and Remove is the off switch. So
 * this controller never hides a live item, and every row states what form is
 * going to the model ("Includes" vs "Includes condensed") and what it weighs.
 *
 * At [COLLAPSE_AT] items the individual rows give way to a single
 * "Includes N Documents" line, because four rows of attachments would eat the
 * screen above the keyboard. Tapping it opens the list upward over the chat.
 */
class IncludeStripController(
    private val context: Context,
    private val strip: LinearLayout,
    private val collapsedRow: View,
    private val listScroll: ScrollView,
    private val list: LinearLayout,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onRemoveInclude(include: ChatInclude)
        fun onCondenseInclude(include: ChatInclude)
        fun onEditInclude(include: ChatInclude)
    }

    companion object {
        /** Item count at which the strip collapses to one line. */
        const val COLLAPSE_AT = 4

        /** Rows visible in the expanded list before it starts scrolling. */
        private const val MAX_VISIBLE_ROWS = 6
        private const val ROW_HEIGHT_DP = 52

        /**
         * Flip to true when Step 2 (Condense / Reduce to Text Only) is built.
         * Until then the action is absent from the row menu rather than
         * present and misleading.
         */
        private const val CONDENSE_BUILT = false
    }

    private var expanded = false
    private var current: List<ChatInclude> = emptyList()

    init {
        collapsedRow.setOnClickListener { toggleExpanded() }
    }

    /** True while the expanded overlay is covering the chat. */
    fun isExpanded(): Boolean = expanded

    /** Collapses the overlay. Returns true if it was open (so a back press
     *  can consume the gesture instead of leaving the chat). */
    fun collapseIfExpanded(): Boolean {
        if (!expanded) return false
        expanded = false
        render()
        return true
    }

    fun bind(includes: List<ChatInclude>) {
        current = includes.filter { it.showsInStrip() }
        // An item removed while the overlay was open must not leave the user
        // staring at an expanded box with nothing left in it.
        if (current.size < COLLAPSE_AT) expanded = false
        render()
    }

    private fun toggleExpanded() {
        expanded = !expanded
        render()
    }

    private fun render() {
        if (current.isEmpty()) {
            strip.visibility = View.GONE
            list.removeAllViews()
            return
        }

        strip.visibility = View.VISIBLE
        val collapsible = current.size >= COLLAPSE_AT
        val showList = !collapsible || expanded

        collapsedRow.visibility = if (collapsible) View.VISIBLE else View.GONE
        listScroll.visibility = if (showList) View.VISIBLE else View.GONE

        if (collapsible) {
            collapsedRow.findViewById<TextView>(R.id.include_collapsed_text)?.text =
                context.getString(R.string.include_collapsed_count, current.size)
            collapsedRow.findViewById<ImageView>(R.id.include_collapsed_chevron)?.contentDescription =
                context.getString(
                    if (expanded) R.string.include_collapse_desc else R.string.include_expand_desc
                )
        }

        if (showList) buildRows() else list.removeAllViews()
        applyListHeightCap(showList)
    }

    /**
     * Caps the expanded list so a long attachment list scrolls inside the
     * strip instead of growing off the top of the screen.
     */
    private fun applyListHeightCap(showList: Boolean) {
        val params = listScroll.layoutParams ?: return
        params.height = if (showList && current.size > MAX_VISIBLE_ROWS) {
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                (MAX_VISIBLE_ROWS * ROW_HEIGHT_DP).toFloat(),
                context.resources.displayMetrics
            ).toInt()
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        listScroll.layoutParams = params
    }

    private fun buildRows() {
        list.removeAllViews()
        val inflater = LayoutInflater.from(context)
        for (include in current) {
            val row = inflater.inflate(R.layout.view_include_row, list, false)
            bindRow(row, include)
            list.addView(row)
        }
    }

    private fun bindRow(row: View, include: ChatInclude) {
        row.findViewById<ImageView>(R.id.include_icon)?.setImageResource(iconFor(include.kind))

        row.findViewById<TextView>(R.id.include_label)?.setText(
            if (include.form == IncludeForm.CONDENSED) {
                R.string.include_label_condensed
            } else {
                R.string.include_label
            }
        )

        row.findViewById<TextView>(R.id.include_name)?.text = include.fileName

        row.findViewById<TextView>(R.id.include_weight)?.text =
            context.getString(R.string.include_weight, grouped(include.currentTokens()))

        val notice = row.findViewById<TextView>(R.id.include_notice)
        val noticeText = noticeText(include.notice)
        if (noticeText == null) {
            notice?.visibility = View.GONE
        } else {
            notice?.visibility = View.VISIBLE
            notice?.text = noticeText
        }

        val menu = row.findViewById<ImageButton>(R.id.include_menu)
        menu?.contentDescription = context.getString(R.string.include_menu_desc, include.fileName)
        menu?.setOnClickListener { showRowMenu(it, include) }
    }

    /**
     * The row's action menu. Which actions exist depends on where the item
     * sits on the ladder: a condensed item offers Edit so the user can fix
     * anything the model's summary missed or trim it further.
     *
     * Condense / Reduce to Text Only are Step 2 of the plan and are NOT
     * offered until that step is built — an item claiming to condense while
     * actually doing something else would be worse than no menu item at all.
     */
    private fun showRowMenu(anchor: View, include: ChatInclude) {
        val popup = PopupMenu(context, anchor)
        val menu = popup.menu
        menu.add(0, MENU_REMOVE, 0, R.string.include_action_remove)
        if (include.form == IncludeForm.CONDENSED) {
            menu.add(0, MENU_EDIT, 1, R.string.include_action_edit)
        } else if (CONDENSE_BUILT) {
            menu.add(
                0, MENU_CONDENSE, 1,
                if (include.kind == IncludeKind.IMAGE) {
                    R.string.include_action_reduce
                } else {
                    R.string.include_action_condense
                }
            )
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_REMOVE -> { callbacks.onRemoveInclude(include); true }
                MENU_CONDENSE -> { callbacks.onCondenseInclude(include); true }
                MENU_EDIT -> { callbacks.onEditInclude(include); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun noticeText(notice: IncludeNotice): String? = when (notice) {
        is IncludeNotice.None -> null
        is IncludeNotice.Large ->
            context.getString(R.string.include_notice_large, grouped(notice.tokens))
        is IncludeNotice.Truncated ->
            context.getString(R.string.include_notice_truncated, grouped(notice.tokens))
        is IncludeNotice.CsvTrimmed -> context.getString(
            R.string.include_notice_csv, grouped(notice.sentRows), grouped(notice.totalRows)
        )
    }

    private fun grouped(value: Int): String = NumberFormat.getIntegerInstance().format(value)

    private fun iconFor(kind: IncludeKind): Int = when (kind) {
        IncludeKind.TXT -> R.drawable.ic_doc_text
        IncludeKind.MARKDOWN -> R.drawable.ic_doc_markdown
        IncludeKind.CSV -> R.drawable.ic_doc_table
        IncludeKind.DOCX -> R.drawable.ic_doc_word
        IncludeKind.IMAGE -> R.drawable.ic_image
    }
}

private const val MENU_REMOVE = 1
private const val MENU_CONDENSE = 2
private const val MENU_EDIT = 3
