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

package org.teslasoft.assistant.ui.adapters.memory

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import org.teslasoft.assistant.R

/**
 * One list item across the whole memory manager.
 *
 *  - [id] stable identifier (memory id, companion id, world id, …).
 *  - [title] the strong first line.
 *  - [tagsLine] optional "Communication · Technical Help · Tone"-style joined
 *    tag row (owner ruling, July 8 2026: no hashtags). The hosting screen
 *    supplies it already-formatted; the adapter never edits tag text.
 *  - [subtitle] optional body/content line.
 *  - [badge] optional pill badge (draft / archived / superseded — the row
 *    intentionally shows nothing when a memory is Active).
 *  - [iconRes] optional leading identity icon (see ic_mem_* drawables).
 *  - [hasAction] shows the trailing edit-square action button.
 *  - [isHeader] non-tappable section header (Archive sections on card lists).
 */
data class MemoryRow(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val badge: String? = null,
    val hasAction: Boolean = false,
    val isHeader: Boolean = false,
    val tagsLine: String? = null,
    val iconRes: Int? = null,
    /** Pending view (owner design, July 8 2026 evening): show the bold
     *  Accept / Delete / Edit action words across the row's bottom. */
    val pendingActions: Boolean = false,
    /** Roleplay pending rows also get Add to Card. */
    val showAddToCard: Boolean = false,
    /** §7 outline treatment: an unactioned Memory Assistant card-placement
     *  suggestion is waiting on this row. */
    val outlined: Boolean = false,
    /** Persistent inline note (owner design, July 9 2026): e.g. "Needs
     *  roleplay target." on an untargeted roleplay draft. */
    val noteLine: String? = null,
    /** Profile Images (phase 8): the identity's assigned image hash, or null.
     *  Only the profile-image row adapter (My Personas / Roleplay Characters)
     *  reads it; the default MemoryRowAdapter ignores it. */
    val imageRef: String? = null
)

class MemoryRowAdapter(
    private val rows: List<MemoryRow>,
    private val context: Context
) : BaseAdapter() {

    interface OnRowListener {
        fun onClick(row: MemoryRow)
        fun onAction(row: MemoryRow, anchor: View)

        /** A pending action word was tapped: one of [ACTION_ACCEPT],
         *  [ACTION_DELETE], [ACTION_EDIT], [ACTION_ADD_TO_CARD]. Default
         *  no-op so screens without a pending view ignore it. */
        fun onPendingAction(row: MemoryRow, action: String) {}

        /** The trailing chevron edit target was tapped in a profile-image row's
         *  pick mode (My Personas from Quick Settings): open the editor without
         *  selecting. Default no-op so other screens ignore it. */
        fun onEditClick(row: MemoryRow) {}
    }

    companion object {
        const val ACTION_ACCEPT = "accept"
        const val ACTION_DELETE = "delete"
        const val ACTION_EDIT = "edit"
        const val ACTION_ADD_TO_CARD = "add_to_card"
    }

    private var listener: OnRowListener? = null

    fun setOnRowListener(l: OnRowListener) { listener = l }

    override fun getCount(): Int = rows.size
    override fun getItem(position: Int): Any = rows[position]
    override fun getItemId(position: Int): Long = position.toLong()

    // Headers use their own layout; two view types keep ListView recycling
    // from handing a header view to a normal row bind (and vice versa).
    override fun getViewTypeCount(): Int = 2
    override fun getItemViewType(position: Int): Int = if (rows[position].isHeader) 1 else 0
    override fun isEnabled(position: Int): Boolean = !rows[position].isHeader

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        if (rows[position].isHeader) {
            val header = convertView
                ?: LayoutInflater.from(context).inflate(R.layout.view_memory_section_header, parent, false)
            header.findViewById<TextView>(R.id.row_header).text = rows[position].title
            return header
        }

        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.view_memory_row, parent, false)

        val title = view.findViewById<TextView>(R.id.row_title)
        val tags = view.findViewById<TextView>(R.id.row_tags)
        val subtitle = view.findViewById<TextView>(R.id.row_subtitle)
        val badge = view.findViewById<TextView>(R.id.row_badge)
        val icon = view.findViewById<ImageView>(R.id.row_icon)
        val action = view.findViewById<ImageButton>(R.id.btn_row_action)
        val ui = view.findViewById<View>(R.id.ui)

        val row = rows[position]
        title.text = row.title

        if (row.iconRes != null) {
            icon.visibility = View.VISIBLE
            icon.setImageResource(row.iconRes)
        } else {
            icon.visibility = View.GONE
        }

        if (row.tagsLine.isNullOrBlank()) {
            tags.visibility = View.GONE
        } else {
            tags.visibility = View.VISIBLE
            tags.text = row.tagsLine
        }

        if (row.subtitle.isNullOrBlank()) {
            subtitle.visibility = View.GONE
        } else {
            subtitle.visibility = View.VISIBLE
            subtitle.text = row.subtitle
        }

        if (row.badge.isNullOrBlank()) {
            badge.visibility = View.GONE
        } else {
            badge.visibility = View.VISIBLE
            badge.text = row.badge
        }

        if (row.hasAction) {
            action.visibility = View.VISIBLE
            action.setOnClickListener { listener?.onAction(row, it) }
        } else {
            action.visibility = View.GONE
            action.setOnClickListener(null)
        }

        val note = view.findViewById<TextView>(R.id.row_note)
        if (row.noteLine.isNullOrBlank()) {
            note.visibility = View.GONE
        } else {
            note.visibility = View.VISIBLE
            note.text = row.noteLine
        }

        // §7: the outline marks "a card placement is waiting on your
        // decision"; it drops once the draft is accepted or deleted (the
        // suggestion clears with the status change). Set on the recycled view
        // every bind so old outlines never linger.
        ui.foreground = if (row.outlined)
            androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_suggestion_outline)
        else null

        val pendingStrip = view.findViewById<View>(R.id.row_pending_actions)
        if (row.pendingActions) {
            pendingStrip.visibility = View.VISIBLE
            view.findViewById<TextView>(R.id.action_accept)
                .setOnClickListener { listener?.onPendingAction(row, ACTION_ACCEPT) }
            view.findViewById<TextView>(R.id.action_delete)
                .setOnClickListener { listener?.onPendingAction(row, ACTION_DELETE) }
            view.findViewById<TextView>(R.id.action_edit)
                .setOnClickListener { listener?.onPendingAction(row, ACTION_EDIT) }
            val addCard = view.findViewById<TextView>(R.id.action_add_card)
            addCard.visibility = if (row.showAddToCard) View.VISIBLE else View.GONE
            addCard.setOnClickListener { listener?.onPendingAction(row, ACTION_ADD_TO_CARD) }
        } else {
            pendingStrip.visibility = View.GONE
        }

        ui.setOnClickListener { listener?.onClick(row) }
        ui.setOnLongClickListener {
            if (row.hasAction) { listener?.onAction(row, it); true } else false
        }

        return view
    }
}
