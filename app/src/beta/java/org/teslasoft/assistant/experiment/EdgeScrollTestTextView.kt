/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.experiment

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * Beta build only. Temporary highlight auto-scroll experiment: a plain
 * scrollable text box that reports every highlight change. Remove with the
 * experiment.
 */
class EdgeScrollTestTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    var onHighlightChanged: ((start: Int, end: Int) -> Unit)? = null

    /** How far the text can scroll inside the box. */
    val maxScroll: Int
        get() {
            val textLayout = layout ?: return 0
            return (textLayout.height + totalPaddingTop + totalPaddingBottom - height).coerceAtLeast(0)
        }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onHighlightChanged?.invoke(selStart, selEnd)
    }
}
