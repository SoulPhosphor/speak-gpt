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

package org.teslasoft.assistant.ui.activities

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.marginBottom
import androidx.core.view.marginRight
import androidx.fragment.app.FragmentActivity
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.PersonaObject
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.adapters.PersonaListItemAdapter
import org.teslasoft.assistant.ui.fragments.dialogs.EditPersonaDialogFragment

class PersonasListActivity : FragmentActivity() {

    private var btnAdd: ExtendedFloatingActionButton? = null
    private var btnBack: ImageButton? = null
    private var listView: ListView? = null
    private var emptyHint: TextView? = null
    private var actionBar: ConstraintLayout? = null

    private var list: ArrayList<PersonaObject> = arrayListOf()
    private var adapter: PersonaListItemAdapter? = null
    private var personaPreferences: PersonaPreferences? = null

    private val selectListener = object : PersonaListItemAdapter.OnSelectListener {
        override fun onClick(position: Int) {
            openEdit(list[position])
        }

        override fun onLongClick(position: Int) {
            openEdit(list[position])
        }
    }

    private val editListener = object : EditPersonaDialogFragment.StateChangesListener {
        override fun onSave(id: String, name: String, prompt: String, makeActive: Boolean) {
            val prefs = personaPreferences ?: return
            val effectiveId = if (id.isEmpty()) prefs.addPersona(name, prompt).id
            else {
                prefs.updatePersona(id, name, prompt)
                id
            }
            if (makeActive) prefs.setActivePersonaId(effectiveId)
            else if (prefs.getActivePersonaId() == effectiveId && !makeActive) prefs.setActivePersonaId("")
            reloadList()
        }

        override fun onDelete(id: String) {
            personaPreferences?.deletePersona(id)
            reloadList()
        }

        override fun onError(message: String, id: String) {
            Toast.makeText(this@PersonasListActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_personas_list)

        btnAdd = findViewById(R.id.btn_add)
        btnBack = findViewById(R.id.btn_back)
        listView = findViewById(R.id.list_view)
        emptyHint = findViewById(R.id.empty_hint)
        actionBar = findViewById(R.id.action_bar)

        val preferences = Preferences.getPreferences(this, "")
        ThemeManager.getThemeManager().applyTheme(this, isDarkThemeEnabled() && preferences.getAmoledPitchBlack())

        if (isDarkThemeEnabled() && preferences.getAmoledPitchBlack()) {
            window.setBackgroundDrawableResource(R.color.amoled_window_background)
            if (Build.VERSION.SDK_INT <= 34) {
                window.navigationBarColor = ResourcesCompat.getColor(resources, R.color.amoled_window_background, theme)
                window.statusBarColor = ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme)
            }
            actionBar?.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
            btnBack?.backgroundTintList = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
        } else {
            val colorDrawable = SurfaceColors.SURFACE_0.getColor(this).toDrawable()
            window.setBackgroundDrawable(colorDrawable)
            if (Build.VERSION.SDK_INT <= 34) {
                window.navigationBarColor = SurfaceColors.SURFACE_0.getColor(this)
                window.statusBarColor = SurfaceColors.SURFACE_4.getColor(this)
            }
            actionBar?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
            btnBack?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
        }

        listView?.divider = null
        personaPreferences = PersonaPreferences.getInstance(this)

        btnBack?.setOnClickListener {
            setResult(RESULT_OK)
            finish()
        }

        btnAdd?.setOnClickListener {
            val dialog = EditPersonaDialogFragment.newInstance("", "", "", false)
            dialog.setListener(editListener)
            dialog.isCancelable = false
            dialog.show(supportFragmentManager, "EditPersonaDialogFragment")
        }

        reloadList()
    }

    private fun openEdit(persona: PersonaObject) {
        val activeId = personaPreferences?.getActivePersonaId().orEmpty()
        val dialog = EditPersonaDialogFragment.newInstance(persona.id, persona.name, persona.systemPrompt, persona.id == activeId)
        dialog.setListener(editListener)
        dialog.isCancelable = false
        dialog.show(supportFragmentManager, "EditPersonaDialogFragment")
    }

    private fun reloadList() {
        val prefs = personaPreferences ?: return
        list.clear()
        list.addAll(prefs.getPersonas())

        runOnUiThread {
            adapter = PersonaListItemAdapter(list, this, prefs.getActivePersonaId())
            adapter?.setOnSelectListener(selectListener)
            listView?.adapter = adapter
            adapter?.notifyDataSetChanged()
            emptyHint?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun isDarkThemeEnabled(): Boolean {
        return when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            else -> false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        adjustPaddings()
    }

    private fun adjustPaddings() {
        if (Build.VERSION.SDK_INT < 35) return
        try {
            actionBar?.setPadding(
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.statusBars()).top,
                0,
                0
            )
            listView?.setPadding(
                0,
                pxToDp(8),
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
            )
            val extendedFab = findViewById<ExtendedFloatingActionButton>(R.id.btn_add)
            val params = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(
                0,
                0,
                extendedFab.marginRight,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom + extendedFab.marginBottom
            )
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            extendedFab.layoutParams = params
        } catch (_: Exception) { /* unused */ }
    }

    private fun pxToDp(px: Int): Int {
        val density = resources.displayMetrics.density
        return (px * density).toInt()
    }
}
