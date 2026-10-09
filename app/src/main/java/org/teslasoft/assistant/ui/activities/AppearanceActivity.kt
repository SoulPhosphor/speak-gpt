/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.activities

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ScrollView
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.ScreenChrome

/** Appearance controls consumed by the adaptable chat message shell. */
class AppearanceActivity : SettingsPageActivity() {

    private lateinit var preferences: Preferences
    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_appearance)

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
        findViewById<View>(R.id.row_name_style)?.setOnClickListener {
            startActivity(Intent(this, NameStyleActivity::class.java))
        }
        findViewById<View>(R.id.row_chat_behavior)?.setOnClickListener {
            startActivity(Intent(this, ChatSettingsActivity::class.java))
        }

        bindSwitch(R.id.switch_staggered_responses, preferences.getStaggeredResponses()) {
            preferences.setStaggeredResponses(it)
        }
        bindSwitch(R.id.switch_profile_images, preferences.getShowChatProfileImages()) {
            preferences.setShowChatProfileImages(it)
        }
        bindSwitch(R.id.switch_names, preferences.getShowChatNames()) {
            preferences.setShowChatNames(it)
        }
        bindSwitch(R.id.switch_ai_bubble, preferences.getShowAiBubble()) {
            preferences.setShowAiBubble(it)
        }
        bindSwitch(R.id.switch_user_bubble, preferences.getShowUserBubble()) {
            preferences.setShowUserBubble(it)
        }
        bindSwitch(R.id.switch_model_names, preferences.getShowModelNames()) {
            preferences.setShowModelNames(it)
        }
        bindSwitch(R.id.switch_token_usage, preferences.getShowTokenUsage()) {
            preferences.setShowTokenUsage(it)
        }
        bindSwitch(
            R.id.switch_hardware_keyboard_shortcuts,
            preferences.getHardwareKeyboardShortcuts()
        ) {
            preferences.setHardwareKeyboardShortcuts(it)
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
            val density = resources.displayMetrics.density
            findViewById<ScrollView>(R.id.scroll)?.setPadding(
                0,
                0,
                0,
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom + (24 * density).toInt()
            )
        } catch (_: Exception) { /* Window insets are not available yet. */ }
    }
}
