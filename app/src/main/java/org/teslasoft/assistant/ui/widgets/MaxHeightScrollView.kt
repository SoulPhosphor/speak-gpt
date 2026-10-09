/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.ui.widgets

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView
import org.teslasoft.assistant.R

/**
 * A scroll box that grows with its content up to `scrollMaxHeight` (set by
 * its style), then scrolls. Used inside a page that itself scrolls.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {
    private val maxHeightPx: Int

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.MaxHeightScrollView, defStyleAttr, 0)
        maxHeightPx = a.getDimensionPixelSize(R.styleable.MaxHeightScrollView_scrollMaxHeight, 0)
        a.recycle()
        isNestedScrollingEnabled = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (maxHeightPx > 0) MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, spec)
    }
}
