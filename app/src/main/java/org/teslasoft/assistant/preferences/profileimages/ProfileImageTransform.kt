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

package org.teslasoft.assistant.preferences.profileimages

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The pure transform-math core of the Framing editor (profile-images-plan.md,
 * "CUSTOM MATRIX REQUIREMENTS"). Deliberately free of any android.* import so
 * every rule here is unit-testable on the JVM; ProfileImageFramingActivity is
 * a thin view shell around this class and must not re-derive any of this math.
 *
 * Coordinate frames:
 *  - Image space: source pixels, x in [0, imgW], y in [0, imgH].
 *  - Crop-centered view space: the fixed 1:1 crop window on screen, centered at
 *    the origin, so its four corners are (+-cropSize/2, +-cropSize/2).
 *
 * The display maps image space -> crop-centered view space via, in order:
 * recenter the image on its own middle, flip X (optional), uniform scale,
 * rotate by (quarterTurns*90 + fineAngle), then translate. The crop window
 * never moves or resizes; the image moves under it.
 *
 * Owner ruling (July 19 2026): the picture MAY be shrunk smaller than the crop
 * square. The old "no empty edges" rule (the crop must always be fully covered)
 * is retired — the editor now clamps zoom into [minZoomScale, maxZoomScale],
 * and [clampParams] keeps the picture covering the crop only while it is big
 * enough to, otherwise centring it so the shrunk picture sits inside the square
 * on the framing background (the Framing view fills that background black, and
 * the empty area is saved black). [isFullyCovered] and [minScale] (the exact
 * cover scale) are kept: [minScale] is still the sensible OPENING fit (picture
 * fills the square) and [isFullyCovered] still tells whether a given state
 * happens to cover.
 */
object ProfileImageTransform {

    /** The picture may shrink until its longer side is this fraction of the
     *  crop (small, but never a speck). Rotation-independent so the floor does
     *  not shift as the picture is twisted. */
    const val ZOOM_OUT_FRACTION = 0.2f
    /** Zoom-in ceiling as a multiple of the exact cover scale, so the zoom bar
     *  has a defined top end and the pinch gesture agrees with it. */
    const val ZOOM_IN_FACTOR = 8f

    /**
     * The user-controlled transform state. Persisted across activity/process
     * recreation (STATE RESTORATION) and turned into the final output matrix.
     *
     * @param translateX image-center offset from the crop center, view px (x)
     * @param translateY image-center offset from the crop center, view px (y)
     * @param scale uniform view-px-per-image-px scale (> 0)
     * @param flipX horizontal mirror
     * @param quarterTurns clockwise 90-degree steps (any int; normalized 0..3)
     * @param fineAngleDeg fine rotation in [-45, 45]; the readout shows THIS
     *   value only — a 90-degree turn never changes it (see [totalRotationDeg]).
     */
    data class Params(
        val translateX: Float = 0f,
        val translateY: Float = 0f,
        val scale: Float = 1f,
        val flipX: Boolean = false,
        val quarterTurns: Int = 0,
        val fineAngleDeg: Float = 0f
    )

    /** A point (used for both image-space and view-space coordinates). */
    data class Point(val x: Double, val y: Double)

    /**
     * Effective rotation actually applied to the image: quarter turns plus the
     * fine angle. Example from the plan: one 90-degree turn plus +7 degrees of
     * fine rotation is 97 degrees effective, while the readout (fineAngleDeg)
     * stays +7 — quarter turns and the fine angle are tracked separately.
     */
    fun totalRotationDeg(p: Params): Double =
        normalizeQuarterTurns(p.quarterTurns) * 90.0 + p.fineAngleDeg

    fun normalizeQuarterTurns(q: Int): Int = ((q % 4) + 4) % 4

