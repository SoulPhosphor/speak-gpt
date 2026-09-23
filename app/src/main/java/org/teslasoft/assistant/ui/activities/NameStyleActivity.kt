/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.activities

import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.chat.ChatNameStyle
import org.teslasoft.assistant.ui.util.ScreenChrome
import org.teslasoft.assistant.ui.widgets.AppDropdown

/**
 * Name Style, opened from Appearance: pick a Type and a Name, then set that
 * name's font size, font style and font. Every change saves at once and the
 * centered preview shows the name exactly as chat will.
 *
 * Ownership: the Default Companion / User styles live in app settings; a
 * Companion's overrides live with the companion, and a Glamour's or Roleplay
 * Character's in its own database record. An override left unset inherits
 * the matching Default.
 */
class NameStyleActivity : FragmentActivity() {

    private enum class Type(val label: Int) {
        COMPANION(R.string.name_style_type_companion),
        GLAMOUR(R.string.name_style_type_glamour),
        ROLEPLAY(R.string.name_style_type_roleplay),
        DEFAULT(R.string.name_style_type_default)
    }

    /** One pickable name. For [Type.DEFAULT] the id is [DEFAULT_COMPANION] or [DEFAULT_USER]. */
    private data class Target(val id: String, val name: String, val previewName: String)

    /** A target's stored overrides plus the Default they inherit from. */
    private data class Current(val base: ChatNameStyle.Resolved, val override: ChatNameStyle.Override?) {
        val resolved: ChatNameStyle.Resolved get() = ChatNameStyle.withOverride(base, override)
    }

    companion object {
        private const val DEFAULT_COMPANION = "companion"
        private const val DEFAULT_USER = "user"
    }

    private lateinit var preferences: Preferences
    private var actionBar: ConstraintLayout? = null

    private lateinit var typeValue: TextView
    private lateinit var nameValue: TextView
    private lateinit var sizeValue: TextView
    private lateinit var styleValue: TextView
    private lateinit var fontValue: TextView
    private lateinit var preview: TextView

