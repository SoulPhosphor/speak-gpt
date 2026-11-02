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

/** Chat Behaviors: optional chat controls, opened from Appearance & Behaviors. */
class ChatBehaviorsActivity : SettingsPageActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_chat_behaviors)

        val actionBar = findViewById<View>(R.id.action_bar)
        val btnBack = findViewById<ImageButton>(R.id.btn_back)
        ScreenChrome.apply(this, actionBar, btnBack)
        ScreenChrome.fitSystemBars(findViewById(R.id.root), actionBar, findViewById(R.id.scroll))
        btnBack.setOnClickListener { finish() }

        val preferences = Preferences.getPreferences(this, "")
        findViewById<MaterialSwitch>(R.id.switch_allow_message_visibility).apply {
            isChecked = preferences.getAllowMessageVisibility()
            setOnCheckedChangeListener { _, checked ->
                preferences.setAllowMessageVisibility(checked)
            }
        }
    }
}
