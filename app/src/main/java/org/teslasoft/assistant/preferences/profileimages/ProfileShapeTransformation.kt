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
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.content.ContextCompat
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import org.teslasoft.assistant.R
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
class ProfileShapeTransformation(context: Context, shape: String) : BitmapTransformation() {

    private val appContext = context.applicationContext
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
                val mask = ContextCompat.getDrawable(appContext, R.drawable.mtrl_shape_clover)?.mutate()
                mask?.setBounds(0, 0, width, height)
                mask?.draw(canvas)
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
    }
}
