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
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.MemoryStore
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap

class DrawerHierarchyAdapter(
    private val activity: FragmentActivity,
    private val currentChatId: () -> String,
    private val callbacks: Callbacks
) : ListAdapter<DrawerRow, DrawerHierarchyAdapter.Holder>(DIFF) {
    private val displayPreferences = Preferences.getPreferences(activity, "")
    private val memoryStates = ConcurrentHashMap<String, String>()
    private val metadataExecutor = Executors.newSingleThreadExecutor { Thread(it, "drawer-metadata") }

    init {
        setHasStableIds(true)
        if (displayPreferences.getShowMemoryStatusOnChatList()) {
            metadataExecutor.execute {
                try {
                    if (MemoryStore.isProvisioned(activity)) {
                        memoryStates.putAll(MemoryStore.getInstance(activity).chatReviewStates())
                        activity.runOnUiThread { notifyDataSetChanged() }
                    }
                } catch (_: Exception) { }
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
                        current = row.value.id == currentChatId()
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
        metadataExecutor.shutdownNow()
        super.onDetachedFromRecyclerView(recyclerView)
    }
}
