package org.teslasoft.assistant.ui.drawer

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.MemoryStore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap

class DrawerHierarchyAdapter(
    private val activity: FragmentActivity,
    private val currentChatId: () -> String,
    private val callbacks: Callbacks
) : ListAdapter<DrawerRow, DrawerHierarchyAdapter.Holder>(DIFF) {
    private val displayPreferences = Preferences.getPreferences(activity, "")
    private val details = HashMap<String, ChatListDetails>()
    // The generation each chat's details were last read in. Older details
    // keep showing until the fresh read lands, so a refresh (opening or
    // closing a folder) never blanks a row's bookmark indicator.
    private val detailsReadIn = HashMap<String, Int>()
    private val detailRequests = HashSet<String>()
    private val detailsGeneration = AtomicInteger(0)
    private var disposed = false
    private val memoryStates = ConcurrentHashMap<String, String>()
    private val metadataExecutor = Executors.newSingleThreadExecutor { Thread(it, "drawer-metadata") }

    init {
        setHasStableIds(true)
        if (displayPreferences.getShowMemoryStatusOnChatList()) loadMemoryStates()
    }

    private fun loadMemoryStates() {
        metadataExecutor.execute {
            try {
                if (MemoryStore.isProvisioned(activity)) {
                    memoryStates.putAll(MemoryStore.getInstance(activity).chatReviewStates())
                    activity.runOnUiThread { notifyDataSetChanged() }
                }
            } catch (_: Exception) { }
        }
    }

    /** The chat-list display options rows are drawn with. Rows whose chat data
     *  is unchanged are not rebound by a list update, so a change to one of
     *  these is applied by [refreshDisplayIfChanged]. */
    private fun displayOptions(): List<Any> = listOf(
        displayPreferences.getShowCompanionImagesInChatList(),
        displayPreferences.getShowModelNamesInChatList(),
        displayPreferences.getShowMemoryStatusOnChatList(),
        GlobalPreferences.getPreferences(activity).getProfileImageShape(),
        displayPreferences.getShowBookmarkIndicatorInChatList(),
        displayPreferences.getShowChatStartDateInChatList()
    )
    private var renderedDisplayOptions = displayOptions()

    fun refreshDisplayIfChanged() {
        val current = displayOptions()
        // A conversation can change without changing its drawer title or ordering.
        detailsGeneration.incrementAndGet()
        detailRequests.clear()
        if (current == renderedDisplayOptions) {
            if (needsDetails()) notifyDataSetChanged()
            return
        }
        val memoryStatusTurnedOn = displayPreferences.getShowMemoryStatusOnChatList() &&
            renderedDisplayOptions[2] == false
        renderedDisplayOptions = current
        details.clear()
        detailsReadIn.clear()
        notifyDataSetChanged()
        if (memoryStatusTurnedOn) loadMemoryStates()
    }
    private fun needsDetails() = displayPreferences.getShowBookmarkIndicatorInChatList() ||
        displayPreferences.getShowChatStartDateInChatList() || displayPreferences.getShowModelNamesInChatList()

    private fun requestDetails(chatId: String, startedAt: Long?) {
        val generation = detailsGeneration.get()
        if (disposed || !needsDetails() || detailsReadIn[chatId] == generation || !detailRequests.add(chatId)) return
        val includeModel = displayPreferences.getShowModelNamesInChatList()
        metadataExecutor.execute {
            if (generation != detailsGeneration.get()) return@execute
            val loaded = try {
                ChatListDetailsReader.read(activity.applicationContext, chatId, startedAt, includeModel)
            } catch (_: Exception) { ChatListDetails(false, null) }
            activity.runOnUiThread {
                if (disposed || generation != detailsGeneration.get()) return@runOnUiThread
                details[chatId] = loaded
                detailsReadIn[chatId] = generation
                currentList.forEachIndexed { position, row ->
                    if (row is DrawerRow.Chat && row.value.id == chatId) notifyItemChanged(position)
                }
            }
        }
    }

    interface Callbacks {
        fun onClick(row: DrawerRow)
        fun onLongClick(anchor: View, row: DrawerRow)
    }

    override fun getItemId(position: Int): Long = getItem(position).stableKey.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.view_flat_chat_row, parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val root: View = itemView.findViewById(R.id.flat_chat_root)
        private val title: TextView = itemView.findViewById(R.id.flat_chat_title)
        private val leading: ImageView = itemView.findViewById(R.id.flat_chat_leading_icon)
        private val chevron: ImageView = itemView.findViewById(R.id.flat_chat_chevron)
        private val snippet: TextView = itemView.findViewById(R.id.flat_chat_snippet)
        private val date: TextView = itemView.findViewById(R.id.flat_chat_date)
        private val identity = FlatChatIdentityBinder(activity, itemView, displayPreferences, memoryStates)
        private val baseStart = itemView.resources.getDimensionPixelSize(R.dimen.drawer_row_padding_start)
        private val nestedExtra = itemView.resources.getDimensionPixelSize(R.dimen.drawer_nested_indent)

        fun bind(row: DrawerRow) {
            identity.reset()
            snippet.visibility = View.GONE
            date.visibility = View.GONE
            chevron.visibility = View.GONE
            root.isEnabled = true
            root.isClickable = true
            root.updatePadding(left = baseStart, right = baseStart)
            title.setTextColor(MaterialColors.getColor(title, com.google.android.material.R.attr.colorOnSurface))
            title.setTypeface(null, android.graphics.Typeface.NORMAL)
            when (row) {
                DrawerRow.Gallery -> {
                    title.setText(R.string.image_gallery_title)
                    showLeading(R.drawable.ic_image)
                }
                is DrawerRow.FoldersHeader -> {
                    title.setText(R.string.drawer_folders)
                    showChevron(row.expanded)
                    title.setTypeface(null, android.graphics.Typeface.BOLD)
                }
                is DrawerRow.Folder -> {
                    title.text = row.value.name
                    showChevron(row.expanded)
                    if (row.value.pinned) showLeading(R.drawable.ic_folder_special)
                }
                is DrawerRow.Section -> {
                    title.text = row.title
                    title.setTypeface(null, android.graphics.Typeface.BOLD)
                    root.isClickable = false
                    root.isEnabled = false
                }
                is DrawerRow.Chat -> {
                    val displayTitle = if (row.value.name.contains("_autoname_")) {
                        itemView.context.getString(R.string.label_untitled_chat)
                    } else row.value.name
                    identity.bind(
                        row.value.id,
                        displayTitle,
                        pinned = row.value.pinned,
                        current = row.value.id == currentChatId(),
                        details = details[row.value.id] ?: ChatListDetails(false, row.value.startedAt)
                    )
                    requestDetails(row.value.id, row.value.startedAt)
                    if (row.nested) root.updatePadding(left = baseStart + nestedExtra, right = baseStart)
                }
            }
            root.setOnClickListener { callbacks.onClick(row) }
            root.setOnLongClickListener {
                callbacks.onLongClick(root, row)
                true
            }
        }

        private fun showLeading(drawable: Int) {
            leading.visibility = View.VISIBLE
            leading.setImageResource(drawable)
            leading.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(leading, androidx.appcompat.R.attr.colorPrimary)
            )
        }

        private fun showChevron(expanded: Boolean) {
            chevron.visibility = View.VISIBLE
            chevron.setImageResource(if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right)
            chevron.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(chevron, com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DrawerRow>() {
            override fun areItemsTheSame(oldItem: DrawerRow, newItem: DrawerRow) =
                oldItem.stableKey == newItem.stableKey
            override fun areContentsTheSame(oldItem: DrawerRow, newItem: DrawerRow) = oldItem == newItem
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        disposed = true
        detailsGeneration.incrementAndGet()
        metadataExecutor.shutdownNow()
        super.onDetachedFromRecyclerView(recyclerView)
    }
}
