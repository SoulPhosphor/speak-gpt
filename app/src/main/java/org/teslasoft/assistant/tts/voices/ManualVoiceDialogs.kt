package org.teslasoft.assistant.tts.voices

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.tts.TtsStorageException
import org.teslasoft.assistant.preferences.tts.TtsStorageFailure

/** Removal of a manually saved Voice ID; only the app's saved copy is ever removed. */
object ManualVoiceDialogs {
    /** The selected voice cannot be removed, so it gets an explanation instead of a confirmation. */
    fun showRemoval(activity: Activity, selected: Boolean, onRemove: () -> Unit): AlertDialog {
        val dialog: AlertDialog
        if (selected) {
            val actions = activity.layoutInflater.inflate(R.layout.dialog_single_action, null)
            dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
                .setTitle(R.string.voice_browser_remove_selected_title)
                .setMessage(R.string.voice_browser_remove_selected_message)
                .setView(actions).create()
            actions.findViewById<MaterialButton>(R.id.btn_dialog_action).apply {
                setText(R.string.btn_ok)
                setOnClickListener { dialog.dismiss() }
            }
        } else {
            val actions = activity.layoutInflater.inflate(R.layout.dialog_two_actions_cancel_first, null)
            dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
                .setTitle(R.string.voice_browser_remove_title)
                .setMessage(R.string.voice_browser_remove_message)
                .setView(actions).create()
            actions.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
                setText(R.string.btn_cancel)
                setOnClickListener { dialog.dismiss() }
            }
            actions.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
                setText(R.string.voice_browser_remove)
                setOnClickListener {
                    dialog.dismiss()
                    onRemove()
                }
            }
        }
        dialog.show()
        return dialog
    }

    enum class StorageAction(val title: Int, val message: Int, val function: Int) {
        SAVE(R.string.voice_browser_save_failed_title, R.string.voice_browser_save_failed_message,
            R.string.storage_function_save_voice_id),
        REMOVE(R.string.voice_browser_remove_failed_title, R.string.voice_browser_remove_failed_message,
            R.string.storage_function_remove_voice_id),
        READ(R.string.voice_browser_read_failed_title, R.string.voice_browser_read_failed_message,
            R.string.storage_function_read_voice_ids)
    }

    fun showAlreadySaved(activity: Activity): AlertDialog =
        showOkay(activity, R.string.voice_browser_already_saved_title,
            activity.getString(R.string.voice_browser_already_saved_message))

    /** The explanation, then what the storage layer actually reported. */
    fun showStorageFailure(activity: Activity, action: StorageAction, error: Throwable): AlertDialog {
        val failureType = when ((error as? TtsStorageException)?.reason) {
            TtsStorageFailure.READ_FAILED -> R.string.storage_failure_read
            TtsStorageFailure.INVALID_DATA -> R.string.storage_failure_invalid
            TtsStorageFailure.WRITE_FAILED -> R.string.storage_failure_write
            else -> R.string.storage_failure_other
        }
        val details = (listOf(activity.getString(R.string.storage_error_heading)) + errorLines(error) +
            activity.getString(R.string.provider_function_line, activity.getString(action.function)) +
            activity.getString(R.string.storage_failure_type_line, activity.getString(failureType))).joinToString("\n")
        return showOkay(activity, action.title, activity.getString(action.message) + "\n\n" + details)
    }

    /** Every exception in the chain, as reported; stored Voice IDs and file paths hold no secrets. */
    fun errorLines(error: Throwable): List<String> = generateSequence(error) { it.cause.takeIf { c -> c !== it } }
        .take(8).map { e -> e.javaClass.simpleName + (e.message?.takeIf(String::isNotBlank)?.let { ": $it" } ?: "") }
        .toList()

    private fun showOkay(activity: Activity, title: Int, message: String): AlertDialog {
        val actions = activity.layoutInflater.inflate(R.layout.dialog_single_action, null)
        val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(title).setMessage(message).setView(actions).create()
        actions.findViewById<MaterialButton>(R.id.btn_dialog_action).apply {
            setText(R.string.btn_ok)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
        return dialog
    }
}
