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

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ListView
import androidx.fragment.app.DialogFragment
import org.teslasoft.assistant.R
import org.teslasoft.assistant.ui.adapters.LanguageListAdapter

/**
 * Full-screen Voice Language picker (owner redesign, Aug 13 2026). Replaces
 * the old pop-up dialog with the same full-screen treatment as Select AI
 * Model: a header with a back button, and a single-select list where
 * tapping a row selects it and closes the screen immediately (no separate
 * Save/Cancel step). The list itself uses the shared
 * Widget.App.PickList.Row family (see themes.xml) so both pickers stay
 * visually and structurally consistent as more of the app gets themed.
 */
class LanguageSelectorDialogFragment : DialogFragment() {
    companion object {
        /** Ordered (code, display label) pairs — unchanged from the previous
         *  pop-up's fixed 13-language list. */
        private val LANGUAGES = listOf(
            "en" to "English",
            "fr" to "French (Français)",
            "de" to "German (Deutsch)",
            "it" to "Italian (Italiano)",
            "ja" to "Japanese (日本)",
            "ko" to "Korean (한국인)",
            "zh_CN" to "Chinese (Simplified) (中文(简体))",
            "zh_TW" to "Chinese (Traditional) (中文(繁体))",
            "es" to "Spanish (Español)",
            "uk" to "Ukrainian (Українська)",
            "ru" to "Russian (Русский)",
            "pl" to "Polish (Polski)",
            "tr" to "Turkish (Türk)"
        )

        fun newInstance(name: String, chatId: String) : LanguageSelectorDialogFragment {
            val languageSelectorDialogFragment = LanguageSelectorDialogFragment()

            val args = Bundle()
            args.putString("name", name)
            args.putString("chatId", chatId)

            languageSelectorDialogFragment.arguments = args

            return languageSelectorDialogFragment
        }
    }

    private var listener: StateChangesListener? = null

    private var btnBack: ImageButton? = null
    private var languageList: ListView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A match-parent view inside the default DialogFragment theme is still
        // a floating window with dialog insets. Use the app's normal screen
        // theme so the shared action bar and content genuinely fill the
        // screen (same approach as AdvancedModelSelectorDialogFragment).
        setStyle(STYLE_NORMAL, R.style.UI_Material)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_select_language, container, false)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        applySystemBarInsets()
    }

    /** Match the app's full-screen activity treatment on Android 15+. */
    private fun applySystemBarInsets() {
        if (Build.VERSION.SDK_INT < 35) return
        val window = dialog?.window ?: return
        val root = view ?: return
        window.decorView.post {
            val insets = window.decorView.rootWindowInsets ?: return@post
            root.findViewById<View>(R.id.action_bar)?.setPadding(
                0,
                insets.getInsets(WindowInsets.Type.statusBars()).top,
                0,
                0
            )
            root.setPadding(
                root.paddingLeft,
                root.paddingTop,
                root.paddingRight,
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentLanguage = requireArguments().getString("name").toString()

        btnBack = view.findViewById(R.id.btn_back)
        languageList = view.findViewById(R.id.language_list)

        btnBack?.setOnClickListener { dismiss() }

        val adapter = LanguageListAdapter(requireContext(), LANGUAGES, currentLanguage)
        adapter.setOnItemClickListener(object : LanguageListAdapter.OnItemClickListener {
            override fun onItemClick(code: String) {
                listener?.onSelected(code)
                dismiss()
            }
        })
        languageList?.adapter = adapter
    }

    fun setStateChangedListener(listener: StateChangesListener) {
        this.listener = listener
    }

    interface StateChangesListener {
        fun onSelected(name: String)
    }
}
