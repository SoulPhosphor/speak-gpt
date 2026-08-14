/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import org.teslasoft.assistant.R
import kotlin.math.ceil
import kotlin.math.max

/**
 * Message TextView used by the tuned chat layout.
 *
 * When a portrait is visible, the first few lines reserve only the horizontal
 * strip actually occupied by the portrait; once the text is below the portrait
 * it returns to the full reading width. The same view also derives the message
 * top margin from the rendered speaker-name line height, so changing name font
 * or size keeps the requested gap without another fixed Y coordinate.
 *
 * The user row forces the paragraph base direction to RTL only while the
 * portrait clearance is active. LeadingMarginSpan is direction-aware, so this
 * places the temporary margin on the visual right; absolute LEFT gravity keeps
 * ordinary LTR user text left-aligned inside the remaining reading area.
 */
class PortraitAwareMessageTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var applyingGeometry = false
    private var baseTopMargin: Int? = null

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (applyingGeometry) {
            super.setText(text, type)
            return
        }

        applyingGeometry = true
        try {
            val portraitVisible = findRowView<ImageView>(R.id.icon)?.visibility == View.VISIBLE
            val name = findRowView<TextView>(R.id.username)
            updateTopMargin(name)

            val source = text ?: ""
            if (!portraitVisible || source.isEmpty()) {
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                super.setText(source, type)
                return
            }

            val isUser = tag?.toString() == "user"
            if (isUser) {
                // LeadingMarginSpan follows paragraph direction. Force the
                // paragraph's leading edge to the right while keeping normal
                // English copy visually left-aligned in the available space.
                textDirection = View.TEXT_DIRECTION_RTL
                gravity = Gravity.LEFT or Gravity.TOP
            } else {
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            }

            val builder = SpannableStringBuilder(source)
            builder.getSpans(0, builder.length, PortraitClearanceSpan::class.java)
                .forEach(builder::removeSpan)

            val margin = portraitClearanceWidthPx()
            val lineCount = portraitClearanceLineCount(name)
            if (margin > 0 && lineCount > 0) {
                builder.setSpan(
                    PortraitClearanceSpan(margin, lineCount),
                    0,
                    builder.length,
                    Spanned.SPAN_PARAGRAPH
                )
            }
            super.setText(builder, BufferType.SPANNABLE)
        } finally {
            applyingGeometry = false
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateTopMargin(findRowView(R.id.username))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun updateTopMargin(name: TextView?) {
        val params = layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (baseTopMargin == null) baseTopMargin = params.topMargin

        val nameVisible = name?.visibility == View.VISIBLE
        val desired = if (nameVisible) {
            val nameTop = dimen(R.dimen.chat_name_portrait_top)
            val gap = dimen(R.dimen.chat_name_body_gap)
            val bubblePadding = dimen(R.dimen.chat_message_content_padding)
            max(0, nameTop + (name?.lineHeight ?: 0) + gap - bubblePadding)
        } else {
            baseTopMargin ?: 0
        }

        if (params.topMargin != desired) {
            params.topMargin = desired
            layoutParams = params
        }
    }

    private fun portraitClearanceWidthPx(): Int {
        val portraitSize = dimen(R.dimen.chat_portrait_size)
        val portraitEdge = dimen(R.dimen.chat_portrait_edge_inset)
        val bubbleEdge = dimen(R.dimen.chat_message_speaker_inset)
        val portraitX = portraitEdge - bubbleEdge // tuned -8dp
        val bubblePadding = dimen(R.dimen.chat_message_content_padding)
        val gap = dimen(R.dimen.chat_portrait_text_gap)
        return max(0, portraitSize + portraitX - bubblePadding + gap)
    }

    private fun portraitClearanceLineCount(name: TextView?): Int {
        val portraitBottom =
            dimen(R.dimen.chat_portrait_top_offset) + dimen(R.dimen.chat_portrait_size)
        val bubblePadding = dimen(R.dimen.chat_message_content_padding)
        val nameTop = dimen(R.dimen.chat_name_portrait_top)
        val nameGap = dimen(R.dimen.chat_name_body_gap)
        val textTop = if (name?.visibility == View.VISIBLE) {
            max(bubblePadding, nameTop + (name?.lineHeight ?: 0) + nameGap)
        } else {
            bubblePadding
        }
        val heightToClear = max(0, portraitBottom - textTop)
        return ceil(heightToClear.toDouble() / lineHeight.coerceAtLeast(1)).toInt()
    }

    private fun dimen(id: Int): Int = resources.getDimensionPixelSize(id)

    private inline fun <reified T : View> findRowView(id: Int): T? {
        var root: View = this
        while (root.parent is View) root = root.parent as View
        return root.findViewById(id)
    }

    private class PortraitClearanceSpan(
        private val firstMargin: Int,
        private val lineCount: Int
    ) : LeadingMarginSpan.LeadingMarginSpan2 {

        override fun getLeadingMargin(first: Boolean): Int = if (first) firstMargin else 0

        override fun getLeadingMarginLineCount(): Int = lineCount

        override fun drawLeadingMargin(
            c: Canvas,
            p: Paint,
            x: Int,
            dir: Int,
            top: Int,
            baseline: Int,
            bottom: Int,
            text: CharSequence,
            start: Int,
            end: Int,
            first: Boolean,
            layout: Layout
        ) = Unit
    }
}
