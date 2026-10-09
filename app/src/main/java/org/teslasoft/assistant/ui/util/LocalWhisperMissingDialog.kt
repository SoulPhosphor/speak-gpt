/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.ui.util

import android.app.Activity
import android.view.View
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R

/**
 * Shown when the selected voice input cannot run yet, instead of silently
 * using another (possibly paid) engine. Actions are stacked top to bottom
 * from least to most likely wanted (owner ruling, Oct 9 2026).
 */
object LocalWhisperMissingDialog {

    /** On-device Whisper selected with no model installed: API (or Set Up
     *  API STT while it is not set up), Google Dictation, Download Whisper. */
    fun show(
        activity: Activity,
        apiConfigured: Boolean,
        onUseApi: () -> Unit,
        onSetUpApi: () -> Unit,
        onUseGoogle: () -> Unit,
        onDownload: () -> Unit
    ) {
        stacked(activity, R.string.local_whisper_missing_title, R.string.local_whisper_missing_message,
            Action(if (apiConfigured) R.string.local_whisper_missing_api else R.string.api_stt_set_up_button,
                if (apiConfigured) onUseApi else onSetUpApi),
            Action(R.string.voice_engine_google, onUseGoogle),
            Action(R.string.local_whisper_missing_download, onDownload))
    }

    /** API Voice Service selected but not set up: Download Whisper (only on a
     *  phone that can run it), Google Dictation, Set Up API STT. */
    fun showApiMissing(
        activity: Activity,
        whisperSupported: Boolean,
        onDownload: () -> Unit,
        onUseGoogle: () -> Unit,
        onSetUpApi: () -> Unit
    ) {
        stacked(activity, R.string.api_stt_missing_title, R.string.api_stt_missing_message,
            if (whisperSupported) Action(R.string.local_whisper_missing_download, onDownload) else null,
            Action(R.string.voice_engine_google, onUseGoogle),
            Action(R.string.api_stt_set_up_button, onSetUpApi))
    }

    private class Action(val label: Int, val run: () -> Unit)

    private fun stacked(activity: Activity, title: Int, message: Int, first: Action?, second: Action, third: Action) {
        val actions = activity.layoutInflater.inflate(R.layout.dialog_three_actions_stacked, null)
        val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(title)
            .setMessage(message)
            .setView(actions)
            .setCancelable(true)
            .create()
        listOf(R.id.btn_dialog_first_action to first, R.id.btn_dialog_second_action to second,
            R.id.btn_dialog_third_action to third).forEach { (id, action) ->
            actions.findViewById<MaterialButton>(id).apply {
                if (action == null) { visibility = View.GONE; return@apply }
                setText(action.label)
                setOnClickListener { dialog.dismiss(); action.run() }
            }
        }
        dialog.show()
    }
}
