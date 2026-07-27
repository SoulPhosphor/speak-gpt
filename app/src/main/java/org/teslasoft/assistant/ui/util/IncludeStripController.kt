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
import android.widget.ScrollView
import android.widget.TextView
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.includes.ChatInclude
import org.teslasoft.assistant.preferences.includes.IncludeKind
import org.teslasoft.assistant.preferences.includes.IncludeNotice
import java.text.NumberFormat

/**
 * Drives the pending Includes strip above the chat's message box. Sent
 * attachments leave this strip and are controlled from their transcript row.
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
    }

    companion object {
        /** Item count at which the strip collapses to one line. */
        const val COLLAPSE_AT = 4

        /** Rows visible in the expanded list before it starts scrolling. */
        private const val MAX_VISIBLE_ROWS = 6
        private const val ROW_HEIGHT_DP = 52
    }

    private var expanded = false
    private var current: List<ChatInclude> = emptyList()
    private val documentHint: TextView? =
        strip.findViewById(R.id.include_document_hint)

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
            documentHint?.visibility = View.GONE
            list.removeAllViews()
            return
        }

        strip.visibility = View.VISIBLE
        documentHint?.visibility = if (current.any { it.kind != IncludeKind.IMAGE }) {
            View.VISIBLE
        } else {
            View.GONE
        }
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

        row.findViewById<TextView>(R.id.include_label)?.setText(R.string.include_label)

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

        val action = row.findViewById<ImageButton>(R.id.include_action)
        action?.setImageResource(R.drawable.ic_close)
        action?.contentDescription =
            context.getString(R.string.include_remove_desc, include.fileName)
        action?.setOnClickListener { callbacks.onRemoveInclude(include) }
    }

    private fun noticeText(notice: IncludeNotice): String? = when (notice) {
        is IncludeNotice.None -> null
        is IncludeNotice.Truncated ->
            context.getString(R.string.include_notice_truncated, grouped(notice.tokens))
        is IncludeNotice.CsvTrimmed -> context.getString(
            R.string.include_notice_csv, grouped(notice.sentRows), grouped(notice.totalRows)
        )
        is IncludeNotice.WorkbookTrimmed -> context.getString(
            R.string.include_notice_workbook,
            grouped(notice.sheets),
            grouped(notice.sentRows),
            grouped(notice.totalRows)
        )
    }

    private fun grouped(value: Int): String = NumberFormat.getIntegerInstance().format(value)

    private fun iconFor(kind: IncludeKind): Int = when (kind) {
        IncludeKind.IMAGE -> R.drawable.ic_image
        else -> R.drawable.ic_file
    }
}
