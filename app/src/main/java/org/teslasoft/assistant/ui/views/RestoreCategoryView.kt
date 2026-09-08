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

package org.teslasoft.assistant.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import com.google.android.material.checkbox.MaterialCheckBox
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.backup.portable.PortableRestoreCategory
import org.teslasoft.assistant.preferences.backup.portable.PortableRestoreMode
import org.teslasoft.assistant.ui.widgets.AppDropdown

/** Compact on-screen category checkbox plus its independent restore mode. */
class RestoreCategoryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private val check: MaterialCheckBox
    private val description: TextView
    private val modeView: TextView
    private lateinit var category: PortableRestoreCategory
    private var mode = PortableRestoreMode.MERGE

    val isCategorySelected: Boolean get() = check.isChecked
    val selectedMode: PortableRestoreMode get() = mode

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_restore_category, this, true)
        check = findViewById(R.id.category_check)
        description = findViewById(R.id.category_description)
        modeView = findViewById(R.id.category_mode)
        check.setOnCheckedChangeListener { _, selected ->
            modeView.isEnabled = selected
            modeView.alpha = if (selected) 1f else DISABLED_ALPHA
        }
        modeView.setOnClickListener { showModeMenu() }
    }

    fun bind(
        restoreCategory: PortableRestoreCategory,
        @StringRes title: Int,
        @StringRes explanation: Int
    ) {
        category = restoreCategory
        check.setText(title)
        description.setText(explanation)
        updateModeLabel()
        AppDropdown.sizeToOptions(modeView, modeLabels()) {
            (width - check.paddingStart).coerceAtLeast(modeView.minimumWidth)
        }
    }

    private fun showModeMenu() {
        if (!::category.isInitialized || !check.isChecked) return
        val labels = modeLabels()
        AppDropdown.show(
            modeView,
            labels,
            selectedIndex = if (mode == PortableRestoreMode.MERGE) 0 else 1
        ) { index ->
            mode = if (index == 0) PortableRestoreMode.MERGE else PortableRestoreMode.REPLACE
            updateModeLabel()
        }
    }

    private fun modeLabels(): List<String> = listOf(
        context.getString(R.string.restore_mode_merge),
        context.getString(R.string.restore_mode_replace)
    )

    private fun updateModeLabel() {
        val label = context.getString(
            if (mode == PortableRestoreMode.MERGE) R.string.restore_mode_merge
            else R.string.restore_mode_replace
        )
        modeView.text = label
        modeView.contentDescription = context.getString(R.string.restore_mode_accessibility, label)
    }

    private companion object {
        const val DISABLED_ALPHA = 0.38f
    }
}
