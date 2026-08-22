/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.chat

import android.content.Context
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin

/**
 * The one Markwon configuration used by chat Markdown and Markdown-aware
 * exports. Keeping the parser setup here prevents PDF output from drifting
 * away from what the chat displays.
 */
object ChatMarkdownRenderer {

    fun builder(context: Context, latexTextSize: Float): Markwon.Builder =
        Markwon.builder(context)
            .usePlugin(HtmlPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(JLatexMathPlugin.create(latexTextSize) { builder ->
                builder.inlinesEnabled(true)
            })

    fun prepare(markdown: String): String = parseLatex(
        markdown.split("\n").joinToString("\n") { it.trim() } + "\n"
    )

    private fun parseLatex(markdown: String): String {
        val pattern = Regex("(`[^`]*`|```[\\s\\S]*?```)|\\\\\\[|\\\\\\]|\\\\\\(|\\\\\\)")
        val result = StringBuilder()
        var index = 0

        pattern.findAll(markdown).forEach { match ->
            if (match.groups[1] != null) {
                result.append(markdown.substring(index, match.range.first))
                result.append(match.value)
            } else {
                result.append(markdown.substring(index, match.range.first))
                when (match.value) {
                    "\\[" -> result.append("$$").append("\n").append("\\[")
                    "\\]" -> result.append("\\]").append("\n").append("$$")
                    "\\(" -> result.append("$$\\(")
                    "\\)" -> result.append("\\)$$")
                }
            }
            index = match.range.last + 1
        }

        result.append(markdown.substring(index))

        val prepared = result.toString()
        val openMatrixPattern = "\\begin{bmatrix}"
        val closeMatrixPattern = "\\end{bmatrix}"
        val openedMatricesCount = prepared.split(openMatrixPattern).size - 1
        val closedMatricesCount = prepared.split(closeMatrixPattern).size - 1
        val openMathPattern = "\\["
        val closeMathPattern = "\\]"
        val openedMathCount = prepared.split(openMathPattern).size - 1
        val closedMathCount = prepared.split(closeMathPattern).size - 1

        if (openedMatricesCount > closedMatricesCount) {
            result.append("\\end{bmatrix}")
        }
        if (openedMathCount > closedMathCount) {
            result.append("\n\\]")
        }

        return result.toString()
    }
}
