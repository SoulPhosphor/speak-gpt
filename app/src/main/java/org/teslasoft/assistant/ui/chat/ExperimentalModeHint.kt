/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.ui.chat

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.widget.EditText
import androidx.core.content.ContextCompat
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences

/**
 * Experimental Mode's reminder in the empty message box (owner ruling,
 * Nov 10 2026): while the chat's Experimental Mode is on, the atom icon sits
 * in front of the "Message" placeholder. It is part of the placeholder only,
 * so it never changes the box's size, the buttons, or the keyboard. The mode
 * is turned off from Quick Settings.
 */
class ExperimentalModeHint(
    private val input: EditText,
    private val preferences: () -> Preferences?
) {
    private val plain: String = input.context.getString(R.string.hint_message)

    /** The "Message" placeholder, with the atom in front while the mode is on. */
    fun messageHint(): CharSequence {
        if (preferences()?.getChatExperimentalMode() != true) return plain
        val icon = ContextCompat.getDrawable(input.context, R.drawable.ic_atom)
            ?.mutate() ?: return plain
        // Sized to the box's own text and colored like the placeholder, so
        // it follows the theme and the user's font size.
        val size = input.textSize.toInt()
        icon.setBounds(0, 0, size, size)
        icon.setTint(input.currentHintTextColor)
        return SpannableString("￼ $plain").apply {
            setSpan(CenteredIconSpan(icon), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Updates the placeholder now, unless it is showing something else
     *  (Listening, Transcribing, and so on). */
    fun refresh() {
        val current = input.hint?.toString() ?: return
        if (current == plain || current.endsWith(" $plain")) input.hint = messageHint()
    }

    /** Centers the icon on the text and leaves the line height untouched. */
    private class CenteredIconSpan(icon: Drawable) : ImageSpan(icon) {
        override fun getSize(
            paint: Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int = drawable.bounds.right

        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val icon = drawable
            val metrics = paint.fontMetricsInt
            val shift = y + (metrics.ascent + metrics.descent) / 2 - icon.bounds.height() / 2
            canvas.save()
            canvas.translate(x, shift.toFloat())
            icon.draw(canvas)
            canvas.restore()
        }
    }
}
