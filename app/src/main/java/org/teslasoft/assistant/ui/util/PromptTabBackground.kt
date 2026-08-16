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
 * 2026): a vertical left edge and horizontal top/bottom edges, with a
 * fixed-width diagonal cut on the trailing edge - narrower at the top,
 * full width at the bottom - so every tab reads as an angled file tab
 * regardless of its own text width. A plain XML `<shape>` cannot express a
 * non-rectangular edge, which is why this exists as a small Drawable
 * instead of a drawable resource; every color it paints with is resolved
 * by the caller from a theme attribute and passed in, never hardcoded
 * here, so the shape stays theme/palette-ready like the rest of the app's
 * shared visual system.
 */
class PromptTabBackground(
    fillColor: Int,
    strokeColor: Int,
    private val strokeWidthPx: Float,
    private val slantWidthPx: Float
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

    private val path = Path()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val inset = strokeWidthPx / 2f
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val slant = slantWidthPx.coerceIn(0f, (w - 2 * inset).coerceAtLeast(0f) * 0.6f)

        path.reset()
        path.moveTo(inset, inset)                 // top-left
        path.lineTo(w - inset - slant, inset)      // top-right, cut inward
        path.lineTo(w - inset, h - inset)          // bottom-right, full width
        path.lineTo(inset, h - inset)              // bottom-left
        path.close()
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
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
