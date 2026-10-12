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
import org.teslasoft.assistant.preferences.MessageBookmarks
import org.teslasoft.assistant.ui.adapters.chat.ChatAdapter
import org.teslasoft.assistant.ui.chat.ChatExportFormat
import org.teslasoft.assistant.ui.chat.ChatExportFormatter
import org.teslasoft.assistant.ui.chat.ChatExportMessage
import org.teslasoft.assistant.ui.chat.ChatExportOptions
import org.teslasoft.assistant.ui.chat.ChatSpeakerNames
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone

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
     * Speaker-name fallbacks for one chat. A message's own stored name (the
     * name shown when it was sent) always wins; [currentCompanion] covers
     * older companion messages stored before names were stamped.
     */
    data class SpeakerNames(
        val currentCompanion: String?,
        val userFallback: String,
        val companionFallback: String
    )

    /** Token accounting never travels in a readable export (owner rule). */
    private val TOKEN_KEYS = setOf(
        ChatAdapter.KEY_MESSAGE_TOKENS,
        ChatAdapter.KEY_MESSAGE_TOKEN_ESTIMATE,
        ChatAdapter.KEY_TOKEN_USAGE_RECORDS,
        ChatAdapter.KEY_MESSAGE_REASONING_TOKENS
    )

    private fun isCompanion(message: Map<String, Any?>): Boolean =
        message["isBot"] == true || message["isBot"]?.toString() == "true"

    fun speakerName(message: Map<String, Any?>, names: SpeakerNames): String {
        val key = if (isCompanion(message)) ChatSpeakerNames.COMPANION_NAME_KEY else ChatSpeakerNames.USER_NAME_KEY
        message[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return if (isCompanion(message)) {
            names.currentCompanion?.trim()?.takeIf { it.isNotEmpty() } ?: names.companionFallback
        } else {
            names.userFallback
        }
    }

    private fun timestamp(message: Map<String, Any?>): Long? =
        message[ChatAdapter.KEY_MESSAGE_TIME]?.toString()?.toLongOrNull()

    private fun model(message: Map<String, Any?>): String? =
        if (isCompanion(message)) {
            message[ChatAdapter.KEY_MESSAGE_MODEL]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }

    /**
     * One chat as a plain-text file: the title, then each message under the
     * same header the individual chat export writes (bookmark, speaker name,
     * date and time, the companion's model). Internal bookkeeping keys are
     * not printed — this is the human-facing rendering.
     */
    fun formatText(
        title: String,
        messages: List<Map<String, Any?>>,
        names: SpeakerNames,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val sb = StringBuilder()
        sb.append(title).append('\n')
        val options = ChatExportOptions(format = ChatExportFormat.TEXT)
        for (message in messages) {
            sb.append('\n')
            val header = ChatExportMessage(
                isCompanion = isCompanion(message),
                name = speakerName(message, names),
                content = "",
                timestampMillis = timestamp(message),
                model = model(message),
                bookmarked = MessageBookmarks.isBookmarked(message)
            )
            sb.append(ChatExportFormatter.formatHeader(header, options, locale, timeZone)).append('\n')
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
     * The message records for the JSON file: each stored record with every
     * token field removed (at any depth, so regenerated versions are covered
     * too), plus the speaker name, formatted date and time, and the
     * companion's model.
     */
    fun jsonRecords(
        messages: List<Map<String, Any?>>,
        names: SpeakerNames,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): List<Map<String, Any?>> = messages.map { message ->
        @Suppress("UNCHECKED_CAST")
        val record = LinkedHashMap(stripTokens(message) as Map<String, Any?>)
        record["name"] = speakerName(message, names)
        timestamp(message)?.let { millis ->
            record["date"] = ChatExportFormatter.formatDate(millis, locale, timeZone)
            record["time"] = ChatExportFormatter.formatTime(millis, locale, timeZone)
        }
        model(message)?.let { record["model"] = it }
        record
    }

    private fun stripTokens(value: Any?): Any? = when (value) {
        is Map<*, *> -> {
            val out = LinkedHashMap<String, Any?>()
            for ((k, v) in value) {
                val key = k.toString()
                if (key !in TOKEN_KEYS) out[key] = stripTokens(v)
            }
            out
        }
        is List<*> -> value.map(::stripTokens)
        else -> value
    }

    /**
     * One chat as pretty-printed JSON: the visible title, the folder name
     * when the user chose to include folder locations, and [messagesJson]
     * (the Gson rendering of [jsonRecords]).
     */
    fun formatJson(title: String, messagesJson: String, folderName: String? = null): String {
        val obj = JSONObject()
        obj.put("name", title)
        obj.put("messages", JSONArray(messagesJson))
        folderName?.let { obj.put("folder_name", it) }
        return obj.toString(2)
    }

    /**
     * ZIP entry paths (without extension) for the selected chats. With
     * [includeFolders] off every chat sits at the top level, as before. With
     * it on, a chat in a folder goes under a directory named after that
     * folder (sanitized like a title; folders whose names differ only by
     * letter case share one directory so extraction on a case-insensitive
     * filesystem cannot split or collide them). Names are made unique per
     * directory.
     */
    fun assignEntryPaths(
        sanitizedTitles: List<String>,
        folderNames: List<String?>,
        includeFolders: Boolean
    ): List<String> {
        val dirByKey = LinkedHashMap<String, String>()
        val dirs = sanitizedTitles.indices.map { i ->
            val folder = if (includeFolders) folderNames[i] else null
            if (folder == null) {
                ""
            } else {
                val dir = sanitizeTitle(folder)
                dirByKey.getOrPut(dir.lowercase()) { dir }
            }
        }
        val out = arrayOfNulls<String>(sanitizedTitles.size)
        for (dir in dirs.distinct()) {
            val indices = sanitizedTitles.indices.filter { dirs[it] == dir }
            val names = assignBaseNames(indices.map { sanitizedTitles[it] })
            indices.forEachIndexed { slot, i ->
                out[i] = if (dir.isEmpty()) names[slot] else "$dir/${names[slot]}"
            }
        }
        return out.map { it!! }
    }

    /** Content fingerprint of one chat (title + full message JSON), hex
     *  SHA-256. Identical content -> identical fingerprint, so the
     *  incremental diff is exact. */
    fun fingerprint(name: String, messagesJson: String, folderName: String? = null): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(name.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(messagesJson.toByteArray(Charsets.UTF_8))
        if (folderName != null) {
            md.update(0)
            md.update(folderName.toByteArray(Charsets.UTF_8))
        }
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
