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

package org.teslasoft.assistant.ui.activities

import android.os.Bundle
import android.view.View
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.context.ContextAssistance
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.ScreenChrome

/**
 * Memory Context Helper Settings (conversation_recall_plan.md G38, M.11):
 * turns context assistance on or off for every chat. Off by default; when
 * first turned on, On-device (Code-Based) is used, the only method built.
 * Turning it off leaves ordinary memory search unchanged.
 */
class MemoryContextHelperSettingsActivity : SettingsPageActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_memory_context_helper_settings)

        val header = findViewById<View>(R.id.action_bar)
        val back = findViewById<View>(R.id.btn_back)
        ScreenChrome.apply(this, header, back)
        ScreenChrome.fitSystemBars(findViewById(R.id.root), header, findViewById(R.id.scroll))
        back.setOnClickListener { finish() }

        val preferences = Preferences.getPreferences(this, "")
        val toggle = findViewById<MaterialSwitch>(R.id.switch_memory_context_helper)
        val method = findViewById<View>(R.id.group_helper_method)
        toggle.isChecked = preferences.getMemoryContextHelperEnabled()
        method.visibility = if (toggle.isChecked) View.VISIBLE else View.GONE
        toggle.setOnCheckedChangeListener { _, checked ->
            if (checked) preferences.setMemoryContextHelperMethod(ContextAssistance.METHOD_CODE_BASED)
            preferences.setMemoryContextHelperEnabled(checked)
            ContextAssistance.settingChanged()
            method.visibility = if (checked) View.VISIBLE else View.GONE
        }
    }
}
