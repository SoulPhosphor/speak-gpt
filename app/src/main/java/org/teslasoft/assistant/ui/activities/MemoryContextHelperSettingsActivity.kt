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
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.context.ContextAssistance
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.AppDropdown
import org.teslasoft.assistant.ui.util.NumberSettingField
import org.teslasoft.assistant.ui.util.ScreenChrome

/**
 * Memory Context Helper Settings (conversation_recall_plan.md G38, M.4,
 * M.11): turns context assistance on or off for every chat and chooses its
 * method, one at a time. Off by default; when first turned on, On-device
 * (Code-Based) is used. API AI can be chosen only once a model is set on
 * Memory Context Helper Tool API; until then the user is directed there.
 * Number of Past Messages to Send is shown while API AI is chosen.
 */
class MemoryContextHelperSettingsActivity : SettingsPageActivity() {

    private lateinit var preferences: Preferences
    private lateinit var methodValue: TextView
    private lateinit var methodHint: TextView
    private lateinit var pastMessagesRow: View

    private val methods = listOf(ContextAssistance.METHOD_CODE_BASED, ContextAssistance.METHOD_API_AI)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_memory_context_helper_settings)

        val header = findViewById<View>(R.id.action_bar)
        val back = findViewById<View>(R.id.btn_back)
        ScreenChrome.apply(this, header, back)
        ScreenChrome.fitSystemBarsAndKeyboard(findViewById(R.id.root), header, findViewById(R.id.scroll))
        back.setOnClickListener { finish() }

        preferences = Preferences.getPreferences(this, "")
        methodValue = findViewById(R.id.dropdown_helper_method)
        methodHint = findViewById(R.id.text_helper_method_hint)
        pastMessagesRow = findViewById(R.id.row_helper_past_messages)

        val toggle = findViewById<MaterialSwitch>(R.id.switch_memory_context_helper)
        val method = findViewById<View>(R.id.group_helper_method)
        toggle.isChecked = preferences.getMemoryContextHelperEnabled()
        method.visibility = if (toggle.isChecked) View.VISIBLE else View.GONE
        toggle.setOnCheckedChangeListener { _, checked ->
            if (checked) preferences.setMemoryContextHelperMethod(ContextAssistance.METHOD_CODE_BASED)
            preferences.setMemoryContextHelperEnabled(checked)
            ContextAssistance.settingChanged()
            method.visibility = if (checked) View.VISIBLE else View.GONE
            showMethod()
        }

        methodValue.setOnClickListener {
            val labels = methods.map { getString(labelFor(it)) }
            AppDropdown.show(methodValue, labels, methods.indexOf(currentMethod())) { index ->
                choose(methods[index])
            }
        }

        NumberSettingField.bindRequired(
            findViewById<EditText>(R.id.field_helper_past_messages), PAST_MESSAGES_RANGE,
            preferences::getMemoryContextHelperPastExchanges,
            preferences::setMemoryContextHelperPastExchanges
        )
        showMethod()
    }

    private fun currentMethod(): String =
        preferences.getMemoryContextHelperMethod().takeIf { it in methods } ?: ContextAssistance.METHOD_CODE_BASED

    private fun choose(chosen: String) {
        if (chosen == currentMethod()) return
        if (chosen == ContextAssistance.METHOD_API_AI && !ContextAssistance.apiModelSet(this)) {
            showNoModel()
            return
        }
        preferences.setMemoryContextHelperMethod(chosen)
        ContextAssistance.settingChanged()
        showMethod()
    }

    private fun showMethod() {
        val current = currentMethod()
        methodValue.setText(labelFor(current))
        methodHint.setText(
            if (current == ContextAssistance.METHOD_API_AI) R.string.memory_context_helper_method_api_ai_hint
            else R.string.memory_context_helper_method_code_based_hint
        )
        pastMessagesRow.visibility =
            if (current == ContextAssistance.METHOD_API_AI) View.VISIBLE else View.GONE
    }

    /** API AI needs a model first (M.4 item 7): the method stays as it was
     *  and the user is directed to Memory Context Helper Tool API. */
    private fun showNoModel() {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.memory_context_helper_no_model_title)
            .setMessage(R.string.memory_context_helper_no_model_message)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.memory_context_helper_no_model_open) { _, _ ->
                startActivity(Intent(this, MemoryContextHelperToolApiActivity::class.java))
            }
            .show()
    }

    private fun labelFor(method: String): Int =
        if (method == ContextAssistance.METHOD_API_AI) R.string.memory_context_helper_method_api_ai
        else R.string.memory_context_helper_method_code_based

    private companion object {
        /** Any whole number the field's three digits hold (M.4 item 1). */
        val PAST_MESSAGES_RANGE = 0..999
    }
}
