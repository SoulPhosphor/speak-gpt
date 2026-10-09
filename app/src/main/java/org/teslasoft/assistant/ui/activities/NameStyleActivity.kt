/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.chat.NameStyleDraft
import org.teslasoft.assistant.ui.chat.ChatNameStyle
import org.teslasoft.assistant.ui.util.ScreenChrome
import org.teslasoft.assistant.util.WindowInsetsUtil
import org.teslasoft.assistant.ui.widgets.AppDropdown
import java.util.EnumSet

/** Name typography is edited in local drafts; only an explicit Save writes it. */
class NameStyleActivity : SettingsPageActivity() {
    private enum class Type(val label: Int) {
        COMPANION(R.string.name_style_type_companion), GLAMOUR(R.string.name_style_type_glamour),
        ROLEPLAY(R.string.name_style_type_roleplay), DEFAULT(R.string.name_style_type_default)
    }
    private data class Target(val type: Type, val id: String, val name: String, val previewName: String) {
        val key: String get() = "${type.name}:$id"
    }
    private data class Draft(val target: Target, val state: NameStyleDraft) {
        val dirty: Boolean get() = state.dirty
    }
    companion object {
        private const val DEFAULT_COMPANION = "companion"
        private const val DEFAULT_USER = "user"
        private const val EXTRA_TARGET_TYPE = "targetType"
        private const val EXTRA_TARGET_ID = "targetId"

        /** Each opens with that name already chosen as the one being styled. */
        fun companionIntent(context: Context, companionId: String): Intent =
            targetIntent(context, Type.COMPANION, companionId)

        fun glamourIntent(context: Context, glamourId: String): Intent =
            targetIntent(context, Type.GLAMOUR, glamourId)

        fun roleplayIntent(context: Context, roleplayCharacterId: String): Intent =
            targetIntent(context, Type.ROLEPLAY, roleplayCharacterId)

        private fun targetIntent(context: Context, type: Type, id: String): Intent =
            Intent(context, NameStyleActivity::class.java)
                .putExtra(EXTRA_TARGET_TYPE, type.name)
                .putExtra(EXTRA_TARGET_ID, id)
    }
    private lateinit var preferences: Preferences
    private lateinit var typeValue: TextView
    private lateinit var nameValue: TextView
    private lateinit var sizeValue: TextView
    private lateinit var styleValue: TextView
    private lateinit var fontValue: TextView
    private lateinit var preview: TextView
    private lateinit var savedPanel: TextView
    private lateinit var restoreButton: MaterialButton
    private lateinit var saveButton: MaterialButton
    private var type: Type? = null
    private var selectedKey: String? = null
    private val drafts = linkedMapOf<String, Draft>()
    private var scratch = ChatNameStyle.Override()
    private var loading = true
    private var saving = false
    private val selected: Draft? get() = selectedKey?.let(drafts::get)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_name_style)
        preferences = Preferences.getPreferences(this, "")
        val back = findViewById<ImageButton>(R.id.btn_back)
        ScreenChrome.apply(this, findViewById(R.id.action_bar), back)
        back.setOnClickListener { requestExit() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = requestExit()
        })
        typeValue = bindRow(R.id.row_type, R.string.name_style_type)
        nameValue = bindRow(R.id.row_name, R.string.name_style_name)
        sizeValue = bindRow(R.id.row_font_size, R.string.name_style_font_size)
        styleValue = bindRow(R.id.row_font_style, R.string.name_style_font_style)
        fontValue = bindRow(R.id.row_font, R.string.name_style_font)
        preview = findViewById(R.id.text_preview)
        savedPanel = findViewById(R.id.saved_style_panel)
        saveButton = findViewById(R.id.btn_save_style)
        restoreButton = findViewById(R.id.btn_restore_original)
        restoreButton.setOnClickListener {
            selected?.state?.restoreOriginal()
            render()
        }
        typeValue.setOnClickListener {
            AppDropdown.show(typeValue, Type.entries.map { getString(it.label) }, type?.ordinal ?: -1) {
                if (type != Type.entries[it]) {
                    type = Type.entries[it]
                    selectedKey = null
                    render()
                }
            }
        }
        nameValue.setOnClickListener { showNames() }
        sizeValue.setOnClickListener {
            val sizes = ChatNameStyle.sizeOptionsSp
            AppDropdown.show(sizeValue, sizes.map(::sizeLabel), sizes.indexOf(resolvedDraft().sizeSp)) {
                edit { copy(sizeSp = sizes[it]) }
            }
        }
        styleValue.setOnClickListener {
            val styles = ChatNameStyle.fontStyles
            AppDropdown.show(styleValue, styles.map { getString(it.label) }, styles.indexOfFirst { it.id == resolvedDraft().fontStyleId }) {
                edit { copy(fontStyle = styles[it].id) }
            }
        }
        fontValue.setOnClickListener {
            val fonts = ChatNameStyle.fontsAlphabetical
            AppDropdown.show(fontValue, fonts.map { it.displayName }, fonts.indexOfFirst { it.id == resolvedDraft().fontId },
                optionTypeface = { ChatNameStyle.typeface(this, fonts[it].id) }) {
                edit { copy(fontId = fonts[it].id) }
            }
        }
        saveButton.setOnClickListener { selected?.let { saveDrafts(listOf(it), exit = false) } }
        type = savedInstanceState?.getString("type")?.let { value -> Type.entries.firstOrNull { it.name == value } }
        selectedKey = savedInstanceState?.getString("selected")
        val targetType = intent.getStringExtra(EXTRA_TARGET_TYPE)?.let { value -> Type.entries.firstOrNull { it.name == value } }
        val targetId = intent.getStringExtra(EXTRA_TARGET_ID).orEmpty()
        if (savedInstanceState == null && targetType != null) {
            type = targetType
            if (targetId.isNotEmpty()) selectedKey = Target(targetType, targetId, "", "").key
        }
        scratch = savedInstanceState?.getString("scratch")?.let { Gson().fromJson(it, ChatNameStyle.Override::class.java) }
            ?: ChatNameStyle.Override()
        val restored = savedInstanceState?.getString("drafts")?.let {
            Gson().fromJson(it, Array<Draft>::class.java).associateBy { draft -> draft.target.key }
        }.orEmpty()
        // Keep restored edits in the collection serialized by onSaveInstanceState
        // before target loading suspends, including across another recreation.
        drafts.putAll(restored)
        render()
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    Type.entries.flatMap(::readTargets).map { target ->
                        val saved = readSaved(target)
                        Draft(target, NameStyleDraft(saved))
                    }
                }
                val loadedKeys = loaded.map { it.target.key }.toSet()
                drafts.keys.retainAll(loadedKeys)
                loaded.forEach { draft -> drafts[draft.target.key] = drafts[draft.target.key]?.copy(target = draft.target) ?: draft }
                if (selectedKey?.let { it in drafts } != true) selectedKey = null
                selected?.let {
                    it.state.pending = merge(it.state.pending, scratch)
                    scratch = ChatNameStyle.Override()
                }
            } catch (_: Exception) { toast(R.string.name_style_load_failed) }
            loading = false
            render()
        }
    }
    private fun bindRow(id: Int, label: Int): TextView = findViewById<View>(id).let {
        it.findViewById<TextView>(R.id.label).setText(label)
        it.findViewById(R.id.value)
    }
    private fun showNames() {
        if (loading) { toast(R.string.name_style_loading); return }
        val choices = drafts.values.filter { type == null || it.target.type == type }
        if (choices.isEmpty()) { toast(R.string.name_style_no_names); return }
        AppDropdown.show(nameValue, choices.map {
            if (type == null) getString(R.string.name_style_grouped_name, getString(it.target.type.label), it.target.name) else it.target.name
        }, choices.indexOfFirst { it.target.key == selectedKey }) { index ->
            val chosen = choices[index]
            selectedKey = chosen.target.key
            type = chosen.target.type
            chosen.state.pending = merge(chosen.state.pending, scratch)
            scratch = ChatNameStyle.Override()
            render()
        }
    }
    private fun merge(base: ChatNameStyle.Override, edits: ChatNameStyle.Override) = ChatNameStyle.Override(
        edits.fontId ?: base.fontId, edits.sizeSp ?: base.sizeSp, edits.fontStyle ?: base.fontStyle)
    private fun edit(change: ChatNameStyle.Override.() -> ChatNameStyle.Override) {
        if (saving) return
        selected?.let { it.state.pending = it.state.pending.change() } ?: run { scratch = scratch.change() }
        render()
    }
    private fun base(target: Target?): ChatNameStyle.Resolved =
        if (target?.type == Type.COMPANION || target == null && type == Type.COMPANION || target?.type == Type.DEFAULT && target.id == DEFAULT_COMPANION)
            ChatNameStyle.companionDefault(preferences) else ChatNameStyle.user(preferences)
    private fun resolvedDraft() = ChatNameStyle.withOverride(base(selected?.target), selected?.state?.pending ?: scratch)
    private fun defaultLabel(target: Target): Int = if (target.type == Type.COMPANION || target.type == Type.DEFAULT && target.id == DEFAULT_COMPANION)
        R.string.name_style_default_companion else R.string.name_style_default_user
    private fun readTargets(type: Type): List<Target> = when (type) {
        Type.COMPANION -> PersonaPreferences.getPersonaPreferences(this).getPersonasList().map { Target(type, it.id, it.label, it.label) }
        Type.GLAMOUR -> memoryStore()?.getActiveUserPersonas().orEmpty().map { Target(type, it.personaId, it.name, it.displayName?.takeIf(String::isNotBlank) ?: it.name) }
        Type.ROLEPLAY -> memoryStore()?.getActiveRoleplayCharacters().orEmpty().map { Target(type, it.roleplayCharacterId, it.name, it.name) }
        Type.DEFAULT -> listOf(Target(type, DEFAULT_COMPANION, getString(R.string.name_style_default_companion), getString(R.string.name_style_default_companion)),
            Target(type, DEFAULT_USER, getString(R.string.name_style_default_user), preferences.getDefaultDisplayedUsername().ifBlank { getString(R.string.chat_role_user) }))
    }.sortedBy { it.name.lowercase() }
    private fun normalized(font: String?, size: Int?, style: String?) = ChatNameStyle.Override(
        font?.takeIf(String::isNotBlank), size?.takeIf { it > 0 }, style?.takeIf(ChatNameStyle::isKnownStyle))
    private fun readSaved(target: Target): ChatNameStyle.Override = when (target.type) {
        Type.DEFAULT -> base(target).let { ChatNameStyle.Override(it.fontId, it.sizeSp, it.fontStyleId) }
        Type.COMPANION -> PersonaPreferences.getPersonaPreferences(this).getPersona(target.id).let { normalized(it.chatNameFontId, it.chatNameSizeSp, it.chatNameFontStyle) }
        Type.GLAMOUR -> requireNotNull(memoryStore()?.getUserPersona(target.id)).let { normalized(it.nameFontId, it.nameSizeSp, it.nameFontStyle) }
        Type.ROLEPLAY -> requireNotNull(memoryStore()?.getRoleplayCharacter(target.id)).let { normalized(it.nameFontId, it.nameSizeSp, it.nameFontStyle) }
    }
    private fun memoryStore(): MemoryStore? = if (MemoryStore.isProvisioned(this)) MemoryStore.getInstance(this) else null
    private fun saveDrafts(items: List<Draft>, exit: Boolean) {
        if (saving) return
        val changes = items.filter { it.dirty }
        if (changes.isEmpty()) { if (exit) finish(); return }
        saving = true
        render()
        lifecycleScope.launch {
            var failed = false
            for (draft in changes.sortedBy { if (it.target.type == Type.DEFAULT) 0 else 1 }) {
                try {
                    val result = withContext(Dispatchers.IO) { write(draft.target, draft.state.pending); readSaved(draft.target) }
                    draft.state.markSaved(result)
                } catch (_: Exception) { failed = true; break }
            }
            saving = false
            render()
            toast(if (failed) R.string.message_save_failed else R.string.message_saved)
            if (exit && !failed) finish()
        }
    }
    private fun write(target: Target, value: ChatNameStyle.Override) {
        when (target.type) {
            Type.DEFAULT -> writeDefault(target.id == DEFAULT_COMPANION, value.fontId, value.sizeSp, value.fontStyle)
            Type.COMPANION -> {
                val personas = PersonaPreferences.getPersonaPreferences(this)
                check(personas.getPersonasList().any { it.id == target.id })
                val persona = personas.getPersona(target.id)
                persona.chatNameFontId = value.fontId.orEmpty()
                persona.chatNameSizeSp = value.sizeSp ?: 0
                persona.chatNameFontStyle = value.fontStyle.orEmpty()
                personas.setPersona(persona)
            }
            Type.GLAMOUR -> {
                val store = requireNotNull(memoryStore())
                requireNotNull(store.getUserPersona(target.id))
                store.setUserPersonaNameStyle(target.id, value.fontId, value.sizeSp, value.fontStyle)
            }
            Type.ROLEPLAY -> {
                val store = requireNotNull(memoryStore())
                requireNotNull(store.getRoleplayCharacter(target.id))
                store.setRoleplayCharacterNameStyle(target.id, value.fontId, value.sizeSp, value.fontStyle)
            }
        }
    }
    private fun writeDefault(companion: Boolean, fontId: String?, sizeSp: Int?, fontStyle: String?) {
        if (companion) {
            fontId?.let { preferences.setAiChatNameFont(it) }
            sizeSp?.let { preferences.setAiChatNameSizeSp(it) }
            fontStyle?.let {
                preferences.setBoldAiChatName(ChatNameStyle.isBold(it))
                preferences.setItalicAiChatName(ChatNameStyle.isItalic(it))
            }
        } else {
            fontId?.let { preferences.setUserChatNameFont(it) }
            sizeSp?.let { preferences.setUserChatNameSizeSp(it) }
            fontStyle?.let {
                preferences.setBoldUserChatName(ChatNameStyle.isBold(it))
                preferences.setItalicUserChatName(ChatNameStyle.isItalic(it))
            }
        }
    }

    private fun requestExit() {
        if (saving) return
        val dirty = drafts.values.filter { it.dirty }
        if (dirty.isEmpty() && scratch == ChatNameStyle.Override()) { finish(); return }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.name_style_unsaved_title)
            .setMessage(if (scratch != ChatNameStyle.Override()) R.string.name_style_unassigned_message else R.string.name_style_unsaved_message)
            .setNegativeButton(R.string.name_style_discard) { _, _ -> finish() }
            .setNeutralButton(R.string.name_style_keep_editing) { _, _ -> }
        if (scratch == ChatNameStyle.Override()) dialog.setPositiveButton(R.string.name_style_save_all) { _, _ -> saveDrafts(dirty, exit = true) }
        dialog.show()
    }
    private fun summary(style: ChatNameStyle.Resolved): String = getString(R.string.name_style_saved_values,
        ChatNameStyle.fontLabel(style.fontId), sizeLabel(style.sizeSp), fontStyleLabel(style))
    private fun fontStyleLabel(style: ChatNameStyle.Resolved) = getString(ChatNameStyle.fontStyles.first { it.id == style.fontStyleId }.label)
    private fun render() {
        val draft = selected
        val select = getString(R.string.dropdown_select)
        typeValue.text = type?.let { getString(it.label) } ?: select
        nameValue.text = draft?.target?.name ?: select
        val style = resolvedDraft()
        sizeValue.text = sizeLabel(style.sizeSp)
        styleValue.text = fontStyleLabel(style)
        fontValue.text = ChatNameStyle.fontLabel(style.fontId)
        fontValue.typeface = ChatNameStyle.typeface(this, style.fontId)
        listOf(typeValue, nameValue, sizeValue, styleValue, fontValue).forEach { it.isEnabled = !saving }
        preview.text = draft?.target?.previewName ?: getString(R.string.name_style_preview_sample)
        ChatNameStyle.apply(preview, this, style)
        savedPanel.text = if (draft == null) "" else {
            val inherited = draft.state.saved == ChatNameStyle.Override()
            val label = if (draft.target.type == Type.DEFAULT || inherited) defaultLabel(draft.target) else R.string.name_style_custom_settings
            val stored = ChatNameStyle.withOverride(base(draft.target), draft.state.saved)
            getString(R.string.name_style_currently_saved, getString(label)) + "\n" + summary(stored) +
                if (draft.target.type != Type.DEFAULT && !inherited) "\n" + getString(defaultLabel(draft.target)) + ": " +
                    getString(R.string.name_style_default_values, ChatNameStyle.fontLabel(base(draft.target).fontId),
                        sizeLabel(base(draft.target).sizeSp), fontStyleLabel(base(draft.target))) else ""
        }
        saveButton.text = draft?.let { getString(R.string.name_style_save_for, it.target.name) } ?: getString(R.string.name_style_save_changes)
        saveButton.isEnabled = draft?.dirty == true && !saving
        restoreButton.visibility = if (draft == null) View.INVISIBLE else View.VISIBLE
        restoreButton.isEnabled = draft != null && !saving
    }
    private fun sizeLabel(size: Int): String = getString(R.string.appearance_size_sp, size)
    private fun toast(id: Int) = Toast.makeText(this, id, Toast.LENGTH_SHORT).show()
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("type", type?.name)
        outState.putString("selected", selectedKey)
        outState.putString("scratch", Gson().toJson(scratch))
        outState.putString("drafts", Gson().toJson(drafts.values.filter { it.dirty }))
        super.onSaveInstanceState(outState)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        WindowInsetsUtil.adjustPaddings(this, R.id.action_bar, EnumSet.of(WindowInsetsUtil.Companion.Flags.STATUS_BAR, WindowInsetsUtil.Companion.Flags.IGNORE_PADDINGS))
        val bar = findViewById<View>(R.id.name_style_save_bar)
        val left = bar.paddingLeft
        val top = bar.paddingTop
        val right = bar.paddingRight
        val bottom = bar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(bar) { view, insets ->
            view.setPadding(left, top, right, bottom + insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }
        ViewCompat.requestApplyInsets(bar)
    }
}
