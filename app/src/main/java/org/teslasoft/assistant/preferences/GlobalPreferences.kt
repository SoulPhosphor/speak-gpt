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

package org.teslasoft.assistant.preferences

import android.content.Context
import android.content.SharedPreferences

class GlobalPreferences private constructor(private var gp: SharedPreferences) {
    companion object {
        private var preferences: GlobalPreferences? = null
        fun getPreferences(context: Context) : GlobalPreferences {
            if (preferences == null) preferences = GlobalPreferences(context.getSharedPreferences("settings", Context.MODE_PRIVATE))

            return preferences!!
        }
    }

    /**
     * Get amoled pitch black mode
     *
     * @return amoled pitch black mode
     * */
    fun getAmoledPitchBlack() : Boolean {
        return gp.getBoolean("amoled_pitch_black", false)
    }

    /**
     * Get UI color palette (see ui-redesign-plan.md). Global, not per-chat.
     *
     * @return palette key, default "violet" (the app's original look)
     * */
    fun getUiPalette() : String {
        return gp.getString("ui_palette", "violet") ?: "violet"
    }

    /**
     * Set UI color palette. Activities apply it on their next onCreate;
     * the theme picker must call recreate() for an immediate change.
     *
     * @param palette palette key
     * */
    fun setUiPalette(palette: String) {
        gp.edit().putString("ui_palette", palette).apply()
    }

    /**
     * The Personal Default (shown in the UI as "Personal Default"; this key
     * predates that name - it was "Default User Image" before the Global
     * Default Image existed, see [getGlobalDefaultImageRef]): the bare hash
     * of a saved Profile Image used when the active user identity (Roleplay
     * Character, then My Persona) has no image of its own. Optional - if
     * unset, the user side falls through to the Global Default. "" means
     * none is set. The catalog/files live in profile_images.db; this only
     * references.
     *
     * @return the image hash, or "" for none
     * */
    fun getDefaultUserImageRef() : String {
        return gp.getString("default_user_image_ref", "") ?: ""
    }

    /**
     * Set the Default User Image reference. Pass "" to clear it (Remove
     * Picture) — this only drops the reference and never deletes the saved
     * gallery image.
     *
     * @param ref the image hash, or "" for none
     * */
    fun setDefaultUserImageRef(ref: String) {
        gp.edit().putString("default_user_image_ref", ref).apply()
    }

    /**
     * The Global Default Image (owner ruling, July 19 2026 - see the
     * ADDENDUM in profile-images-plan.md): one shared Profile Image used as
     * the ultimate fallback on BOTH the Companion side and the user side,
     * when nothing more specific is available (including when
     * [getDefaultUserImageRef] - the Personal Default - is itself unset).
     * "" means not yet seeded; [GlobalDefaultImageSeeder] fills this in
     * automatically the first time it is needed. A SEPARATE key from
     * default_user_image_ref - the two are independent preferences.
     *
     * @return the image hash, or "" for none
     * */
    fun getGlobalDefaultImageRef() : String {
        return gp.getString("global_default_image_ref", "") ?: ""
    }

    /**
     * Set the Global Default Image reference. Pass "" to clear it - this
     * only drops the reference and never deletes the saved gallery image.
     *
     * @param ref the image hash, or "" for none
     * */
    fun setGlobalDefaultImageRef(ref: String) {
        gp.edit().putString("global_default_image_ref", ref).apply()
    }

    /**
     * Default Shape applied to uploaded Profile Images (never to built-in
     * glyph avatars or the generic user icon). One of "flower" | "circle" |
     * "square"; defaults to "flower" (the existing clover geometry).
     *
     * @return the shape key
     * */
    fun getProfileImageShape() : String {
        return gp.getString("profile_image_shape", "flower") ?: "flower"
    }

    /**
     * Set the Default Shape for uploaded Profile Images.
     *
     * @param shape one of "flower" | "circle" | "square"
     * */
    fun setProfileImageShape(shape: String) {
        gp.edit().putString("profile_image_shape", shape).apply()
    }
}
