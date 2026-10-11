package org.teslasoft.assistant.ui.chat

import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.teslasoft.assistant.R

/** A fixed, in-layout strip. Scrolling the transcript never dismisses it. */
class BookmarkNavigationController(
    private val panel: View,
    private val transcript: RecyclerView,
    private val adapter: RecyclerView.Adapter<*>,
    private val readTargets: () -> List<BookmarkNavigation.Target>
) {
    private val navigation = BookmarkNavigation()
    private val strip: View = panel.findViewById(R.id.bookmark_search_strip)
    private val input: EditText = panel.findViewById(R.id.bookmark_search_input)
    private val clear: ImageButton = panel.findViewById(R.id.bookmark_search_clear)
    private val previous: ImageButton = panel.findViewById(R.id.bookmark_previous)
    private val next: ImageButton = panel.findViewById(R.id.bookmark_next)
    private val count: TextView = panel.findViewById(R.id.bookmark_count)
    private var searchExpanded = false
    private var disposed = false
    private val observer = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = onTranscriptChanged()
        override fun onItemRangeChanged(start: Int, size: Int) = onTranscriptChanged()
        override fun onItemRangeChanged(start: Int, size: Int, payload: Any?) = onTranscriptChanged()
        override fun onItemRangeInserted(start: Int, size: Int) = onTranscriptChanged()
        override fun onItemRangeRemoved(start: Int, size: Int) = onTranscriptChanged()
        override fun onItemRangeMoved(from: Int, to: Int, size: Int) = onTranscriptChanged()
    }

    init {
        panel.findViewById<View>(R.id.bookmark_close).setOnClickListener { close() }
        panel.findViewById<View>(R.id.bookmark_toggle_search).setOnClickListener {
            searchExpanded = !searchExpanded
            if (!searchExpanded) releaseSearchKeyboard()
            render()
        }
        previous.setOnClickListener { jump(navigation.move(-1)); render() }
        next.setOnClickListener { jump(navigation.move(1)); render() }
        panel.findViewById<View>(R.id.bookmark_search_submit).setOnClickListener { submit() }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false
        }
        clear.setOnClickListener {
            input.text.clear()
            navigation.clearSearch()
            render()
        }
        adapter.registerAdapterDataObserver(observer)
        refresh()
    }

    fun hasBookmarks(): Boolean {
        refresh()
        return navigation.hasBookmarks
    }

    fun open() {
        refresh()
        if (!navigation.isOpen) {
            input.text.clear()
            searchExpanded = false
        }
        val target = navigation.open()
        render()
        jump(target)
    }

    private fun onTranscriptChanged() {
        if (navigation.isOpen) refresh()
    }

    fun refresh() {
        if (disposed) return
        navigation.refresh(readTargets())
        render()
    }

    private fun submit() {
        navigation.refresh(readTargets())
        val target = navigation.search(input.text.toString())
        releaseSearchKeyboard()
        render()
        jump(target)
    }

    private fun close() {
        navigation.close()
        releaseSearchKeyboard()
        input.text.clear()
        searchExpanded = false
        render()
    }

    private fun render() {
        panel.visibility = if (navigation.isOpen) View.VISIBLE else View.GONE
        if (!navigation.isOpen) {
            releaseSearchKeyboard()
            input.text.clear()
            searchExpanded = false
        }
        strip.visibility = if (searchExpanded) View.VISIBLE else View.GONE
        clear.visibility = if (navigation.query != null) View.VISIBLE else View.GONE
        count.text = panel.context.getString(
            if (navigation.query == null) R.string.bookmark_count else R.string.bookmark_result_count,
            navigation.ordinal, navigation.count
        )
        previous.isEnabled = navigation.canNavigate
        next.isEnabled = navigation.canNavigate
    }

    private fun releaseSearchKeyboard() {
        if (!input.hasFocus()) return
        (panel.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    private fun jump(target: BookmarkNavigation.Target?) {
        if (target == null) return
        // Wait for the strip's new height before positioning the message.
        transcript.post {
            if (disposed) return@post
            val current = readTargets().firstOrNull { it.id == target.id } ?: return@post
            transcript.stopScroll()
            (transcript.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(current.position, 0)
                ?: transcript.scrollToPosition(current.position)
        }
    }

    fun dispose() {
        disposed = true
        adapter.unregisterAdapterDataObserver(observer)
    }
}
