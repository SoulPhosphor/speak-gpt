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
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.chatsearch.ChatSearchIndexManager
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.ScreenChrome

/**
 * Chat Behavior (owner ruling, Sept 2026): opened from the Chat Behavior row
 * in Appearance. Optional chat controls, plus the Identity section that sets
 * how the user's own messages are labeled in chat.
 */
class ChatSettingsActivity : FragmentActivity() {

    private lateinit var preferences: Preferences
    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_chat_settings)

        preferences = Preferences.getPreferences(this, "")
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)

        applyTheme()
        bindControls()
    }

    private fun applyTheme() {
        ScreenChrome.apply(this, actionBar, btnBack)
    }

    private fun bindControls() {
        btnBack?.setOnClickListener { finish() }

        findViewById<MaterialSwitch>(R.id.switch_top_positioned_audio_control)?.apply {
            isChecked = preferences.getTopPositionedAudioControl()
            setOnCheckedChangeListener { _, value ->
                preferences.setTopPositionedAudioControl(value)
            }
        }

        findViewById<MaterialSwitch>(R.id.switch_thinking_indicator)?.apply {
            isChecked = preferences.getShowThinkingIndicator()
            setOnCheckedChangeListener { _, value -> preferences.setShowThinkingIndicator(value) }
        }

        findViewById<MaterialSwitch>(R.id.switch_show_thinking)?.apply {
            isChecked = preferences.getShowThinking()
            setOnCheckedChangeListener { _, value -> preferences.setShowThinking(value) }
        }

        findViewById<MaterialSwitch>(R.id.switch_chat_list_companion_images)?.apply {
            isChecked = preferences.getShowCompanionImagesInChatList()
            setOnCheckedChangeListener { _, value ->
                preferences.setShowCompanionImagesInChatList(value)
            }
        }

        findViewById<TextInputEditText>(R.id.field_default_username)?.apply {
            setText(preferences.getDefaultDisplayedUsername())
            doAfterTextChanged {
                preferences.setDefaultDisplayedUsername(it?.toString()?.trim().orEmpty())
            }
        }

        findViewById<MaterialSwitch>(R.id.switch_roleplay_names_replace_glamour)?.apply {
            isChecked = preferences.getRoleplayNamesReplaceGlamour()
            setOnCheckedChangeListener { _, value -> preferences.setRoleplayNamesReplaceGlamour(value) }
        }

        findViewById<android.view.View>(R.id.rebuild_search_index)?.setOnClickListener { button ->
            button.isEnabled = false
            ChatSearchIndexManager.get(this).rebuild {
                runOnUiThread { if (!isFinishing && !isDestroyed) button.isEnabled = true }
            }
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
