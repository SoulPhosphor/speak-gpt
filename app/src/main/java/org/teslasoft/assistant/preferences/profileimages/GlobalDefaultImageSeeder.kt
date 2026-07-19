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
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.GlobalPreferences

/**
 * Seeds the Global Default Image (profile-images-plan.md ADDENDUM, owner
 * ruling July 19 2026): the app must never require a user to pick or
 * upload anything for a shared default to exist ("users shouldn't have to
 * select images"). The out-of-the-box value is a repurposed built-in icon
 * from CustomizeAssistantDialog's preset row - the "gemini" preset
 * (google_bard.xml, two overlapping sparkle/star shapes) - rendered ONCE
 * into a real, permanent 512x512 Profile Image the first time it is
 * needed, so it behaves as an ordinary catalog entry (viewable, reusable,
 * replaceable) rather than a hardcoded special case.
 *
 * Must be called off the main thread - it renders a bitmap and writes a
 * permanent file (ProfileImageStore.save()).
 */
object GlobalDefaultImageSeeder {

    private const val SEED_SIZE = 512

    /**
     * Returns the Global Default Image hash, seeding it if it is unset or
     * the catalog no longer has it (e.g. the file was deleted). Returns ""
     * only if rendering or saving the seed fails - callers must treat that
     * exactly like any other absent reference, never crash.
     */
    fun ensureSeeded(context: Context): String {
        val preferences = GlobalPreferences.getPreferences(context)
        val store = ProfileImageStore.getInstance(context)

        val existing = preferences.getGlobalDefaultImageRef()
        if (existing.isNotEmpty() && store.contains(existing)) return existing

        val bitmap = renderBuiltinIcon(context, R.drawable.google_bard, R.color.accent_900) ?: return ""
        val hash = store.save(bitmap) ?: return ""
        preferences.setGlobalDefaultImageRef(hash)
        return hash
    }

    /**
     * Rasterizes a built-in vector icon - normally tinted live at draw time
     * (see CustomizeAssistantDialog, which applies the same accent_900 tint
     * to this exact drawable) - into a fixed-color bitmap. A permanent
     * Profile Image is a plain JPEG; it cannot re-tint itself later the way
     * the live vector does, so the color must be locked in now. Composited
     * over white first (FRAMING OUTPUT: transparent regions never become
     * black or undefined in the final JPEG).
     */
    private fun renderBuiltinIcon(context: Context, drawableRes: Int, colorRes: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, drawableRes)?.mutate() ?: return null
        DrawableCompat.setTint(drawable, ContextCompat.getColor(context, colorRes))

        val bitmap = Bitmap.createBitmap(SEED_SIZE, SEED_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        drawable.setBounds(0, 0, SEED_SIZE, SEED_SIZE)
        drawable.draw(canvas)
        return bitmap
    }
}
