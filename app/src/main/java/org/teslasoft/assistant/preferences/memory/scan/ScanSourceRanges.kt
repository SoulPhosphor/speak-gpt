/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.scan

import org.json.JSONArray
import org.json.JSONObject

/**
 * The message ranges a run finished reading, kept on its history row so a
 * Rerun can read the same part of each chat again. Message UUID anchors
 * only ([ScanPosition]); an empty start means the chat's beginning.
 */
object ScanSourceRanges {

    data class Range(val chatId: String, val startAnchors: List<String>, val endAnchors: List<String>)

    fun encode(ranges: List<Range>): String = JSONArray().apply {
        for (r in ranges) put(JSONObject().apply {
            put("chat", r.chatId)
            put("start", JSONArray(r.startAnchors))
            put("end", JSONArray(r.endAnchors))
        })
    }.toString()

    fun decode(raw: String?): List<Range> = try {
        val a = JSONArray(raw ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val chat = o.optString("chat", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Range(chat, strings(o.optJSONArray("start")), strings(o.optJSONArray("end")))
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun append(raw: String?, more: List<Range>): String = encode(decode(raw) + more)

    /** [live] with the chat id of each range taken from [stored] where the
     *  same range (same message anchors) was moved to a renamed chat since
     *  [live] was read, so a running scan never writes the old id back. */
    fun adoptRenames(live: List<Range>, stored: List<Range>): List<Range> = live.map { range ->
        stored.firstOrNull {
            it.chatId != range.chatId && it.startAnchors == range.startAnchors && it.endAnchors == range.endAnchors
        }?.let { range.copy(chatId = it.chatId) } ?: range
    }

    /** [raw] with every range of [oldChatId] moved to [newChatId] (a chat
     *  rename), or null when no range names [oldChatId]. */
    fun repoint(raw: String?, oldChatId: String, newChatId: String): String? {
        val ranges = decode(raw)
        if (ranges.none { it.chatId == oldChatId }) return null
        return encode(ranges.map { if (it.chatId == oldChatId) it.copy(chatId = newChatId) else it })
    }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
}
