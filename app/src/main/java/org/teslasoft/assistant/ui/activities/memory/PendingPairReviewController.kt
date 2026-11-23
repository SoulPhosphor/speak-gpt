package org.teslasoft.assistant.ui.activities.memory

import android.app.Activity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.*
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingTokenCounter
import org.teslasoft.assistant.preferences.memory.librarian.Librarian
import org.teslasoft.assistant.preferences.memory.librarian.MemoryTokenBudget
import org.teslasoft.assistant.ui.util.DiscardChangesDialog
import org.teslasoft.assistant.ui.widgets.MemoryTokenMeter

/** Dedicated Pending actions; legacy resolutions never receive these records. */
internal class PendingPairReviewController(
    private val activity: Activity,
    private val snapshot: PendingPairSnapshot,
    private val runWork: (() -> Unit) -> Unit,
    private val setLocked: (Boolean) -> Unit,
    private val reload: () -> Unit,
    private val finish: () -> Unit
) {
    private val buttons = ArrayList<View>()
    private var editing: Editor? = null
    private var running = false
    private class Editor(val record: MemoryRecord, val panel: View, val field: EditText,
        val meter: MemoryTokenMeter, val save: View, val cancel: View)

    fun bindCard(card: LinearLayout, record: MemoryRecord) {
        val actions = activity.layoutInflater.inflate(R.layout.view_pending_pair_actions, card, false)
        card.addView(actions)
        val editor = Editor(record, actions.findViewById(R.id.pair_editor), actions.findViewById(R.id.pair_content),
            card.findViewById(R.id.memory_length_meter), actions.findViewById(R.id.pair_edit_save), actions.findViewById(R.id.pair_edit_cancel))
        editor.field.doAfterTextChanged {
            if (editing === editor) editor.meter.refresh(MemoryTokenBudget.Input.forEdit(record, it?.toString().orEmpty(), record.tagsJson))
        }
        button(actions, R.id.pair_edit) {
            editing = editor
            editor.field.setText(record.content)
            editor.panel.visibility = View.VISIBLE
            refresh()
        }
        button(actions, R.id.pair_discard) { confirm(PendingPairAction.DISCARD, record) }
        button(actions, R.id.pair_save) { confirm(PendingPairAction.SAVE, record) }
        editor.save.setOnClickListener {
            val content = editor.field.text.toString()
            if (content.isBlank()) toast(R.string.mem_edit_required) else apply(PendingPairAction.EDIT, record, content)
        }
        editor.cancel.setOnClickListener {
            editor.panel.visibility = View.GONE
            editor.meter.bind(record)
            editing = null
            refresh()
        }
    }

    fun addKeepBoth(parent: LinearLayout) {
        val view = activity.layoutInflater.inflate(R.layout.view_pending_pair_keep, parent, false)
        parent.addView(view)
        view.findViewById<View>(R.id.pair_keep_hint).visibility = if (snapshot.isLinkedAlternative) View.VISIBLE else View.GONE
        button(view, R.id.pair_keep) { apply(PendingPairAction.KEEP_BOTH) }
    }

    fun addOpenPair(card: LinearLayout, open: () -> Unit) {
        val view = activity.layoutInflater.inflate(R.layout.view_pending_pair_open, card, false)
        card.addView(view)
        buttons.add(view)
        view.setOnClickListener { if (!running && editing == null) open() }
    }

    fun handleBack(): Boolean {
        if (running) return true
        val editor = editing ?: return false
        if (editor.field.text.toString() == editor.record.content) finish()
        else DiscardChangesDialog.show(activity) { finish() }
        return true
    }

    fun setExternalEnabled(enabled: Boolean) {
        buttons.forEach { it.isEnabled = enabled && !running && editing == null }
    }

    fun onFailure() { running = false; refresh() }
    private fun button(view: View, id: Int, action: () -> Unit) {
        val button = view.findViewById<View>(id)
        buttons.add(button)
        button.setOnClickListener { if (!running && editing == null) action() }
    }

    private fun confirm(action: PendingPairAction, record: MemoryRecord) {
        running = true
        refresh()
        val discard = action == PendingPairAction.DISCARD
        val actions = activity.layoutInflater.inflate(
            if (discard) R.layout.dialog_pending_pair_discard else R.layout.dialog_two_actions, null)
        val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(if (discard) R.string.mem_pair_discard_title else R.string.mem_pair_save_title)
            .setMessage(activity.getString(if (discard) R.string.mem_pair_discard_message else R.string.mem_pair_save_message, record.content))
            .setView(actions).create()
        var committed = false
        actions.findViewById<MaterialButton>(if (discard) R.id.pair_confirm_discard else R.id.btn_dialog_primary_action).apply {
            setText(if (discard) R.string.memory_discard_confirm_button else R.string.btn_save)
            setOnClickListener { committed = true; dialog.dismiss(); apply(action, record) }
        }
        actions.findViewById<MaterialButton>(if (discard) R.id.pair_confirm_cancel else R.id.btn_dialog_destructive_action).apply {
            setText(R.string.btn_cancel)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.setOnDismissListener { if (!committed) { running = false; refresh() } }
        dialog.show()
    }

    private fun apply(action: PendingPairAction, record: MemoryRecord? = null, content: String? = null) {
        running = true
        refresh()
        runWork {
            val input = when (action) {
                PendingPairAction.EDIT -> record?.let { MemoryTokenBudget.Input.forEdit(it, content.orEmpty(), it.tagsJson) }
                PendingPairAction.SAVE -> record?.let { MemoryTokenBudget.Input.from(it) }
                else -> null
            }
            val reader = if (input != null) EmbeddingTokenCounter.reader(activity) else null
            if (input != null && reader != null && !reader.measure(input).fits) {
                activity.runOnUiThread { onFailure(); MemoryLengthDialog.show(activity) }
                return@runWork
            }
            when (val result = MemoryStore.getInstance(activity).resolvePendingPair(snapshot, action, record?.memoryId, content)) {
                is MemoryStore.ResolutionResult.Applied -> {
                    result.reindexMemoryIds.forEach { Librarian.getInstance(activity).reindexMemory(it) }
                    activity.runOnUiThread {
                        running = false; editing = null; refresh()
                        toast(when (action) {
                            PendingPairAction.KEEP_BOTH -> R.string.mem_pair_kept
                            PendingPairAction.EDIT -> R.string.mem_pair_edited
                            PendingPairAction.DISCARD -> R.string.mem_pair_discarded
                            PendingPairAction.SAVE -> R.string.memory_saved
                        })
                        if (action == PendingPairAction.EDIT) reload() else finish()
                    }
                }
                else -> activity.runOnUiThread {
                    running = false; editing = null; refresh(); toast(R.string.mem_review_stale); reload()
                }
            }
        }
    }

    private fun refresh() {
        val locked = running || editing != null
        buttons.forEach { it.isEnabled = !locked }
        editing?.let { it.save.isEnabled = !running; it.cancel.isEnabled = !running; it.field.isEnabled = !running }
        setLocked(locked)
    }
    private fun toast(message: Int) {
        if (!activity.isFinishing && !activity.isDestroyed) Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
