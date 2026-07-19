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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import org.teslasoft.assistant.preferences.profileimages.ProfileImageTransform
import kotlin.math.min

/**
 * The interactive shell around [ProfileImageTransform]: it draws the source
 * bitmap under a fixed, centred square crop window (with a dimmed scrim and
 * corner guides) and turns pan / pinch / flip / 90-degree-rotate / fine-angle
 * input into transform [ProfileImageTransform.Params]. It NEVER computes the
 * transform math itself — every gesture updates the params and re-runs
 * [ProfileImageTransform.clampToCover], so the crop can never expose an empty
 * edge. No display mask (Flower/Circle/Square) is ever shown here; this editor
 * produces one square master image and display masks are applied later.
 */
class FramingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bitmap: Bitmap? = null
    private var params = ProfileImageTransform.Params()
    private var paramsInitialized = false

    private val cropRect = RectF()
    private var cropSize = 0f

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(1f)
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(3f)
    }
    private val drawMatrix = Matrix()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (bitmap == null) return true
            // Zoom about the crop centre: translation (image-centre offset from
            // crop centre) scales with the zoom, keeping the centre stable.
            val f = detector.scaleFactor
            params = params.copy(
                scale = params.scale * f,
                translateX = params.translateX * f,
                translateY = params.translateY * f
            )
            clampAndInvalidate()
            return true
        }
    })

    private var lastPanX = 0f
    private var lastPanY = 0f

    /** Fired when the crop first becomes valid (a bitmap is loaded) so the host can enable Done. */
    var onReadyListener: (() -> Unit)? = null

    fun hasImage(): Boolean = bitmap != null

    fun setBitmap(bmp: Bitmap) {
        bitmap = bmp
        // Defer the initial cover-fit until the view has a size; onSizeChanged
        // completes it when cropSize is known.
        if (cropSize > 0f) initParamsForCurrentSize()
        onReadyListener?.invoke()
        invalidate()
    }

    fun getParams(): ProfileImageTransform.Params = params

    fun setParams(p: ProfileImageTransform.Params) {
        params = p
        paramsInitialized = true
        if (cropSize > 0f) clampAndInvalidate() else invalidate()
    }

    fun toggleFlip() {
        params = params.copy(flipX = !params.flipX)
        clampAndInvalidate()
    }

    /** One clockwise 90-degree step; never disturbs the fine angle. */
    fun rotate90() {
        params = params.copy(quarterTurns = ProfileImageTransform.normalizeQuarterTurns(params.quarterTurns + 1))
        clampAndInvalidate()
    }

    fun setFineAngle(deg: Float) {
        params = params.copy(fineAngleDeg = deg.coerceIn(-45f, 45f))
        clampAndInvalidate()
    }

    fun getFineAngle(): Float = params.fineAngleDeg

    /**
     * Renders the confirmed [outputSize] x [outputSize] result. Fills white
     * first so transparent source pixels flatten over white (never black or
     * undefined) before the caller JPEG-encodes it. Coverage is guaranteed by
     * clampToCover, so the output has no empty edges.
     */
    fun getResultBitmap(outputSize: Int): Bitmap? {
        val bmp = bitmap ?: return null
        if (cropSize <= 0f) return null
        val out = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val m = Matrix()
        m.setValues(ProfileImageTransform.outputMatrixValues(params, bmp.width, bmp.height, cropSize, outputSize))
        canvas.drawBitmap(bmp, m, bitmapPaint)
        return out
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cropSize = min(w, h) * 0.82f
        val cx = w / 2f
        val cy = h / 2f
        val half = cropSize / 2f
        cropRect.set(cx - half, cy - half, cx + half, cy + half)
        if (bitmap != null) {
            if (paramsInitialized) clampAndInvalidate() else initParamsForCurrentSize()
        }
    }

    private fun initParamsForCurrentSize() {
        val bmp = bitmap ?: return
        if (cropSize <= 0f) return
        val floor = ProfileImageTransform.minScale(bmp.width, bmp.height, cropSize, 0.0)
        params = ProfileImageTransform.Params(scale = floor)
        params = ProfileImageTransform.clampToCover(params, bmp.width, bmp.height, cropSize)
        paramsInitialized = true
        invalidate()
    }

    private fun clampAndInvalidate() {
        val bmp = bitmap ?: return
        if (cropSize <= 0f) return
        params = ProfileImageTransform.clampToCover(params, bmp.width, bmp.height, cropSize)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        if (cropSize <= 0f) return

        drawMatrix.setValues(
            ProfileImageTransform.displayMatrixValues(params, bmp.width, bmp.height, cropRect.centerX(), cropRect.centerY())
        )
        canvas.drawBitmap(bmp, drawMatrix, bitmapPaint)

        // Dim everything outside the crop square (four bands around it).
        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, scrimPaint)
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), scrimPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, scrimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, scrimPaint)

        canvas.drawRect(cropRect, borderPaint)
        drawCornerGuides(canvas)
    }

    private fun drawCornerGuides(canvas: Canvas) {
        val len = cropSize * 0.12f
        val l = cropRect.left
        val t = cropRect.top
        val r = cropRect.right
        val b = cropRect.bottom
        // Top-left
        canvas.drawLine(l, t, l + len, t, guidePaint)
        canvas.drawLine(l, t, l, t + len, guidePaint)
        // Top-right
        canvas.drawLine(r, t, r - len, t, guidePaint)
        canvas.drawLine(r, t, r, t + len, guidePaint)
        // Bottom-left
        canvas.drawLine(l, b, l + len, b, guidePaint)
        canvas.drawLine(l, b, l, b - len, guidePaint)
        // Bottom-right
        canvas.drawLine(r, b, r - len, b, guidePaint)
        canvas.drawLine(r, b, r, b - len, guidePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastPanX = event.x
                lastPanY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastPanX
                    val dy = event.y - lastPanY
                    lastPanX = event.x
                    lastPanY = event.y
                    params = params.copy(translateX = params.translateX + dx, translateY = params.translateY + dy)
                    clampAndInvalidate()
                } else {
                    // A second pointer landed; reset the pan anchor so the next
                    // single-finger move doesn't jump.
                    lastPanX = event.x
                    lastPanY = event.y
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
