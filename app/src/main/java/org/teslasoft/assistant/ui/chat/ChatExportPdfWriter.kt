/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.chat

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream

object ChatExportPdfWriter {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val LEFT = 42f
    private const val RIGHT = 42f
    private const val TOP = 44f
    private const val BOTTOM = 44f
    private const val TEXT_SIZE = 10.5f
    private const val LINE_SPACING = 15f

    fun toBytes(content: String): ByteArray {
        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TEXT_SIZE
        }
        val maxWidth = PAGE_WIDTH - LEFT - RIGHT
        val lines = content.split('\n').flatMap { wrapLine(it.removeSuffix("\r"), paint, maxWidth) }
        val linesPerPage = ((PAGE_HEIGHT - TOP - BOTTOM) / LINE_SPACING).toInt().coerceAtLeast(1)

        try {
            lines.chunked(linesPerPage).forEachIndexed { pageIndex, pageLines ->
                val pageInfo = PdfDocument.PageInfo.Builder(
                    PAGE_WIDTH,
                    PAGE_HEIGHT,
                    pageIndex + 1
                ).create()
                val page = document.startPage(pageInfo)
                val baseline = TOP - paint.fontMetrics.ascent
                pageLines.forEachIndexed { lineIndex, line ->
                    page.canvas.drawText(
                        line,
                        LEFT,
                        baseline + lineIndex * LINE_SPACING,
                        paint
                    )
                }
                document.finishPage(page)
            }

            ByteArrayOutputStream().use { output ->
                document.writeTo(output)
                return output.toByteArray()
            }
        } finally {
            document.close()
        }
    }

    private fun wrapLine(line: String, paint: Paint, maxWidth: Float): List<String> {
        if (line.isEmpty()) return listOf("")

        val result = mutableListOf<String>()
        var start = 0
        while (start < line.length) {
            val count = paint.breakText(line, start, line.length, true, maxWidth, null)
                .coerceAtLeast(1)
            result += line.substring(start, start + count)
            start += count
        }
        return result
    }
}
