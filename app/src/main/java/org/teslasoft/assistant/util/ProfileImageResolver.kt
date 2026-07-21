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
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.profileimages.ProfileImageStore
import java.io.File

/**
 * Resolves which picture actually shows for an identity, applying the owner's
 * two default-avatar cascades (chat/rows/editors, owner rulings July 21 2026):
 *
 *  - AI side (Companions): the identity's own picture, else the **Default AI
 *    Avatar** (the Global Default image). The glyph fallback is the caller's
 *    last resort, only reached when no Default AI Avatar has been seeded/set.
 *  - User side (My Personas, user Roleplay Characters, the user's own chat
 *    bubble): the identity's own picture, else the **Default Personal Avatar**
 *    (Personal Default). When neither is set, this returns null and the caller
 *    shows the generic person icon — the person icon must never appear while a
 *    Personal Default is set.
 *
 * It also produces the screen-reader content description for a picture slot,
 * which describes what is ACTUALLY on screen (owner-approved accessibility
 * scheme, July 21 2026): a custom picture reads as "<Name>'s picture"; a slot
 * falling back to a default names that default instead of pretending a custom
 * picture exists.
 *
 * Reads GlobalPreferences + ProfileImageStore; the image catalog/files live in
 * the unencrypted profile_images.db, so this never touches the memory store.
 */
object ProfileImageResolver {

    /** AI/Companion side: own picture → Default AI Avatar. null only when
     *  neither exists (caller shows its own last-resort glyph). */
    fun resolveAiImageFile(context: Context, ownRef: String?): File? {
        resolveRef(context, ownRef)?.let { return it }
        return resolveRef(context, GlobalPreferences.getPreferences(context).getGlobalDefaultImageRef())
    }

    /** User side: own picture → Default Personal Avatar. null when neither is
     *  set (caller shows the generic person icon). */
    fun resolveUserImageFile(context: Context, ownRef: String?): File? {
        resolveRef(context, ownRef)?.let { return it }
        return resolveRef(context, GlobalPreferences.getPreferences(context).getDefaultUserImageRef())
    }

    /** True when a Personal Default is set — used only for the "no personal
     *  picture set" content description branch. */
    fun hasPersonalDefault(context: Context): Boolean =
        GlobalPreferences.getPreferences(context).getDefaultUserImageRef().isNotEmpty()

    /** A file for [ref] if the hash is non-blank and the file exists, else null. */
    private fun resolveRef(context: Context, ref: String?): File? {
        if (ref.isNullOrEmpty()) return null
        val file = ProfileImageStore.getInstance(context).imageFile(ref)
        return if (file != null && file.exists()) file else null
    }

    /* ----------------------- content descriptions ----------------------- */

    /** AI-side slot label: "<Name>'s picture" when the identity has its own
     *  picture, else "Default AI avatar" (what actually shows). */
    fun aiContentDescription(context: Context, name: String, ownRef: String?): String =
        if (!ownRef.isNullOrEmpty()) context.getString(R.string.profile_image_named_picture, name)
        else context.getString(R.string.profile_image_default_ai_avatar_desc)

    /** User-side slot label: "<Name>'s picture" when the identity has its own
     *  picture, else "Default personal avatar" (when a Personal Default is set)
     *  or "No personal picture set" (bare person icon). */
    fun userContentDescription(context: Context, name: String, ownRef: String?): String = when {
        !ownRef.isNullOrEmpty() -> context.getString(R.string.profile_image_named_picture, name)
        hasPersonalDefault(context) -> context.getString(R.string.profile_image_default_personal_avatar_desc)
        else -> context.getString(R.string.profile_image_no_personal_picture_desc)
    }
}
