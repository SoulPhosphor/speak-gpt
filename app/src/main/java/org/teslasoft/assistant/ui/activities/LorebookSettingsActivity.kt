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
import android.widget.EditText
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.lorebook.LorebookLimits
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.NumberSettingField
import org.teslasoft.assistant.ui.util.ScreenChrome

/**
 * Lorebook Settings (owner request, Stage 2): the lorebook entry limit and
 * the optional character limit used by typed sending, voice and resending
 * alike (LorebookLimits).
 */
class LorebookSettingsActivity : SettingsPageActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_lorebook_settings)

        val header = findViewById<View>(R.id.action_bar)
        val back = findViewById<View>(R.id.btn_back)
        ScreenChrome.apply(this, header, back)
        ScreenChrome.fitSystemBarsAndKeyboard(findViewById(R.id.root), header, findViewById(R.id.scroll))
        back.setOnClickListener { finish() }

        val preferences = Preferences.getPreferences(this, "")
        NumberSettingField.bindRequired(
            findViewById<EditText>(R.id.field_lorebook_max_entries),
            LorebookLimits.MIN_ENTRIES..LorebookLimits.MAX_ENTRIES,
            read = { preferences.getLorebookLimitSettings().maxEntries },
            write = preferences::setLorebookMaxEntries
        )

        val toggle = findViewById<MaterialSwitch>(R.id.switch_lorebook_limit_characters)
        val characters = findViewById<View>(R.id.row_lorebook_max_characters)
        toggle.isChecked = preferences.getLorebookLimitSettings().characterLimitEnabled
        characters.visibility = if (toggle.isChecked) View.VISIBLE else View.GONE
        toggle.setOnCheckedChangeListener { _, checked ->
            preferences.setLorebookCharacterLimitEnabled(checked)
            characters.visibility = if (checked) View.VISIBLE else View.GONE
        }
        NumberSettingField.bindOptional(
            findViewById<EditText>(R.id.field_lorebook_max_characters),
            LorebookLimits.MIN_CHARS..LorebookLimits.MAX_CHARS,
            read = { preferences.getLorebookLimitSettings().maxCharacters },
            write = preferences::setLorebookMaxCharacters
        )
    }
}
