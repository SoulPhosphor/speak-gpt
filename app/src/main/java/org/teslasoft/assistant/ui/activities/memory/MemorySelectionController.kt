package org.teslasoft.assistant.ui.activities.memory

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.ManualMemorySelection

/** Reusable browser selection contract. A cancelled launch leaves the calling draft alone. */
data class MemorySelectionRequest(val chatId: String, val selectedIds: List<String>)

class MemorySelectionContract : ActivityResultContract<MemorySelectionRequest, List<String>?>() {
    companion object {
        const val EXTRA_SELECT = "memory_selection_mode"
        const val EXTRA_IDS = "selected_memory_ids"
    }
    override fun createIntent(context: Context, input: MemorySelectionRequest): Intent =
        Intent(context, MemoryBrowserActivity::class.java)
            .putExtra("chatId", input.chatId)
            .putExtra(EXTRA_SELECT, true)
            .putStringArrayListExtra(EXTRA_IDS, ArrayList(input.selectedIds))
    override fun parseResult(resultCode: Int, intent: Intent?): List<String>? =
        if (resultCode == Activity.RESULT_OK) intent?.getStringArrayListExtra(EXTRA_IDS).orEmpty() else null
}

/** Owns selection, the confirmation row, and exit dialogs; no chat-specific behavior. */
class MemorySelectionController(
    private val activity: FragmentActivity,
    savedState: Bundle?,
    private val changed: () -> Unit
) {
    companion object { private const val STATE_IDS = "memory_picker_selected_ids" }
    val enabled = activity.intent.getBooleanExtra(MemorySelectionContract.EXTRA_SELECT, false)
    private val selection = ManualMemorySelection(
        savedState?.getStringArrayList(STATE_IDS)
            ?: activity.intent.getStringArrayListExtra(MemorySelectionContract.EXTRA_IDS).orEmpty()
    )
    fun ids(): List<String> = selection.ids()
    fun save(outState: Bundle) { if (enabled) outState.putStringArrayList(STATE_IDS, ArrayList(ids())) }
    fun toggle(id: String) { if (selection.toggle(id)) changed() }

    fun bind() {
        if (!enabled) return
        activity.findViewById<View>(R.id.memory_selection_controls).visibility = View.VISIBLE
        activity.findViewById<View>(R.id.memory_selection_okay).setOnClickListener { confirm() }
        activity.findViewById<ImageButton>(R.id.btn_back).setOnClickListener { leave() }
        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
    }

    private fun finishWith(ids: List<String>) {
        activity.setResult(Activity.RESULT_OK, Intent().putStringArrayListExtra(
            MemorySelectionContract.EXTRA_IDS, ArrayList(ids)
        ))
        activity.finish()
    }
    private fun confirm() {
        if (ids().isNotEmpty()) { finishWith(ids()); return }
        dialog(activity.getString(R.string.memory_selection_empty_title),
            activity.getString(R.string.memory_selection_empty_body), false,
            R.string.btn_cancel, R.string.btn_ok, {}, { finishWith(emptyList()) })
    }
    private fun leave() {
        val ids = ids()
        if (ids.isEmpty()) { finishWith(emptyList()); return }
        dialog(activity.resources.getQuantityString(R.plurals.memory_selection_attach_title, ids.size),
            activity.resources.getQuantityString(R.plurals.memory_selection_selected, ids.size, ids.size), true,
            R.string.memory_selection_attach, R.string.memory_selection_discard,
            { finishWith(ids) }, { finishWith(emptyList()) })
    }
    private fun dialog(
        title: String, message: String, primaryFirst: Boolean,
        leftLabel: Int, rightLabel: Int, leftAction: () -> Unit, rightAction: () -> Unit
    ) {
        val builder = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
        val actions = activity.layoutInflater.cloneInContext(builder.context).inflate(
            if (primaryFirst) R.layout.dialog_two_actions else R.layout.dialog_two_actions_cancel_first, null
        )
        val dialog = builder.setTitle(title).setMessage(message).setView(actions).create()
        val leftId = if (primaryFirst) R.id.btn_dialog_primary_action else R.id.btn_dialog_destructive_action
        val rightId = if (primaryFirst) R.id.btn_dialog_destructive_action else R.id.btn_dialog_primary_action
        actions.findViewById<MaterialButton>(leftId).apply {
            setText(leftLabel); setOnClickListener { dialog.dismiss(); leftAction() }
        }
        actions.findViewById<MaterialButton>(rightId).apply {
            setText(rightLabel); setOnClickListener { dialog.dismiss(); rightAction() }
        }
        dialog.show()
    }
}
