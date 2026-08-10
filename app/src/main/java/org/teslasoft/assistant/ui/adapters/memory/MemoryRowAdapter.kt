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
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.color.MaterialColors
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
    /** Associative Memory Pending card (Step 1.5): render the dedicated pending
     *  card — full proposed memory, top-left caution (only with matches),
     *  top-right info, bottom-right save/discard or Review. [subtitle] carries
     *  the FULL content for this card (not the one-line preview). The per-draft
     *  match state is fetched lazily from the host via [OnRowListener]. */
    val pendingCard: Boolean = false,
    /** Profile Images (phase 8): the identity's assigned image hash, or null.
     *  Only the profile-image row adapter (My Personas / Roleplay Characters)
     *  reads it; the default MemoryRowAdapter ignores it. */
    val imageRef: String? = null,
    /** Use the Memory pending-card warning/error treatment for [iconRes]. */
    val iconTintError: Boolean = false
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

        /* Associative Memory Pending card actions (Step 1.5). Default no-ops so
         * screens without a pending card ignore them. */

        /** The current Possible Match state for a pending card, fetched lazily
         *  at bind. The host computes it per suggestion off-thread and caches it
         *  for the session; returning [MATCH_LOADING] tells the card to show the
         *  narrow spinner. Default [MATCH_NONE]. */
        fun pendingMatchState(row: MemoryRow): Int = MemoryRowAdapter.MATCH_NONE

        /** Info control tapped (source / provenance / evidence). */
        fun onInfo(row: MemoryRow) {}

        /** Save/disk icon tapped on a conflict-free suggestion (approve). */
        fun onSave(row: MemoryRow) {}

        /** Discard X tapped on a conflict-free suggestion. */
        fun onDiscard(row: MemoryRow) {}

        /** Review tapped on a suggestion with possible matches. */
        fun onReview(row: MemoryRow) {}

        /** Retry tapped after a comparison failure. */
        fun onRetry(row: MemoryRow) {}
    }

    companion object {
        const val ACTION_ACCEPT = "accept"
        const val ACTION_DELETE = "delete"
        const val ACTION_EDIT = "edit"
        const val ACTION_ADD_TO_CARD = "add_to_card"

        /** Possible Match states for a pending card (Step 1.5). */
        const val MATCH_LOADING = 0   // comparison running — show the spinner only
        const val MATCH_NONE = 1      // no possible match — save / discard
        const val MATCH_CONFLICT = 2  // one or more possible matches — Review
        const val MATCH_FAILED = 3    // comparison failed — never read as "none"; offer Retry
    }

    private var listener: OnRowListener? = null

    fun setOnRowListener(l: OnRowListener) { listener = l }

    override fun getCount(): Int = rows.size
    override fun getItem(position: Int): Any = rows[position]
    override fun getItemId(position: Int): Long = position.toLong()

    // Distinct view types keep ListView recycling from handing one layout's
    // view to a different bind: 0 normal row, 1 header, 2 pending card.
    override fun getViewTypeCount(): Int = 3
    override fun getItemViewType(position: Int): Int = when {
        rows[position].isHeader -> 1
        rows[position].pendingCard -> 2
        else -> 0
    }
    override fun isEnabled(position: Int): Boolean = !rows[position].isHeader

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        if (rows[position].isHeader) {
            val header = convertView
                ?: LayoutInflater.from(context).inflate(R.layout.view_memory_section_header, parent, false)
            header.findViewById<TextView>(R.id.row_header).text = rows[position].title
            return header
        }

        if (rows[position].pendingCard) {
            return bindPendingCard(rows[position], convertView, parent)
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
            icon.contentDescription = context.getString(
                if (row.iconTintError) R.string.model_cleanup_unavailable
                else R.string.mem_row_icon
            )
            icon.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(
                    icon,
                    if (row.iconTintError) com.google.android.material.R.attr.colorError
                    else com.google.android.material.R.attr.colorPrimary
                )
            )
        } else {
            icon.visibility = View.GONE
            icon.imageTintList = null
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

    /**
     * Bind an Associative Memory Pending card (Step 1.5). The full proposed
     * memory is shown; the Possible Match state is read lazily from the host at
     * bind and drives which controls appear:
     *   LOADING  → spinner only; no save/discard/Review (never "conflict-free"
     *              while the check runs);
     *   NONE     → discard X + save;
     *   CONFLICT → caution icon + Review;
     *   FAILED   → Retry (a failure is never silently treated as "no match").
     * The Info control is present in every state. State is keyed by the draft
     * id in the host, so a recycled view is always re-bound from current data.
     */
    private fun bindPendingCard(row: MemoryRow, convertView: View?, parent: ViewGroup?): View {
        val view = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.view_memory_pending_card, parent, false)

        view.findViewById<ImageView>(R.id.pending_icon).apply {
            if (row.iconRes != null) { visibility = View.VISIBLE; setImageResource(row.iconRes) }
            else visibility = View.GONE
        }
        view.findViewById<TextView>(R.id.pending_title).text = row.title
        view.findViewById<TextView>(R.id.pending_tags).apply {
            if (row.tagsLine.isNullOrBlank()) visibility = View.GONE
            else { visibility = View.VISIBLE; text = row.tagsLine }
        }
        view.findViewById<TextView>(R.id.pending_content).apply {
            if (row.subtitle.isNullOrBlank()) visibility = View.GONE
            else { visibility = View.VISIBLE; text = row.subtitle }
        }

        val caution = view.findViewById<ImageView>(R.id.pending_caution)
        val loading = view.findViewById<View>(R.id.pending_loading)
        val info = view.findViewById<ImageButton>(R.id.pending_info)
        val discard = view.findViewById<ImageButton>(R.id.pending_discard)
        val save = view.findViewById<ImageButton>(R.id.pending_save)
        val review = view.findViewById<View>(R.id.pending_review)
        val retry = view.findViewById<View>(R.id.pending_retry)

        info.setOnClickListener { listener?.onInfo(row) }

        // Reset every control on the recycled view, then show only this state's.
        caution.visibility = View.GONE
        loading.visibility = View.GONE
        discard.visibility = View.GONE
        save.visibility = View.GONE
        review.visibility = View.GONE
        retry.visibility = View.GONE

        when (listener?.pendingMatchState(row) ?: MATCH_NONE) {
            MATCH_LOADING -> loading.visibility = View.VISIBLE
            MATCH_CONFLICT -> {
                caution.visibility = View.VISIBLE
                review.visibility = View.VISIBLE
                review.setOnClickListener { listener?.onReview(row) }
            }
            MATCH_FAILED -> {
                retry.visibility = View.VISIBLE
                retry.setOnClickListener { listener?.onRetry(row) }
            }
            else -> { // MATCH_NONE
                discard.visibility = View.VISIBLE
                save.visibility = View.VISIBLE
                discard.setOnClickListener { listener?.onDiscard(row) }
                save.setOnClickListener { listener?.onSave(row) }
            }
        }
        return view
    }
}
