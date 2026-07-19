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

package org.teslasoft.assistant.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * A horizontal tick-mark dial for quick fine-rotation adjustment
 * (profile-images-plan.md: "Fine-rotation tick-mark dial"). Range -45..+45
 * degrees with decimals; drag left/right to change the angle. The exact-entry
 * numeric dialog is the precise counterpart. The value shown as the readout
 * lives in a separate TextView owned by the activity; this view only reports
 * changes via [onAngleChanged].
 */
class RotationDialView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val minAngle = -45f
    private val maxAngle = 45f

    private var angle = 0f
    private var lastX = 0f

    /** view px travelled per degree of change while dragging. */
    private val pxPerDegree: Float get() = dp(6f)

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(1f)
        alpha = 160
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(1.5f)
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(2f)
    }

    /** Notified on every drag change with the clamped angle. */
    var onAngleChanged: ((Float) -> Unit)? = null

    fun getAngle(): Float = angle

    /** Sets the angle from outside (e.g. the numeric dialog) without firing the listener. */
    fun setAngle(value: Float) {
        angle = value.coerceIn(minAngle, maxAngle)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val h = height.toFloat()

        // Ticks every degree; taller ticks every 15 degrees. The tick for the
        // current angle sits under the centre pointer.
        var deg = -45
        while (deg <= 45) {
            val x = centerX + (deg - angle) * pxPerDegree
            if (x >= 0f && x <= width) {
                val major = deg % 15 == 0
                val tickHeight = if (major) h * 0.5f else h * 0.3f
                val paint = if (major) majorTickPaint else tickPaint
                canvas.drawLine(x, (h - tickHeight) / 2f, x, (h + tickHeight) / 2f, paint)
            }
            deg++
        }

        // Fixed centre pointer marking the selected angle.
        canvas.drawLine(centerX, h * 0.15f, centerX, h * 0.85f, centerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                lastX = event.x
                // Dragging right decreases the angle (the strip moves left).
                val newAngle = (angle - dx / pxPerDegree).coerceIn(minAngle, maxAngle)
                if (newAngle != angle) {
                    angle = newAngle
                    onAngleChanged?.invoke(roundToTenth(angle))
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                performClick()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun roundToTenth(value: Float): Float = (value * 10f).roundToInt() / 10f

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
