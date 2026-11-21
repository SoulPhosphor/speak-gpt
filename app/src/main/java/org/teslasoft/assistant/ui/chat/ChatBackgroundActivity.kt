/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupWindow
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import androidx.fragment.app.FragmentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.archivist.Archivist
import org.teslasoft.assistant.service.MemoryAnalysisForegroundService
import org.teslasoft.assistant.service.MemoryAnalysisState

/**
 * The chat header's background-work icon and its popover (owner design,
 * Nov 18 2026; Pause and Resume added in Stage 6A). The icon shows while a
 * memory scan or summarizing runs, and in every open chat while a memory scan
 * is paused, so a paused scan is not forgotten. Tapping it shows one line per
 * activity. A running scan reads "Memory Scanner 85%" with a Pause button; a
 * paused scan reads "Memory Scanner" with a Resume button; the text is on the
 * left and the button on the right. The summarizing line is unchanged. The
 * popover closes on a tap elsewhere, follows progress while open, and closes
 * with the icon once the work is done. An interrupted scan shows nothing here
 * until it resumes.
 */
class ChatBackgroundActivity(
    private val activity: FragmentActivity,
    private val icon: ImageButton,
    /** The view the popover drops below: the chat header. */
    private val header: View
) {
    private var memoryScanPercent: Int? = null
    private var memoryScanPaused = false
    private var summarizingPercent: Int? = null
    private var popover: PopupWindow? = null
    private var memoryRow: View? = null
    private var memoryLine: TextView? = null
    private var memoryAction: MaterialButton? = null
    private var summarizingLine: TextView? = null

    fun attach() {
        icon.setOnClickListener { togglePopover() }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                MemoryAnalysisForegroundService.state.combine(
                    MemoryAnalysisForegroundService.suspended
                ) { s, waiting -> s to waiting }.collect { (s, waiting) ->
                    memoryScanPercent = (s as? MemoryAnalysisState.Running)?.let {
                        BackgroundActivityPercent.memoryScan(it.progress)
                    }
                    memoryScanPaused = memoryScanPercent == null && waiting?.paused == true
                    refresh()
                }
            }
        }
    }

    /** The current chat's summarizing progress, or null when none runs. */
    fun setSummarizing(percent: Int?) {
        summarizingPercent = percent
        refresh()
    }

    fun release() {
        popover?.dismiss()
        popover = null
    }

    private fun lines(): Pair<String?, String?> = Pair(
        memoryScanPercent?.let { activity.getString(R.string.chat_activity_memory_scan, it) }
            ?: if (memoryScanPaused) activity.getString(R.string.chat_activity_memory_scan_paused) else null,
        summarizingPercent?.let { activity.getString(R.string.chat_activity_summarizing, it) }
    )

    private fun refresh() {
        val (memory, summarizing) = lines()
        val any = memory != null || summarizing != null
        icon.visibility = if (any) View.VISIBLE else View.GONE
        // The icon's spoken label is the same wording the popover shows.
        icon.contentDescription = listOfNotNull(memory, summarizing).joinToString("\n")
        if (!any) {
            release()
            return
        }
        bindLines(memory, summarizing)
    }

    private fun bindLines(memory: String?, summarizing: String?) {
        memoryRow?.visibility = if (memory != null) View.VISIBLE else View.GONE
        memoryLine?.text = memory
        memoryAction?.let { button ->
            val running = memoryScanPercent != null
            button.setText(if (running) R.string.memory_scan_pause else R.string.memory_scan_resume)
            button.setOnClickListener {
                if (running) MemoryAnalysisForegroundService.requestPause(activity)
                else MemoryAnalysisForegroundService.requestResume(activity)
            }
        }
        summarizingLine?.let {
            it.text = summarizing
            it.visibility = if (summarizing != null) View.VISIBLE else View.GONE
        }
    }

    private fun togglePopover() {
        if (popover?.isShowing == true) {
            release()
            return
        }
        if (activity.isFinishing || activity.isDestroyed || !header.isAttachedToWindow) return
        val content = LayoutInflater.from(activity).inflate(R.layout.view_chat_activity_popover, null)
        memoryRow = content.findViewById(R.id.activity_row_memory_scan)
        memoryLine = content.findViewById(R.id.activity_line_memory_scan)
        memoryAction = content.findViewById(R.id.activity_action_memory_scan)
        summarizingLine = content.findViewById(R.id.activity_line_summarizing)
        val window = PopupWindow(
            content,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            // The popover draws its own surface; the window itself is clear.
            setBackgroundDrawable(ContextCompat.getDrawable(activity, android.R.color.transparent))
            isOutsideTouchable = true
            setOnDismissListener {
                if (popover === this) {
                    popover = null
                    memoryRow = null
                    memoryLine = null
                    memoryAction = null
                    summarizingLine = null
                }
            }
        }
        popover = window
        val (memory, summarizing) = lines()
        bindLines(memory, summarizing)
        window.showAsDropDown(header)
    }
}

/** The percentage each background activity reports, from work actually done. */
object BackgroundActivityPercent {

    /** The scan's approved percentage ([Archivist.Progress.percent]); 0
     *  before the scan reports anything. */
    fun memoryScan(progress: Archivist.Progress?): Int = progress?.percent() ?: 0
}
