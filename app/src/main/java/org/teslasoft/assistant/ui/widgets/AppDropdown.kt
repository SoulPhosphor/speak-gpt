/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.widgets

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.ListPopupWindow
import org.teslasoft.assistant.R
import kotlin.math.ceil
import kotlin.math.min

/**
 * Shared behavior for the app's canonical dropdown control.
 *
 * Visual properties live in Widget.App.Dropdown.* and its theme roles. This
 * helper owns the behavior styles cannot express: measuring standalone controls,
 * joining the open anchor to its menu, keeping the selected value in the anchor,
 * and removing all list/pressed feedback.
 */
object AppDropdown {

    /** Size [anchor] to its longest option, capped by the caller's live layout. */
    fun sizeToOptions(anchor: TextView, labels: List<String>, maxWidth: () -> Int) {
        anchor.post {
            if (!anchor.isAttachedToWindow) return@post
            // Include the displayed value as well as available choices. This
            // matters for read-only linked values and deleted-reference labels
            // that may not appear in the active option list.
            val candidateLabels = labels + anchor.text.toString()
            val textWidth = candidateLabels.maxOfOrNull { anchor.paint.measureText(it) } ?: 0f
            val chevronWidth = anchor.compoundDrawablesRelative[2]?.intrinsicWidth ?: 0
            val desired = ceil(textWidth).toInt() + anchor.paddingStart + anchor.paddingEnd +
                chevronWidth + anchor.compoundDrawablePadding
            val cap = maxWidth().coerceAtLeast(anchor.minimumWidth)
            val width = min(desired, cap)
            if (width > 0 && anchor.layoutParams.width != width) {
                anchor.layoutParams = anchor.layoutParams.apply { this.width = width }
            }
        }
    }

    /** Open a border-continuous, feedback-free menu under [anchor]. */
    fun show(
        anchor: TextView,
        labels: List<String>,
        selectedIndex: Int = labels.indexOf(anchor.text.toString()),
        onPick: (Int) -> Unit
    ) {
        if (labels.isEmpty() || !anchor.isEnabled) return

        // The anchor is the selected top option while the control is open. Do
        // not repeat that value immediately below it as though the anchor were
        // a field label. Keep original indices so callbacks still address the
        // caller's unfiltered value/id lists.
        val menuOptions = labels.mapIndexed { index, label -> index to label }
            .filterNot { (index, _) -> index == selectedIndex }
        if (menuOptions.isEmpty()) return

        val context = anchor.context
        val popup = ListPopupWindow(context)
        val adapter = object : ArrayAdapter<String>(
            context,
            R.layout.view_dropdown_option,
            menuOptions.map { it.second }
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val textView = super.getView(position, convertView, parent) as TextView
                textView.setTypeface(textView.typeface, Typeface.NORMAL)
                return textView
            }
        }

        val closedTypeface = anchor.typeface
        anchor.background = AppCompatResources.getDrawable(context, R.drawable.bg_dropdown_open_anchor)
        if (selectedIndex in labels.indices) {
            anchor.setTypeface(closedTypeface, Typeface.BOLD)
        }
        popup.anchorView = anchor
        popup.isModal = true
        popup.width = anchor.width
        popup.height = ListPopupWindow.WRAP_CONTENT
        popup.setBackgroundDrawable(AppCompatResources.getDrawable(context, R.drawable.bg_dropdown_menu))
        popup.setAdapter(adapter)
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            onPick(menuOptions[position].first)
        }
        popup.setOnDismissListener {
            anchor.background = AppCompatResources.getDrawable(context, R.drawable.bg_dropdown_closed)
            anchor.typeface = closedTypeface
        }
        popup.show()

        // ListPopupWindow otherwise supplies a state selector even when each
        // option has a static background. The owner explicitly wants no touch,
        // pressed, or selection flash at all.
        popup.listView?.apply {
            selector = ColorDrawable(Color.TRANSPARENT)
            divider = null
            dividerHeight = 0
        }
    }
}
