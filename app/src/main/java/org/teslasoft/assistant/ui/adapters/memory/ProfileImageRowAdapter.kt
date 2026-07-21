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
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.util.ProfileImageBinder
import org.teslasoft.assistant.util.ProfileImageResolver

/**
 * A memory-list adapter that renders rows in the shared house row style with a
 * leading profile picture (owner ruling, July 21 2026 - My Personas and
 * Roleplay Characters rows). Reuses [MemoryRow] and the same
 * [MemoryRowAdapter.OnRowListener] contract as the default adapter, so the
 * hosting [org.teslasoft.assistant.ui.activities.memory.MemoryScreenActivity]
 * wires clicks/actions identically - only the row look changes.
 *
 * Both screens this serves are user-side identities, so the picture resolves
 * through the user-side cascade (own picture -> Default Personal Avatar ->
 * generic person icon) and its screen-reader label follows the same
 * owner-approved scheme as everywhere else.
 *
 * @param rowLayoutRes the per-row layout - view_user_persona_row (with a
 *   subtitle) or view_roleplay_character_row (title only). It must contain
 *   `persona_avatar` (ImageView) + `persona_label` (title TextView) + a
 *   `persona_menu` chevron; the subtitle layout also has `persona_subtitle`.
 */
class ProfileImageRowAdapter(
    private val rows: List<MemoryRow>,
    private val context: Context,
    private val rowLayoutRes: Int
) : BaseAdapter() {

    private var listener: MemoryRowAdapter.OnRowListener? = null

    fun setOnRowListener(l: MemoryRowAdapter.OnRowListener) { listener = l }

    override fun getCount(): Int = rows.size
    override fun getItem(position: Int): Any = rows[position]
    override fun getItemId(position: Int): Long = position.toLong()

    // Headers (Archive sections) use their own layout; two view types keep
    // ListView recycling from mixing a header view with a normal row.
    override fun getViewTypeCount(): Int = 2
    override fun getItemViewType(position: Int): Int = if (rows[position].isHeader) 1 else 0
    override fun isEnabled(position: Int): Boolean = !rows[position].isHeader

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val row = rows[position]

        if (row.isHeader) {
            val header = convertView
                ?: LayoutInflater.from(context).inflate(R.layout.view_memory_section_header, parent, false)
            header.findViewById<TextView>(R.id.row_header).text = row.title
            return header
        }

        val view = convertView ?: LayoutInflater.from(context).inflate(rowLayoutRes, parent, false)

        val avatar = view.findViewById<ImageView>(R.id.persona_avatar)
        val title = view.findViewById<TextView>(R.id.persona_label)
        val subtitle = view.findViewById<TextView?>(R.id.persona_subtitle)
        val menu = view.findViewById<View>(R.id.persona_menu)
        val ui = view.findViewById<View>(R.id.ui)

        title.text = row.title

        // Subtitle only exists in the with-subtitle layout; hide it when blank
        // rather than showing an empty line (no fake subtitles - style rule).
        if (subtitle != null) {
            if (row.subtitle.isNullOrBlank()) {
                subtitle.visibility = View.GONE
            } else {
                subtitle.visibility = View.VISIBLE
                subtitle.text = row.subtitle
            }
        }

        // User-side cascade: own picture -> Default Personal Avatar -> person
        // icon. Bound through the shared binder so a recycled row can't keep a
        // previous identity's picture or tint.
        val file = ProfileImageResolver.resolveUserImageFile(context, row.imageRef)
        val shape = GlobalPreferences.getPreferences(context).getProfileImageShape()
        ProfileImageBinder.bind(context, avatar, file, shape) { iv ->
            iv.setImageResource(R.drawable.ic_user)
            iv.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(iv.context, R.color.accent_900))
        }
        avatar.contentDescription = ProfileImageResolver.userContentDescription(context, row.title, row.imageRef)

        // Preserve the memory list's interaction model: tapping the row opens
        // the editor (onClick); the trailing chevron and a long-press open the
        // row menu (onAction).
        if (row.hasAction) {
            menu.visibility = View.VISIBLE
            menu.setOnClickListener { listener?.onAction(row, it) }
        } else {
            menu.visibility = View.GONE
            menu.setOnClickListener(null)
        }

        ui.setOnClickListener { listener?.onClick(row) }
        ui.setOnLongClickListener {
            if (row.hasAction) { listener?.onAction(row, it); true } else false
        }

        return view
    }
}
