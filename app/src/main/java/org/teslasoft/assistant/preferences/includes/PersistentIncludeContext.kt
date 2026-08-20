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

package org.teslasoft.assistant.preferences.includes

/**
 * Derives persistent Include visibility from the stored message history.
 *
 * This object deliberately returns the Include records already held by their
 * original user messages. It never writes an Include field onto the later
 * message and never creates a UI-only attachment record.
 */
object PersistentIncludeContext {

    const val INCLUDES_KEY = "includes"

    /**
     * Returns the earlier sent Includes represented before [position] when the
     * row at [position] is a later user message. Order follows original message
     * order, then Include order within each message.
     */
    fun earlierForUserMessage(
        messages: List<Map<String, Any>>,
        position: Int,
        includesKey: String = INCLUDES_KEY
    ): List<ChatInclude> {
        if (position !in messages.indices || messages[position]["isBot"] == true) {
            return emptyList()
        }

        return allSent(messages.subList(0, position), includesKey)
    }

    /** Returns every sent Include once, preserving canonical history order. */
    fun allSent(
        messages: List<Map<String, Any>>,
        includesKey: String = INCLUDES_KEY
    ): List<ChatInclude> {
        val seen = HashSet<String>()
        val result = ArrayList<ChatInclude>()
        for (message in messages) {
            if (message["isBot"] == true) continue
            for (include in ChatInclude.listFromJson(message[includesKey]?.toString())) {
                if (seen.add(include.id)) result.add(include)
            }
        }
        return result
    }
}
