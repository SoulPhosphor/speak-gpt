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

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * The companion prompt tab's file-tab silhouette (owner design, Aug 16
 * 2026). Draws a trapezoidal shape with a fixed-width diagonal cut on
 * the trailing (right) edge. Tabs after the first in a row also get a
 * matching left-side slant so adjacent tabs tessellate into a
 * continuous outline when placed with negative overlap.
 *
 * [isFirstInRow]: when true the left edge is a straight vertical line;
 * when false the left edge slants from (slantWidth, height) up to
 * (0, 0), matching the previous tab's right slant so the two edges
 * share the same diagonal line.
 *
 * [drawBottomEdge]: when false the bottom stroke is suppressed so the
 * active tab merges visually into the prompt frame beneath it.
 *
 * Every color is resolved by the caller from a theme attribute and
 * passed in, never hardcoded.
 */
class PromptTabBackground(
    fillColor: Int,
    strokeColor: Int,
    private val strokeWidthPx: Float,
    private val slantWidthPx: Float,
    private val isFirstInRow: Boolean = true,
    private val drawBottomEdge: Boolean = true
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = strokeColor
        strokeWidth = strokeWidthPx
        strokeJoin = Paint.Join.MITER
    }

    private val fillPath = Path()
    private val strokePath = Path()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val inset = strokeWidthPx / 2f
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val slant = slantWidthPx.coerceIn(0f, (w - 2 * inset).coerceAtLeast(0f) * 0.6f)

        fillPath.reset()
        if (isFirstInRow) {
            fillPath.moveTo(inset, inset)
            fillPath.lineTo(w - inset - slant, inset)
            fillPath.lineTo(w - inset, h - inset)
            fillPath.lineTo(inset, h - inset)
        } else {
            fillPath.moveTo(inset, inset)
            fillPath.lineTo(w - inset - slant, inset)
            fillPath.lineTo(w - inset, h - inset)
            fillPath.lineTo(slant + inset, h - inset)
        }
        fillPath.close()

        strokePath.reset()
        if (drawBottomEdge) {
            strokePath.addPath(fillPath)
        } else {
            if (isFirstInRow) {
                strokePath.moveTo(inset, h - inset)
                strokePath.lineTo(inset, inset)
            } else {
                strokePath.moveTo(slant + inset, h - inset)
                strokePath.lineTo(inset, inset)
            }
            strokePath.lineTo(w - inset - slant, inset)
            strokePath.lineTo(w - inset, h - inset)
        }
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(strokePath, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in the base Drawable API; still required to implement it")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
