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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.profileimages.ProfileImageStore
import org.teslasoft.assistant.util.Hash
import org.teslasoft.assistant.util.ProfileImageBinder

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

    private var ui: LinearLayout? = null
    private var personaAvatar: ImageView? = null
    private var personaLabel: TextView? = null

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
        personaAvatar = mView?.findViewById(R.id.persona_avatar)
        personaLabel = mView?.findViewById(R.id.persona_label)

        val item = dataArray[position]

        personaLabel?.text = item["label"]

        // Leading Companion picture (profile-images-plan.md, COMPANION
        // SELECTION LIST): the assigned picture with the Default Shape, or a
        // neutral placeholder glyph when none is assigned or the file is
        // missing. Bound through the shared binder so a recycled row never
        // keeps another Companion's picture. No "currently active" state is
        // shown here at all (owner ruling, July 19 2026) - that lives in
        // Quick Settings and the last-used companion logic, not this list.
        personaAvatar?.let { avatar ->
            val personaId = Hash.hash(item["label"] ?: "")
            val avatarRef = PersonaPreferences.getPersonaPreferences(mContext).getPersona(personaId).avatarRef
            val avatarFile = if (avatarRef.isNotEmpty()) ProfileImageStore.getInstance(mContext).imageFile(avatarRef) else null
            val shape = GlobalPreferences.getPreferences(mContext).getProfileImageShape()
            ProfileImageBinder.bind(mContext, avatar, avatarFile, shape) { iv ->
                iv.setImageResource(R.drawable.ic_photo)
                iv.imageTintList = ColorStateList.valueOf(mContext.getColor(R.color.accent_900))
            }
        }

        ui?.setOnClickListener {
            listener?.onClick(position)
        }

        return mView!!
    }

    interface OnSelectListener {
        fun onClick(position: Int)
    }
}
