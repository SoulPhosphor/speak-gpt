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
import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R

/**
 * Same pop-up dialog this has always been (owner ruling, Aug 13 2026: keep
 * the look/interaction unchanged). The only change is how the "checked
 * tile" row highlight is applied: the selected/unselected background and
 * text color now come from Widget.App.PickList.Row and its two
 * TextAppearance states (see themes.xml) instead of a runtime color tint
 * hard-coded to @color/accent_900 / @color/window_background / @color/
 * neutral_200 - the fix needed so this dialog follows the active theme
 * once real palettes exist.
 */
class LanguageSelectorDialogFragment : DialogFragment() {
    companion object {
        fun newInstance(name: String, chatId: String, showAutomatic: Boolean = false) : LanguageSelectorDialogFragment {
            val languageSelectorDialogFragment = LanguageSelectorDialogFragment()

            val args = Bundle()
            args.putString("name", name)
            args.putString("chatId", chatId)
            args.putBoolean("showAutomatic", showAutomatic)

            languageSelectorDialogFragment.arguments = args

            return languageSelectorDialogFragment
        }
    }

    private var builder: AlertDialog.Builder? = null

    private var listener: StateChangesListener? = null

    private var language = "en"

    private var radios: Map<String, RadioButton?> = emptyMap()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        builder = MaterialAlertDialogBuilder(this.requireContext(), R.style.App_MaterialAlertDialog)

        val view: View = this.layoutInflater.inflate(R.layout.fragment_select_language, null)

        radios = mapOf(
            "auto" to view.findViewById(R.id.lngAuto),
            "en" to view.findViewById(R.id.lngEn),
            "fr" to view.findViewById(R.id.lngFr),
            "de" to view.findViewById(R.id.lngDe),
            "it" to view.findViewById(R.id.lngIt),
            "ja" to view.findViewById(R.id.lngJp),
            "ko" to view.findViewById(R.id.lngKp),
            "zh_CN" to view.findViewById(R.id.lngCnS),
            "zh_TW" to view.findViewById(R.id.lngCnT),
            "es" to view.findViewById(R.id.lngEs),
            "uk" to view.findViewById(R.id.lngUk),
            "ru" to view.findViewById(R.id.lngRu),
            "pl" to view.findViewById(R.id.lngPl),
            "tr" to view.findViewById(R.id.lngTr)
        )

        builder!!.setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_save) { _, _ -> validateForm() }
            .setNegativeButton(R.string.btn_cancel) { _, _ ->  }

        language = requireArguments().getString("name").toString()

        if (requireArguments().getBoolean("showAutomatic", false)) {
            radios["auto"]?.visibility = View.VISIBLE
        }

        radios.forEach { (code, radio) ->
            radio?.isChecked = language == code
            radio?.setOnClickListener {
                language = code
                applySelection()
            }
        }

        applySelection()

        return builder!!.create()
    }

    /** Repaints every row's checked-tile background/text for the current
     *  [language] - the same visual states the old per-row tint produced,
     *  now theme-attribute-driven (see Widget.App.PickList.Row). */
    private fun applySelection() {
        val ctx = requireActivity()
        radios.values.forEach { radio ->
            radio ?: return@forEach
            if (radio.id == radios[language]?.id) {
                radio.background = ContextCompat.getDrawable(ctx, R.drawable.btn_accent_tonal_selector_v4)
                radio.setTextAppearance(R.style.TextAppearance_App_PickList_Selected)
            } else {
                radio.background = ContextCompat.getDrawable(ctx, R.drawable.btn_accent_tonal_selector_v3)
                radio.setTextAppearance(R.style.TextAppearance_App_PickList_Unselected)
            }
        }
    }

    private fun validateForm() {
        if (language != "") {
            listener!!.onSelected(language)
        } else {
            listener!!.onFormError(language)
        }
    }

    fun setStateChangedListener(listener: StateChangesListener) {
        this.listener = listener
    }

    interface StateChangesListener {
        fun onSelected(name: String)
        fun onFormError(name: String)
    }
}
