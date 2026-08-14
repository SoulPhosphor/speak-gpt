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

    private companion object {
        // Unicode bidi isolates used to keep left-to-right user text ordered
        // correctly inside the right-to-left paragraph that positions the
        // portrait clearance margin. LRI opens a left-to-right isolate; PDI
        // closes it.
        private const val LEFT_TO_RIGHT_ISOLATE = "\u2066"
        private const val POP_DIRECTIONAL_ISOLATE = "\u2069"
    }

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
                // paragraph's leading edge to the right (mirroring the AI side)
                // while keeping normal English copy visually left-aligned in
                // the available space.
                textDirection = View.TEXT_DIRECTION_RTL
                gravity = Gravity.LEFT or Gravity.TOP
            } else {
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            }

            // The user side's right-to-left base direction (set above) exists
            // ONLY to place the portrait clearance on the correct (right) side,
            // mirroring the AI side. Isolate each of the user's own paragraphs
            // as left-to-right so that base direction never reorders their
            // punctuation: without this a trailing period was pulled to the
            // visual start of a line, showing "root." as ".root". A paragraph
            // break ends a bidi isolate, so every paragraph is wrapped, not just
            // the first. The isolate characters are display-only — copy, edit,
            // share, and speak all read the stored message, not this view's text.
            val builder = if (isUser) {
                val isolated = source.toString().split('\n').joinToString("\n") { line ->
                    if (line.isEmpty()) line
                    else LEFT_TO_RIGHT_ISOLATE + line + POP_DIRECTIONAL_ISOLATE
                }
                SpannableStringBuilder(isolated)
            } else {
                SpannableStringBuilder(source)
            }
            builder.getSpans(0, builder.length, PortraitClearanceSpan::class.java)
                .forEach(builder::removeSpan)

            val margin = portraitClearanceWidthPx()
            val lineCount = portraitClearanceLineCount(name)
            if (margin > 0 && lineCount > 0) {
                // Inset only the opening lines that actually sit beside the
                // portrait, and only within the first paragraph. A
                // LeadingMarginSpan2's first-line count restarts at every
                // paragraph break, so a span covering the whole message
                // re-indents the top of every paragraph and wastes space down
                // the reply. Scoping it to the first paragraph keeps the
                // stagger at the portrait and returns all later text to the
                // full reading width.
                val firstParagraphEnd =
                    builder.indexOf('\n').let { if (it < 0) builder.length else it + 1 }
                builder.setSpan(
                    PortraitClearanceSpan(margin, lineCount),
                    0,
                    firstParagraphEnd,
                    Spanned.SPAN_PARAGRAPH
                )
            }
            super.setText(builder, BufferType.SPANNABLE)
        } finally {
            applyingGeometry = false
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateTopMargin(findRowView<TextView>(R.id.username))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun updateTopMargin(name: TextView?) {
        val params = layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (baseTopMargin == null) baseTopMargin = params.topMargin

        val nameVisible = name?.visibility == View.VISIBLE
        val desired = if (nameVisible) {
            val gap = dimen(R.dimen.chat_name_body_gap)
            val bubblePadding = dimen(R.dimen.chat_message_content_padding)
            val bubble = findRowView<View>(R.id.bubble_bg)
            val portraitVisible = findRowView<ImageView>(R.id.icon)?.visibility == View.VISIBLE
            val bubblePainted = bubble?.background != null
            val nameBottomInBubble = if (portraitVisible || bubblePainted) {
                // Read the name's and bubble's REAL current top margins —
                // already applied by ChatAdapter.updateIdentityGeometry for
                // this bind — instead of assuming the portrait layout's fixed
                // offsets. Without this the no-portrait, bubble-on state (the
                // name centered on the bubble's top border, not sitting
                // chat_name_portrait_top below a portrait-aligned bubble) left
                // a large dead band between the name and the first line.
                val nameTop = (name?.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
                val bubbleTop = (bubble?.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
                max(0, nameTop + (name?.lineHeight ?: 0) - bubbleTop)
            } else {
                // No-portrait, bubble-off presentation is out of scope for
                // this pass; keep its existing (portrait-anchor)
                // approximation unchanged.
                dimen(R.dimen.chat_name_portrait_top) + (name?.lineHeight ?: 0)
            }
            max(0, nameBottomInBubble + gap - bubblePadding)
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
        var row: View? = this
        while (row != null && row.id != R.id.ui) {
            row = row.parent as? View
        }
        return row?.findViewById(id)
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
