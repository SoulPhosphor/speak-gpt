/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.ui.chat

/**
 * Joins the Voice Debug Log lines that one voice moment writes at the same
 * instant into a single log entry.
 *
 * A moment such as "the reply finished and the mic reopened" used to write
 * several separate entries (the re-arm, the listening turn, the mic route),
 * each with its own timestamp and Copy button. Code that produces such a
 * moment runs inside [run]; every line offered on the same thread while it
 * runs is collected and written once when the outermost [run] ends.
 *
 * Only lines written synchronously in one call chain are joined. Lines from
 * another thread, or written outside [run], are not collected: the caller
 * writes them individually, exactly as before. Nothing spread over time is
 * ever held back, so a process that dies mid-moment loses nothing that was
 * already written.
 */
class VoiceLogGroup(private val write: (tag: String, level: String, message: String) -> Unit) {

    data class Line(val tag: String, val level: String, val message: String)

    private var depth = 0
    private var owner: Thread? = null
    private val lines = ArrayList<Line>()

    /** Runs [block]; the lines it offers are written as one entry afterwards.
     *  Nested calls join the outermost one. */
    fun <T> run(block: () -> T): T {
        val current = Thread.currentThread()
        if (depth > 0 && owner !== current) return block()
        if (depth == 0) owner = current
        depth++
        try {
            return block()
        } finally {
            depth--
            if (depth == 0) {
                owner = null
                flush()
            }
        }
    }

    /** True when the line was collected for the current moment; false means
     *  no moment is open on this thread and the caller writes it now. */
    fun offer(tag: String, level: String, message: String): Boolean {
        if (depth == 0 || owner !== Thread.currentThread()) return false
        lines.add(Line(tag, level, message))
        return true
    }

    private fun flush() {
        if (lines.isEmpty()) return
        val collected = lines.toList()
        lines.clear()
        val entry = compose(collected) ?: return
        try {
            write(entry.tag, entry.level, entry.message)
        } catch (_: Throwable) { /* diagnostics must never disturb the caller */ }
    }

    companion object {
        private const val INDENT = "  "

        /**
         * The single entry for [lines]: the first line's tag and text lead;
         * every later line follows on its own indented line, prefixed with its
         * own tag so nothing about its source is lost. The entry's level is the
         * most severe of its lines. Continuation lines are indented, so no line
         * inside the entry can be mistaken for the start of a new entry.
         */
        fun compose(lines: List<Line>): Line? {
            val head = lines.firstOrNull() ?: return null
            val sb = StringBuilder(indentContinuation(head.message, INDENT))
            for (line in lines.drop(1)) {
                sb.append('\n').append(INDENT).append(line.tag).append(": ")
                    .append(indentContinuation(line.message, INDENT + INDENT))
            }
            val level = lines.maxByOrNull { severity(it.level) }!!.level
            return Line(head.tag, level, sb.toString())
        }

        private fun indentContinuation(message: String, indent: String): String =
            message.trimEnd().replace("\n", "\n$indent")

        private fun severity(level: String): Int = when (level.lowercase()) {
            "error" -> 4
            "warning" -> 3
            "info" -> 2
            "debug" -> 1
            else -> 0
        }
    }
}