    private var type: Type? = null
    private var targets: List<Target> = emptyList()
    private var target: Target? = null
    private var current: Current? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_name_style)

        preferences = Preferences.getPreferences(this, "")
        actionBar = findViewById(R.id.action_bar)
        val btnBack = findViewById<ImageButton>(R.id.btn_back)
        ScreenChrome.apply(this, actionBar, btnBack)
        btnBack?.setOnClickListener { finish() }

        typeValue = bindRow(R.id.row_type, R.string.name_style_type)
        nameValue = bindRow(R.id.row_name, R.string.name_style_name)
        sizeValue = bindRow(R.id.row_font_size, R.string.name_style_font_size)
        styleValue = bindRow(R.id.row_font_style, R.string.name_style_font_style)
        fontValue = bindRow(R.id.row_font, R.string.name_style_font)
        preview = findViewById(R.id.text_preview)

        typeValue.setOnClickListener { showTypePicker() }
        nameValue.setOnClickListener { showNamePicker() }
        sizeValue.setOnClickListener { showSizePicker() }
        styleValue.setOnClickListener { showStylePicker() }
        fontValue.setOnClickListener { showFontPicker() }

        render()
    }

    override fun onResume() {
        super.onResume()
        // A name may have been renamed or deleted elsewhere; reload the list.
        if (type != null) loadTargets(keepSelection = true)
    }

    private fun bindRow(rowId: Int, labelRes: Int): TextView {
        val row = findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.label).setText(labelRes)
        return row.findViewById(R.id.value)
    }

    /* ------------------------------ pickers ------------------------------ */

    private fun showTypePicker() {
        val types = Type.entries
        AppDropdown.show(typeValue, types.map { getString(it.label) }, type?.let(types::indexOf) ?: -1) { index ->
            if (types[index] == type) return@show
            type = types[index]
            target = null
            current = null
            targets = emptyList()
            render()
            loadTargets(keepSelection = false)
        }
    }

    private fun showNamePicker() {
        AppDropdown.show(nameValue, targets.map { it.name }, targets.indexOfFirst { it.id == target?.id }) { index ->
            target = targets[index]
            loadCurrent()
        }
    }

    private fun showSizePicker() {
        val style = current?.resolved ?: return
        val sizes = ChatNameStyle.sizeOptionsSp
        AppDropdown.show(sizeValue, sizes.map(::sizeLabel), sizes.indexOf(style.sizeSp)) { index ->
            save(sizeSp = sizes[index])
        }
    }

    private fun showStylePicker() {
        val style = current?.resolved ?: return
        val styles = ChatNameStyle.fontStyles
        AppDropdown.show(
            styleValue,
            styles.map { getString(it.label) },
            styles.indexOfFirst { it.id == style.fontStyleId }
        ) { index ->
            save(fontStyle = styles[index].id)
        }
    }

    private fun showFontPicker() {
        val style = current?.resolved ?: return
        val fonts = ChatNameStyle.fontsAlphabetical
        AppDropdown.show(
            fontValue,
            fonts.map { it.displayName },
            fonts.indexOfFirst { it.id == style.fontId },
            optionTypeface = { index -> ChatNameStyle.typeface(this, fonts[index].id) }
        ) { index ->
            save(fontId = fonts[index].id)
        }
    }

    /* ------------------------------ loading ------------------------------ */

    private fun loadTargets(keepSelection: Boolean) {
        val requestedType = type ?: return
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { readTargets(requestedType) }
            if (type != requestedType) return@launch
            targets = loaded
            val keep = if (keepSelection) loaded.firstOrNull { it.id == target?.id } else null
            target = keep
            if (keep == null) {
                current = null
                render()
            } else {
                loadCurrent()
            }
        }
    }

    private fun readTargets(type: Type): List<Target> = try {
        when (type) {
            Type.COMPANION -> PersonaPreferences.getPersonaPreferences(this).getPersonasList()
                .map { Target(it.id, it.label, it.label) }
                .sortedBy { it.name.lowercase() }
            Type.GLAMOUR -> memoryStore()?.getActiveUserPersonas().orEmpty()
                .map { Target(it.personaId, it.name, it.displayName?.takeIf(String::isNotBlank) ?: it.name) }
            Type.ROLEPLAY -> memoryStore()?.getActiveRoleplayCharacters().orEmpty()
                .map { Target(it.roleplayCharacterId, it.name, it.name) }
            Type.DEFAULT -> listOf(
                getString(R.string.name_style_default_companion).let { Target(DEFAULT_COMPANION, it, it) },
                Target(
                    DEFAULT_USER,
                    getString(R.string.name_style_default_user),
                    preferences.getDefaultDisplayedUsername().trim()
                        .ifEmpty { getString(R.string.chat_role_user) }
                )
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun loadCurrent() {
        val requestedType = type ?: return
        val requestedTarget = target ?: return
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { readCurrent(requestedType, requestedTarget.id) }
            if (type != requestedType || target != requestedTarget) return@launch
            current = loaded
            render()
        }
    }

    private fun readCurrent(type: Type, id: String): Current? = try {
        when (type) {
            Type.DEFAULT -> Current(
                if (id == DEFAULT_COMPANION) ChatNameStyle.companionDefault(preferences) else ChatNameStyle.user(preferences),
                null
            )
            Type.COMPANION -> {
                val persona = PersonaPreferences.getPersonaPreferences(this).getPersona(id)
                Current(
                    ChatNameStyle.companionDefault(preferences),
                    ChatNameStyle.Override(persona.chatNameFontId, persona.chatNameSizeSp, persona.chatNameFontStyle)
                )
            }
            Type.GLAMOUR -> memoryStore()?.getUserPersona(id)?.let {
                Current(ChatNameStyle.user(preferences), ChatNameStyle.Override(it.nameFontId, it.nameSizeSp, it.nameFontStyle))
            }
            Type.ROLEPLAY -> memoryStore()?.getRoleplayCharacter(id)?.let {
                Current(ChatNameStyle.user(preferences), ChatNameStyle.Override(it.nameFontId, it.nameSizeSp, it.nameFontStyle))
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Never provisions the memory store just to list names. */
    private fun memoryStore(): MemoryStore? =
        if (MemoryStore.isProvisioned(this)) MemoryStore.getInstance(this) else null

    /* ------------------------------ saving ------------------------------ */

    /** Saves the one changed value for the selected name, then re-renders. */
    private fun save(fontId: String? = null, sizeSp: Int? = null, fontStyle: String? = null) {
        val saveType = type ?: return
        val saveTarget = target ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    write(saveType, saveTarget.id, fontId, sizeSp, fontStyle)
                } catch (_: Exception) {
                    // Nothing saved; the re-read below shows the stored value.
                }
            }
            loadCurrent()
        }
    }

    private fun write(type: Type, id: String, fontId: String?, sizeSp: Int?, fontStyle: String?) {
        when (type) {
            Type.DEFAULT -> writeDefault(id == DEFAULT_COMPANION, fontId, sizeSp, fontStyle)
            Type.COMPANION -> {
                val personas = PersonaPreferences.getPersonaPreferences(this)
                val persona = personas.getPersona(id)
                fontId?.let { persona.chatNameFontId = it }
                sizeSp?.let { persona.chatNameSizeSp = it }
                fontStyle?.let { persona.chatNameFontStyle = it }
                personas.setPersona(persona)
            }
            Type.GLAMOUR -> {
                val store = memoryStore() ?: return
                val record = store.getUserPersona(id) ?: return
                store.setUserPersonaNameStyle(
                    id,
                    fontId ?: record.nameFontId,
                    sizeSp ?: record.nameSizeSp,
                    fontStyle ?: record.nameFontStyle
                )
            }
            Type.ROLEPLAY -> {
                val store = memoryStore() ?: return
                val record = store.getRoleplayCharacter(id) ?: return
                store.setRoleplayCharacterNameStyle(
                    id,
                    fontId ?: record.nameFontId,
                    sizeSp ?: record.nameSizeSp,
                    fontStyle ?: record.nameFontStyle
                )
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

    /* ------------------------------ rendering ------------------------------ */

    private fun render() {
        val select = getString(R.string.dropdown_select)
        typeValue.text = type?.let { getString(it.label) } ?: select
        nameValue.text = target?.name ?: select
        nameValue.isEnabled = type != null

        val style = current?.resolved
        val stylesEnabled = style != null && target != null
        for (value in listOf(sizeValue, styleValue, fontValue)) value.isEnabled = stylesEnabled

        if (style == null || target == null) {
            sizeValue.text = select
            styleValue.text = select
            fontValue.text = select
            fontValue.typeface = Typeface.DEFAULT
            preview.visibility = View.GONE
            return
        }

        sizeValue.text = sizeLabel(style.sizeSp)
        styleValue.text = ChatNameStyle.fontStyles.firstOrNull { it.id == style.fontStyleId }
            ?.let { getString(it.label) } ?: select
        fontValue.text = ChatNameStyle.fontLabel(style.fontId)
        // The closed Font control shows the chosen font in its own typeface.
        fontValue.typeface = ChatNameStyle.typeface(this, style.fontId)

        preview.text = target?.previewName
        ChatNameStyle.apply(preview, this, style)
        preview.visibility = View.VISIBLE
    }

    private fun sizeLabel(sizeSp: Int): String = getString(R.string.appearance_size_sp, sizeSp)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT < 35) return
        try {
            val insets = window.decorView.rootWindowInsets
            actionBar?.setPadding(0, insets.getInsets(WindowInsets.Type.statusBars()).top, 0, 0)
            val density = resources.displayMetrics.density
            findViewById<ScrollView>(R.id.scroll)?.setPadding(
                0,
                0,
                0,
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom + (24 * density).toInt()
            )
        } catch (_: Exception) { /* Window insets are not available yet. */ }
    }
}
