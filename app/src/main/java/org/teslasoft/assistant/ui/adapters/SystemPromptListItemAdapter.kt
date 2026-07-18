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
import androidx.core.content.ContextCompat
import com.google.android.material.elevation.SurfaceColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.dto.SystemPromptObject

/**
 * Two distinct row looks depending on why this list is open (owner ruling,
 * July 18 2026):
 *  - Manager mode (browsing/editing, from AI System Settings): a plain
 *    title-only row with a chevron, since tapping opens the editor.
 *  - Pick mode (choosing a prompt for a chat, from Quick Settings): the
 *    existing "checked tile" scheme, unchanged — the same highlight-on-select
 *    look the activation list uses. There's nothing to "open" here, tapping
 *    selects, so no chevron.
 * Each mode has its own item layout; mode is fixed for the adapter's whole
 * lifetime (set once from the activity's intent extra), so the two never mix
 * within one instance's recycled views.
 */
class SystemPromptListItemAdapter(
    private val dataArray: ArrayList<SystemPromptObject>,
    private val mContext: Context,
    private val pickMode: Boolean
) : BaseAdapter() {

    override fun getCount(): Int = dataArray.size

    override fun getItem(position: Int): Any = dataArray[position]

    override fun getItemId(position: Int): Long = position.toLong()

    private var listener: OnSelectListener? = null

    // Id of the prompt active for the chat, drawn inverted. Only used in pick mode.
    private var selectedId: String = ""

    fun setOnSelectListener(listener: OnSelectListener) {
        this.listener = listener
    }

    fun setSelectedId(id: String) {
        this.selectedId = id
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val inflater = mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

        val layoutRes = if (pickMode) R.layout.view_system_prompt_item else R.layout.view_system_prompt_item_row
        val mView: View = convertView ?: inflater.inflate(layoutRes, parent, false)

        val ui: View? = mView.findViewById(R.id.ui)
        val title: TextView? = mView.findViewById(R.id.system_prompt_title)

        val item = dataArray[position]
        title?.text = item.title

        if (pickMode) {
            // Recycled views must have both states set explicitly every bind.
            val isSelected = selectedId.isNotEmpty() && item.id == selectedId
            if (isSelected) {
                ui?.backgroundTintList = null
                ui?.background = ContextCompat.getDrawable(mContext, R.drawable.tile_active)
                title?.setTextColor(mContext.getColor(R.color.text_title_inv))
            } else {
                ui?.background = ContextCompat.getDrawable(mContext, R.drawable.btn_accent_tonal_selector_v3)
                ui?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_2.getColor(mContext))
                title?.setTextColor(mContext.getColor(R.color.accent_900))
            }
        } else {
            mView.findViewById<View>(R.id.system_prompt_chevron)?.contentDescription = item.title
        }

        ui?.setOnClickListener {
            listener?.onClick(position)
        }

        ui?.setOnLongClickListener {
            listener?.onLongClick(position)
            return@setOnLongClickListener true
        }

        return mView
    }

    interface OnSelectListener {
        fun onClick(position: Int)
        fun onLongClick(position: Int)
    }
}
