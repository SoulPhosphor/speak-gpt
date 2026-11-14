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

package org.teslasoft.assistant.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.teslasoft.assistant.R

/**
 * A one-choice row of words inside a pill (Widget.App.SegmentedPill). The
 * chosen word sits on a highlight capsule that slides over to a newly chosen
 * word. The words are the layout's own children (Widget.App.SegmentedPill.Option);
 * hidden words take no space and the capsule follows the visible ones.
 * Colors, sizes and timing all come from the style.
 */
class SegmentedPill @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val animationMs: Long
    private val thumb = RectF()
    private var selected: View? = null
    private var animator: ValueAnimator? = null

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.SegmentedPill, defStyleAttr, 0)
        thumbPaint.color = a.getColor(R.styleable.SegmentedPill_segmentThumbColor, 0)
        animationMs = a.getInt(R.styleable.SegmentedPill_segmentAnimationMs, 0).toLong()
        a.recycle()
        orientation = HORIZONTAL
        setWillNotDraw(false)
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        ViewCompat.setAccessibilityDelegate(child, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.RadioButton"
                info.isCheckable = true
                info.isChecked = host.isSelected
            }
        })
    }

    /** Marks [child] as the chosen word; [animate] slides the capsule to it. */
    fun select(child: View, animate: Boolean) {
        val changed = selected !== child
        selected = child
        for (i in 0 until childCount) getChildAt(i).isSelected = getChildAt(i) === child
        if (!changed || !animate || !isLaidOut || thumb.isEmpty || child.width == 0) {
            animator?.cancel()
            snapToSelected()
            return
        }
        val from = RectF(thumb)
        val to = boundsOf(child)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = animationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                thumb.set(
                    from.left + (to.left - from.left) * f,
                    to.top,
                    from.right + (to.right - from.right) * f,
                    to.bottom
                )
                invalidate()
            }
            start()
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Words shown or hidden move the chosen one; keep the capsule on it.
        if (animator?.isRunning != true) snapToSelected()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!thumb.isEmpty) {
            val radius = thumb.height() / 2f
            canvas.drawRoundRect(thumb, radius, radius, thumbPaint)
        }
        super.dispatchDraw(canvas)
    }

    private fun snapToSelected() {
        val child = selected
        if (child == null || child.visibility != VISIBLE || child.width == 0) thumb.setEmpty()
        else thumb.set(boundsOf(child))
        invalidate()
    }

    private fun boundsOf(child: View) = RectF(
        child.left.toFloat(), child.top.toFloat(), child.right.toFloat(), child.bottom.toFloat()
    )
}
