package org.teslasoft.assistant.ui.chat

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.ManualMemorySelection
import org.teslasoft.assistant.ui.activities.memory.MemorySelectionContract
import org.teslasoft.assistant.ui.activities.memory.MemorySelectionRequest

/** Unsent memory attachments. Sent ids are metadata for retries, never paperclip Includes. */
class MemoryAttachmentController(
    private val activity: FragmentActivity,
    private val changed: () -> Unit
) {
    companion object {
        const val MESSAGE_KEY = "manually_attached_memory_ids"
        fun fromMessage(message: Map<String, Any?>?): List<String> = decode(message?.get(MESSAGE_KEY) as? String)
        private fun decode(json: String?): List<String> = try {
            val array = JSONArray(json ?: "[]")
            ManualMemorySelection((0 until array.length()).map { array.getString(it) }).ids()
        } catch (_: Exception) { emptyList() }
    }
    private val drafts by lazy { activity.getSharedPreferences("pending_memory_attachments", Context.MODE_PRIVATE) }
    private var chatId = ""
    private var bound = false
    private var chatMode = false
    private var resultBeforeBind: List<String>? = null
    private var selectedIds: List<String> = emptyList()
    private var strip: View? = null
    private var label: TextView? = null
    private val picker = activity.registerForActivityResult(MemorySelectionContract()) { result ->
        if (result != null) {
            if (bound) replace(result) else resultBeforeBind = result
        }
    }
    fun bind(chatId: String) {
        this.chatId = chatId
        selectedIds = decode(drafts.getString(chatId, null))
        bound = true
        strip = activity.findViewById(R.id.memory_attachment_strip)
        strip?.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) changed()
        }
        label = activity.findViewById(R.id.memory_attachment_label)
        activity.findViewById<View>(R.id.action_add_memory).setOnClickListener {
            activity.findViewById<View>(R.id.vision_action_selector).visibility = View.GONE
            open()
        }
        activity.findViewById<View>(R.id.memory_attachment_settings).setOnClickListener { open() }
        activity.findViewById<View>(R.id.memory_attachment_remove).setOnClickListener { confirmRemoval() }
        val restoredResult = resultBeforeBind
        resultBeforeBind = null
        if (restoredResult != null) replace(restoredResult) else render()
    }
    /** Playground keeps the draft but never displays chat-only attachment controls. */
    fun setChatMode(enabled: Boolean) {
        if (chatMode == enabled) return
        chatMode = enabled
        render()
    }
    /** Measured attachment space, including stylesheet-defined margins. */
    fun occupiedHeight(): Int {
        val view = strip ?: return 0
        if (view.visibility != View.VISIBLE) return 0
        val margins = view.layoutParams as? ViewGroup.MarginLayoutParams
        return view.height + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0)
    }
    fun ids(): List<String> = selectedIds.toList()
    private fun open() { picker.launch(MemorySelectionRequest(chatId, ids())) }
    private fun replace(ids: List<String>) {
        selectedIds = ManualMemorySelection(ids).ids()
        drafts.edit().apply {
            if (selectedIds.isEmpty()) remove(chatId) else putString(chatId, JSONArray(selectedIds).toString())
        }.apply()
        render()
    }
    /** Store ids atomically with the message. Draft consumption waits for a successful commit. */
    fun attachTo(message: MutableMap<String, Any>, ids: List<String>) {
        if (ids.isNotEmpty()) message[MESSAGE_KEY] = JSONArray(ids).toString()
    }
    fun completeSend(sentIds: List<String>) {
        if (selectedIds == sentIds) replace(emptyList())
    }
    private fun render() {
        strip?.visibility = if (chatMode && selectedIds.isNotEmpty()) View.VISIBLE else View.GONE
        label?.text = activity.resources.getQuantityString(
            R.plurals.memory_attachment_count, selectedIds.size, selectedIds.size
        )
        if (selectedIds.isNotEmpty()) {
            activity.findViewById<View>(R.id.memory_attachment_remove)?.contentDescription =
                activity.resources.getQuantityString(R.plurals.memory_attachment_remove_action, selectedIds.size)
            activity.findViewById<View>(R.id.memory_attachment_settings)?.contentDescription =
                activity.resources.getQuantityString(R.plurals.memory_attachment_settings_action, selectedIds.size)
        }
        changed()
    }
    private fun confirmRemoval() {
        val count = selectedIds.size
        if (count == 0) return
        val builder = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
        val actions = activity.layoutInflater.cloneInContext(builder.context)
            .inflate(R.layout.dialog_two_actions_cancel_first, null)
        val dialog = builder.setTitle(activity.resources.getQuantityString(R.plurals.memory_attachment_remove_title, count))
            .setMessage(R.string.memory_attachment_remove_body).setView(actions).create()
        actions.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
            setText(R.string.btn_cancel); setOnClickListener { dialog.dismiss() }
        }
        actions.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.btn_ok); setOnClickListener { dialog.dismiss(); replace(emptyList()) }
        }
        dialog.show()
    }
}
