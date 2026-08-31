package org.teslasoft.assistant.ui.adapters

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.fragment.app.FragmentActivity
import com.google.android.material.color.MaterialColors
import java.text.DateFormat
import java.util.Date
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.chatsearch.SearchDocumentKind
import org.teslasoft.assistant.preferences.chatsearch.SearchResult
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.ui.drawer.FlatChatIdentityBinder
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap

class SearchResultAdapter(
    private val activity: FragmentActivity,
    private val onClick: (SearchResult) -> Unit
) : ListAdapter<SearchResult, SearchResultAdapter.Holder>(DIFF) {
    private val preferences = Preferences.getPreferences(activity, "")
    private val memoryStates = ConcurrentHashMap<String, String>()
    private val metadataExecutor = Executors.newSingleThreadExecutor { Thread(it, "search-metadata") }

    init {
        if (preferences.getShowMemoryStatusOnChatList()) metadataExecutor.execute {
            try {
                if (MemoryStore.isProvisioned(activity)) {
                    memoryStates.putAll(MemoryStore.getInstance(activity).chatReviewStates())
                    activity.runOnUiThread { notifyDataSetChanged() }
                }
            } catch (_: Exception) { }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.view_flat_chat_row, parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.flat_chat_title)
        private val snippet: TextView = itemView.findViewById(R.id.flat_chat_snippet)
        private val date: TextView = itemView.findViewById(R.id.flat_chat_date)
        private val chevron: View = itemView.findViewById(R.id.flat_chat_chevron)
        private val identity = FlatChatIdentityBinder(activity, itemView, preferences, memoryStates)

        fun bind(result: SearchResult) {
            identity.bind(result.chatId, result.chatTitle, pinned = result.chatPinned, current = false)
            chevron.visibility = View.GONE
            if (result.kind == SearchDocumentKind.TITLE) {
                title.text = highlighted(result.matchedText, result.highlightRanges)
                snippet.visibility = View.GONE
            } else {
                snippet.visibility = View.VISIBLE
                snippet.text = highlighted(result.matchedText, result.highlightRanges)
            }
            val timestamp = result.messageTimestamp
                ?: if (result.kind == SearchDocumentKind.TITLE) result.chatTimestamp else null
            if (timestamp == null || timestamp <= 0L) {
                date.visibility = View.GONE
                date.text = ""
            } else {
                date.visibility = View.VISIBLE
                date.text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(timestamp))
            }
            itemView.setOnClickListener { onClick(result) }
            itemView.setOnLongClickListener(null)
        }

        private fun highlighted(text: String, ranges: List<IntRange>): SpannableString {
            val value = SpannableString(text)
            val color = MaterialColors.getColor(
                itemView, com.google.android.material.R.attr.colorSecondaryContainer
            )
            ranges.forEach { range ->
                val start = range.first.coerceIn(0, text.length)
                val end = (range.last + 1).coerceIn(start, text.length)
                if (start < end) {
                    value.setSpan(BackgroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    value.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            return value
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SearchResult>() {
            override fun areItemsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean =
                oldItem.rowId == newItem.rowId
            override fun areContentsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean = oldItem == newItem
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        metadataExecutor.shutdownNow()
        super.onDetachedFromRecyclerView(recyclerView)
    }
}
