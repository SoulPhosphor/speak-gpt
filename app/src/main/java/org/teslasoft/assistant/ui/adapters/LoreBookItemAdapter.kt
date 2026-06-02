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
import android.widget.ImageButton
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.elevation.SurfaceColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.dto.LoreBookEntry

class LoreBookItemAdapter(private val dataArray: ArrayList<LoreBookEntry>, private var mContext: Context) : BaseAdapter() {
    override fun getCount(): Int {
        return dataArray.size
    }

    override fun getItem(position: Int): Any {
        return dataArray[position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    private var listener: OnSelectListener? = null

    fun setOnSelectListener(listener: OnSelectListener) {
        this.listener = listener
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val inflater = mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

        var mView: View? = convertView

        if (mView == null) {
            mView = inflater.inflate(R.layout.view_lorebook_item, parent, false)
        }

        val ui: ConstraintLayout? = mView?.findViewById(R.id.ui)
        val memoryLabel: TextView? = mView?.findViewById(R.id.memory_label)
        val memoryContent: TextView? = mView?.findViewById(R.id.memory_content)
        val memoryTriggers: TextView? = mView?.findViewById(R.id.memory_triggers)
        val btnEdit: ImageButton? = mView?.findViewById(R.id.btn_edit_memory)

        val item = dataArray[position]

        // Disabled memories get a "(Disabled)" suffix so the state is visible at a glance.
        memoryLabel?.text = if (item.enabled) {
            item.label
        } else {
            mContext.getString(R.string.label_memory_disabled).let { "${item.label} ($it)" }
        }

        memoryContent?.text = item.content

        memoryTriggers?.text = if (item.triggers.isEmpty()) {
            mContext.getString(R.string.label_no_triggers)
        } else {
            item.triggers.joinToString("  •  ")
        }

        // Recycled views must have their look reset every bind. Disabled rows are dimmed.
        ui?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_2.getColor(mContext))
        ui?.alpha = if (item.enabled) 1.0f else 0.5f

        ui?.setOnClickListener {
            listener?.onClick(position)
        }

        ui?.setOnLongClickListener {
            listener?.onClick(position)
            return@setOnLongClickListener true
        }

        btnEdit?.setOnClickListener {
            listener?.onClick(position)
        }

        return mView!!
    }

    interface OnSelectListener {
        fun onClick(position: Int)
    }
}
