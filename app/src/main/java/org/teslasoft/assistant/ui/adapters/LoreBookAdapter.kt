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
import org.teslasoft.assistant.preferences.dto.LoreBook

/**
 * Lists lorebooks. Tapping a row opens its memories; the cog edits the book itself.
 */
class LoreBookAdapter(
    private val dataArray: ArrayList<LoreBook>,
    private val counts: HashMap<String, Int>,
    private var mContext: Context
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

    private var listener: OnSelectListener? = null

    fun setOnSelectListener(listener: OnSelectListener) {
        this.listener = listener
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val inflater = mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

        var mView: View? = convertView

        if (mView == null) {
            mView = inflater.inflate(R.layout.view_lorebook_book, parent, false)
        }

        val ui: ConstraintLayout? = mView?.findViewById(R.id.ui)
        val bookName: TextView? = mView?.findViewById(R.id.book_name)
        val bookCount: TextView? = mView?.findViewById(R.id.book_count)
        val bookDescription: TextView? = mView?.findViewById(R.id.book_description)
        val btnEdit: ImageButton? = mView?.findViewById(R.id.btn_edit_book)

        val item = dataArray[position]

        bookName?.text = item.name

        val count = counts[item.id] ?: 0
        bookCount?.text = mContext.resources.getQuantityString(R.plurals.lorebook_memory_count, count, count)

        if (item.description.isBlank()) {
            bookDescription?.visibility = View.GONE
        } else {
            bookDescription?.visibility = View.VISIBLE
            bookDescription?.text = item.description
        }

        ui?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_2.getColor(mContext))

        ui?.setOnClickListener {
            listener?.onClick(position)
        }

        ui?.setOnLongClickListener {
            listener?.onSettingsClick(position)
            return@setOnLongClickListener true
        }

        btnEdit?.setOnClickListener {
            listener?.onSettingsClick(position)
        }

        return mView!!
    }

    interface OnSelectListener {
        fun onClick(position: Int)
        fun onSettingsClick(position: Int)
    }
}