    /**
     * The smallest uniform scale at which the (centered) image can cover the
     * crop square at the given effective rotation. A square crop rotated by
     * theta has bounding half-extent cropHalf*(|cos|+|sin|) in image space, so
     * scale must reach cropSize*(|cos|+|sin|)/min(imgW,imgH). This is the floor
     * used to clamp zoom-out; actual coverage with translation is still
     * validated by [isFullyCovered].
     */
    fun minScale(imgW: Int, imgH: Int, cropSize: Float, totalRotationDeg: Double): Float {
        if (imgW <= 0 || imgH <= 0 || cropSize <= 0f) return 0f
        val r = Math.toRadians(totalRotationDeg)
        val factor = abs(cos(r)) + abs(sin(r))
        return (cropSize * factor / min(imgW, imgH)).toFloat()
    }

    /** The smallest allowed zoom (picture at its smallest): its longer side
     *  shrunk to [ZOOM_OUT_FRACTION] of the crop. Rotation-independent. */
    fun minZoomScale(imgW: Int, imgH: Int, cropSize: Float): Float {
        if (imgW <= 0 || imgH <= 0 || cropSize <= 0f) return 0f
        return ZOOM_OUT_FRACTION * cropSize / max(imgW, imgH)
    }

    /** The largest allowed zoom: [ZOOM_IN_FACTOR] times the un-rotated cover
     *  scale. Bounds the zoom bar and caps the pinch so the two agree. */
    fun maxZoomScale(imgW: Int, imgH: Int, cropSize: Float): Float {
        if (imgW <= 0 || imgH <= 0 || cropSize <= 0f) return 0f
        return ZOOM_IN_FACTOR * cropSize / min(imgW, imgH)
    }

    /** Maps a zoom-bar fraction in [0,1] to a scale, geometrically (equal
     *  fractions are equal zoom ratios, which reads evenly on a slider). */
    fun scaleForZoomFraction(fraction: Float, minZoom: Float, maxZoom: Float): Float {
        if (minZoom <= 0f || maxZoom <= minZoom) return minZoom
        val f = fraction.coerceIn(0f, 1f)
        return (minZoom.toDouble() * (maxZoom.toDouble() / minZoom.toDouble()).pow(f.toDouble())).toFloat()
    }

    /** The inverse of [scaleForZoomFraction], clamped to [0,1] — where a given
     *  scale sits on the zoom bar. */
    fun zoomFractionForScale(scale: Float, minZoom: Float, maxZoom: Float): Float {
        if (minZoom <= 0f || maxZoom <= minZoom || scale <= 0f) return 0f
        val f = (ln(scale.toDouble() / minZoom.toDouble()) / ln(maxZoom.toDouble() / minZoom.toDouble())).toFloat()
        return f.coerceIn(0f, 1f)
    }

    /** The four crop-square corners inverse-mapped into image space. */
    fun cropCornersInImageSpace(p: Params, imgW: Int, imgH: Int, cropSize: Float): List<Point> {
        val half = cropSize / 2.0
        return listOf(
            Point(-half, -half), Point(half, -half), Point(half, half), Point(-half, half)
        ).map { viewToImage(p, imgW, imgH, it) }
    }

    /**
     * The no-empty-edges authority: true iff every crop corner maps inside the
     * image. A tiny epsilon absorbs float rounding so an exact edge counts as
     * covered.
     */
    fun isFullyCovered(p: Params, imgW: Int, imgH: Int, cropSize: Float): Boolean {
        if (imgW <= 0 || imgH <= 0 || p.scale <= 0f) return false
        val eps = 1e-3
        return cropCornersInImageSpace(p, imgW, imgH, cropSize).all {
            it.x >= -eps && it.x <= imgW + eps && it.y >= -eps && it.y <= imgH + eps
        }
    }

