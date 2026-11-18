/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.util

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View

/**
 * Tappable chat names in the error log (owner design, Nov 18 2026). A log
 * line names a chat as `"Chat name" [chat:ID]`; the error log shows only the
 * quoted name, as a link that opens that chat. The link's look is the log
 * text view's own link color, so it follows the theme.
 */
object ErrorLogChatLinks {

    private val MARKER = Regex("\"([^\"]*)\" \\[chat:([^\\]\\s]+)]")

    /** A chat name as it is written into a log line. */
    fun mark(name: String, chatId: String): String =
        "\"${name.replace("\"", "'")}\" [chat:$chatId]"

    /** One link found in a line: the displayed text's name range and its chat. */
    data class Link(val start: Int, val end: Int, val chatId: String)

    /** [raw] with every marker reduced to its quoted name, plus where each
     *  linked name sits in the result. */
    fun parse(raw: CharSequence): Pair<String, List<Link>> {
        val out = StringBuilder()
        val links = ArrayList<Link>()
        var last = 0
        for (match in MARKER.findAll(raw)) {
            out.append(raw, last, match.range.first)
            val name = match.groupValues[1]
            out.append('"')
            val start = out.length
            out.append(name)
            links.add(Link(start, out.length, match.groupValues[2]))
            out.append('"')
            last = match.range.last + 1
        }
        out.append(raw, last, raw.length)
        return Pair(out.toString(), links)
    }

    /** [raw] as shown in the error log: names tappable, markers hidden. Spans
     *  already on [raw] are not kept, so call this before any other styling. */
    fun render(raw: CharSequence, onOpenChat: (String) -> Unit): CharSequence {
        if (!MARKER.containsMatchIn(raw)) return raw
        val (text, links) = parse(raw)
        val styled = SpannableStringBuilder(text)
        for (link in links) {
            styled.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = onOpenChat(link.chatId)
            }, link.start, link.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return styled
    }
}
