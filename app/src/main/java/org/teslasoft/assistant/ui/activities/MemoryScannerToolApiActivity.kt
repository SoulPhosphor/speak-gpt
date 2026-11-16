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

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ModelPickerScope
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.dedicated.DedicatedModelRows
import org.teslasoft.assistant.ui.dedicated.DedicatedParametersSection
import org.teslasoft.assistant.ui.util.SaveIconFlash
import org.teslasoft.assistant.ui.util.ScreenChrome

/**
 * Memory Scanner Tool API, opened from AI Settings (owner design, Nov 16
 * 2026): the Memory Scanner's endpoint, model, provider routing and
 * parameters, moved here unchanged from Memory Scanner Settings.
 *
 * Endpoint, model and routing save at once on change (DedicatedModelRows).
 * The parameters save only with the header Save, which flashes the save disc
 * and shows a toast. Leaving with unsaved parameter changes asks first
 * (owner ruling, Nov 8 2026): Cancel stays, Save & Exit saves and leaves,
 * Okay leaves without saving.
 */
class MemoryScannerToolApiActivity : SettingsPageActivity() {

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var btnSave: ImageButton? = null

    private var modelRows: DedicatedModelRows? = null
    private var parametersSection: DedicatedParametersSection? = null

    private val endpointEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> modelRows?.onEndpointEditorResult(result) }

    private val routingSetupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> modelRows?.onRoutingSetupResult(result) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_memory_scanner_tool_api)

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        btnSave = findViewById(R.id.btn_save)

        ScreenChrome.apply(this, actionBar, btnBack, btnSave)
        ScreenChrome.fitSystemBarsAndKeyboard(findViewById(R.id.root), actionBar, findViewById(R.id.scroll))

        val section = DedicatedParametersSection(
            findViewById(R.id.root), ModelPickerScope.MEMORY_SCANNER, autoSave = false
        )
        parametersSection = section
        modelRows = DedicatedModelRows(
            this, ModelPickerScope.MEMORY_SCANNER,
            findViewById<TextView>(R.id.text_archivist_endpoint_value),
            findViewById<ImageButton>(R.id.btn_edit_archivist_endpoint),
            findViewById<TextView>(R.id.text_archivist_model_value),
            findViewById<MaterialButton>(R.id.btn_view_all_archivist_models),
            findViewById<View>(R.id.section_archivist_routing),
            findViewById<TextView>(R.id.text_archivist_routing_value),
            endpointEditorLauncher, routingSetupLauncher
        ) { section.onModelSelected() }
        findViewById<MaterialButton>(R.id.btn_reset_model_parameters)
            .setOnClickListener { section.showRecommended() }

        btnSave?.setOnClickListener { save() }
        btnBack?.setOnClickListener { attemptExit() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { attemptExit() }
        })
    }

    override fun onResume() {
        super.onResume()
        modelRows?.refresh()
        parametersSection?.syncIfUnedited()
    }

    private fun save() {
        parametersSection?.commit()
        btnSave?.let { SaveIconFlash.flash(it) }
        Toast.makeText(this, R.string.message_saved, Toast.LENGTH_SHORT).show()
    }

    private fun attemptExit() {
        if (parametersSection?.hasUnsavedChanges() != true) {
            finish()
            return
        }
        val actions = layoutInflater.inflate(R.layout.dialog_three_actions_row, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.memory_parameters_discard_title)
            .setMessage(R.string.memory_parameters_discard_message)
            .setView(actions)
            .create()
        actions.findViewById<MaterialButton>(R.id.btn_dialog_cancel_action).apply {
            setText(R.string.btn_cancel)
            setOnClickListener { dialog.dismiss() }
        }
        actions.findViewById<MaterialButton>(R.id.btn_dialog_middle_action).apply {
            setText(R.string.btn_save_and_exit)
            setOnClickListener {
                dialog.dismiss()
                save()
                finish()
            }
        }
        actions.findViewById<MaterialButton>(R.id.btn_dialog_final_action).apply {
            setText(R.string.okay)
            setOnClickListener {
                dialog.dismiss()
                finish()
            }
        }
        dialog.show()
    }
}
