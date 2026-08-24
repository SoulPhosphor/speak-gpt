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
import android.graphics.Rect
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextDirectionHeuristics
import android.text.style.LeadingMarginSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatTextView
import org.teslasoft.assistant.R

/**
 * Text that flows around the portrait's current rendered bounds.
 *
 * The portrait is treated as a temporary exclusion area, not as a permanent
 * column. Each instance measures its own top against the live portrait bottom,
 * so a name, metadata row, or opened Thinking disclosure can consume some or
 * all of the overlap before the next block is laid out. Changing the portrait's
 * dimensions therefore changes the clearance automatically.
 */
class PortraitAwareMessageTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var applyingGeometry = false
    private var sourceText: CharSequence = ""
    private var appliedMargin = Int.MIN_VALUE
    private var appliedLineCount = Int.MIN_VALUE
    private var appliedPortraitOnLeft: Boolean? = null
    private var refreshPosted = false
    private val normalGravity = gravity
    private val normalTextDirection = textDirection

    private companion object {
        private const val LEFT_TO_RIGHT_ISOLATE = "\u2066"
        private const val RIGHT_TO_LEFT_ISOLATE = "\u2067"
        private const val POP_DIRECTIONAL_ISOLATE = "\u2069"
    }

    private data class PortraitFlow(
        val geometry: PortraitExclusionGeometry.TextFlow,
        val portraitOnLeft: Boolean?
    )

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (applyingGeometry) {
            super.setText(text, type)
            return
        }

        sourceText = withoutPortraitSpans(text ?: "")
        applyPortraitGeometry(force = true)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        requestPortraitGeometryUpdate()
    }

    /** Re-evaluate after the row changes (including Thinking open/close). */
    fun requestPortraitGeometryUpdate() {
        if (refreshPosted || !isAttachedToWindow) return
        refreshPosted = true
        post {
            refreshPosted = false
            applyPortraitGeometry(force = false)
        }
    }

    private fun applyPortraitGeometry(force: Boolean) {
        if (applyingGeometry) return

        val portraitVisible = findRowView<ImageView>(R.id.icon)?.visibility == View.VISIBLE
        val source = sourceText
        val measuredFlow = if (portraitVisible && source.isNotEmpty()) {
            measuredPortraitFlow()
        } else {
            PortraitFlow(PortraitExclusionGeometry.TextFlow(0, 0), null)
        }
        val flow = measuredFlow.geometry

        if (!force && flow.leadingMarginPx == appliedMargin &&
            flow.lineCount == appliedLineCount &&
            measuredFlow.portraitOnLeft == appliedPortraitOnLeft) {
            return
        }
        appliedMargin = flow.leadingMarginPx
        appliedLineCount = flow.lineCount
        appliedPortraitOnLeft = measuredFlow.portraitOnLeft

        applyingGeometry = true
        try {
            val portraitOnLeft = measuredFlow.portraitOnLeft
            if (portraitVisible && portraitOnLeft != null) {
                // LeadingMarginSpan follows paragraph direction. Match that
                // direction to the portrait's measured physical side, then
                // isolate the actual prose direction inside it below.
                textDirection = if (portraitOnLeft) {
                    View.TEXT_DIRECTION_LTR
                } else {
                    View.TEXT_DIRECTION_RTL
                }
                gravity = if (isRtl(source, 0, source.length)) {
                    Gravity.RIGHT or Gravity.TOP
                } else {
                    Gravity.LEFT or Gravity.TOP
                }
            } else {
                textDirection = normalTextDirection
                gravity = normalGravity
            }

            val builder = displayBuilder(source, portraitOnLeft)
            if (flow.leadingMarginPx > 0 && flow.lineCount > 0 && builder.isNotEmpty()) {
                // LeadingMarginSpan2 restarts its line count at later paragraph
                // boundaries. Limit this span to the opening paragraph so the
                // portrait exclusion cannot reappear farther down the reply.
                val firstParagraphEnd =
                    builder.indexOf('\n').let { if (it < 0) builder.length else it + 1 }
                builder.setSpan(
                    PortraitClearanceSpan(flow.leadingMarginPx, flow.lineCount),
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

    private fun measuredPortraitFlow(): PortraitFlow {
        val row = findRowView<ViewGroup>(R.id.ui)
        val portrait = findRowView<ImageView>(R.id.icon)
        if (row != null && portrait != null && row.width > 0 && portrait.width > 0 && width > 0) {
            val portraitBounds = boundsInRow(row, portrait)
            val contentBounds = boundsInRow(row, this)
            val horizontal = PortraitExclusionGeometry.horizontalExclusion(
                portraitLeft = portraitBounds.left,
                portraitRight = portraitBounds.right,
                contentLeft = contentBounds.left,
                contentRight = contentBounds.right,
                gapPx = dimen(R.dimen.chat_portrait_text_gap)
            )
            val flow = PortraitExclusionGeometry.textFlow(
                portraitNearEdge = horizontal.leadingMarginPx,
                contentEdge = 0,
                portraitBottom = portraitBounds.bottom,
                contentTop = contentBounds.top,
                gapPx = 0,
                lineHeightPx = lineHeight
            )
            return PortraitFlow(flow, horizontal.portraitOnLeft)
        }

        // Pre-layout fallback only. The first real row layout immediately
        // replaces this with measured portrait and text bounds.
        val portraitSize = portrait?.layoutParams?.width?.takeIf { it > 0 }
            ?: dimen(R.dimen.chat_portrait_size)
        val portraitBottom = dimen(R.dimen.chat_portrait_top_offset) + portraitSize
        val contentTop = dimen(R.dimen.chat_message_content_padding)
        val rowDirection = row?.layoutDirection ?: layoutDirection
        val portraitOnLogicalStart = tag?.toString() != "user"
        val portraitOnLeft = if (portraitOnLogicalStart) {
            rowDirection != View.LAYOUT_DIRECTION_RTL
        } else {
            rowDirection == View.LAYOUT_DIRECTION_RTL
        }
        val flow = PortraitExclusionGeometry.textFlow(
            portraitNearEdge = portraitSize + dimen(R.dimen.chat_portrait_edge_inset),
            contentEdge = dimen(R.dimen.chat_message_speaker_inset) +
                dimen(R.dimen.chat_message_content_padding),
            portraitBottom = portraitBottom,
            contentTop = contentTop,
            gapPx = dimen(R.dimen.chat_portrait_text_gap),
            lineHeightPx = lineHeight
        )
        return PortraitFlow(flow, portraitOnLeft)
    }

    private fun displayBuilder(
        source: CharSequence,
        portraitOnLeft: Boolean?
    ): SpannableStringBuilder {
        val builder = SpannableStringBuilder(source)
        builder.getSpans(0, builder.length, PortraitClearanceSpan::class.java)
            .forEach(builder::removeSpan)

        if (portraitOnLeft == null || source.isEmpty()) return builder

        // The paragraph direction controls which physical edge receives the
        // leading margin. Preserve the prose's own bidi direction with isolates
        // whenever it differs, inserting backwards so existing Markdown spans
        // retain their ranges.
        val marginParagraphIsRtl = !portraitOnLeft
        val paragraphs = mutableListOf<Triple<Int, Int, String>>()
        var start = 0
        while (start < source.length) {
            val newline = source.indexOf('\n', start)
            val end = if (newline < 0) source.length else newline
            if (end > start) {
                val contentIsRtl = isRtl(source, start, end - start)
                if (contentIsRtl != marginParagraphIsRtl) {
                    val isolate = if (contentIsRtl) {
                        RIGHT_TO_LEFT_ISOLATE
                    } else {
                        LEFT_TO_RIGHT_ISOLATE
                    }
                    paragraphs += Triple(start, end, isolate)
                }
            }
            if (newline < 0) break
            start = newline + 1
        }
        paragraphs.asReversed().forEach { (paragraphStart, paragraphEnd, isolate) ->
            builder.insert(paragraphEnd, POP_DIRECTIONAL_ISOLATE)
            builder.insert(paragraphStart, isolate)
        }
        return builder
    }

    private fun isRtl(source: CharSequence, start: Int, count: Int): Boolean =
        TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(source, start, count)

    private fun withoutPortraitSpans(source: CharSequence): CharSequence {
        if (source !is Spanned) return source
        val builder = SpannableStringBuilder(source)
        builder.getSpans(0, builder.length, PortraitClearanceSpan::class.java)
            .forEach(builder::removeSpan)
        return builder
    }

    private fun boundsInRow(row: ViewGroup, view: View): Rect {
        val bounds = Rect(0, 0, view.width, view.height)
        row.offsetDescendantRectToMyCoords(view, bounds)
        return bounds
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
