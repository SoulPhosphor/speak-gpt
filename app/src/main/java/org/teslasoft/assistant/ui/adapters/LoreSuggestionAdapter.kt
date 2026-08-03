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

package org.teslasoft.assistant.ui.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.LorebookSuggestionRecord

/**
 * Lists pending Lorebook Memory suggestions in the Lorebooks Pending area (Step
 * 1.7). Each row shows the proposed entry text and its trigger keywords (no
 * separate title), an Assign Lorebook control showing the chosen destination
 * (or the "Assign Lorebook" prompt when none is chosen yet), and Edit /
 * Approve / Delete. The adapter renders only; every action is handled by the
 * host activity through [Listener]. [bookNames] maps a lore book id to its
 * display name so an assigned row can show the book it will be written to.
 */
class LoreSuggestionAdapter(
    private val data: List<LorebookSuggestionRecord>,
    private val bookNames: Map<String, String>,
    private val context: Context
) : BaseAdapter() {

    interface Listener {
        fun onAssign(position: Int)
        fun onEdit(position: Int)
        fun onApprove(position: Int)
        fun onDelete(position: Int)
    }

    private var listener: Listener? = null

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    override fun getCount(): Int = data.size
    override fun getItem(position: Int): Any = data[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = convertView ?: inflater.inflate(R.layout.view_lore_suggestion, parent, false)

        val item = data[position]

        view.findViewById<TextView>(R.id.suggestion_content)?.text = item.content
        view.findViewById<TextView>(R.id.suggestion_triggers)?.text =
            context.getString(R.string.lore_suggestion_triggers, item.triggers.joinToString(", "))

        val assign = view.findViewById<TextView>(R.id.suggestion_assign)
        val bookName = item.assignedLorebookId?.let { bookNames[it] }
        assign?.text = bookName ?: context.getString(R.string.lore_suggestion_assign)
        assign?.setOnClickListener { listener?.onAssign(position) }

        view.findViewById<MaterialButton>(R.id.btn_edit)?.setOnClickListener { listener?.onEdit(position) }
        view.findViewById<MaterialButton>(R.id.btn_approve)?.setOnClickListener { listener?.onApprove(position) }
        view.findViewById<MaterialButton>(R.id.btn_delete)?.setOnClickListener { listener?.onDelete(position) }

        return view
    }
}
