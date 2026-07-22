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

package org.teslasoft.assistant.preferences.backup.readable

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Pure logic (unit-tested) for the Human-Readable Chat Backup ZIP (owner
 * directive, July 22 2026):
 *
 *  - Inner filenames are the chat's VISIBLE TITLE, sanitized only as far as
 *    filename safety requires — never replaced by a hash (owner rule).
 *  - Duplicate titles are numbered: "Chat Title.txt", "Chat Title-2.txt",
 *    "Chat Title-3.txt" (case-insensitive, so extraction on a
 *    case-insensitive filesystem cannot collide).
 *  - Text + JSON runs share ONE sanitized base name per chat.
 *  - The incremental selection ("New and Updated Chats") is a pure diff of
 *    per-chat content fingerprints against the last verified baseline.
 */
object ReadableChatFormats {

    /** Longest allowed base name — generous for a title, safe for providers. */
    const val MAX_BASE_NAME = 120

    /** Fallback base name when a title sanitizes to nothing. */
    const val EMPTY_TITLE_FALLBACK = "Chat"

    /**
     * Make a chat title safe as a filename: characters that cannot appear in
     * filenames across Android/Windows/SAF providers (`\ / : * ? " < > |` and
     * control characters) are removed, whitespace is collapsed, trailing
     * dots/spaces (invalid on Windows) are trimmed, length is capped. The
     * visible words are otherwise preserved — sanitize ONLY what is unsafe.
     */
    fun sanitizeTitle(raw: String?): String {
        if (raw == null) return EMPTY_TITLE_FALLBACK
        val cleaned = raw
            .filter { it.code >= 32 && it !in "\\/:*?\"<>|" }
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.', ' ')
        val capped = cleaned.take(MAX_BASE_NAME).trimEnd('.', ' ')
        return capped.ifEmpty { EMPTY_TITLE_FALLBACK }
    }

    /**
     * Assign one unique base name per chat, in order: the sanitized title,
     * then "-2", "-3", … on duplicates. Case-insensitive uniqueness; also
     * guards against a numbered candidate colliding with a real title that
     * already ends in "-2".
     */
    fun assignBaseNames(sanitizedTitles: List<String>): List<String> {
        val used = HashSet<String>()
        val out = ArrayList<String>(sanitizedTitles.size)
        for (title in sanitizedTitles) {
            var candidate = title
            var n = 1
            while (!used.add(candidate.lowercase())) {
                n++
                candidate = "$title-$n"
            }
            out.add(candidate)
        }
        return out
    }

    /**
     * One chat as a plain-text file: the title, then each message as a
     * labeled block. Internal bookkeeping keys (completion state, error
     * details) are not printed — this is the human-facing rendering; the JSON
     * format carries the full raw record.
     */
    fun formatText(title: String, messages: List<Map<String, Any?>>): String {
        val sb = StringBuilder()
        sb.append(title).append('\n')
        for (message in messages) {
            sb.append('\n')
            val isBot = message["isBot"] == true || message["isBot"]?.toString() == "true"
            sb.append(if (isBot) "Assistant:" else "You:").append('\n')
            val text = message["message"]?.toString().orEmpty()
            if (text.isNotEmpty()) {
                sb.append(text).append('\n')
            } else if (message.containsKey("image")) {
                sb.append("[Image]").append('\n')
            } else {
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    /**
     * One chat as pretty-printed JSON: the visible title plus the raw message
     * records exactly as stored (full fidelity — a restore or another tool
     * gets everything the app had). [messagesJson] is the Gson rendering of
     * the stored message list.
     */
    fun formatJson(title: String, messagesJson: String): String {
        val obj = JSONObject()
        obj.put("name", title)
        obj.put("messages", JSONArray(messagesJson))
        return obj.toString(2)
    }

    /** Content fingerprint of one chat (title + full message JSON), hex
     *  SHA-256. Identical content -> identical fingerprint, so the
     *  incremental diff is exact. */
    fun fingerprint(name: String, messagesJson: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(name.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(messagesJson.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The incremental selection: chat ids whose fingerprint is new or changed
     * against [baseline]. Pure; order follows [current]'s iteration order.
     */
    fun selectChanged(current: Map<String, String>, baseline: Map<String, String>): List<String> =
        current.entries.filter { (id, fp) -> baseline[id] != fp }.map { it.key }

    /** Serialize a fingerprint baseline for persistence. */
    fun baselineToJson(baseline: Map<String, String>): String {
        val obj = JSONObject()
        for ((id, fp) in baseline) obj.put(id, fp)
        return obj.toString()
    }

    /** Parse a persisted baseline; a missing/corrupt baseline is EMPTY, which
     *  fails safe — everything counts as new and gets exported again. */
    fun baselineFromJson(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            val out = LinkedHashMap<String, String>()
            for (key in obj.keys()) out[key] = obj.getString(key)
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
