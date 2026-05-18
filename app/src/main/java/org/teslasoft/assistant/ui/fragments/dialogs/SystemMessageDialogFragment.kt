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

package org.teslasoft.assistant.ui.fragments.dialogs

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.ui.activities.PersonasListActivity

class SystemMessageDialogFragment : DialogFragment() {
    companion object {
        fun newInstance(prompt: String) : SystemMessageDialogFragment {
            val activationPromptDialogFragment = SystemMessageDialogFragment()

            val args = Bundle()
            args.putString("prompt", prompt)

            activationPromptDialogFragment.arguments = args

            return activationPromptDialogFragment
        }
    }

    private var builder: AlertDialog.Builder? = null

    private var context: Context? = null

    private var promptInput: EditText? = null
    private var personaStatus: TextView? = null
    private var btnManagePersonas: Button? = null

    private var listener: StateChangesListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        context = this.activity
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_system, container, false)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        builder = MaterialAlertDialogBuilder(this.requireContext(), R.style.App_MaterialAlertDialog)

        val view: View = this.layoutInflater.inflate(R.layout.fragment_system, null)

        promptInput = view.findViewById(R.id.prompt_input)
        personaStatus = view.findViewById(R.id.persona_status)
        btnManagePersonas = view.findViewById(R.id.btn_manage_personas)

        promptInput?.setText(requireArguments().getString("prompt"))

        refreshPersonaStatus()

        btnManagePersonas?.setOnClickListener {
            startActivity(Intent(requireContext(), PersonasListActivity::class.java))
        }

        builder!!.setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_save) { _, _ -> validateForm() }
            .setNegativeButton(R.string.btn_cancel) { _, _ ->  }

        return builder!!.create()
    }

    override fun onResume() {
        super.onResume()
        refreshPersonaStatus()
    }

    private fun refreshPersonaStatus() {
        val ctx = context ?: return
        val active = PersonaPreferences.getInstance(ctx).getActivePersona()
        personaStatus?.text = if (active != null) {
            getString(R.string.persona_status_using, active.name)
        } else {
            getString(R.string.persona_status_none)
        }
    }

    private fun validateForm() {
        listener!!.onEdit(promptInput?.text.toString())
    }

    fun setStateChangedListener(listener: StateChangesListener) {
        this.listener = listener
    }

    fun interface StateChangesListener {
        fun onEdit(prompt: String)
    }
}
