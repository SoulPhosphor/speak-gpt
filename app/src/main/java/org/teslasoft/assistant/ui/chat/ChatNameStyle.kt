/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.chat

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView
import androidx.annotation.FontRes
import androidx.annotation.StringRes
import androidx.core.content.res.ResourcesCompat
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.PersonaObject

/**
 * The single registry and resolver for chat speaker-name typography.
 *
 * The Name Style screen owns every value. The Default user and companion
 * styles live in app settings; a Companion, Glamour or Roleplay Character may
 * override each of font, size and font style independently, and an empty /
 * null override inherits the default. Message layouts never select fonts,
 * sizes or styles directly.
 */
object ChatNameStyle {

    const val DEFAULT_FONT_ID = "roboto"
    const val DEFAULT_SIZE_SP = 21

    const val STYLE_NORMAL = "normal"
    const val STYLE_BOLD = "bold"
    const val STYLE_ITALIC = "italic"
    const val STYLE_BOLD_ITALIC = "bold_italic"

    data class FontOption(
        val id: String,
        val displayName: String,
        @FontRes val fontRes: Int
    )

    data class FontStyleOption(val id: String, @StringRes val label: Int)

    data class Resolved(
        val fontId: String,
        val sizeSp: Int,
        val bold: Boolean,
        val italic: Boolean = false
    ) {
        val fontStyleId: String get() = styleId(bold, italic)
    }

    /** One entity's stored overrides; null/empty fields inherit the default. */
    data class Override(
        val fontId: String? = null,
        val sizeSp: Int? = null,
        val fontStyle: String? = null
    )

    val fonts: List<FontOption> = listOf(
        FontOption(DEFAULT_FONT_ID, "Roboto", R.font.roboto_ttf),
        FontOption("kalnia", "Kalnia", R.font.kalnia),
        FontOption("homemade_apple", "Homemade Apple", R.font.homemade_apple),
        FontOption("crafty_girls", "Crafty Girls", R.font.crafty_girls),
        FontOption("manufacturing_consent", "Manufacturing Consent", R.font.manufacturing_consent),
        FontOption("special_elite", "Special Elite", R.font.special_elite),
        FontOption("solitreo", "Solitreo", R.font.solitreo),
        FontOption("sn_pro", "SN Pro", R.font.sn_pro)
    )

    /** [fonts] in alphabetical order, for pickers. */
    val fontsAlphabetical: List<FontOption> get() = fonts.sortedBy { it.displayName.lowercase() }

    val fontStyles: List<FontStyleOption> = listOf(
        FontStyleOption(STYLE_NORMAL, R.string.name_style_font_style_normal),
        FontStyleOption(STYLE_BOLD, R.string.name_style_font_style_bold),
        FontStyleOption(STYLE_ITALIC, R.string.name_style_font_style_italic),
        FontStyleOption(STYLE_BOLD_ITALIC, R.string.name_style_font_style_bold_italic)
    )

    // One-sp increments keep the tuned 21sp target selectable while preserving
    // the existing supported 12-32sp range.
    val sizeOptionsSp: List<Int> = (12..32).toList()

    fun fontIdOrDefault(fontId: String?): String =
        fonts.firstOrNull { it.id == fontId }?.id ?: DEFAULT_FONT_ID

    fun fontLabel(fontId: String?): String =
        fonts.firstOrNull { it.id == fontIdOrDefault(fontId) }?.displayName
            ?: fonts.first().displayName

    fun typeface(context: Context, fontId: String?): Typeface {
        val option = fonts.firstOrNull { it.id == fontIdOrDefault(fontId) } ?: fonts.first()
        return ResourcesCompat.getFont(context, option.fontRes) ?: Typeface.DEFAULT
    }

    /** The complete styled typeface for [style]: font plus bold/italic. */
    fun styledTypeface(context: Context, style: Resolved): Typeface =
        Typeface.create(typeface(context, style.fontId), typefaceStyle(style.bold, style.italic))

    fun styleId(bold: Boolean, italic: Boolean): String = when {
        bold && italic -> STYLE_BOLD_ITALIC
        bold -> STYLE_BOLD
        italic -> STYLE_ITALIC
        else -> STYLE_NORMAL
    }

    fun isKnownStyle(styleId: String?): Boolean = fontStyles.any { it.id == styleId }

    fun isBold(styleId: String): Boolean = styleId == STYLE_BOLD || styleId == STYLE_BOLD_ITALIC

    fun isItalic(styleId: String): Boolean = styleId == STYLE_ITALIC || styleId == STYLE_BOLD_ITALIC

    private fun typefaceStyle(bold: Boolean, italic: Boolean): Int = when {
        bold && italic -> Typeface.BOLD_ITALIC
        bold -> Typeface.BOLD
        italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }

    private fun clampSize(sizeSp: Int): Int = sizeSp.coerceIn(sizeOptionsSp.first(), sizeOptionsSp.last())

    /** The Default user name style (no Glamour or Roleplay override). */
    fun user(preferences: Preferences): Resolved = Resolved(
        fontId = fontIdOrDefault(preferences.getUserChatNameFont()),
        sizeSp = clampSize(preferences.getUserChatNameSizeSp()),
        bold = preferences.getBoldUserChatName(),
        italic = preferences.getItalicUserChatName()
    )

    /** The Default companion name style (no per-companion override). */
    fun companionDefault(preferences: Preferences): Resolved = Resolved(
        fontId = fontIdOrDefault(preferences.getAiChatNameFont()),
        sizeSp = clampSize(preferences.getAiChatNameSizeSp()),
        bold = preferences.getBoldAiChatName(),
        italic = preferences.getItalicAiChatName()
    )

    fun ai(preferences: Preferences, companion: PersonaObject? = null): Resolved =
        withOverride(
            companionDefault(preferences),
            companion?.let { Override(it.chatNameFontId, it.chatNameSizeSp, it.chatNameFontStyle) }
        )

    /** [base] with each non-empty field of [override] applied on top. */
    fun withOverride(base: Resolved, override: Override?): Resolved {
        if (override == null) return base
        val fontId = override.fontId?.takeIf { it.isNotEmpty() }?.let(::fontIdOrDefault) ?: base.fontId
        val sizeSp = override.sizeSp?.takeIf { it > 0 }?.let(::clampSize) ?: base.sizeSp
        val styleId = override.fontStyle?.takeIf(::isKnownStyle) ?: base.fontStyleId
        return Resolved(fontId, sizeSp, isBold(styleId), isItalic(styleId))
    }

    fun apply(textView: TextView, context: Context, style: Resolved) {
        textView.typeface = styledTypeface(context, style)
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.sizeSp.toFloat())
    }
}
