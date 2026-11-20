package org.teslasoft.assistant.ui.chat

import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.LinearLayoutManager
import org.teslasoft.assistant.R

/**
 * Jump to Top / Jump to Bottom arrows in the transcript's end margin.
 * The rail is shown exactly while the transcript is shown.
 */
class ChatJumpButtonsController(
    private val rail: View,
    private val transcript: ChatTranscriptRecyclerView
) {
    private var disposed = false
    private val visibilityMirror = ViewTreeObserver.OnGlobalLayoutListener { syncVisibility() }

    init {
        rail.findViewById<View>(R.id.chat_jump_top).setOnClickListener { jumpToTop() }
        rail.findViewById<View>(R.id.chat_jump_bottom).setOnClickListener { jumpToBottom() }
        rail.viewTreeObserver.addOnGlobalLayoutListener(visibilityMirror)
        syncVisibility()
    }

    private fun jumpToTop() {
        if ((transcript.adapter?.itemCount ?: 0) == 0) return
        // A jump is the user's own scroll: auto-scroll must not pull it back.
        transcript.onUserInteraction?.invoke()
        transcript.stopScroll()
        transcript.releaseResizeAnchor()
        (transcript.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
            ?: transcript.scrollToPosition(0)
    }

    private fun jumpToBottom() {
        transcript.onUserInteraction?.invoke()
        transcript.stopScroll()
        transcript.scrollToTranscriptEnd()
    }

    private fun syncVisibility() {
        if (disposed) return
        val shown = if (transcript.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        if (rail.visibility != shown) rail.visibility = shown
    }

    fun dispose() {
        disposed = true
        if (rail.viewTreeObserver.isAlive) rail.viewTreeObserver.removeOnGlobalLayoutListener(visibilityMirror)
    }
}
