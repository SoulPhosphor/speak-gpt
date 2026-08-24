/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 */

package org.teslasoft.assistant.ui.chat

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import org.teslasoft.assistant.R
import kotlin.math.max

/**
 * A one-row message action bar until the response-version navigator would no
 * longer fit intact. Only then, and only that complete navigator, moves to a
 * second row aligned to the right edge. This avoids both clipping and splitting
 * the prev/count/next/promote controls across lines on narrow screens.
 */
class ResponsiveMessageActionsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private var wrapVersionNavigator = false
    private var primaryRowHeight = 0
    private var versionRowHeight = 0
    private val rowGap = (2f * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var primaryWidth = 0
        var versionWidth = 0
        primaryRowHeight = 0
        versionRowHeight = 0

        val versionNavigator: View? = findViewById(R.id.version_nav)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            measureChildWithMargins(
                child,
                widthMeasureSpec,
                paddingLeft + paddingRight,
                heightMeasureSpec,
                paddingTop + paddingBottom
            )
            val width = outerWidth(child)
            val height = outerHeight(child)
            if (child === versionNavigator) {
                versionWidth = width
                versionRowHeight = height
            } else {
                primaryWidth += width
                primaryRowHeight = max(primaryRowHeight, height)
            }
        }

        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val availableWidth = if (widthMode == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            (widthSize - paddingLeft - paddingRight).coerceAtLeast(0)
        }
        wrapVersionNavigator = versionWidth > 0 && primaryWidth > 0 &&
            primaryWidth + versionWidth > availableWidth

        if (!wrapVersionNavigator) {
            primaryRowHeight = max(primaryRowHeight, versionRowHeight)
        }
        val contentWidth = if (wrapVersionNavigator) {
            max(primaryWidth, versionWidth)
        } else {
            primaryWidth + versionWidth
        }
        val contentHeight = if (wrapVersionNavigator) {
            primaryRowHeight + rowGap + versionRowHeight
        } else {
            primaryRowHeight
        }

        setMeasuredDimension(
            resolveSize(contentWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val versionNavigator: View? = findViewById(R.id.version_nav)
        var cursor = paddingLeft

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE ||
                (wrapVersionNavigator && child === versionNavigator)
            ) {
                continue
            }
            layoutInRow(child, cursor, paddingTop, primaryRowHeight)
            cursor += outerWidth(child)
        }

        if (wrapVersionNavigator && versionNavigator?.visibility == View.VISIBLE) {
            val navigatorLeft = width - paddingRight - outerWidth(versionNavigator)
            layoutInRow(
                versionNavigator,
                navigatorLeft,
                paddingTop + primaryRowHeight + rowGap,
                versionRowHeight
            )
        }
    }

    private fun layoutInRow(child: View, outerLeft: Int, rowTop: Int, rowHeight: Int) {
        val params = child.layoutParams as MarginLayoutParams
        val occupiedHeight = child.measuredHeight + params.topMargin + params.bottomMargin
        val childLeft = outerLeft + params.leftMargin
        val childTop = rowTop + ((rowHeight - occupiedHeight) / 2).coerceAtLeast(0) +
            params.topMargin
        child.layout(
            childLeft,
            childTop,
            childLeft + child.measuredWidth,
            childTop + child.measuredHeight
        )
    }

    private fun outerWidth(child: View): Int {
        val params = child.layoutParams as MarginLayoutParams
        return child.measuredWidth + params.leftMargin + params.rightMargin
    }

    private fun outerHeight(child: View): Int {
        val params = child.layoutParams as MarginLayoutParams
        return child.measuredHeight + params.topMargin + params.bottomMargin
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun generateLayoutParams(params: LayoutParams): LayoutParams =
        MarginLayoutParams(params)

    override fun checkLayoutParams(params: LayoutParams): Boolean =
        params is MarginLayoutParams
}
