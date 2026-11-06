/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.ui.chat

import android.view.View
import android.widget.TextView
import org.teslasoft.assistant.R
import org.teslasoft.assistant.ui.util.CompactActionPopup

/**
 * The Selection Mode area beneath the chat header: the selected count, its
 * hint, and the Copy, Share, Delete, Export and more-options actions. The chat
 * screen owns what each action does; this class only shows the area and
 * reports which action the user chose.
 */
internal class ChatSelectionBar(
    private val root: View,
    private val actions: Actions
) {
    interface Actions {
        fun onCopySelected()
        fun onShareSelected()
        fun onDeleteSelected()
        fun onSelectAll()
        fun onDeselectAll()
        fun onExportSelected()
        fun onExitMode()
    }

    private val count: TextView = root.findViewById(R.id.chat_selection_count)

    init {
        root.findViewById<View>(R.id.chat_selection_copy).setOnClickListener { actions.onCopySelected() }
        root.findViewById<View>(R.id.chat_selection_share).setOnClickListener { actions.onShareSelected() }
        root.findViewById<View>(R.id.chat_selection_delete).setOnClickListener { actions.onDeleteSelected() }
        root.findViewById<View>(R.id.chat_selection_export).setOnClickListener { actions.onExportSelected() }
        root.findViewById<View>(R.id.chat_selection_more).setOnClickListener { anchor -> showMoreMenu(anchor) }
    }

    val isShowing: Boolean
        get() = root.visibility == View.VISIBLE

    fun show(selectedCount: Int) {
        setCount(selectedCount)
        root.visibility = View.VISIBLE
    }

    fun hide() {
        root.visibility = View.GONE
    }

    fun setCount(selectedCount: Int) {
        count.text = root.resources.getString(R.string.chat_selection_count, selectedCount)
    }

    private fun showMoreMenu(anchor: View) {
        val resources = anchor.resources
        CompactActionPopup(anchor.context, anchor)
            .add(MENU_SELECT_ALL, 0, resources.getString(R.string.chat_selection_select_all))
            .add(MENU_DESELECT_ALL, 1, resources.getString(R.string.chat_selection_deselect_all))
            .add(MENU_EXIT, 2, resources.getString(R.string.chat_selection_exit))
            .onAction { id ->
                when (id) {
                    MENU_SELECT_ALL -> actions.onSelectAll()
                    MENU_DESELECT_ALL -> actions.onDeselectAll()
                    MENU_EXIT -> actions.onExitMode()
                }
                true
            }
            .show()
    }

    private companion object {
        const val MENU_SELECT_ALL = 1
        const val MENU_DESELECT_ALL = 2
        const val MENU_EXIT = 3
    }
}
