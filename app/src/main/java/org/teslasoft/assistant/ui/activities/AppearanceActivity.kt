/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.activities

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.drawable.toDrawable
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.chat.ChatNameStyle
import org.teslasoft.assistant.ui.widgets.AppDropdown

/** Appearance controls consumed by the adaptable chat message shell. */
class AppearanceActivity : FragmentActivity() {

    companion object {
        private const val STATE_PREVIEW_TEXT = "appearance_preview_text"
    }

    private lateinit var preferences: Preferences
    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var userFont: TextView? = null
    private var userSize: TextView? = null
    private var aiFont: TextView? = null
    private var aiSize: TextView? = null
    private var previewText: TextInputEditText? = null
    private var previewList: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_appearance)

        preferences = Preferences.getPreferences(this, "")
        bindViews()
        savedInstanceState?.getString(STATE_PREVIEW_TEXT)?.let { previewText?.setText(it) }
        applyTheme()
        bindControls()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        userFont = findViewById(R.id.text_user_name_font)
        userSize = findViewById(R.id.text_user_name_size)
        aiFont = findViewById(R.id.text_ai_name_font)
        aiSize = findViewById(R.id.text_ai_name_size)
        previewText = findViewById(R.id.field_preview_text)
        previewList = findViewById(R.id.font_preview_list)
    }

    private fun applyTheme() {
        window.setBackgroundDrawable(SurfaceColors.SURFACE_0.getColor(this).toDrawable())
        if (Build.VERSION.SDK_INT <= 34) {
            @Suppress("DEPRECATION")
            window.navigationBarColor = SurfaceColors.SURFACE_0.getColor(this)
            @Suppress("DEPRECATION")
            window.statusBarColor = SurfaceColors.SURFACE_4.getColor(this)
        }
        actionBar?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
        btnBack?.backgroundTintList =
            ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
    }

    private fun bindControls() {
        btnBack?.setOnClickListener { finish() }

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

        refreshNameStyleValues()
        userFont?.setOnClickListener { showFontPicker(it as TextView, true) }
        aiFont?.setOnClickListener { showFontPicker(it as TextView, false) }
        userSize?.setOnClickListener { showSizePicker(it as TextView, true) }
        aiSize?.setOnClickListener { showSizePicker(it as TextView, false) }

        previewText?.doAfterTextChanged { rebuildFontPreviews(it?.toString().orEmpty()) }
        rebuildFontPreviews(previewText?.text?.toString().orEmpty())
    }

    private fun bindSwitch(id: Int, checked: Boolean, save: (Boolean) -> Unit) {
        findViewById<MaterialSwitch>(id)?.apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> save(value) }
        }
    }

    private fun refreshNameStyleValues() {
        val fontLabels = ChatNameStyle.fonts.map { it.displayName }
        userFont?.apply {
            text = ChatNameStyle.fontLabel(preferences.getUserChatNameFont())
            AppDropdown.sizeToOptions(this, fontLabels) { availableWidth(this) }
        }
        aiFont?.apply {
            text = ChatNameStyle.fontLabel(preferences.getAiChatNameFont())
            AppDropdown.sizeToOptions(this, fontLabels) { availableWidth(this) }
        }

        val sizeLabels = ChatNameStyle.sizeOptionsSp.map { sizeLabel(it) }
        userSize?.apply {
            text = sizeLabel(preferences.getUserChatNameSizeSp())
            AppDropdown.sizeToOptions(this, sizeLabels) { availableWidth(this) }
        }
        aiSize?.apply {
            text = sizeLabel(preferences.getAiChatNameSizeSp())
            AppDropdown.sizeToOptions(this, sizeLabels) { availableWidth(this) }
        }
    }

    private fun availableWidth(anchor: TextView): Int =
        (anchor.parent as? View)?.width ?: resources.displayMetrics.widthPixels

    private fun showFontPicker(anchor: TextView, user: Boolean) {
        val options = ChatNameStyle.fonts
        val currentId = if (user) preferences.getUserChatNameFont() else preferences.getAiChatNameFont()
        val current = options.indexOfFirst { it.id == ChatNameStyle.fontIdOrDefault(currentId) }
            .coerceAtLeast(0)
        AppDropdown.show(anchor, options.map { it.displayName }, current) { position ->
            if (user) {
                preferences.setUserChatNameFont(options[position].id)
            } else {
                preferences.setAiChatNameFont(options[position].id)
            }
            refreshNameStyleValues()
        }
    }

    private fun showSizePicker(anchor: TextView, user: Boolean) {
        val options = ChatNameStyle.sizeOptionsSp
        val currentSize = if (user) {
            preferences.getUserChatNameSizeSp()
        } else {
            preferences.getAiChatNameSizeSp()
        }
        AppDropdown.show(anchor, options.map { sizeLabel(it) }, options.indexOf(currentSize)) { position ->
            if (user) {
                preferences.setUserChatNameSizeSp(options[position])
            } else {
                preferences.setAiChatNameSizeSp(options[position])
            }
            refreshNameStyleValues()
        }
    }

    private fun sizeLabel(sizeSp: Int): String = getString(R.string.appearance_size_sp, sizeSp)

    private fun rebuildFontPreviews(sample: String) {
        val container = previewList ?: return
        container.removeAllViews()
        val verticalPadding = (8 * resources.displayMetrics.density).toInt()
        for (font in ChatNameStyle.fonts) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, verticalPadding, 0, verticalPadding)
            }
            row.addView(TextView(this, null, 0, R.style.Widget_App_Row_Subtitle).apply {
                text = font.displayName
            })
            row.addView(TextView(this, null, 0, R.style.Widget_App_Row_Title).apply {
                text = sample
                typeface = Typeface.create(ChatNameStyle.typeface(this@AppearanceActivity, font.id), Typeface.BOLD)
                textSize = ChatNameStyle.DEFAULT_SIZE_SP.toFloat()
            })
            container.addView(row)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PREVIEW_TEXT, previewText?.text?.toString().orEmpty())
        super.onSaveInstanceState(outState)
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
