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

import com.google.gson.GsonBuilder
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.CodingErrorAction
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * Writes the memory export (the same `companion-memory-export-v1` document
 * [MemorySeedCodec.serialize] produces) as a stream, so the app never holds
 * the whole export in memory. The old export built one string of every
 * memory and every chat before writing it; on a large chat history that
 * string alone ran the app out of its working memory at every start.
 *
 * Only one chat's history is in memory at a time. The memory-store part is
 * the same object tree as before, written one element at a time. Readers
 * parse the file as JSON, so key order, spacing and number spelling do not
 * matter; every member and value is the same as in the old format.
 *
 * Pure JVM (Gson's stream writer and org.json), so it is unit-tested.
 */
internal object MemoryExportStream {

    /** One chat in `app_chats`. [messages] is null when the chat's history
     *  could not be read: it is written as `"unavailable": true`, never as an
     *  empty history, and the export is marked incomplete. */
    class Chat(val header: Map<String, Any>, val messages: List<Map<String, Any?>>?)

    /** What a finished write produced. */
    class Written(val bytes: Long, val sha256: ByteArray, val chatsComplete: Boolean)

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    /**
     * Writes the whole document to [out] (not closed). [memoryRoot] holds
     * every member except `app_chats` and `export_meta`. [chats] null omits
     * `app_chats` and marks the export incomplete (the chat list itself was
     * unreadable). [storageDegraded] is asked after the chats are written.
     * Text that is not valid Unicode fails the write instead of being
     * silently replaced.
     */
    fun write(
        out: OutputStream,
        memoryRoot: JSONObject,
        chats: Iterator<Chat>?,
        exportedAtIso: String?,
        storageDegraded: () -> Boolean
    ): Written {
        val digest = MessageDigest.getInstance("SHA-256")
        val counting = CountingOutputStream(DigestOutputStream(out, digest))
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = BufferedWriter(OutputStreamWriter(counting, encoder), 64 * 1024)
        val json = JsonWriter(text)
        json.setIndent("  ")
        json.isHtmlSafe = false

        json.beginObject()
        val keys = memoryRoot.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            json.name(key)
            writeOrgJson(json, memoryRoot.get(key))
        }

        var complete = chats != null
        if (chats != null) {
            json.name("app_chats")
            json.beginArray()
            while (chats.hasNext()) {
                val chat = chats.next()
                json.beginObject()
                for ((key, value) in chat.header) {
                    json.name(key)
                    gson.toJson(value, value.javaClass, json)
                }
                if (chat.messages != null) {
                    json.name("messages")
                    gson.toJson(chat.messages, chat.messages.javaClass, json)
                } else {
                    json.name("unavailable").value(true)
                    complete = false
                }
                json.endObject()
            }
            json.endArray()
        }

        val chatsComplete = complete && !storageDegraded()
        json.name("export_meta")
        writeOrgJson(json, MemorySeedCodec.exportMeta(exportedAtIso, chatsComplete))
        json.endObject()
        json.flush()
        text.flush()
        return Written(counting.count, digest.digest(), chatsComplete)
    }

    /** An org.json value, one array element at a time so no member of the
     *  memory part is turned into one large string either. */
    private fun writeOrgJson(json: JsonWriter, value: Any?) {
        when (value) {
            null, JSONObject.NULL -> json.nullValue()
            is JSONArray -> {
                json.beginArray()
                for (i in 0 until value.length()) writeOrgJson(json, value.get(i))
                json.endArray()
            }
            is JSONObject -> json.jsonValue(value.toString())
            is String -> json.value(value)
            is Boolean -> json.value(value)
            is Number -> json.jsonValue(JSONObject.numberToString(value))
            else -> json.value(value.toString())
        }
    }

    /**
     * Streams the export into [target] through a sibling `.tmp` file. The
     * file only takes the target's name after every byte was forced to disk,
     * read back and matched (size and SHA-256), and the document was checked
     * to be complete. Nothing is left behind on failure: the temporary file
     * is removed and the target is untouched. Returns the result, or null
     * when the export did not verify. Exceptions and out-of-memory errors
     * propagate after the temporary file is removed.
     */
    fun writeVerifiedFile(target: File, body: (OutputStream) -> Written): Written? {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            val written = FileOutputStream(tmp).use { file ->
                val buffered = file.buffered(64 * 1024)
                val result = body(buffered)
                buffered.flush()
                file.fd.sync()
                result
            }
            if (!matches(tmp, written) || !isCompleteDocument(tmp)) {
                tmp.delete()
                return null
            }
            // Timestamped names normally make the target fresh; if it does
            // exist (clock collision), replacing it is the intended outcome.
            val renamed = tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))
            if (!renamed) {
                tmp.delete()
                return null
            }
            if (!matches(target, written)) {
                target.delete()
                return null
            }
            return written
        } catch (t: Throwable) {
            try { tmp.delete() } catch (_: Throwable) { /* nothing left to clean */ }
            throw t
        }
    }

    /** True when [file] holds exactly the bytes that were written. */
    fun matches(file: File, written: Written): Boolean {
        if (file.length() != written.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(FileInputStream(file), digest).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (input.read(buffer) >= 0) { /* digesting */ }
        }
        return MessageDigest.isEqual(digest.digest(), written.sha256)
    }

    /**
     * Reads the file through once without loading it: a single JSON object
     * with `schema_version` and a closing `export_meta`, and nothing after
     * it. A cut-off or damaged file fails here.
     */
    fun isCompleteDocument(file: File): Boolean = try {
        FileInputStream(file).use { isCompleteDocument(it) }
    } catch (_: Exception) {
        false
    }

    fun isCompleteDocument(input: InputStream): Boolean = try {
        val reader = JsonReader(InputStreamReader(input.buffered(64 * 1024), Charsets.UTF_8))
        var hasSchema = false
        var hasMeta = false
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "schema_version" -> hasSchema = true
                "export_meta" -> hasMeta = true
            }
            reader.skipValue()
        }
        reader.endObject()
        hasSchema && hasMeta && reader.peek() == JsonToken.END_DOCUMENT
    } catch (_: Exception) {
        false
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }
}
