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

package org.teslasoft.assistant.util

import android.content.Context
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import org.teslasoft.assistant.preferences.profileimages.ProfileShapeTransformation
import java.io.File

/**
 * The single shared entry point for binding a Profile Image into an
 * ImageView (profile-images-plan.md, "PROFILE IMAGE SHAPE RENDERING": every
 * RecyclerView bind must reset the view before loading). Every call site
 * that shows a Companion / My Persona / Roleplay Character / Default User
 * Image picture must bind through this rather than hand-rolling Glide calls,
 * so a recycled row can never briefly show another identity's picture or
 * stale tint.
 *
 * This class only knows how to render ONE resolved file (or fall back). It
 * has no opinion on identity precedence (Companion image -> legacy avatar ->
 * built-in, etc.) or on how [imageFile] was resolved - that is each later
 * phase's job.
 */
object ProfileImageBinder {

    /**
     * @param imageFile the resolved Profile Image file, or null/missing to
     *   use [fallback] instead.
     * @param shape the current Default Shape (profile_image_shape), applied
     *   via [ProfileShapeTransformation]. Callers should pass
     *   GlobalPreferences.getProfileImageShape() - this class does not read
     *   preferences itself, so tests and previews can pass an explicit shape.
     * @param fallback sets the complete fallback state (built-in glyph,
     *   generic user icon, its own tint, etc.) when there is no image to
     *   show. Called on the SAME already-reset [imageView].
     */
    fun bind(context: Context, imageView: ImageView, imageFile: File?, shape: String, fallback: (ImageView) -> Unit) {
        // Reset first, unconditionally: cancels any in-flight/pending Glide
        // request still targeting this (possibly recycled) view, so it can
        // never complete later and clobber whichever branch runs below.
        Glide.with(context).clear(imageView)
        imageView.setImageDrawable(null)
        imageView.imageTintList = null
        imageView.background = null
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP

        if (imageFile != null && imageFile.exists()) {
            val transformation = ProfileShapeTransformation(context, shape)
            Glide.with(context)
                .load(imageFile)
                .apply(RequestOptions().transform(transformation))
                .into(imageView)
        } else {
            fallback(imageView)
        }
    }
}
