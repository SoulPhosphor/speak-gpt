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
import org.teslasoft.assistant.preferences.ActivationPromptPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ActivationPromptObject
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.adapters.ActivationPromptListItemAdapter
import org.teslasoft.assistant.ui.fragments.dialogs.EditActivationPromptDialogFragment
import org.teslasoft.assistant.util.Hash

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

    private fun newEditDialog(activationPrompt: ActivationPromptObject, position: Int): EditActivationPromptDialogFragment {
        return EditActivationPromptDialogFragment.newInstance(
            activationPrompt.label,
            activationPrompt.prompt,
            position
        )
    }

    private fun newEmptyActivationPrompt(): ActivationPromptObject {
        return ActivationPromptObject("", "")
    }

    private fun openEditDialog(position: Int) {
        val label = list[position]["label"] ?: return
        val activationPrompt = activationPromptPreferences!!.getActivationPrompt(Hash.hash(label))
        val dialog = newEditDialog(activationPrompt, position)
        dialog.setListener(editDialogListener)
        dialog.setCancelable(false)
        dialog.show(supportFragmentManager, "EditActivationPromptDialogFragment")
    }

    private fun finishWithActive(label: String) {
        val resultIntent = Intent()
        resultIntent.putExtra("activationPromptId", Hash.hash(label))
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private var onSelectListener: ActivationPromptListItemAdapter.OnSelectListener = object : ActivationPromptListItemAdapter.OnSelectListener {
        override fun onClick(position: Int) {
            if (pickMode) {
                val label = list[position]["label"] ?: return
                finishWithActive(label)
            } else {
                openEditDialog(position)
            }
        }

        override fun onLongClick(position: Int) {
            openEditDialog(position)
        }

        override fun onSettingsClick(position: Int) {
            openEditDialog(position)
        }
    }

    private var editDialogListener: EditActivationPromptDialogFragment.StateChangesListener = object : EditActivationPromptDialogFragment.StateChangesListener {
        override fun onAdd(activationPrompt: ActivationPromptObject) {
            activationPromptPreferences!!.setActivationPrompt(activationPrompt)
            reloadList()
        }

        override fun onEdit(oldLabel: String, activationPrompt: ActivationPromptObject, position: Int) {
            activationPromptPreferences!!.editActivationPrompt(list[position]["label"] ?: return, activationPrompt)
            reloadList()
        }

        override fun onDelete(position: Int, id: String) {
            activationPromptPreferences!!.deleteActivationPrompt(id)
            reloadList()
        }

        override fun onError(message: String, position: Int) {
            Toast.makeText(this@ActivationPromptsListActivity, message, Toast.LENGTH_SHORT).show()
            val activationPrompt = if (position == -1) {
                newEmptyActivationPrompt()
            } else {
                val label = list[position]["label"] ?: return
                activationPromptPreferences!!.getActivationPrompt(Hash.hash(label))
            }
            val dialog = newEditDialog(activationPrompt, position)
            dialog.setListener(this)
            dialog.setCancelable(false)
            dialog.show(supportFragmentManager, "EditActivationPromptDialogFragment")
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
            map["label"] = i.label
            map["prompt"] = i.prompt
            list.add(map)
        }

        // R8 bug fix
        if (list == null) list = arrayListOf()

        runOnUiThread {
            adapter = ActivationPromptListItemAdapter(list, this)
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
            val dialog = newEditDialog(newEmptyActivationPrompt(), -1)
            dialog.setListener(editDialogListener)
            dialog.setCancelable(false)
            dialog.show(supportFragmentManager, "EditActivationPromptDialogFragment")
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
