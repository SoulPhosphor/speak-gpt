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
 * 2026). Every tab shares one continuous horizontal top edge across the
 * row. The diagonal slant appears only as an internal separator between
 * adjacent tabs (and as the outer right edge of the last tab in the
 * row). The fill is always a trapezoid whose right edge follows the
 * slant; the top stroke extends past the fill to the view's full width
 * on non-last tabs so the top line reads as one piece.
 *
 * [isFirstInRow]: draw the outer left edge stroke.
 * [isLastInRow]: the slant is the outer right boundary; do not extend
 *   the top stroke past it.
 * [drawBottomEdge]: false on the active tab in the bottom row so it
 *   merges into the prompt frame.
 *
 * Every color is resolved by the caller from a theme attribute, never
 * hardcoded.
 */
class PromptTabBackground(
    fillColor: Int,
    strokeColor: Int,
    private val strokeWidthPx: Float,
    private val slantWidthPx: Float,
    private val isFirstInRow: Boolean = true,
    private val isLastInRow: Boolean = true,
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

        // Fill: trapezoid whose right edge follows the slant. Same shape
        // for every tab — first, middle, or last.
        fillPath.reset()
        fillPath.moveTo(inset, inset)
        fillPath.lineTo(w - inset - slant, inset)
        fillPath.lineTo(w - inset, h - inset)
        fillPath.lineTo(inset, h - inset)
        fillPath.close()

        // Stroke: open path of individual segments.
        strokePath.reset()

        // Left edge (first tab only).
        if (isFirstInRow) {
            strokePath.moveTo(inset, h - inset)
            strokePath.lineTo(inset, inset)
        } else {
            strokePath.moveTo(inset, inset)
        }

        if (isLastInRow) {
            // Top ends at the slant start, then the slant is the outer edge.
            strokePath.lineTo(w - inset - slant, inset)
            strokePath.lineTo(w - inset, h - inset)
        } else {
            // Top extends to the view's full width (continuous top line).
            strokePath.lineTo(w - inset, inset)
            // Internal separator: diagonal from slant-start to bottom-right.
            strokePath.moveTo(w - inset - slant, inset)
            strokePath.lineTo(w - inset, h - inset)
        }

        // Bottom edge.
        if (drawBottomEdge) {
            strokePath.lineTo(inset, h - inset)
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
