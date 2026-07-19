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
import kotlin.math.atan2
import kotlin.math.min

/**
 * The interactive shell around [ProfileImageTransform]: it draws the source
 * bitmap under a fixed, centred square crop window (with a dimmed scrim and
 * corner guides) and turns pan / pinch / flip / 90-degree-rotate / fine-angle
 * input into transform [ProfileImageTransform.Params]. It NEVER computes the
 * transform math itself — every gesture updates the params and re-runs
 * [ProfileImageTransform.clampParams], which keeps the picture within its zoom
 * bounds and either covering the crop or centred inside it (owner ruling,
 * July 19 2026: the picture MAY be shrunk smaller than the crop; the uncovered
 * area is the black background). No display mask (Flower/Circle/Square) is ever
 * shown here; this editor produces one square master image and display masks
 * are applied later.
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
            clampAndInvalidate(notifyZoom = true)
            return true
        }
    })

    private var lastPanX = 0f
    private var lastPanY = 0f

    private var rotationAnchorDeg = 0f
    private var isTwoFingerRotating = false

    /** Fired when the crop first becomes valid (a bitmap is loaded) so the host can enable Done. */
    var onReadyListener: (() -> Unit)? = null

    /** Fired whenever a two-finger twist changes the fine angle, so the host
     *  can keep the tick dial and readout in sync with the gesture. */
    var onFineAngleChangedListener: ((Float) -> Unit)? = null

    /** Fired when a PINCH changes the zoom, so the host can keep the zoom bar
     *  in sync (as a fraction in [0,1]). Not fired for slider-driven changes
     *  ([setZoomFraction]) — the slider is already the source there, so echoing
     *  back would loop. */
    var onZoomChangedListener: ((Float) -> Unit)? = null

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
     * Renders the confirmed [outputSize] x [outputSize] result. Fills BLACK
     * first (owner ruling, July 19 2026), so both any area the shrunk picture
     * does not cover AND transparent source pixels flatten to black (never
     * undefined) before the caller JPEG-encodes it. A pickable background
     * colour is a possible later feature; for now the framing background is
     * always black.
     */
    fun getResultBitmap(outputSize: Int): Bitmap? {
        val bmp = bitmap ?: return null
        if (cropSize <= 0f) return null
        val out = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
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
        // Open at the cover fit: the picture fills the square (the user can then
        // shrink or zoom from there).
        val fit = ProfileImageTransform.minScale(bmp.width, bmp.height, cropSize, 0.0)
        params = ProfileImageTransform.Params(scale = fit)
        params = ProfileImageTransform.clampParams(params, bmp.width, bmp.height, cropSize)
        paramsInitialized = true
        invalidate()
    }

    private fun clampAndInvalidate(notifyZoom: Boolean = false) {
        val bmp = bitmap ?: return
        if (cropSize <= 0f) return
        params = ProfileImageTransform.clampParams(params, bmp.width, bmp.height, cropSize)
        if (notifyZoom) onZoomChangedListener?.invoke(getZoomFraction())
        invalidate()
    }

    /** Where the current zoom sits on the zoom bar, in [0,1]. */
    fun getZoomFraction(): Float {
        val bmp = bitmap ?: return 0f
        if (cropSize <= 0f) return 0f
        val minZoom = ProfileImageTransform.minZoomScale(bmp.width, bmp.height, cropSize)
        val maxZoom = ProfileImageTransform.maxZoomScale(bmp.width, bmp.height, cropSize)
        return ProfileImageTransform.zoomFractionForScale(params.scale, minZoom, maxZoom)
    }

    /** Sets the zoom from a bar fraction in [0,1], zooming about the crop centre
     *  exactly like a pinch (translation scales with the zoom so the centre
     *  stays put). Slider-driven, so it does NOT fire [onZoomChangedListener]. */
    fun setZoomFraction(fraction: Float) {
        val bmp = bitmap ?: return
        if (cropSize <= 0f) return
        val minZoom = ProfileImageTransform.minZoomScale(bmp.width, bmp.height, cropSize)
        val maxZoom = ProfileImageTransform.maxZoomScale(bmp.width, bmp.height, cropSize)
        val newScale = ProfileImageTransform.scaleForZoomFraction(fraction, minZoom, maxZoom)
        val old = params.scale
        val ratio = if (old > 0f) newScale / old else 1f
        params = params.copy(
            scale = newScale,
            translateX = params.translateX * ratio,
            translateY = params.translateY * ratio
        )
        clampAndInvalidate()
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
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    rotationAnchorDeg = twoFingerAngleDeg(event)
                    isTwoFingerRotating = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastPanX
                    val dy = event.y - lastPanY
                    lastPanX = event.x
                    lastPanY = event.y
                    params = params.copy(translateX = params.translateX + dx, translateY = params.translateY + dy)
                } else {
                    // A second pointer landed; reset the pan anchor so the next
                    // single-finger move doesn't jump.
                    lastPanX = event.x
                    lastPanY = event.y

                    // Pinch pans/zooms and twists at once, same as any photo
                    // editor - scale is already handled above by
                    // scaleDetector; the twist between the two fingers feeds
                    // the same fine angle the tick dial controls, clamped the
                    // same way.
                    if (isTwoFingerRotating && event.pointerCount >= 2) {
                        val currentDeg = twoFingerAngleDeg(event)
                        var delta = currentDeg - rotationAnchorDeg
                        while (delta > 180f) delta -= 360f
                        while (delta < -180f) delta += 360f
                        rotationAnchorDeg = currentDeg
                        if (delta != 0f) {
                            val newAngle = (params.fineAngleDeg + delta).coerceIn(-45f, 45f)
                            params = params.copy(fineAngleDeg = newAngle)
                            onFineAngleChangedListener?.invoke(newAngle)
                        }
                    }
                }
                clampAndInvalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                isTwoFingerRotating = false
            }
        }
        return true
    }

    /** Angle in degrees of the line between the first two pointers. */
    private fun twoFingerAngleDeg(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