    /**
     * Clamps [p] to a valid state (owner ruling, July 19 2026): scale is held
     * within [[minZoomScale], [maxZoomScale]] — NOT raised to the cover scale,
     * so the picture may be smaller than the crop — then translation is shifted
     * per axis so that, on any axis where the picture is big enough to cover the
     * crop, it keeps covering it (panning still allowed along an overhang), and
     * on any axis where it is smaller than the crop it is centred (the shrunk
     * picture sits inside the square, the rest saved as the framing background).
     * Called by the view after every pan/pinch/rotate/flip/restore.
     */
    fun clampParams(p: Params, imgW: Int, imgH: Int, cropSize: Float): Params {
        if (imgW <= 0 || imgH <= 0 || cropSize <= 0f) return p

        val minZoom = minZoomScale(imgW, imgH, cropSize)
        val maxZoom = maxZoomScale(imgW, imgH, cropSize)
        val scaled = if (p.scale < minZoom || p.scale > maxZoom) {
            p.copy(scale = p.scale.coerceIn(minZoom, maxZoom))
        } else p

        // The four image-space corners shift rigidly with translation, so bring
        // their bounding box inside [0,imgW]x[0,imgH] by shifting in image space,
        // then convert that image-space shift back into a translation delta.
        val corners = cropCornersInImageSpace(scaled, imgW, imgH, cropSize)
        val minX = corners.minOf { it.x }
        val maxX = corners.maxOf { it.x }
        val minY = corners.minOf { it.y }
        val maxY = corners.maxOf { it.y }

        val shiftImgX = axisShift(minX, maxX, imgW.toDouble())
        val shiftImgY = axisShift(minY, maxY, imgH.toDouble())
        if (shiftImgX == 0.0 && shiftImgY == 0.0) return scaled

        // p = q - L*translate, so shifting every corner by +s in image space
        // needs translate change delta with -L*delta = s, i.e. delta = -L^-1 s.
        // L = (1/scale)*flip*R(-theta); L^-1 = scale*R(theta)*flip.
        val theta = Math.toRadians(totalRotationDeg(scaled))
        val cosT = cos(theta)
        val sinT = sin(theta)
        val flip = if (scaled.flipX) -1.0 else 1.0
        // delta = -scale * R(theta) * flip * (shiftImgX, shiftImgY)
        val fx = flip * shiftImgX
        val fy = shiftImgY
        val rx = cosT * fx - sinT * fy
        val ry = sinT * fx + cosT * fy
        val deltaX = -scaled.scale * rx
        val deltaY = -scaled.scale * ry

        return scaled.copy(
            translateX = scaled.translateX + deltaX.toFloat(),
            translateY = scaled.translateY + deltaY.toFloat()
        )
    }

    /**
     * The image-pixel -> output-pixel affine for rendering the final
     * [outputSize] x [outputSize] result, in Android Matrix.setValues order
     * [MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y, 0, 0, 1].
     * Maps the crop square onto the full output, so the covered crop fills the
     * result with no blank edges.
     */
    fun outputMatrixValues(p: Params, imgW: Int, imgH: Int, cropSize: Float, outputSize: Int): FloatArray {
        val display = displayAffine(p, imgW, imgH)
        val k = outputSize / cropSize.toDouble()
        // O = T(outputSize/2) * S(k): view (crop-centered) -> output pixels.
        val output = Affine(k, 0.0, 0.0, k, outputSize / 2.0, outputSize / 2.0)
        val f = output.times(display)
        return floatArrayOf(
            f.a.toFloat(), f.c.toFloat(), f.e.toFloat(),
            f.b.toFloat(), f.d.toFloat(), f.f.toFloat(),
            0f, 0f, 1f
        )
    }

