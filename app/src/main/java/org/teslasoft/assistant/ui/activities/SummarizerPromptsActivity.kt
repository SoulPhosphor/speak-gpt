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

import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.CompanionPromptVariant
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.DiscardChangesDialog
import org.teslasoft.assistant.ui.util.PromptVariantEditor
import org.teslasoft.assistant.ui.util.SaveIconFlash
import org.teslasoft.assistant.ui.util.ScreenChrome
import org.teslasoft.assistant.ui.widgets.AppDropdown
import org.teslasoft.assistant.util.summarizer.SummarizerPromptSets

/**
 * Summarizer Prompts (owner ruling, Oct 3 2026), reached from Summarizer
 * Settings. One collection is open at a time: the Edit Prompt dropdown only
 * chooses, and Go opens the choice (asking first when the open collection has
 * unsaved changes). The collection is edited with the same multiple-prompt
 * editor as Edit Companion; built-in prompts cannot be deleted. Nothing is
 * stored until the header Save icon is tapped.
 */
class SummarizerPromptsActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var btnSave: ImageButton? = null
    private var textKind: TextView? = null
    private var btnGo: MaterialButton? = null
    private var textKindTitle: TextView? = null
    private var textKindHint: TextView? = null
    private var editor: PromptVariantEditor? = null

    /** The collection open in the editor. */
    private var openKind = SummarizerPromptSets.Kind.SUMMARY

    /** The dropdown's current choice; opened only through Go. */
    private var chosenKind = SummarizerPromptSets.Kind.SUMMARY

    /** The open collection as last saved — the unsaved-changes baseline. */
    private var savedJson = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_summarizer_prompts)

        preferences = Preferences.getPreferences(this, "")
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        btnSave = findViewById(R.id.btn_save)
        textKind = findViewById(R.id.text_prompt_kind)
        btnGo = findViewById(R.id.btn_go)
        textKindTitle = findViewById(R.id.text_prompt_kind_title)
        textKindHint = findViewById(R.id.text_prompt_kind_hint)
        ScreenChrome.apply(this, actionBar, btnBack, btnSave)

        // Built-in prompts of whichever collection is open cannot be deleted.
        editor = PromptVariantEditor(
            this,
            findViewById(R.id.prompt_variant_editor),
            R.string.summarizer_prompts_field_hint,
            null
        ) { SummarizerPromptSets.isBuiltIn(openKind, it.id) }

        val restoredOpen = savedInstanceState?.getString(STATE_OPEN_KIND)
            ?.let { name -> SummarizerPromptSets.Kind.entries.firstOrNull { it.name == name } }
        if (restoredOpen != null) {
            openKind = restoredOpen
            chosenKind = savedInstanceState.getString(STATE_CHOSEN_KIND)
                ?.let { name -> SummarizerPromptSets.Kind.entries.firstOrNull { it.name == name } }
                ?: restoredOpen
            savedJson = savedInstanceState.getString(STATE_SAVED_JSON).orEmpty()
            editor?.load(
                CompanionPromptVariant.fromJson(savedInstanceState.getString(STATE_EDITOR_JSON).orEmpty()),
                savedInstanceState.getInt(STATE_ACTIVE_INDEX, 0),
                CompanionPromptVariant.fromJson(savedJson)
            )
            showKindText()
        } else {
            openCollection(SummarizerPromptSets.Kind.SUMMARY)
        }

        textKind?.setOnClickListener { showKindDropdown() }
        btnGo?.setOnClickListener { onGo() }
        btnSave?.setOnClickListener { save() }
        btnBack?.setOnClickListener { attemptLeave() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                attemptLeave()
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_OPEN_KIND, openKind.name)
        outState.putString(STATE_CHOSEN_KIND, chosenKind.name)
        outState.putString(STATE_SAVED_JSON, savedJson)
        editor?.let {
            outState.putString(STATE_EDITOR_JSON, it.toJson())
            outState.putInt(STATE_ACTIVE_INDEX, it.activeIndex())
        }
    }

    private fun kindLabel(kind: SummarizerPromptSets.Kind): String = getString(
        when (kind) {
            SummarizerPromptSets.Kind.SUMMARY -> R.string.summarizer_prompt_kind_summary
            SummarizerPromptSets.Kind.COMPACTION -> R.string.summarizer_prompt_kind_compaction
            SummarizerPromptSets.Kind.IMAGE -> R.string.summarizer_prompt_kind_image
        }
    )

    private fun kindIntro(kind: SummarizerPromptSets.Kind): String = getString(
        when (kind) {
            SummarizerPromptSets.Kind.SUMMARY -> R.string.summarizer_prompts_summary_intro
            SummarizerPromptSets.Kind.COMPACTION -> R.string.summarizer_prompts_compaction_intro
            SummarizerPromptSets.Kind.IMAGE -> R.string.summarizer_prompts_image_intro
        }
    )

    private fun showKindText() {
        textKind?.text = kindLabel(chosenKind)
        textKindTitle?.text = kindLabel(openKind)
        textKindHint?.text = kindIntro(openKind) + "\n\n" + getString(R.string.summarizer_prompts_how_it_works)
    }

    private fun openCollection(kind: SummarizerPromptSets.Kind) {
        val prefs = preferences ?: return
        val variants = SummarizerPromptSets.load(prefs, kind)
        openKind = kind
        chosenKind = kind
        savedJson = CompanionPromptVariant.toJson(variants)
        editor?.load(variants, variants.indexOfFirst { it.isDefault }.coerceAtLeast(0))
        showKindText()
    }

    private fun showKindDropdown() {
        val anchor = textKind ?: return
        val kinds = SummarizerPromptSets.Kind.entries
        AppDropdown.show(anchor, kinds.map { kindLabel(it) }, kinds.indexOf(chosenKind)) { position ->
            chosenKind = kinds[position]
            showKindText()
        }
    }

    private fun isDirty(): Boolean = (editor?.toJson() ?: savedJson) != savedJson

    private fun onGo() {
        val target = chosenKind
        if (target == openKind) return
        if (isDirty()) {
            DiscardChangesDialog.show(this) { openCollection(target) }
        } else {
            openCollection(target)
        }
    }

    private fun save() {
        val prefs = preferences ?: return
        val current = editor ?: return
        SummarizerPromptSets.save(prefs, openKind, current.variants())
        savedJson = current.toJson()
        current.markSaved()
        btnSave?.let { SaveIconFlash.flash(it) }
        Toast.makeText(this, R.string.companion_editor_saved_toast, Toast.LENGTH_SHORT).show()
    }

    private fun attemptLeave() {
        if (isDirty()) {
            DiscardChangesDialog.show(this) { finish() }
        } else {
            finish()
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
            val scroll = findViewById<ScrollView>(R.id.scroll)
            val density = resources.displayMetrics.density
            scroll?.setPadding(
                0,
                0,
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom +
                    (24 * density).toInt()
            )
        } catch (_: Exception) { /* unused */ }
    }

    private companion object {
        const val STATE_OPEN_KIND = "state_open_kind"
        const val STATE_CHOSEN_KIND = "state_chosen_kind"
        const val STATE_SAVED_JSON = "state_saved_json"
        const val STATE_EDITOR_JSON = "state_editor_json"
        const val STATE_ACTIVE_INDEX = "state_active_index"
    }
}
