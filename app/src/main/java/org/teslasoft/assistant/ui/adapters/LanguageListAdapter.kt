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
import androidx.core.content.ContextCompat
import org.teslasoft.assistant.R

/**
 * Rows for the full-screen Voice Language picker. Mirrors the "checked
 * tile" look used by the Select AI Model list, but resolves both selection
 * states through the shared Widget.App.PickList.Row family (see
 * themes.xml) instead of a runtime color tint, so the highlight follows
 * the active theme/palette rather than a hard-coded color.
 */
class LanguageListAdapter(
    private val context: Context,
    private val items: List<Pair<String, String>>,
    private var selectedCode: String
) : BaseAdapter() {

    private var listener: OnItemClickListener? = null

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Any = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = (convertView as? TextView)
            ?: (LayoutInflater.from(context).inflate(R.layout.view_language_pick_item, parent, false) as TextView)

        val (code, label) = items[position]
        view.text = label

        if (code == selectedCode) {
            view.background = ContextCompat.getDrawable(context, R.drawable.btn_accent_tonal_selector_v4)
            view.setTextAppearance(R.style.TextAppearance_App_PickList_Selected)
        } else {
            view.background = ContextCompat.getDrawable(context, R.drawable.btn_accent_tonal_selector_v3)
            view.setTextAppearance(R.style.TextAppearance_App_PickList_Unselected)
        }

        view.setOnClickListener { listener?.onItemClick(code) }

        return view
    }

    fun setSelected(code: String) {
        selectedCode = code
        notifyDataSetChanged()
    }

    interface OnItemClickListener {
        fun onItemClick(code: String)
    }

    fun setOnItemClickListener(listener: OnItemClickListener) {
        this.listener = listener
    }
}
