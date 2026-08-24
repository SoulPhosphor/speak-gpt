/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.activities

import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ScrollView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.theme.ThemeManager

/**
 * Chat Settings (owner ruling, Aug 2026): a container in Settings, directly
 * beneath Appearance, for optional chat controls. It currently holds the
 * Thinking Indicator and Show Thinking toggles; more controls will be added
 * here later.
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
