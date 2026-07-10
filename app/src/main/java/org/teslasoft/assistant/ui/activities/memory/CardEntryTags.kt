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

package org.teslasoft.assistant.ui.activities.memory

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import org.teslasoft.assistant.preferences.memory.RpTagRecord

/**
 * Renders a Zone 2 card entry's roleplay tags as tappable outlined boxes into
 * a ChipGroup. Shared by the three card screens so the tag logic lives in one
 * place. Each tag is an outlined ~4dp box with no fill (owner ruling, July 10
 * 2026 — "B, no background color"); tapping it opens that tag's cross-card page
 * directly, skipping the tag index. The page itself is roleplay-scoped, so the
 * realm wall holds. The ChipGroup's chipSpacingHorizontal (set in the layout)
 * keeps roughly three letters of gap between tags.
 */
object CardEntryTags {

    fun render(activity: FragmentActivity, group: ChipGroup, tags: List<RpTagRecord>, chatId: String) {
        group.removeAllViews()
        if (tags.isEmpty()) {
            group.visibility = View.GONE
            return
        }
        group.visibility = View.VISIBLE

        val accent = MaterialColors.getColor(group, androidx.appcompat.R.attr.colorPrimary)
        val dm = group.resources.displayMetrics
        val corner = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, dm)
        val strokeW = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, dm)

        for (tag in tags) {
            val chip = Chip(activity).apply {
                text = tag.name
                setTextColor(accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                chipBackgroundColor = ColorStateList.valueOf(Color.TRANSPARENT)
                chipStrokeColor = ColorStateList.valueOf(accent)
                chipStrokeWidth = strokeW
                chipCornerRadius = corner
                isCheckable = false
                isClickable = true
                setEnsureMinTouchTargetSize(false)
                setOnClickListener {
                    activity.startActivity(
                        Intent(activity, RpTagViewActivity::class.java)
                            .putExtra("chatId", chatId)
                            .putExtra("tagId", tag.tagId)
                            .putExtra("tagName", tag.name)
                    )
                }
            }
            group.addView(chip)
        }
    }
}
