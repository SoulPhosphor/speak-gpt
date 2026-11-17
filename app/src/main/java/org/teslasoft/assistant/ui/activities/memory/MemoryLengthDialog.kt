package org.teslasoft.assistant.ui.activities.memory

import android.app.Activity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R

object MemoryLengthDialog {
    fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val actions = activity.layoutInflater.inflate(R.layout.dialog_single_action, null)
        val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_cannot_save_title)
            .setMessage(R.string.mem_cannot_save_token_limit)
            .setView(actions)
            .create()
        actions.findViewById<MaterialButton>(R.id.btn_dialog_action).apply {
            setText(R.string.btn_ok)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
    }
}
