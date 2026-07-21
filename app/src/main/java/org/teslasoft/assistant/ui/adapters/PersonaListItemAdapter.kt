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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.profileimages.ProfileImageStore
import org.teslasoft.assistant.util.Hash
import org.teslasoft.assistant.util.ProfileImageBinder

// pickMode (owner ruling, July 21 2026): when this list is opened from Quick
// Settings to CHOOSE the chat's Companion, tapping the non-chevron area selects
// that Companion instantly (onClick) and only the chevron edit target opens the
// editor (onEditClick). When opened from Characters / create-first-companion
// (pickMode = false) the chevron is decorative and the whole row edits. Fixed
// for the adapter's whole lifetime (read once from the activity's intent).
class PersonaListItemAdapter(private val dataArray: ArrayList<HashMap<String, String>>, private var mContext: Context, private val pickMode: Boolean = false) : BaseAdapter() {
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

        val editTarget = mView?.findViewById<FrameLayout>(R.id.persona_edit_target)

        ui?.setOnClickListener {
            listener?.onClick(position)
        }

        // Only in pick mode is the chevron a separate, tappable edit affordance;
        // in manager mode it stays inert so the whole-row tap (openEditor) wins.
        if (pickMode) {
            editTarget?.isClickable = true
            editTarget?.setOnClickListener {
                listener?.onEditClick(position)
            }
        } else {
            editTarget?.isClickable = false
            editTarget?.setOnClickListener(null)
        }

        return mView!!
    }

    interface OnSelectListener {
        // Manager mode: opens the editor. Pick mode: selects the Companion for
        // the chat.
        fun onClick(position: Int)
        // Pick mode only (chevron tap): opens the editor without selecting.
        fun onEditClick(position: Int)
    }
}
