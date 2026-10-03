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

package org.teslasoft.assistant.ui.util

import android.content.res.ColorStateList
import android.widget.ImageButton
import androidx.core.content.res.ResourcesCompat
import org.teslasoft.assistant.R

/**
 * Save confirmation for a header save icon (owner ruling, Oct 3 2026): the
 * disk icon itself turns green, then returns to its normal tint. The button's
 * background is never touched. A repeated save restarts the timer instead of
 * capturing the green tint as "normal".
 */
object SaveIconFlash {
    private const val DURATION_MS = 2500L

    private class Pending(val normalTint: ColorStateList?, val revert: Runnable)

    fun flash(button: ImageButton) {
        val pending = button.getTag(R.id.tag_save_icon_flash) as? Pending
        val normalTint = if (pending != null) {
            button.removeCallbacks(pending.revert)
            pending.normalTint
        } else {
            button.imageTintList
        }
        button.imageTintList = ColorStateList.valueOf(
            ResourcesCompat.getColor(button.resources, R.color.light_green, button.context.theme)
        )
        val revert = Runnable {
            button.imageTintList = normalTint
            button.setTag(R.id.tag_save_icon_flash, null)
        }
        button.setTag(R.id.tag_save_icon_flash, Pending(normalTint, revert))
        button.postDelayed(revert, DURATION_MS)
    }
}
