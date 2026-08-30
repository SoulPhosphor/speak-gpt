/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 **************************************************************************/

package org.teslasoft.assistant.ui.util

import android.content.Context
import android.view.View
import android.widget.PopupMenu
import org.teslasoft.assistant.R

/** Shared compact anchored-management menu used by galleries and the drawer. */
class CompactActionPopup(context: Context, anchor: View) {
    private val popup = PopupMenu(context, anchor, 0, 0, R.style.Widget_App_CompactActionPopup)

    fun add(id: Int, order: Int, title: CharSequence, enabled: Boolean = true) = apply {
        popup.menu.add(0, id, order, title).isEnabled = enabled
    }

    fun onAction(listener: (Int) -> Boolean) = apply {
        popup.setOnMenuItemClickListener { listener(it.itemId) }
    }

    fun show() = popup.show()
}
