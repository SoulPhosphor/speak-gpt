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
import org.teslasoft.assistant.preferences.chatnavigation.ChatListIndex
import org.teslasoft.assistant.preferences.memory.MemoryStore
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap

class DrawerHierarchyAdapter(
    private val activity: FragmentActivity,
    private val currentChatId: () -> String,
    private val callbacks: Callbacks,
    private val dispatchUpdate: (() -> Unit) -> Unit = { it() }
) : ListAdapter<DrawerRow, DrawerHierarchyAdapter.Holder>(DIFF) {
    private val displayPreferences = Preferences.getPreferences(activity, "")
    private val companionImages = CompanionImageLookup(activity)
    private var disposed = false
    private var folderPlaceholderHeight: Int? = null
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
                    activity.runOnUiThread { dispatchUpdate { if (!disposed) notifyDataSetChanged() } }
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

    /** Worker thread. Looks up the companion pictures [chats] will show and
     *  redraws rows whose companion's picture changed. */
    fun prepareCompanionImages(chats: Collection<org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationItem>) {
        if (!displayPreferences.getShowCompanionImagesInChatList()) return
        val changed = companionImages.prepare(chats.mapNotNull { it.listIndex?.companionId })
        if (changed.isEmpty()) return
        activity.runOnUiThread {
            dispatchUpdate {
                if (!disposed) currentList.forEachIndexed { position, row ->
                    if (row is DrawerRow.Chat && row.value.listIndex?.companionId in changed) notifyItemChanged(position)
                }
            }
        }
    }

    fun refreshDisplayIfChanged() {
        val current = displayOptions()
        if (current == renderedDisplayOptions) return
        val memoryStatusTurnedOn = displayPreferences.getShowMemoryStatusOnChatList() &&
            renderedDisplayOptions[2] == false
        renderedDisplayOptions = current
        notifyDataSetChanged()
        if (memoryStatusTurnedOn) loadMemoryStates()
    }

    interface Callbacks {
        fun onClick(row: DrawerRow)
        fun onLongClick(anchor: View, row: DrawerRow)
    }

    override fun getItemId(position: Int): Long = getItem(position).stableKey.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.view_flat_chat_row, parent, false)
    )

    /** Reserve the locked container's actual height in the main list. This
     * lets the first main chat scroll fully below it, even in a short list. */
    fun setFolderPlaceholderHeight(height: Int?) {
        if (folderPlaceholderHeight == height) return
        folderPlaceholderHeight = height
        val index = currentList.indexOfFirst { it is DrawerRow.FoldersHeader }
        if (index >= 0) notifyItemChanged(index)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val placeholder = row is DrawerRow.FoldersHeader && folderPlaceholderHeight != null
        holder.itemView.visibility = if (placeholder) View.INVISIBLE else View.VISIBLE
        val height = if (placeholder) folderPlaceholderHeight!! else ViewGroup.LayoutParams.WRAP_CONTENT
        if (holder.itemView.layoutParams.height != height) {
            holder.itemView.layoutParams = holder.itemView.layoutParams.apply { this.height = height }
        }
        if (!placeholder) holder.bind(row)
    }

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val root: View = itemView.findViewById(R.id.flat_chat_root)
        private val title: TextView = itemView.findViewById(R.id.flat_chat_title)
        private val leading: ImageView = itemView.findViewById(R.id.flat_chat_leading_icon)
        private val chevron: ImageView = itemView.findViewById(R.id.flat_chat_chevron)
        private val snippet: TextView = itemView.findViewById(R.id.flat_chat_snippet)
        private val date: TextView = itemView.findViewById(R.id.flat_chat_date)
        private val identity = FlatChatIdentityBinder(activity, itemView, displayPreferences, memoryStates, companionImages)
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
                        index = row.value.listIndex,
                        startedAt = row.value.startedAt ?: row.value.listIndex?.startedAt
                    )
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
        metadataExecutor.shutdownNow()
        super.onDetachedFromRecyclerView(recyclerView)
    }
}
