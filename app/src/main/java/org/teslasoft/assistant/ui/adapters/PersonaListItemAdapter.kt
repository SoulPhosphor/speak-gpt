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

class PersonaListItemAdapter(private val dataArray: ArrayList<HashMap<String, String>>, private var mContext: Context) : BaseAdapter() {
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
    private var personaLabel: TextView? = null
    private var personaPrompt: TextView? = null

    private var listener: OnSelectListener? = null

    fun setOnSelectListener(listener: OnSelectListener) {
        this.listener = listener
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val inflater = mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

        var mView: View? = convertView

        if (mView == null) {
            mView = inflater.inflate(R.layout.view_persona_item, parent, false)
        }

        ui = mView?.findViewById(R.id.ui)
        personaLabel = mView?.findViewById(R.id.persona_label)
        personaPrompt = mView?.findViewById(R.id.persona_prompt)

        ui?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_2.getColor(mContext))

        val item = dataArray[position]

        personaLabel?.text = item["label"]
        personaPrompt?.text = item["prompt"]

        ui?.setOnClickListener {
            listener?.onClick(position)
        }

        ui?.setOnLongClickListener {
            listener?.onLongClick(position)
            return@setOnLongClickListener true
        }

        return mView!!
    }

    interface OnSelectListener {
        fun onClick(position: Int)
        fun onLongClick(position: Int)
    }
}
