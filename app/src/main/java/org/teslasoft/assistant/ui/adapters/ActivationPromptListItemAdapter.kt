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
import androidx.core.content.ContextCompat
import com.google.android.material.elevation.SurfaceColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.util.Hash

/**
 * Two distinct row looks depending on why this list is open (owner ruling,
 * July 18 2026), same split as the system prompt library:
 *  - Manager mode (browsing/editing, the screen with the "Add" button): a
 *    plain title-only row with a chevron — no prompt-text preview, just the
 *    label. The separate always-visible edit button is dropped; long-press
 *    still opens the editor, same as before.
 *  - Pick mode (choosing a prompt for a chat, from Quick Settings): the
 *    existing "checked tile" scheme, unchanged.
 * Mode is fixed for the adapter's whole lifetime, so the two never mix
 * within one instance's recycled views.
 */
class ActivationPromptListItemAdapter(
    private val dataArray: ArrayList<HashMap<String, String>>,
    private var mContext: Context,
    private val pickMode: Boolean
) : BaseAdapter() {
    override fun getCount(): Int {
        return dataArray.size
    }

    override fun getItem(position: Int): Any {
        return dataArray[position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    private var ui: View? = null
    private var activationLabel: TextView? = null
    private var activationPrompt: TextView? = null
    private var btnEdit: ImageButton? = null

    private var listener: OnSelectListener? = null

    // Id of the activation prompt active for the chat, drawn inverted (same
    // scheme as a checked tile in Settings). Only used in pick mode.
    private var selectedId: String = ""

    fun setOnSelectListener(listener: OnSelectListener) {
        this.listener = listener
    }

    fun setSelectedId(id: String) {
        this.selectedId = id
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val inflater = mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

        val layoutRes = if (pickMode) R.layout.view_activation_prompt_item else R.layout.view_activation_prompt_item_row
        var mView: View? = convertView

        if (mView == null) {
            mView = inflater.inflate(layoutRes, parent, false)
        }

        ui = mView?.findViewById(R.id.ui)
        activationLabel = mView?.findViewById(R.id.activation_label)

        val item = dataArray[position]
        activationLabel?.text = item["label"]

        if (pickMode) {
            activationPrompt = mView?.findViewById(R.id.activation_prompt)
            btnEdit = mView?.findViewById(R.id.btn_edit_activation_prompt)
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

            btnEdit?.setOnClickListener {
                listener?.onSettingsClick(position)
            }
        } else {
            mView?.findViewById<View>(R.id.activation_prompt_chevron)?.contentDescription = item["label"]
        }

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
        fun onSettingsClick(position: Int)
    }
}
