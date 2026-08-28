package org.teslasoft.assistant.tts.api

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R
import org.teslasoft.assistant.ui.activities.VoiceBrowserActivity

/** Shared TTS error composition; no transcript changes or logging side effects. */
object TtsVoiceDialogs {
    fun show(activity: Activity, chatId: String, failure: TtsFailure, retry: () -> Unit = {}): AlertDialog {
        val message = TtsFailures.message(failure)
        val permanent = "Select New Voice" in message.actions
        val two = permanent || "Retry" in message.actions
        val actions = activity.layoutInflater.inflate(if (two)
            R.layout.dialog_two_actions_cancel_first else R.layout.dialog_single_action, null)
        val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(message.title)
            .setMessage(listOfNotNull(message.explanation, TtsAndroidServices.providerDetails(activity, failure)).joinToString("\n\n"))
            .setView(actions).create()
        if (two) {
            actions.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
                setText(if (permanent) R.string.btn_ok else R.string.btn_cancel)
                setOnClickListener { dialog.dismiss() }
            }
            actions.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
                setText(if (permanent) R.string.tts_select_new_voice else R.string.health_btn_retry)
                setOnClickListener {
                    dialog.dismiss()
                    if (permanent) activity.startActivity(Intent(activity, VoiceBrowserActivity::class.java)
                        .putExtra(VoiceBrowserActivity.EXTRA_CHAT_ID, chatId)) else retry()
                }
            }
        } else actions.findViewById<MaterialButton>(R.id.btn_dialog_action).apply {
            setText(R.string.btn_ok)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
        return dialog
    }
}
