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
import org.teslasoft.assistant.R

/**
 * Save confirmation for a header save icon (owner ruling, Oct 3 2026): the
 * disk icon turns green on success or red on validation failure, then returns
 * to its normal tint. Appearance comes from shared styles; the background is
 * never touched. Repeated feedback restarts the timer while retaining the
 * original tint, including when switching between success and failure.
 */
object SaveIconFlash {
    private class Pending(val normalTint: ColorStateList?, val revert: Runnable)

    fun flash(button: ImageButton) = flash(
        button, R.style.Widget_App_SaveIcon_Success, R.integer.save_icon_success_duration_ms
    )

    fun flashError(button: ImageButton) = flash(
        button, R.style.Widget_App_SaveIcon_Error, R.integer.save_icon_error_duration_ms
    )

    /** Persistent saved state for screens that clear feedback on the next edit. */
    fun markSaved(button: ImageButton) = flash(button, R.style.Widget_App_SaveIcon_Success, null)

    fun reset(button: ImageButton) {
        val pending = button.getTag(R.id.tag_save_icon_flash) as? Pending ?: return
        button.removeCallbacks(pending.revert)
        pending.revert.run()
    }

    private fun flash(button: ImageButton, styleRes: Int, durationRes: Int?) {
        val pending = button.getTag(R.id.tag_save_icon_flash) as? Pending
        val normalTint = if (pending != null) {
            button.removeCallbacks(pending.revert)
            pending.normalTint
        } else {
            button.imageTintList
        }
        val attributes = button.context.obtainStyledAttributes(styleRes, intArrayOf(android.R.attr.tint))
        try {
            button.imageTintList = attributes.getColorStateList(0)
        } finally {
            attributes.recycle()
        }
        val revert = Runnable {
            button.imageTintList = normalTint
            button.setTag(R.id.tag_save_icon_flash, null)
        }
        button.setTag(R.id.tag_save_icon_flash, Pending(normalTint, revert))
        if (durationRes != null) {
            button.postDelayed(revert, button.resources.getInteger(durationRes).toLong())
        }
    }
}
