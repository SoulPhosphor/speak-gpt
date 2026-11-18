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

package org.teslasoft.assistant.preferences.memory

import org.json.JSONArray

/**
 * How much of one chat has been scanned for memories, for the Create Outline
 * screen's Memory Scan Progress line (owner design, Nov 18 2026): 100% when
 * nothing is left to scan, Unscanned when nothing has been scanned, otherwise
 * the first and last scanned message numbers. Message numbers count every
 * message in the chat from 1, the same numbering as each message's own
 * Message number.
 */
object ChatScanCoverage {

    sealed interface Status {
        data object Unscanned : Status
        data object Complete : Status
        data class Range(val first: Int, val last: Int) : Status
    }

    /** One chat message as the scanner sees it. */
    data class Message(val id: String, val role: String, val text: String)

    /**
     * [messages] is the chat in order; [scannedContents] the content of each
     * scanned transcript row (a JSON list of turns); [hasUnscanned] whether any
     * row still waits to be scanned. Null when part of the chat was scanned but
     * none of it can be matched to a current message (for example, every
     * scanned message has since been edited or deleted).
     */
    fun describe(
        messages: List<Message>,
        scannedContents: List<String>,
        hasUnscanned: Boolean
    ): Status? {
        if (scannedContents.isEmpty()) return Status.Unscanned
        if (!hasUnscanned) return Status.Complete
        val numberById = HashMap<String, Int>()
        messages.forEachIndexed { i, m -> if (m.id.isNotBlank()) numberById.putIfAbsent(m.id, i + 1) }
        val used = HashSet<Int>()
        var first = Int.MAX_VALUE
        var last = Int.MIN_VALUE
        for (content in scannedContents) {
            val turns = try {
                JSONArray(content)
            } catch (_: Exception) {
                continue
            }
            for (t in 0 until turns.length()) {
                val turn = turns.optJSONObject(t) ?: continue
                val id = turn.optString(TranscriptTurnVisibility.MESSAGE_ID)
                val number = if (id.isNotBlank()) {
                    numberById[id]
                } else {
                    // A turn captured before message IDs were recorded: the
                    // first unused message with the same role and text.
                    val role = turn.optString("role")
                    val text = turn.optString("content")
                    messages.indices.firstOrNull { i ->
                        (i + 1) !in used && messages[i].role == role && messages[i].text == text
                    }?.plus(1)
                } ?: continue
                used.add(number)
                if (number < first) first = number
                if (number > last) last = number
            }
        }
        return if (used.isEmpty()) null else Status.Range(first, last)
    }
}
