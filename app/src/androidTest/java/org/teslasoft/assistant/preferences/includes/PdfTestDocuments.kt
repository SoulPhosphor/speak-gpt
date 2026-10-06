/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.includes

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import java.io.File

/** Builds small real PDFs on the device for extraction and import tests. */
object PdfTestDocuments {
    private const val WIDTH = 612
    private const val HEIGHT = 792

    /** One page whose text is real PDF text, readable without OCR. */
    fun embeddedText(context: Context, text: String): File =
        write(context, listOf(PageKind.EMBEDDED to text))

    /**
     * One page whose words are drawn as filled shapes, not text, on the
     * page's default transparent background. Only OCR can read it, and only
     * when the rendered page has a white background.
     */
    fun shapesOnly(context: Context, text: String): File =
        write(context, listOf(PageKind.SHAPES to text))

    fun mixed(context: Context, embedded: String, shapes: String): File =
        write(context, listOf(PageKind.EMBEDDED to embedded, PageKind.SHAPES to shapes))

    fun manyShapePages(context: Context, pages: Int): File =
        write(context, List(pages) { PageKind.SHAPES to "Page number ${it + 1}" })

    private enum class PageKind { EMBEDDED, SHAPES }

    private fun write(context: Context, pages: List<Pair<PageKind, String>>): File {
        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 48f
        }
        pages.forEachIndexed { index, (kind, text) ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, index + 1).create())
            when (kind) {
                PageKind.EMBEDDED -> page.canvas.drawText(text, 72f, 144f, paint)
                PageKind.SHAPES -> {
                    val path = Path()
                    paint.getTextPath(text, 0, text.length, 72f, 144f, path)
                    page.canvas.drawPath(path, paint)
                }
            }
            document.finishPage(page)
        }
        val file = File(context.cacheDir, "pdf-test-${System.nanoTime()}.pdf")
        file.outputStream().use { document.writeTo(it) }
        document.close()
        return file
    }
}
