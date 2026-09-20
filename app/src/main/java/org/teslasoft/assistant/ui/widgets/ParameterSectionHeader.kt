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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.use
import org.teslasoft.assistant.R
import org.teslasoft.assistant.ui.util.ParameterInfoDialog

/**
 * A section title paired with the circled-i information button used across the
 * app. The title shows inline; tapping the button opens the shared parameter
 * information dialog with the title as its heading and this header's info text
 * as its body. This replaced the always-on subtext that used to sit under each
 * parameter title, so an explanation is one tap away instead of crowding the
 * screen. An empty info text hides the button.
 */
class ParameterSectionHeader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val titleView: TextView
    private val infoButton: ImageButton
    private var infoText: CharSequence = ""

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_parameter_section_header, this, true)
        titleView = findViewById(R.id.parameter_title)
        infoButton = findViewById(R.id.parameter_info_button)

        attrs?.let {
            context.theme.obtainStyledAttributes(it, R.styleable.ParameterSectionHeader, 0, 0).use { a ->
                setTitle(a.getString(R.styleable.ParameterSectionHeader_parameterTitle) ?: "")
                setInfo(a.getString(R.styleable.ParameterSectionHeader_parameterInfo) ?: "")
            }
        }

        infoButton.setOnClickListener {
            if (infoText.isNotEmpty()) {
                ParameterInfoDialog.show(context, titleView.text ?: "", infoText)
            }
        }
    }

    fun setTitle(text: CharSequence) {
        titleView.text = text
    }

    fun setInfo(text: CharSequence) {
        infoText = text
        infoButton.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }
}
