/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.activities

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.RecentModelsPreferences
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.ScreenChrome

/** App-wide chat-list display choices, independent from message appearance. */
class AppAppearanceActivity : SettingsPageActivity() {

    private lateinit var preferences: Preferences
    private lateinit var recentModels: RecentModelsPreferences
    private lateinit var recentLimit: EditText
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
        recentModels = RecentModelsPreferences(this)
        recentLimit = findViewById(R.id.model_picker_recent_limit)
        recentLimit.setText(recentModels.limit().toString())
        recentLimit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveRecentLimit()
        }
        recentLimit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) recentLimit.clearFocus()
            false
        }
    }

    override fun onPause() {
        saveRecentLimit()
        super.onPause()
    }

    private fun saveRecentLimit() {
        val limit = recentLimit.text.toString().toIntOrNull()
        if (limit != null && limit != recentModels.limit()) recentModels.setLimit(limit)
        recentLimit.setText(recentModels.limit().toString())
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
    }

    private fun applyTheme() {
        ScreenChrome.apply(this, actionBar, btnBack)
        ScreenChrome.fitSystemBarsAndKeyboard(findViewById(R.id.root), actionBar, findViewById(R.id.scroll))
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
        // Display-only: never changes memory capture or stored memory state.
        bindSwitch(R.id.switch_chat_list_memory_status, preferences.getShowMemoryStatusOnChatList()) {
            preferences.setShowMemoryStatusOnChatList(it)
        }
        bindSwitch(R.id.switch_chat_list_lock_folders, preferences.getLockFolderLocationInChatList()) {
            preferences.setLockFolderLocationInChatList(it)
        }
        bindSwitch(R.id.switch_chat_list_separate_folders, preferences.getOpenFoldersInSeparateArea()) {
            preferences.setOpenFoldersInSeparateArea(it)
        }
        bindSwitch(R.id.switch_logs_show_copy_buttons, preferences.getShowLogCopyButtons()) {
            preferences.setShowLogCopyButtons(it)
        }
        bindSwitch(R.id.switch_open_last_used_chat, preferences.getOpenLastUsedChatAtLaunch()) {
            preferences.setOpenLastUsedChatAtLaunch(it)
        }
        bindSwitch(R.id.switch_model_settings_enabled, preferences.getModelSettingsEnabled()) {
            preferences.setModelSettingsEnabled(it)
        }
    }

    private fun bindSwitch(id: Int, checked: Boolean, save: (Boolean) -> Unit) {
        findViewById<MaterialSwitch>(id)?.apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> save(value) }
        }
    }
}
