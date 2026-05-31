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
import androidx.core.content.ContextCompat
import com.google.android.material.elevation.SurfaceColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.util.Hash

class ActivationPromptListItemAdapter(private val dataArray: ArrayList<HashMap<String, String>>, private var mContext: Context) : BaseAdapter() {
    override fun getCount(): Int {
        return dataArray.size
    }

    override fun getItem(position: Int): Any {
        return dataArray[position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    private var ui: ConstraintLayout? = null
    private var activationLabel: TextView? = null
    private var activationPrompt: TextView? = null
    private var btnEdit: ImageButton? = null

    private var listener: OnSelectListener? = null

    // Id of the activation prompt active for the chat, drawn inverted (same
    // scheme as a checked tile in Settings). Empty = none active / manager mode.
    private var selectedId: String = ""

    fun setOnSelectListener(listener: OnSelectListener) {
        this.listener = listener
    }

    fun setSelectedId(id: String) {
        this.selectedId = id
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val inflater = mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

        var mView: View? = convertView

        if (mView == null) {
            mView = inflater.inflate(R.layout.view_activation_prompt_item, parent, false)
        }

        ui = mView?.findViewById(R.id.ui)
        activationLabel = mView?.findViewById(R.id.activation_label)
        activationPrompt = mView?.findViewById(R.id.activation_prompt)
        btnEdit = mView?.findViewById(R.id.btn_edit_activation_prompt)

        val item = dataArray[position]

        activationLabel?.text = item["label"]
        activationPrompt?.text = item["prompt"]

        // Recycled views must have both states set explicitly every bind.
        val isSelected = selectedId.isNotEmpty() && Hash.hash(item["label"] ?: "") == selectedId
        if (isSelected) {
            ui?.backgroundTintList = null
            ui?.background = ContextCompat.getDrawable(mContext, R.drawable.tile_active)
            activationLabel?.setTextColor(mContext.getColor(R.color.text_title_inv))
            activationPrompt?.setTextColor(mContext.getColor(R.color.text_subtitle_inv))
            btnEdit?.imageTintList = ColorStateList.valueOf(mContext.getColor(R.color.window_background))
        } else {
            ui?.background = ContextCompat.getDrawable(mContext, R.drawable.btn_accent_tonal_selector_v3)
            ui?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_2.getColor(mContext))
            activationLabel?.setTextColor(mContext.getColor(R.color.accent_900))
            activationPrompt?.setTextColor(mContext.getColor(R.color.text_subtitle))
            btnEdit?.imageTintList = ColorStateList.valueOf(mContext.getColor(R.color.accent_900))
        }

        ui?.setOnClickListener {
            listener?.onClick(position)
        }

        ui?.setOnLongClickListener {
            listener?.onLongClick(position)
            return@setOnLongClickListener true
        }

        btnEdit?.setOnClickListener {
            listener?.onSettingsClick(position)
        }

        return mView!!
    }

    interface OnSelectListener {
        fun onClick(position: Int)
        fun onLongClick(position: Int)
        fun onSettingsClick(position: Int)
    }
}
