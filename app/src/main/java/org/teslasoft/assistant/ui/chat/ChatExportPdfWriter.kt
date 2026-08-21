/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.TimeZone

/**
 * Writes a paginated PDF using the same Markwon Markdown renderer as chat.
 * The PDF therefore contains rendered headings, emphasis, lists, code blocks,
 * tables, task lists, strikethrough, and LaTeX instead of printing Markdown
 * punctuation as plain text.
 */
object ChatExportPdfWriter {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val PAGE_MARGIN = 40
    private const val CONTENT_WIDTH = PAGE_WIDTH - (PAGE_MARGIN * 2)
    private const val CONTENT_HEIGHT = PAGE_HEIGHT - (PAGE_MARGIN * 2)
    private const val BODY_TEXT_SIZE = 12f

    fun toBytes(
        context: Context,
        messages: List<ChatExportMessage>,
        options: ChatExportOptions,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): ByteArray {
        val rendered = renderDocument(context, messages, options, locale, timeZone)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = BODY_TEXT_SIZE
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val layout = StaticLayout.Builder
            .obtain(rendered, 0, rendered.length, textPaint, CONTENT_WIDTH)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(true)
            .setLineSpacing(0f, 1.15f)
            .build()

        val document = PdfDocument()
        try {
            writePages(document, layout)
            return ByteArrayOutputStream().use { output ->
                document.writeTo(output)
                output.toByteArray()
            }
        } finally {
            document.close()
        }
    }

    private fun renderDocument(
        context: Context,
        messages: List<ChatExportMessage>,
        options: ChatExportOptions,
        locale: Locale,
        timeZone: TimeZone
    ): SpannableStringBuilder {
        val renderedDocument = SpannableStringBuilder()
        val markwon = ChatMarkdownRenderer.builder(context, BODY_TEXT_SIZE).build()

        messages.forEachIndexed { index, message ->
            val nameStart = renderedDocument.length
            renderedDocument.append(
                ChatExportFormatter.formatHeader(message, options, locale, timeZone)
            )
            val nameEnd = (nameStart + message.name.length).coerceAtMost(renderedDocument.length)
            if (nameEnd > nameStart) {
                renderedDocument.setSpan(
                    StyleSpan(Typeface.BOLD),
                    nameStart,
                    nameEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            renderedDocument.append('\n')
            if (message.isCompanion) {
                renderedDocument.append(
                    markwon.toMarkdown(ChatMarkdownRenderer.prepare(message.content))
                )
            } else {
                // User messages are intentionally plain in the chat surface;
                // preserve that same behavior in the PDF export.
                renderedDocument.append(message.content)
            }

            if (index < messages.lastIndex) {
                renderedDocument.append("\n\n")
            }
        }

        return renderedDocument
    }

    private fun writePages(document: PdfDocument, layout: StaticLayout) {
        var firstLine = 0
        var pageNumber = 1
        val lineCount = layout.lineCount.coerceAtLeast(1)

        while (firstLine < lineCount) {
            val firstLineTop = layout.getLineTop(firstLine)
            var nextLine = firstLine
            while (nextLine < lineCount &&
                layout.getLineBottom(nextLine) - firstLineTop <= CONTENT_HEIGHT
            ) {
                nextLine++
            }
            if (nextLine == firstLine) nextLine++

            val page = document.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber)
                    .create()
            )
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            canvas.save()
            canvas.clipRect(
                PAGE_MARGIN.toFloat(),
                PAGE_MARGIN.toFloat(),
                (PAGE_WIDTH - PAGE_MARGIN).toFloat(),
                (PAGE_HEIGHT - PAGE_MARGIN).toFloat()
            )
            canvas.translate(
                PAGE_MARGIN.toFloat(),
                PAGE_MARGIN.toFloat() - firstLineTop.toFloat()
            )
            layout.draw(canvas)
            canvas.restore()
            document.finishPage(page)
            firstLine = nextLine
            pageNumber++
        }
    }
}
