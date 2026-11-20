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
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.ScreenChrome

/**
 * Alert Sounds, reached from Appearance & Behaviors. Holds the sound alert
 * for model errors, moved here unchanged (same preference and default) from
 * Logging Preferences.
 */
class AlertSoundsActivity : SettingsPageActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_alert_sounds)

        val preferences = Preferences.getPreferences(this, "")
        val actionBar = findViewById<View>(R.id.action_bar)
        val btnBack = findViewById<View>(R.id.btn_back)
        ScreenChrome.apply(this, actionBar, btnBack)
        ScreenChrome.fitSystemBars(findViewById(R.id.root), actionBar, findViewById(R.id.scroll))

        btnBack.setOnClickListener { finish() }

        findViewById<MaterialSwitch>(R.id.switch_error_sound).apply {
            isChecked = preferences.getErrorSound()
            setOnCheckedChangeListener { _, checked -> preferences.setErrorSound(checked) }
        }
    }
}
