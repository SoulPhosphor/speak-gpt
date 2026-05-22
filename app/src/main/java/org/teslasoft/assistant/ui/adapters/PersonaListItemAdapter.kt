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
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.elevation.SurfaceColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.dto.PersonaObject

class PersonaListItemAdapter(
    private val dataArray: ArrayList<PersonaObject>,
    private var mContext: Context,
    private var activeId: String
) : BaseAdapter() {

    override fun getCount(): Int = dataArray.size
    override fun getItem(position: Int): Any = dataArray[position]
    override fun getItemId(position: Int): Long = position.toLong()

    private var listener: OnSelectListener? = null

    fun setOnSelectListener(listener: OnSelectListener) {
        this.listener = listener
    }

    fun setActiveId(id: String) {
        this.activeId = id
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val inflater = mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = convertView ?: inflater.inflate(R.layout.view_persona_item, parent, false)

        val ui = view.findViewById<ConstraintLayout>(R.id.ui)
        val nameView = view.findViewById<TextView>(R.id.persona_name)
        val previewView = view.findViewById<TextView>(R.id.persona_preview)
        val activeBadge = view.findViewById<TextView>(R.id.persona_active_badge)

        ui?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_2.getColor(mContext))

        val item = dataArray[position]
        nameView?.text = item.name
        previewView?.text = if (item.systemPrompt.isBlank()) mContext.getString(R.string.persona_empty_prompt_preview) else item.systemPrompt
        activeBadge?.visibility = if (item.id == activeId) View.VISIBLE else View.GONE

        ui?.setOnClickListener { listener?.onClick(position) }
        ui?.setOnLongClickListener {
            listener?.onLongClick(position)
            true
        }

        return view
    }

    interface OnSelectListener {
        fun onClick(position: Int)
        fun onLongClick(position: Int)
    }
}
