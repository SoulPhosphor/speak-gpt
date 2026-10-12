/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.activities

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.damaged.DamagedChatLog
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.ScreenChrome
import java.text.DateFormat
import java.util.Date

/** Damaged Chat Files (owner ruling, Oct 12 2026), opened from the logs list. */
class DamagedChatLogActivity : SettingsPageActivity() {

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_damaged_chat_log)
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        ScreenChrome.apply(this, actionBar, btnBack)
        btnBack?.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        val app = applicationContext
        Thread {
            val detections = DamagedChatLog.detections(app)
            runOnUiThread { if (!isFinishing && !isDestroyed) render(detections) }
        }.start()
    }

    private fun render(detections: List<DamagedChatLog.Detection>) {
        val container = findViewById<LinearLayout>(R.id.damaged_chat_detections) ?: return
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val unavailable = getString(R.string.damaged_value_unavailable)
        val dateTime = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM)
        for (detection in detections) {
            val block = inflater.inflate(R.layout.view_damaged_chat_detection, container, false)
            block.findViewById<TextView>(R.id.damaged_detected_during).text =
                getString(R.string.damaged_detected_during, getString(detection.operation.label))
            block.findViewById<TextView>(R.id.damaged_detected_date).text =
                getString(R.string.damaged_detected_date, dateTime.format(Date(detection.detectedAt)))
            val chats = block.findViewById<LinearLayout>(R.id.damaged_detection_chats)
            for (chat in detection.chats) {
                val entry = inflater.inflate(R.layout.view_damaged_chat_entry, chats, false)
                entry.findViewById<TextView>(R.id.damaged_chat_title).text =
                    getString(R.string.damaged_original_chat_title, chat.title ?: unavailable)
                entry.findViewById<TextView>(R.id.damaged_chat_started).text =
                    getString(R.string.damaged_original_start_date, chat.startedAt?.let { date.format(Date(it)) } ?: unavailable)
                entry.findViewById<TextView>(R.id.damaged_chat_first_username).text =
                    getString(R.string.damaged_first_username, chat.firstUsername ?: unavailable)
                entry.findViewById<TextView>(R.id.damaged_chat_first_companion).text =
                    getString(R.string.damaged_first_companion_name, chat.firstCompanionName ?: unavailable)
                entry.findViewById<TextView>(R.id.damaged_chat_model).text =
                    getString(R.string.damaged_most_used_model, chat.mostUsedModel ?: unavailable)
                chats.addView(entry)
            }
            container.addView(block)
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
