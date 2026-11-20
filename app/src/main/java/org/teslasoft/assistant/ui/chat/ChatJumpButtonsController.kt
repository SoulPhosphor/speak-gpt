package org.teslasoft.assistant.ui.chat

import android.view.View
import android.view.ViewTreeObserver
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences

/**
 * Scroll to Top / Scroll to Bottom arrows in the transcript's side margin.
 * Chat Settings > Show Chat Scrolling Navigation turns them on or off and
 * picks the left or right side; the settings are re-read whenever the chat
 * window regains focus (for example on return from Chat Settings). While
 * enabled, the rail is shown exactly while the transcript is shown.
 */
class ChatJumpButtonsController(
    private val rail: View,
    private val transcript: ChatTranscriptRecyclerView
) {
    private var disposed = false
    private var enabled = true
    private val visibilityMirror = ViewTreeObserver.OnGlobalLayoutListener { syncVisibility() }
    private val settingsReader = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if (focused) applySettings()
    }

    init {
        rail.findViewById<View>(R.id.chat_jump_top).setOnClickListener { jumpToTop() }
        rail.findViewById<View>(R.id.chat_jump_bottom).setOnClickListener { jumpToBottom() }
        rail.viewTreeObserver.addOnGlobalLayoutListener(visibilityMirror)
        rail.viewTreeObserver.addOnWindowFocusChangeListener(settingsReader)
        applySettings()
    }

    private fun applySettings() {
        if (disposed) return
        val preferences = Preferences.getPreferences(rail.context, "")
        enabled = preferences.getShowChatScrollNavigation()
        val onLeft = preferences.getChatScrollNavigationOnLeft()
        (rail.layoutParams as? ConstraintLayout.LayoutParams)?.let { params ->
            val anchor = transcript.id
            val left = if (onLeft) anchor else ConstraintLayout.LayoutParams.UNSET
            val right = if (onLeft) ConstraintLayout.LayoutParams.UNSET else anchor
            if (params.leftToLeft != left || params.rightToRight != right) {
                params.leftToLeft = left
                params.rightToRight = right
                rail.layoutParams = params
            }
        }
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
        val shown = if (enabled && transcript.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        if (rail.visibility != shown) rail.visibility = shown
    }

    fun dispose() {
        disposed = true
        val observer = rail.viewTreeObserver
        if (observer.isAlive) {
            observer.removeOnGlobalLayoutListener(visibilityMirror)
            observer.removeOnWindowFocusChangeListener(settingsReader)
        }
    }
}
