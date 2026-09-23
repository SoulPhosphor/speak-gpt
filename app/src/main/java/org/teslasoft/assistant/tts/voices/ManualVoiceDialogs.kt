package org.teslasoft.assistant.tts.voices

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R

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

    fun showAlreadySaved(activity: Activity): AlertDialog =
        showOkay(activity, R.string.voice_browser_already_saved_title,
            activity.getString(R.string.voice_browser_already_saved_message))

    /** The plain explanation for the most specific known cause. */
    fun showStorageFailure(activity: Activity, operation: ManualVoiceStorageErrors.Operation, error: Throwable,
        onDismiss: () -> Unit = {}): AlertDialog {
        val (title, message) = when (ManualVoiceStorageErrors.classify(operation, error)) {
            ManualVoiceStorageErrors.Kind.NO_SPACE -> R.string.voice_storage_no_space_title to R.string.voice_storage_no_space_message
            ManualVoiceStorageErrors.Kind.ACCESS -> R.string.voice_storage_access_title to R.string.voice_storage_access_message
            ManualVoiceStorageErrors.Kind.DAMAGED -> R.string.voice_storage_damaged_title to R.string.voice_storage_damaged_message
            ManualVoiceStorageErrors.Kind.READ_FAILED -> R.string.voice_storage_read_failed_title to R.string.voice_storage_read_failed_message
            ManualVoiceStorageErrors.Kind.NOT_FOUND -> R.string.voice_storage_not_found_title to R.string.voice_storage_not_found_message
            ManualVoiceStorageErrors.Kind.SAVE_UNVERIFIED -> R.string.voice_storage_save_failed_title to R.string.voice_storage_save_unverified_message
            ManualVoiceStorageErrors.Kind.REMOVE_UNVERIFIED -> R.string.voice_storage_remove_failed_title to R.string.voice_storage_remove_unverified_message
            ManualVoiceStorageErrors.Kind.SAVE_FAILED -> R.string.voice_storage_save_failed_title to R.string.voice_storage_save_failed_message
            ManualVoiceStorageErrors.Kind.REMOVE_FAILED -> R.string.voice_storage_remove_failed_title to R.string.voice_storage_remove_failed_message
        }
        return showOkay(activity, title, activity.getString(message), onDismiss)
    }

    private fun showOkay(activity: Activity, title: Int, message: String, onDismiss: () -> Unit = {}): AlertDialog {
        val actions = activity.layoutInflater.inflate(R.layout.dialog_single_action, null)
        val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(title).setMessage(message).setView(actions).create()
        dialog.setOnDismissListener { onDismiss() }
        actions.findViewById<MaterialButton>(R.id.btn_dialog_action).apply {
            setText(R.string.btn_ok)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
        return dialog
    }
}
