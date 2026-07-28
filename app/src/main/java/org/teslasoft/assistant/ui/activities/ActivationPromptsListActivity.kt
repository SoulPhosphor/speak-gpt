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
import org.teslasoft.assistant.preferences.ActivationPromptPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ActivationPromptObject
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.adapters.ActivationPromptListItemAdapter

class ActivationPromptsListActivity : FragmentActivity() {

    private var btnAdd: ExtendedFloatingActionButton? = null
    private var btnBack: ImageButton? = null
    private var activityTitle: TextView? = null
    private var listView: ListView? = null

    private var list: ArrayList<HashMap<String, String>> = arrayListOf()
    private var adapter: ActivationPromptListItemAdapter? = null

    private var activationPromptPreferences: ActivationPromptPreferences? = null

    private var actionBar: ConstraintLayout? = null

    // Pick mode (launched from Quick Settings): tapping a pill selects it for the
    // chat and returns. Manager mode (launched from Characters): tapping edits.
    private var pickMode: Boolean = false
    private var currentActivationId: String = ""

    private fun newEmptyActivationPrompt(): ActivationPromptObject {
        return ActivationPromptObject("", "")
    }

    // Applies the full-screen editor's result exactly as the old dialog
    // listener did: a save adds or renames the prompt then reloads the list;
    // a delete removes it and reloads the list.
    private val editActivationPromptLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        when (data.getStringExtra(EditActivationPromptActivity.EXTRA_RESULT_ACTION)) {
            EditActivationPromptActivity.ACTION_SAVE -> {
                // The prompt carries its stable id (blank for a new one, which
                // setActivationPrompt mints in place). One save path for add AND
                // rename — a rename updates the record under the same id.
                val activationPrompt = EditActivationPromptActivity.readResultActivationPrompt(data)
                activationPromptPreferences!!.setActivationPrompt(activationPrompt)
                reloadList()
            }
            EditActivationPromptActivity.ACTION_DELETE -> {
                val id = data.getStringExtra(EditActivationPromptActivity.EXTRA_RESULT_ID)
                if (id != null) {
                    activationPromptPreferences!!.deleteActivationPrompt(id)
                    reloadList()
                }
            }
        }
    }

    private fun openEditor(position: Int) {
        val id = list[position]["id"] ?: return
        val activationPrompt = activationPromptPreferences!!.getActivationPrompt(id)
        editActivationPromptLauncher.launch(EditActivationPromptActivity.createIntent(this, activationPrompt, position))
    }

    private fun openCreate() {
        editActivationPromptLauncher.launch(EditActivationPromptActivity.createIntent(this, newEmptyActivationPrompt(), -1))
    }

    private fun finishWithActive(id: String) {
        val resultIntent = Intent()
        resultIntent.putExtra("activationPromptId", id)
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private var onSelectListener: ActivationPromptListItemAdapter.OnSelectListener = object : ActivationPromptListItemAdapter.OnSelectListener {
        override fun onClick(position: Int) {
            if (pickMode) {
                val id = list[position]["id"] ?: return
                finishWithActive(id)
            } else {
                openEditor(position)
            }
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

        setContentView(R.layout.activity_activation_prompt_list)

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

        pickMode = intent.getBooleanExtra("pickMode", false)
        currentActivationId = intent.getStringExtra("currentActivationId") ?: ""

        activationPromptPreferences = ActivationPromptPreferences.getActivationPromptPreferences(this)
        initialize()
    }

    private fun reloadList() {
        if (list == null) list = arrayListOf()

        list.clear()
        val promptsList = activationPromptPreferences!!.getActivationPromptsList()

        for (i in promptsList) {
            val map = HashMap<String, String>()
            map["id"] = i.id
            map["label"] = i.label
            map["prompt"] = i.prompt
            list.add(map)
        }

        // R8 bug fix
        if (list == null) list = arrayListOf()

        runOnUiThread {
            adapter = ActivationPromptListItemAdapter(list, this, pickMode)
            adapter!!.setOnSelectListener(onSelectListener)
            adapter!!.setSelectedId(if (pickMode) currentActivationId else "")
            listView!!.adapter = adapter
            adapter!!.notifyDataSetChanged()
        }
    }

    private fun isDarkThemeEnabled(): Boolean {
        return when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            else -> false
        }
    }

    private fun initialize() {
        reloadList()

        btnBack!!.setOnClickListener {
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
