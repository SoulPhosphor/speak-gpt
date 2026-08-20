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

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.includes.ChatInclude
import org.teslasoft.assistant.preferences.includes.IncludeForm
import org.teslasoft.assistant.preferences.includes.IncludeKind
import org.teslasoft.assistant.preferences.includes.IncludeNotice
import java.text.NumberFormat
import java.util.Locale

/**
 * The single post-send Includes management surface used by message and
 * composer paperclips. It receives canonical Include ids and asks the caller
 * for the current records immediately before rendering; it never stores or
 * copies an attachment record at the UI location where it is opened.
 */
object IncludesPopupController {

    interface Callbacks {
        fun onIncludeEdit(includeId: String)
        fun onIncludeRemove(includeId: String)
        fun onIncludeCondense(includeId: String)
    }

    private const val MENU_EDIT = 1
    private const val MENU_REMOVE = 2
    private const val MENU_CONDENSE = 3
    private const val MAX_POPUP_HEIGHT_DP = 400

    fun show(
        anchor: View,
        includeIds: List<String>,
        resolveCurrent: (Set<String>) -> List<ChatInclude>,
        callbacks: Callbacks
    ) {
        val wantedIds = includeIds.distinct()
        if (wantedIds.isEmpty()) return

        val byId = resolveCurrent(wantedIds.toSet()).associateBy { it.id }
        val current = wantedIds.mapNotNull { byId[it] }
        if (current.isEmpty()) return

        val content = LayoutInflater.from(anchor.context)
            .inflate(R.layout.view_includes_popup, null)
        val list = content.findViewById<LinearLayout>(R.id.includes_popup_list)
        val scroll = content.findViewById<ScrollView>(R.id.includes_popup_scroll)
        val inflater = LayoutInflater.from(anchor.context)
        lateinit var popup: PopupWindow

        for (include in current) {
            val row = inflater.inflate(R.layout.view_includes_popup_item, list, false)
            bindRow(row, include)
            row.findViewById<ImageButton>(R.id.includes_popup_item_action)?.setOnClickListener {
                showItemMenu(
                    it,
                    include,
                    onAction = { popup.dismiss() },
                    callbacks = callbacks
                )
            }
            list.addView(row)
        }

        popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = anchor.resources.displayMetrics.density * 8f

        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val maxHeight = dp(anchor, MAX_POPUP_HEIGHT_DP)
        if (content.measuredHeight > maxHeight) {
            scroll.layoutParams = scroll.layoutParams.apply {
                height = maxHeight - dp(anchor, 48)
            }
            content.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
        }

        // The popup rises from the action area, matching the existing Details
        // and sent-Includes controls rather than covering the composer row.
        popup.showAsDropDown(
            anchor,
            0,
            -(anchor.height + content.measuredHeight),
            Gravity.START
        )
    }

    private fun bindRow(row: View, include: ChatInclude) {
        row.findViewById<ImageView>(R.id.includes_popup_item_icon)
            ?.setImageResource(iconFor(include.kind))
        row.findViewById<TextView>(R.id.includes_popup_item_name)?.text = include.fileName
        row.findViewById<TextView>(R.id.includes_popup_item_kind)?.text =
            include.kind.key.uppercase(Locale.ROOT)
        row.findViewById<TextView>(R.id.includes_popup_item_state)?.text = stateLabel(include)
        row.findViewById<TextView>(R.id.includes_popup_item_weight)?.text =
            row.context.getString(
                R.string.include_weight,
                NumberFormat.getIntegerInstance().format(include.currentTokens())
            )

        val notice = row.findViewById<TextView>(R.id.includes_popup_item_notice)
        val noticeText = noticeText(row, include.notice)
        if (noticeText == null) {
            notice?.visibility = View.GONE
        } else {
            notice?.visibility = View.VISIBLE
            notice?.text = noticeText
        }

        row.findViewById<ImageButton>(R.id.includes_popup_item_action)?.contentDescription =
            row.context.getString(R.string.include_menu_desc, include.fileName)
    }

    private fun showItemMenu(
        anchor: View,
        include: ChatInclude,
        onAction: () -> Unit,
        callbacks: Callbacks
    ) {
        val popup = PopupMenu(anchor.context, anchor)
        when (include.form) {
            IncludeForm.FULL -> {
                popup.menu.add(
                    0,
                    MENU_CONDENSE,
                    0,
                    if (include.kind.isImage()) {
                        R.string.include_action_reduce
                    } else {
                        R.string.include_action_condense
                    }
                )
                popup.menu.add(0, MENU_REMOVE, 1, R.string.include_action_remove)
            }

            IncludeForm.CONDENSED -> {
                popup.menu.add(0, MENU_EDIT, 0, R.string.include_action_edit)
                popup.menu.add(0, MENU_REMOVE, 1, R.string.include_action_remove)
            }

            IncludeForm.ARTIFACT -> {
                // An Artifact is already removed from model-facing content;
                // its established action is editing the retained bookmark.
                popup.menu.add(0, MENU_EDIT, 0, R.string.include_action_edit)
            }
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EDIT -> {
                    onAction()
                    callbacks.onIncludeEdit(include.id)
                }
                MENU_REMOVE -> {
                    onAction()
                    callbacks.onIncludeRemove(include.id)
                }
                MENU_CONDENSE -> {
                    onAction()
                    callbacks.onIncludeCondense(include.id)
                }
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun stateLabel(include: ChatInclude): String = when (include.form) {
        IncludeForm.FULL -> "Full"
        IncludeForm.CONDENSED -> if (include.kind.isImage()) "Reduced" else "Condensed"
        IncludeForm.ARTIFACT -> "Artifact"
    }

    private fun noticeText(row: View, notice: IncludeNotice): String? {
        val context = row.context
        val grouped: (Int) -> String = {
            NumberFormat.getIntegerInstance().format(it)
        }
        return when (notice) {
            is IncludeNotice.None -> null
            is IncludeNotice.Truncated -> context.getString(
                R.string.include_notice_truncated,
                grouped(notice.tokens)
            )
            is IncludeNotice.CsvTrimmed -> context.getString(
                R.string.include_notice_csv,
                grouped(notice.sentRows),
                grouped(notice.totalRows)
            )
            is IncludeNotice.WorkbookTrimmed -> context.getString(
                R.string.include_notice_workbook,
                grouped(notice.sheets),
                grouped(notice.sentRows),
                grouped(notice.totalRows)
            )
        }
    }

    private fun iconFor(kind: IncludeKind): Int =
        if (kind.isImage()) R.drawable.ic_image else R.drawable.ic_file

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density).toInt()
}
