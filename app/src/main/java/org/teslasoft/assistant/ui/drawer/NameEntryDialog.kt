package org.teslasoft.assistant.ui.drawer

import android.view.LayoutInflater
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationFailure
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationResult

object NameEntryDialog {
    fun show(
        activity: FragmentActivity,
        title: Int,
        initialValue: String = "",
        save: (String) -> ChatNavigationResult<*>,
        onSaved: () -> Unit
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_name_entry, null)
        val layout = view.findViewById<TextInputLayout>(R.id.name_entry_layout)
        val field = view.findViewById<TextInputEditText>(R.id.name_entry_field)
        field.setText(initialValue)
        val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(title)
            .setView(view)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.image_gallery_okay, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                layout.error = null
                when (val result = save(field.text?.toString().orEmpty())) {
                    is ChatNavigationResult.Success -> { dialog.dismiss(); onSaved() }
                    is ChatNavigationResult.Failure -> layout.error = activity.getString(
                        when (result.reason) {
                            ChatNavigationFailure.BLANK_NAME -> R.string.folder_name_blank
                            ChatNavigationFailure.DUPLICATE_NAME -> R.string.folder_name_duplicate
                            else -> R.string.label_sorry_action_failed
                        }
                    )
                }
            }
            field.requestFocus()
            field.setSelection(0, field.text?.length ?: 0)
        }
        dialog.show()
    }
}

