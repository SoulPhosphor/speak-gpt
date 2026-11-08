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

package org.teslasoft.assistant.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.Button
import androidx.constraintlayout.widget.ConstraintLayout
import org.teslasoft.assistant.R

/**
 * A pop-up's row of side-by-side buttons (Widget.App.Dialog.Actions.EndRow)
 * that keeps its buttons on one line when it can (owner ruling, Nov 8 2026).
 *
 * When the buttons do not fit at their normal text size, every button's text
 * shrinks by the same amount until they fit, so the buttons always match. The
 * text never goes below dialog_action_min_text_size, which is the chat's
 * message information text size; past that, the row's Flow moves the last
 * buttons to the next line instead. All sizes are in sp, so they follow the
 * phone's text size setting.
 */
class DialogActionRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val minimumTextSize = resources.getDimension(R.dimen.dialog_action_min_text_size)
    private val gap = resources.getDimension(R.dimen.dialog_action_gap)
    private val step = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, STEP_SP, resources.displayMetrics)

    /** Each button's own text size from its style, before any shrinking. */
    private val normalSizes = HashMap<Button, Float>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            fitButtons(MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun fitButtons(available: Int) {
        val buttons = (0 until childCount).map(::getChildAt)
            .filterIsInstance<Button>()
            .filter { it.visibility != View.GONE }
        if (buttons.size < 2 || available <= 0) return
        buttons.forEach { normalSizes.getOrPut(it) { it.textSize } }
        var size = buttons.minOf { normalSizes.getValue(it) }
        apply(buttons, size)
        while (requiredWidth(buttons) > available && size > minimumTextSize) {
            size = maxOf(minimumTextSize, size - step)
            apply(buttons, size)
        }
    }

    private fun apply(buttons: List<Button>, size: Float) {
        buttons.forEach { button ->
            if (button.textSize != size) button.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        }
    }

    private fun requiredWidth(buttons: List<Button>): Float {
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        return buttons.sumOf { button ->
            button.measure(unbounded, unbounded)
            val params = button.layoutParams as? MarginLayoutParams
            (button.measuredWidth + (params?.leftMargin ?: 0) + (params?.rightMargin ?: 0)).toDouble()
        }.toFloat() + gap * (buttons.size - 1)
    }

    private companion object {
        /** How much the text shrinks per try. */
        const val STEP_SP = 0.5f
    }
}
