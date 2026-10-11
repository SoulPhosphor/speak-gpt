/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.activities

import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ScrollView
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.ScreenChrome

/** App-wide chat-list display choices, independent from message appearance. */
class AppAppearanceActivity : SettingsPageActivity() {

    private lateinit var preferences: Preferences
    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_app_appearance)

        preferences = Preferences.getPreferences(this, "")
        bindViews()
        applyTheme()
        bindControls()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
    }

    private fun applyTheme() {
        ScreenChrome.apply(this, actionBar, btnBack)
    }

    private fun bindControls() {
        btnBack?.setOnClickListener { finish() }
        bindSwitch(R.id.switch_chat_list_companion_images, preferences.getShowCompanionImagesInChatList()) {
            preferences.setShowCompanionImagesInChatList(it)
        }
        bindSwitch(R.id.switch_chat_list_bookmarks, preferences.getShowBookmarkIndicatorInChatList()) {
            preferences.setShowBookmarkIndicatorInChatList(it)
        }
        bindSwitch(R.id.switch_chat_list_model_names, preferences.getShowModelNamesInChatList()) {
            preferences.setShowModelNamesInChatList(it)
        }
        bindSwitch(R.id.switch_chat_list_start_date, preferences.getShowChatStartDateInChatList()) {
            preferences.setShowChatStartDateInChatList(it)
        }
    }

    private fun bindSwitch(id: Int, checked: Boolean, save: (Boolean) -> Unit) {
        findViewById<MaterialSwitch>(id)?.apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> save(value) }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT < 35) return
        try {
            val insets = window.decorView.rootWindowInsets
            actionBar?.setPadding(0, insets.getInsets(WindowInsets.Type.statusBars()).top, 0, 0)
            findViewById<ScrollView>(R.id.scroll)?.setPadding(
                0,
                0,
                0,
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            )
        } catch (_: Exception) { /* Window insets are not available yet. */ }
    }
}
