/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.ui.util

import android.app.Activity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R

/**
 * Shown when on-device Whisper is the selected voice input but no model is
 * installed. Replaces the old silent fallback to paid cloud Whisper. Actions
 * top to bottom: API, Google Dictation, Download Whisper (owner ruling,
 * Oct 9 2026). The API action is not wired yet and deliberately does nothing.
 */
object LocalWhisperMissingDialog {
    fun show(activity: Activity, onUseGoogle: () -> Unit, onDownload: () -> Unit) {
        val actions = activity.layoutInflater.inflate(R.layout.dialog_three_actions_stacked, null)
        val api = actions.findViewById<MaterialButton>(R.id.btn_dialog_first_action)
        val google = actions.findViewById<MaterialButton>(R.id.btn_dialog_second_action)
        val download = actions.findViewById<MaterialButton>(R.id.btn_dialog_third_action)
        api.setText(R.string.local_whisper_missing_api)
        google.setText(R.string.voice_engine_google)
        download.setText(R.string.local_whisper_missing_download)

        val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.local_whisper_missing_title)
            .setMessage(R.string.local_whisper_missing_message)
            .setView(actions)
            .setCancelable(true)
            .create()
        google.setOnClickListener {
            dialog.dismiss()
            onUseGoogle()
        }
        download.setOnClickListener {
            dialog.dismiss()
            onDownload()
        }
        dialog.show()
    }
}