    /**
     * The image-pixel -> actual-view-pixel affine for DRAWING the image under
     * the crop window, in Android Matrix.setValues order. Same display
     * transform as coverage/output use, plus a shift so the crop center lands
     * at ([cropCenterX], [cropCenterY]) in the view. The view is a shell: it
     * only calls this and draws — it must not recompute the transform itself.
     */
    fun displayMatrixValues(p: Params, imgW: Int, imgH: Int, cropCenterX: Float, cropCenterY: Float): FloatArray {
        val toView = Affine(1.0, 0.0, 0.0, 1.0, cropCenterX.toDouble(), cropCenterY.toDouble())
        val m = toView.times(displayAffine(p, imgW, imgH))
        return floatArrayOf(
            m.a.toFloat(), m.c.toFloat(), m.e.toFloat(),
            m.b.toFloat(), m.d.toFloat(), m.f.toFloat(),
            0f, 0f, 1f
        )
    }

    /* --------------------------- internals --------------------------- */

    /**
     * The image-space shift for one axis. [lo,hi] is the crop's footprint on
     * that axis in image space; [size] is the image extent (imgW or imgH).
     *  - Footprint no wider than the image (picture covers this axis): keep the
     *    footprint inside [0,size], i.e. keep the crop covered (0 if it already
     *    is, so an overhang can still be panned).
     *  - Footprint wider than the image (picture smaller than the crop here):
     *    centre the image within the footprint, so the shrunk picture sits
     *    centred in the square rather than clinging to an edge.
     */
    private fun axisShift(lo: Double, hi: Double, size: Double): Double {
        val eps = 1e-6
        val span = hi - lo
        return if (span <= size + eps) {
            when {
                lo < -eps -> -lo                 // push right until lo == 0
                hi > size + eps -> size - hi     // push left until hi == size
                else -> 0.0
            }
        } else {
            size / 2.0 - (lo + hi) / 2.0         // centre image in the footprint
        }
    }

    /** image space -> crop-centered view space. */
    private fun displayAffine(p: Params, imgW: Int, imgH: Int): Affine {
        val sx = if (p.flipX) -p.scale.toDouble() else p.scale.toDouble()
        val sy = p.scale.toDouble()
        val theta = Math.toRadians(totalRotationDeg(p))
        val cosT = cos(theta)
        val sinT = sin(theta)

        // D = T(tx,ty) * R(theta) * S(sx,sy) * T(-imgW/2,-imgH/2)
        val recenter = Affine(1.0, 0.0, 0.0, 1.0, -imgW / 2.0, -imgH / 2.0)
        val scale = Affine(sx, 0.0, 0.0, sy, 0.0, 0.0)
        val rotate = Affine(cosT, sinT, -sinT, cosT, 0.0, 0.0)
        val translate = Affine(1.0, 0.0, 0.0, 1.0, p.translateX.toDouble(), p.translateY.toDouble())
        return translate.times(rotate).times(scale).times(recenter)
    }

    private fun viewToImage(p: Params, imgW: Int, imgH: Int, view: Point): Point =
        displayAffine(p, imgW, imgH).inverse().map(view)

    /**
     * A 2D affine: x' = a*x + c*y + e, y' = b*x + d*y + f. Column-vector
     * convention so [times] composes as matrix multiply (this applied after
     * other).
     */
    private data class Affine(
        val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double
    ) {
        fun times(o: Affine): Affine = Affine(
            a = a * o.a + c * o.b,
            b = b * o.a + d * o.b,
            c = a * o.c + c * o.d,
            d = b * o.c + d * o.d,
            e = a * o.e + c * o.f + e,
            f = b * o.e + d * o.f + f
        )

        fun map(pt: Point): Point = Point(a * pt.x + c * pt.y + e, b * pt.x + d * pt.y + f)

        fun inverse(): Affine {
            val det = a * d - b * c
            require(abs(det) > 1e-12) { "non-invertible transform" }
            val ia = d / det
            val ib = -b / det
            val ic = -c / det
            val id = a / det
            // inverse translation: -(inverseLinear * (e,f))
            val ie = -(ia * e + ic * f)
            val ifv = -(ib * e + id * f)
            return Affine(ia, ib, ic, id, ie, ifv)
        }
    }
}
