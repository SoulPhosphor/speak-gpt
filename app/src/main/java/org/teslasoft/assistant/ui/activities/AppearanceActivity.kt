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
import org.teslasoft.assistant.R
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.ScreenChrome

/** Appearance destinations: app appearance, name style, and chat settings. */
class AppearanceActivity : SettingsPageActivity() {

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_appearance)

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
        findViewById<View>(R.id.row_app_appearance)?.setOnClickListener {
            startActivity(Intent(this, AppAppearanceActivity::class.java))
        }
        findViewById<View>(R.id.row_name_style)?.setOnClickListener {
            startActivity(Intent(this, NameStyleActivity::class.java))
        }
        findViewById<View>(R.id.row_chat_behavior)?.setOnClickListener {
            startActivity(Intent(this, ChatSettingsActivity::class.java))
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
