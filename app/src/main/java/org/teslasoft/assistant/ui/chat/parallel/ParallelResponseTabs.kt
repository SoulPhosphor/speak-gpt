package org.teslasoft.assistant.ui.chat.parallel

import android.content.res.ColorStateList
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.google.android.material.color.MaterialColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.conversation.responsegroups.*
import org.teslasoft.assistant.preferences.MessageCompletionState

/** A row binder, with no request state and no canonical writes. Ordinary replies allocate no tabs. */
internal object ParallelResponseTabs {
    fun bind(root: View, row: Map<String, Any>, enabled: Boolean,
        browse: (String, String) -> Unit, stop: (String?) -> Unit,
        savePending: Boolean = false, retrySave: () -> Unit = {}): Boolean {
        val container = root.findViewById<View>(R.id.parallel_response_controls) ?: return false
        val read = ResponseGroupCodec.read(row)
        val group = (read as? ResponseGroupRead.Valid)?.group
        container.visibility = if (read == ResponseGroupRead.Absent) View.GONE else View.VISIBLE
        if (read == ResponseGroupRead.Absent) return false
        if (group == null) {
            root.findViewById<LinearLayout>(R.id.parallel_response_tabs).removeAllViews()
            root.findViewById<LinearLayout>(R.id.parallel_response_versions).removeAllViews()
            listOf(R.id.parallel_retry_save, R.id.parallel_stop_slot, R.id.parallel_stop_all).forEach { root.findViewById<View>(it).visibility = View.GONE }
            root.findViewById<TextView>(R.id.parallel_response_status).setText(
                if (read is ResponseGroupRead.Unsupported) R.string.parallel_responses_unsupported else R.string.parallel_responses_unreadable)
            return true
        }
        root.findViewById<View>(R.id.parallel_retry_save).apply {
            visibility = if (savePending) View.VISIBLE else View.GONE
            isEnabled = enabled
            setOnClickListener { retrySave() }
        }
        val tabs = root.findViewById<LinearLayout>(R.id.parallel_response_tabs)
        val sameGroup = tabs.tag == group.groupId
        val tabScroll = tabs.parent as? HorizontalScrollView
        val tabX = if (sameGroup) tabScroll?.scrollX ?: 0 else 0
        tabs.tag = group.groupId
        tabs.removeAllViews()
        group.slots.forEach { slot ->
            val version = slot.versions.single { it.versionId == slot.foregroundVersionId }
            val state = version.fields["state"].orEmpty()
            val label = slot.assignment.label.substringAfterLast('/')
            val tab = TextView(root.context, null, 0, R.style.Widget_App_ParallelResponse_Tab).apply {
                text = label + "\n" + root.context.getString(when (state) {
                    MessageCompletionState.STREAMING -> R.string.parallel_generating
                    MessageCompletionState.FAILED -> R.string.parallel_tab_failed
                    MessageCompletionState.STOPPED -> R.string.parallel_cancelled
                    MessageCompletionState.INTERRUPTED -> R.string.parallel_tab_interrupted
                    else -> R.string.parallel_complete
                })
                isSelected = slot.slotId == group.foregroundSlotId
                ViewCompat.setAccessibilityHeading(this, false)
                contentDescription = root.context.getString(R.string.parallel_tab_description,
                    slot.assignment.label, slot.assignment.endpointProfileId, status(root, state),
                    if (slot.slotId == group.primarySlotId) root.context.getString(R.string.parallel_primary) else "")
                if (slot.slotId == group.primarySlotId) {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_bookmark_star, 0, 0, 0)
                    compoundDrawableTintList = ColorStateList.valueOf(MaterialColors.getColor(this, R.attr.appRowSubtitleColor))
                }
                isEnabled = enabled
                setOnClickListener { browse(slot.slotId, slot.foregroundVersionId) }
            }
            tabs.addView(tab)
        }
        tabScroll?.post {
            if (tabs.tag == group.groupId) tabScroll.scrollTo(
                if (sameGroup) tabX else tabs.getChildAt(group.slots.indexOfFirst { it.slotId == group.foregroundSlotId }).left, 0)
        }
        val slot = group.slots.single { it.slotId == group.foregroundSlotId }
        val version = slot.versions.single { it.versionId == slot.foregroundVersionId }
        val state = version.fields["state"].orEmpty()
        root.findViewById<TextView>(R.id.parallel_response_status).apply {
            text = listOf(if (savePending) root.context.getString(R.string.parallel_save_failed) else status(root, state), version.fields["providerWarningText"])
                .filterNot { it.isNullOrBlank() }.joinToString(" · ")
        }
        root.findViewById<View>(R.id.parallel_stop_slot).apply {
            visibility = if (ParallelResponseSelection.running(slot)) View.VISIBLE else View.GONE
            isEnabled = enabled
            setOnClickListener { stop(slot.slotId) }
        }
        root.findViewById<View>(R.id.parallel_stop_all).apply {
            visibility = if (ParallelResponseSelection.running(group)) View.VISIBLE else View.GONE
            isEnabled = enabled
            setOnClickListener { stop(null) }
        }
        val versions = root.findViewById<LinearLayout>(R.id.parallel_response_versions)
        val sameSlot = versions.tag == slot.slotId
        val versionScroll = versions.parent as? HorizontalScrollView
        val versionX = if (sameSlot) versionScroll?.scrollX ?: 0 else 0
        versions.tag = slot.slotId
        versions.removeAllViews()
        if (slot.versions.size > 1) slot.versions.forEachIndexed { index, saved ->
            versions.addView(TextView(root.context, null, 0, R.style.Widget_App_ParallelResponse_Tab).apply {
                text = root.context.getString(R.string.version_nav_count, index + 1, slot.versions.size)
                isSelected = saved.versionId == slot.foregroundVersionId
                isEnabled = enabled
                setOnClickListener { browse(slot.slotId, saved.versionId) }
            })
        }
        versionScroll?.post {
            if (versions.tag == slot.slotId) versionScroll.scrollTo(if (sameSlot) versionX else
                versions.getChildAt(slot.versions.indexOfFirst { it.versionId == slot.foregroundVersionId })?.left ?: 0, 0)
        }
        return true
    }

    private fun status(root: View, state: String): String = root.context.getString(when (state) {
        MessageCompletionState.STREAMING -> R.string.parallel_generating
        MessageCompletionState.FAILED -> R.string.parallel_failed
        MessageCompletionState.STOPPED -> R.string.parallel_cancelled
        MessageCompletionState.INTERRUPTED -> R.string.parallel_interrupted
        else -> R.string.parallel_complete
    })
}
