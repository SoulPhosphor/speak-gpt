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

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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
import org.teslasoft.assistant.util.Hash

class PersonasListActivity : FragmentActivity() {

    private var btnAdd: ExtendedFloatingActionButton? = null
    private var btnBack: ImageButton? = null
    private var activityTitle: TextView? = null
    private var listView: ListView? = null

    private var list: ArrayList<HashMap<String, String>> = arrayListOf()
    private var adapter: PersonaListItemAdapter? = null

    private var personaPreferences: PersonaPreferences? = null

    private var actionBar: ConstraintLayout? = null

    // The persona currently active for the chat, so the list can highlight it.
    private var currentPersonaId: String = ""

    private fun newEmptyPersona(): PersonaObject {
        return PersonaObject("", "")
    }

    // Applies the full-screen editor's result exactly as the old dialog listener
    // did: a save adds or edits the companion then selects it and finishes
    // (finishWithActive); a delete removes it and reloads the list.
    private val editPersonaLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        when (data.getStringExtra(EditPersonaActivity.EXTRA_RESULT_ACTION)) {
            EditPersonaActivity.ACTION_SAVE -> {
                val persona = EditPersonaActivity.readResultPersona(data)
                val pos = data.getIntExtra(EditPersonaActivity.EXTRA_POSITION, -1)
                val oldLabel = data.getStringExtra(EditPersonaActivity.EXTRA_RESULT_OLD_LABEL) ?: ""
                if (pos == -1) {
                    personaPreferences!!.setPersona(persona)
                } else {
                    personaPreferences!!.editPersona(oldLabel, persona)
                }
                finishWithActive(persona.label)
            }
            EditPersonaActivity.ACTION_DELETE -> {
                val id = data.getStringExtra(EditPersonaActivity.EXTRA_RESULT_ID)
                if (id != null) {
                    personaPreferences!!.deletePersona(id)
                    reloadList()
                }
            }
        }
    }

    private fun openEditor(position: Int) {
        val label = list[position]["label"] ?: return
        val persona = personaPreferences!!.getPersona(Hash.hash(label))
        editPersonaLauncher.launch(EditPersonaActivity.createIntent(this, persona, position))
    }

    private fun openCreate() {
        editPersonaLauncher.launch(EditPersonaActivity.createIntent(this, newEmptyPersona(), -1))
    }

    private fun finishWithActive(label: String) {
        val resultIntent = Intent()
        resultIntent.putExtra("personaId", Hash.hash(label))
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private var onSelectListener: PersonaListItemAdapter.OnSelectListener = object : PersonaListItemAdapter.OnSelectListener {
        // Tapping the pill body selects the persona for the chat (the cog edits).
        override fun onClick(position: Int) {
            val label = list[position]["label"] ?: return
            finishWithActive(label)
        }

        override fun onLongClick(position: Int) {
            openEditor(position)
        }

        override fun onSettingsClick(position: Int) {
            openEditor(position)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)

        setContentView(R.layout.activity_persona_list)

        btnAdd = findViewById(R.id.btn_add)
        btnBack = findViewById(R.id.btn_back)
        activityTitle = findViewById(R.id.activity_title)
        listView = findViewById(R.id.list_view)
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

        currentPersonaId = intent.getStringExtra("currentPersonaId") ?: ""

        personaPreferences = PersonaPreferences.getPersonaPreferences(this)
        initialize()

        // Launched to create the very first companion (from ChatActivity when a
        // new chat has none): drop the owner straight onto the creation form
        // rather than an empty list. Guarded on savedInstanceState so a
        // rotation doesn't reopen it.
        if (savedInstanceState == null && intent.getBooleanExtra("createOnStart", false)) {
            openCreate()
        }
    }

    private fun reloadList() {
        if (list == null) list = arrayListOf()

        list.clear()
        val personasList = personaPreferences!!.getPersonasList()

        for (i in personasList) {
            val map = HashMap<String, String>()
            map["label"] = i.label
            map["prompt"] = i.prompt
            list.add(map)
        }

        // R8 bug fix
        if (list == null) list = arrayListOf()

        runOnUiThread {
            adapter = PersonaListItemAdapter(list, this)
            adapter!!.setOnSelectListener(onSelectListener)
            adapter!!.setSelectedId(currentPersonaId)
            listView!!.adapter = adapter
            adapter!!.notifyDataSetChanged()
        }
    }

    private fun isDarkThemeEnabled(): Boolean {
        return when (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            Configuration.UI_MODE_NIGHT_NO -> false
            Configuration.UI_MODE_NIGHT_UNDEFINED -> false
            else -> false
        }
    }

    private fun initialize() {
        reloadList()

        btnBack!!.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        btnAdd!!.setOnClickListener {
            openCreate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        adjustPaddings()
    }

    private fun adjustPaddings() {
        if (Build.VERSION.SDK_INT < 35) return
        try {
            val actionBar = findViewById<ConstraintLayout>(R.id.action_bar)
            actionBar?.setPadding(
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.statusBars()).top,
                0,
                0
            )

            val list = findViewById<ListView>(R.id.list_view)
            list?.setPadding(
                0,
                pxToDp(8),
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
            )

            val extendedFab = findViewById<ExtendedFloatingActionButton>(R.id.btn_add)
            val params: ConstraintLayout.LayoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, extendedFab!!.marginRight, window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom + extendedFab!!.marginBottom)
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
