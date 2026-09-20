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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.PathParser
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

/**
 * The single approved Glide transformation for shaping uploaded Profile
 * Images (profile-images-plan.md, "PROFILE IMAGE SHAPE RENDERING"). Never
 * apply a second, competing view-clipping system (ShapeableImageView, a
 * custom outline provider, etc.) alongside this one.
 *
 * - Flower masks with the existing clover vector (res/drawable/
 *   mtrl_shape_clover.xml), rasterized to the bitmap's own size - the vector
 *   auto-scales its 184x184 viewport to whatever bounds it is drawn into, so
 *   no manual path parsing is needed.
 * - Circle masks with an oval covering the full bitmap.
 * - Square performs no masking at all (the plan requires true 90-degree
 *   corners): the source bitmap is returned unchanged.
 *
 * Built-in glyph avatars, their backgrounds, and the generic user icon never
 * go through this class - it is applied only to uploaded Profile Images.
 */
class ProfileShapeTransformation(@Suppress("unused") context: Context, shape: String) : BitmapTransformation() {

    // context is retained in the constructor signature for API stability (all
    // call sites pass one); the clover is now drawn from its path data, so no
    // Context is needed at transform time.
    private val shape = ProfileImageShape.normalize(shape)

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        if (shape == ProfileImageShape.SQUARE) return toTransform

        val width = toTransform.width
        val height = toTransform.height
        val output = pool.get(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, width, height)

        when (shape) {
            ProfileImageShape.CIRCLE -> canvas.drawOval(RectF(rect), paint)
            else -> { // FLOWER
                // Fill the clover shape ourselves, straight from its path data
                // scaled to the bitmap, with an opaque paint. Only the mask's
                // alpha matters (SRC_IN below clips the source bitmap, the
                // mask's colour never reaches the output). The previous
                // approach loaded res/drawable/mtrl_shape_clover.xml and relied
                // on its ?attr/colorPrimary fill: that theme attribute did not
                // resolve against the Application context the transformation
                // runs on, so the mask came out fully transparent and any
                // Flower-shaped picture (and the shape preview) rendered
                // nothing at all. Drawing the path directly removes that theme
                // dependency entirely. Path/viewport copied from that drawable.
                val cloverPath = PathParser.createPathFromPathData(CLOVER_PATH_DATA)
                val scale = Matrix().apply { setScale(width / CLOVER_VIEWPORT, height / CLOVER_VIEWPORT) }
                cloverPath.transform(scale)
                paint.color = Color.BLACK
                canvas.drawPath(cloverPath, paint)
            }
        }

        // Clip the source bitmap to whatever was just drawn (the mask's
        // alpha), matching the mask-then-SRC_IN pattern already used for the
        // built-in avatar rounding elsewhere in the app.
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(toTransform, rect, rect, paint)

        return output
    }

    override fun equals(other: Any?): Boolean =
        other is ProfileShapeTransformation && other.shape == shape

    override fun hashCode(): Int = ID.hashCode() * 31 + shape.hashCode()

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(ID_BYTES)
        messageDigest.update(ProfileImageShape.cacheKeyComponent(shape).toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val ID = "org.teslasoft.assistant.preferences.profileimages.ProfileShapeTransformation"
        private val ID_BYTES = ID.toByteArray(Charsets.UTF_8)

        // The clover outline, copied verbatim from res/drawable/
        // mtrl_shape_clover.xml (a 184x184 viewport). Drawn directly so the
        // Flower mask never depends on a theme colour resolving.
        private const val CLOVER_VIEWPORT = 184f
        private const val CLOVER_PATH_DATA =
            "M92 12C80.568 12 69.9033 6.62805 59.1725 2.68596C54.4428 0.948493 49.3322 0 44 0C19.6995 0 0 " +
            "19.6995 0 44C0 49.3322 0.948493 54.4428 2.68596 59.1725C6.62806 69.9033 12 80.568 12 92C12 " +
            "103.432 6.62806 114.097 2.68596 124.828C0.948493 129.557 0 134.668 0 140C0 164.301 19.6995 184 " +
            "44 184C49.3322 184 54.4428 183.052 59.1724 181.314C69.9033 177.372 80.568 172 92 172C103.432 " +
            "172 114.097 177.372 124.828 181.314C129.557 183.052 134.668 184 140 184C164.301 184 184 164.301 " +
            "184 140C184 134.668 183.052 129.557 181.314 124.828C177.372 114.097 172 103.432 172 92C172 " +
            "80.568 177.372 69.9033 181.314 59.1724C183.052 54.4428 184 49.3322 184 44C184 19.6995 164.301 0 " +
            "140 0C134.668 0 129.557 0.948493 124.828 2.68596C114.097 6.62806 103.432 12 92 12Z"
    }
}
