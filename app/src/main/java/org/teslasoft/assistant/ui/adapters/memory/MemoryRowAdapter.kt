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
import android.widget.TextView
import org.teslasoft.assistant.R

/**
 * One list item across the whole memory manager: a stable [id], a [title], and
 * optional [subtitle]/[badge]. [hasAction] controls the trailing overflow
 * button (edit/protect/archive/delete menu for records that have one). Kept
 * behaviour-free — the hosting [org.teslasoft.assistant.ui.activities.memory.MemoryScreenActivity]
 * decides what a tap or an action press does.
 */
data class MemoryRow(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val badge: String? = null,
    val hasAction: Boolean = false
)

class MemoryRowAdapter(
    private val rows: List<MemoryRow>,
    private val context: Context
) : BaseAdapter() {

    interface OnRowListener {
        fun onClick(row: MemoryRow)
        fun onAction(row: MemoryRow, anchor: View)
    }

    private var listener: OnRowListener? = null

    fun setOnRowListener(l: OnRowListener) { listener = l }

    override fun getCount(): Int = rows.size
    override fun getItem(position: Int): Any = rows[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.view_memory_row, parent, false)

        val title = view.findViewById<TextView>(R.id.row_title)
        val subtitle = view.findViewById<TextView>(R.id.row_subtitle)
        val badge = view.findViewById<TextView>(R.id.row_badge)
        val action = view.findViewById<ImageButton>(R.id.btn_row_action)
        val ui = view.findViewById<View>(R.id.ui)

        val row = rows[position]
        title.text = row.title

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

        ui.setOnClickListener { listener?.onClick(row) }
        ui.setOnLongClickListener {
            if (row.hasAction) { listener?.onAction(row, it); true } else false
        }

        return view
    }
}
