/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.activities

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.ScreenChrome

/** Memory Browser Settings: browser presentation, opened from Appearance & Behaviors. */
class MemoryBrowserSettingsActivity : SettingsPageActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_memory_browser_settings)

        val actionBar = findViewById<View>(R.id.action_bar)
        val btnBack = findViewById<ImageButton>(R.id.btn_back)
        ScreenChrome.apply(this, actionBar, btnBack)
        ScreenChrome.fitSystemBars(findViewById(R.id.root), actionBar, findViewById(R.id.scroll))
        btnBack.setOnClickListener { finish() }

        val preferences = Preferences.getPreferences(this, "")
        findViewById<MaterialSwitch>(R.id.switch_memory_image_hints).apply {
            isChecked = preferences.getUseMemoryTypeImageHints()
            setOnCheckedChangeListener { _, checked ->
                preferences.setUseMemoryTypeImageHints(checked)
            }
        }
        findViewById<MaterialSwitch>(R.id.switch_memory_show_text).apply {
            isChecked = preferences.getShowMemoryText()
            setOnCheckedChangeListener { _, checked -> preferences.setShowMemoryText(checked) }
        }
    }
}
