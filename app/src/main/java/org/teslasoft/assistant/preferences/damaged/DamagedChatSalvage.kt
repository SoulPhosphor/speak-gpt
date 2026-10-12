/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.damaged

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.teslasoft.assistant.preferences.MessageIdentity
import java.io.StringReader

/**
 * Pure message recovery and merging for chat repair (owner rulings, Oct 12
 * 2026). Recovery keeps every complete, valid message it can read from a
 * damaged copy; merging never drops a readable message, removes duplicates,
 * and keeps chronological order where timestamps allow.
 */
object DamagedChatSalvage {

    private const val TIME_KEY = "messageTime"

    /**
     * Every complete, valid message in [raw] (the stored chat text): the whole
     * list when it parses, otherwise each message in order up to the first
     * point the text can no longer be read.
     */
    fun salvage(raw: String?): List<HashMap<String, Any>> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<HashMap<String, Any>>()
        try {
            val reader = JsonReader(StringReader(raw))
            reader.isLenient = true
            if (reader.peek() != JsonToken.BEGIN_ARRAY) return emptyList()
            reader.beginArray()
            val type = object : TypeToken<HashMap<String, Any>>() {}.type
            while (reader.hasNext()) {
                if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                    reader.skipValue()
                    continue
                }
                val element = JsonParser.parseReader(reader)
                val message: HashMap<String, Any> = Gson().fromJson(element, type) ?: continue
                if (isValidMessage(message)) out.add(message)
            }
        } catch (_: Exception) {
            // The text ends or breaks here; everything complete before it is kept.
        }
        return out
    }

    private fun isValidMessage(message: Map<String, Any>): Boolean {
        if (message["message"] !is String) return false
        val isBot = message["isBot"]
        return isBot is Boolean || isBot == "true" || isBot == "false"
    }

    /**
     * [recovered] messages merged into [current] (the chat's readable
     * messages). Every current message is kept; a recovered message already
     * present (same message ID, or same speaker, text and time) is not added
     * twice. With a time on every message the result is in time order;
     * otherwise recovered (older) messages come first, each side in its own
     * order.
     */
    fun merge(
        recovered: List<HashMap<String, Any>>,
        current: List<HashMap<String, Any>>
    ): ArrayList<HashMap<String, Any>> {
        // Duplicates are judged only against the readable messages, so two
        // genuinely repeated recovered messages are both kept.
        val present = current.mapTo(HashSet()) { identity(it) }
        val added = recovered.filter { identity(it) !in present }
        val combined = ArrayList<HashMap<String, Any>>(added.size + current.size)
        combined.addAll(added)
        combined.addAll(current)
        if (combined.all { time(it) != null }) {
            combined.sortWith(compareBy { time(it) }) // stable: equal times keep their order
        }
        return combined
    }

    private fun identity(message: Map<String, Any>): String {
        val id = MessageIdentity.idOf(message)
        if (id.isNotBlank()) return "id:$id"
        val bot = message["isBot"]?.toString()
        return "m:$bot|${message[TIME_KEY]}|${message["message"]}"
    }

    private fun time(message: Map<String, Any>): Long? =
        message[TIME_KEY]?.toString()?.toLongOrNull()
}
