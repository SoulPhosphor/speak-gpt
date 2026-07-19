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
 * Seeds built-in icons as ordinary, permanent Profile Images
 * (profile-images-plan.md ADDENDUM, owner rulings July 19 2026).
 *
 * Two distinct rules, both implemented here:
 *
 * 1. The Global Default Image must never require a user to pick or upload
 *    anything ("users shouldn't have to select images") - the out-of-the-box
 *    value is CustomizeAssistantDialog's "gemini" preset (google_bard.xml,
 *    two overlapping sparkle/star shapes - the owner calls this "the double
 *    star"), rendered once into a real 512x512 Profile Image.
 * 2. When the user later changes the Global Default, every existing
 *    built-in preset must remain a selectable option, not just the seeded
 *    one ("why take away options"). Rather than building a second, separate
 *    picker UI (which would need new, unapproved wording), ALL FOUR presets
 *    are seeded as ordinary catalog entries the existing Profile Images
 *    gallery already shows and lets you pick, upload-alongside, or delete
 *    like any other image - no new screen or text needed.
 *
 * Must be called off the main thread - it renders bitmaps and writes
 * permanent files (ProfileImageStore.save()). Safe to call repeatedly:
 * save() dedupes by content hash, so re-seeding an already-present icon is
 * a no-op file-wise.
 */
object GlobalDefaultImageSeeder {

    private const val SEED_SIZE = 512
    // Not `const val`: R.* fields are non-final in this project's AGP
    // configuration, so they are not valid Kotlin compile-time constants.
    private val TINT_COLOR_RES = R.color.accent_900

    /** avatarId -> built-in drawable, matching StaticAvatarParser's mapping. */
    private val BUILTIN_PRESETS = listOf(
        "gpt" to R.drawable.chatgpt_icon,
        "gemini" to R.drawable.google_bard,
        "perplexity" to R.drawable.perplexity_ai,
        "speakgpt" to R.drawable.assistant
    )

    /** The preset used as the Global Default's out-of-the-box value. */
    private const val DEFAULT_SEED_AVATAR_ID = "gemini"

    /**
     * Ensures the Global Default Image is seeded, and that every built-in
     * preset exists in the catalog as a selectable option. Returns the
     * Global Default hash, or "" only if rendering/saving every preset
     * failed - callers must treat that exactly like any other absent
     * reference, never crash.
     */
    fun ensureSeeded(context: Context): String {
        val preferences = GlobalPreferences.getPreferences(context)
        val store = ProfileImageStore.getInstance(context)

        val seededByAvatarId = HashMap<String, String>()
        for ((avatarId, drawableRes) in BUILTIN_PRESETS) {
            val bitmap = renderBuiltinIcon(context, drawableRes, TINT_COLOR_RES) ?: continue
            val hash = store.save(bitmap) ?: continue
            seededByAvatarId[avatarId] = hash
        }

        val existing = preferences.getGlobalDefaultImageRef()
        if (existing.isNotEmpty() && store.contains(existing)) return existing

        val defaultHash = seededByAvatarId[DEFAULT_SEED_AVATAR_ID] ?: return existing
        preferences.setGlobalDefaultImageRef(defaultHash)
        return defaultHash
    }

    /**
     * Rasterizes a built-in vector icon - normally tinted live at draw time
     * (see CustomizeAssistantDialog, which applies this same accent_900
     * tint to all four presets) - into a fixed-color bitmap. A permanent
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
