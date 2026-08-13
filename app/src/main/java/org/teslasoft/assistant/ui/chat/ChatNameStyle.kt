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
import androidx.core.content.res.ResourcesCompat
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.PersonaObject

/**
 * The single registry and resolver for chat speaker-name typography.
 *
 * Appearance owns the user and AI defaults. A companion may override either AI
 * value independently; an empty font id or zero size inherits the Appearance
 * value. Message layouts never select fonts or sizes directly.
 */
object ChatNameStyle {

    const val DEFAULT_FONT_ID = "roboto"
    const val DEFAULT_SIZE_SP = 18

    data class FontOption(
        val id: String,
        val displayName: String,
        @FontRes val fontRes: Int
    )

    data class Resolved(
        val fontId: String,
        val sizeSp: Int,
        val bold: Boolean
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

    val sizeOptionsSp: List<Int> = (12..32 step 2).toList()

    fun fontIdOrDefault(fontId: String?): String =
        fonts.firstOrNull { it.id == fontId }?.id ?: DEFAULT_FONT_ID

    fun fontLabel(fontId: String?): String =
        fonts.firstOrNull { it.id == fontIdOrDefault(fontId) }?.displayName
            ?: fonts.first().displayName

    fun typeface(context: Context, fontId: String?): Typeface {
        val option = fonts.firstOrNull { it.id == fontIdOrDefault(fontId) } ?: fonts.first()
        return ResourcesCompat.getFont(context, option.fontRes) ?: Typeface.DEFAULT
    }

    fun user(preferences: Preferences): Resolved = Resolved(
        fontId = fontIdOrDefault(preferences.getUserChatNameFont()),
        sizeSp = preferences.getUserChatNameSizeSp().coerceIn(12, 32),
        bold = preferences.getBoldUserChatName()
    )

    fun ai(preferences: Preferences, companion: PersonaObject? = null): Resolved {
        val inheritedFont = fontIdOrDefault(preferences.getAiChatNameFont())
        val inheritedSize = preferences.getAiChatNameSizeSp().coerceIn(12, 32)
        return Resolved(
            fontId = if (companion?.chatNameFontId.isNullOrEmpty()) {
                inheritedFont
            } else {
                fontIdOrDefault(companion?.chatNameFontId)
            },
            sizeSp = companion?.chatNameSizeSp?.takeIf { it > 0 }?.coerceIn(12, 32)
                ?: inheritedSize,
            bold = preferences.getBoldAiChatName()
        )
    }

    fun apply(textView: TextView, context: Context, style: Resolved) {
        textView.typeface = Typeface.create(
            typeface(context, style.fontId),
            if (style.bold) Typeface.BOLD else Typeface.NORMAL
        )
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.sizeSp.toFloat())
    }
}
