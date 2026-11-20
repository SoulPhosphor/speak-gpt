package org.teslasoft.assistant.preferences.memory

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

class MemoryExportStreamTest {

    @get:Rule val folder = TemporaryFolder()

    private fun memoryData(): MemoryStoreData {
        // A real export shape: parse a minimal document through the codec so
        // every field the serializer touches is populated the normal way.
        return MemorySeedCodec.parse(
            """
            {
              "schema_version": "1.11.0",
              "owner_profile": { "portrait": "Owner portrait.", "updated_at": "2026-11-01T00:00:00Z" },
              "companions": [
                { "companion_id": "c-9", "current_name": "Wren", "essence": "warm",
                  "memory_participation": "full", "hard_limits": [],
                  "created_at": "2026-11-01T00:00:00Z", "status": "active" }
              ],
              "entities": [
                { "entity_id": "e-9", "kind": "place", "name": "Lake", "summary": "a lake",
                  "status": "active", "importance": 2 }
              ]
            }
            """.trimIndent()
        )
    }

    private fun chat(name: String, id: String, messages: List<Map<String, Any?>>?) =
        MemoryExportStream.Chat(linkedMapOf<String, Any>("name" to name, "chat_id" to id), messages)

    private fun stream(
        data: MemoryStoreData = memoryData(),
        chats: List<MemoryExportStream.Chat>? = emptyList(),
        degraded: Boolean = false,
        out: OutputStream = ByteArrayOutputStream()
    ): MemoryExportStream.Written = MemoryExportStream.write(
        out, MemorySeedCodec.memoryRoot(data), chats?.iterator(), "2026-11-20T00:00:00Z"
    ) { degraded }

    @Test fun streamedExportHasTheSameContentAsTheOldSerializer() {
        val data = memoryData()
        val messages = listOf<Map<String, Any?>>(
            mapOf("message" to "hi <there> & \"you\"", "isBot" to false),
            mapOf("message" to "reply", "isBot" to true)
        )
        val out = ByteArrayOutputStream()
        stream(data, listOf(chat("First", "id-1", messages)), out = out)
        val streamed = JSONObject(out.toString("UTF-8"))

        val old = JSONObject(
            MemorySeedCodec.serialize(
                data,
                appChats = JSONArray().put(JSONObject().apply {
                    put("name", "First")
                    put("chat_id", "id-1")
                    put("messages", JSONArray(com.google.gson.Gson().toJson(messages)))
                }),
                exportedAtIso = "2026-11-20T00:00:00Z"
            )
        )
        assertEquals(old.keySet(), streamed.keySet())
        for (key in old.keySet()) assertEquals(key, old.get(key).toString(), streamed.get(key).toString())
        // Restore reads it with the same parser as before.
        assertEquals(data, MemorySeedCodec.parse(out.toString("UTF-8")))
    }

    @Test fun unreadableChatIsMarkedAndTheExportIsIncomplete() {
        val out = ByteArrayOutputStream()
        val written = stream(chats = listOf(chat("Locked", "id-2", null)), out = out)
        val doc = JSONObject(out.toString("UTF-8"))
        val entry = doc.getJSONArray("app_chats").getJSONObject(0)
        assertTrue(entry.getBoolean("unavailable"))
        assertFalse(entry.has("messages"))
        assertFalse(doc.getJSONObject("export_meta").getBoolean("app_chats_complete"))
        assertFalse(written.chatsComplete)
    }

    @Test fun unreadableChatListOmitsChatsAndIsIncomplete() {
        val out = ByteArrayOutputStream()
        stream(chats = null, out = out)
        val doc = JSONObject(out.toString("UTF-8"))
        assertFalse(doc.has("app_chats"))
        assertFalse(doc.getJSONObject("export_meta").getBoolean("app_chats_complete"))
    }

    @Test fun degradedStorageMarksTheExportIncomplete() {
        val out = ByteArrayOutputStream()
        stream(degraded = true, out = out)
        assertFalse(JSONObject(out.toString("UTF-8")).getJSONObject("export_meta").getBoolean("app_chats_complete"))
    }

    @Test fun completeExportOmitsTheIncompleteMarker() {
        val out = ByteArrayOutputStream()
        stream(out = out)
        assertFalse(JSONObject(out.toString("UTF-8")).getJSONObject("export_meta").has("app_chats_complete"))
    }

    @Test fun exportLargerThanTheWholeHeapNeverHoldsItInMemory() {
        // More output than this JVM may hold at all: the old single-string
        // export could not survive this; the stream must.
        val target = Runtime.getRuntime().maxMemory() + 64L * 1024 * 1024
        val messageText = "x".repeat(1024 * 1024)
        var produced = 0L
        val chats = generateSequence {
            if (produced >= target) null else {
                produced += messageText.length
                // A fresh map per chat; nothing is kept once written.
                MemoryExportStream.Chat(
                    linkedMapOf<String, Any>("name" to "Chat $produced", "chat_id" to "id-$produced"),
                    listOf(mapOf("message" to messageText, "isBot" to false))
                )
            }
        }.iterator()
        var count = 0L
        val sink = object : OutputStream() {
            override fun write(b: Int) { count++ }
            override fun write(b: ByteArray, off: Int, len: Int) { count += len }
        }
        val written = MemoryExportStream.write(
            sink, MemorySeedCodec.memoryRoot(memoryData()), chats, null
        ) { false }
        assertTrue(written.bytes > target)
        assertEquals(count, written.bytes)
    }

    @Test fun verifiedFileIsPublishedOnlyWhenComplete() {
        val target = File(folder.root, "memory-export-2026-11-20T00-00-00Z.json")
        val written = MemoryExportStream.writeVerifiedFile(target) { out -> stream(out = out) }
        assertTrue(written != null && target.isFile)
        assertTrue(MemoryExportStream.isCompleteDocument(target))
        assertFalse(File(folder.root, target.name + ".tmp").exists())
    }

    @Test fun interruptedExportLeavesNoFileAndNoTemporaryFile() {
        val existing = File(folder.root, "memory-export-2026-11-19T00-00-00Z.json").apply { writeText("{\"keep\":true}") }
        val target = File(folder.root, "memory-export-2026-11-20T00-00-00Z.json")
        try {
            MemoryExportStream.writeVerifiedFile(target) { out ->
                out.write("{\"schema_version\":1,\"app_chats\":[".toByteArray())
                throw OutOfMemoryError("simulated")
            }
            fail("the interruption must propagate")
        } catch (_: OutOfMemoryError) { /* expected */ }
        assertFalse(target.exists())
        assertFalse(File(folder.root, target.name + ".tmp").exists())
        assertEquals("{\"keep\":true}", existing.readText())
    }

    @Test fun cutOffDocumentIsNeverPublished() {
        val target = File(folder.root, "memory-export-2026-11-20T00-00-00Z.json")
        val result = MemoryExportStream.writeVerifiedFile(target) { out ->
            val bytes = "{\"schema_version\":1,\"app_chats\":[".toByteArray()
            out.write(bytes)
            MemoryExportStream.Written(bytes.size.toLong(), java.security.MessageDigest.getInstance("SHA-256").digest(bytes), true)
        }
        assertNull(result)
        assertFalse(target.exists())
    }

    @Test fun invalidUnicodeFailsInsteadOfBeingReplaced() {
        val broken = listOf<Map<String, Any?>>(mapOf("message" to "bad \uD800 text"))
        val target = File(folder.root, "memory-export-2026-11-20T00-00-00Z.json")
        try {
            MemoryExportStream.writeVerifiedFile(target) { out -> stream(chats = listOf(chat("C", "id", broken)), out = out) }
            fail("malformed text must fail the export")
        } catch (_: Exception) { /* expected */ }
        assertFalse(target.exists())
    }

    @Test fun completeDocumentCheck() {
        assertTrue(MemoryExportStream.isCompleteDocument(ByteArrayInputStream(
            "{\"schema_version\":1,\"export_meta\":{}}".toByteArray())))
        assertFalse(MemoryExportStream.isCompleteDocument(ByteArrayInputStream(
            "{\"schema_version\":1}".toByteArray())))
        assertFalse(MemoryExportStream.isCompleteDocument(ByteArrayInputStream(
            "{\"schema_version\":1,\"export_meta\":{}".toByteArray())))
        assertFalse(MemoryExportStream.isCompleteDocument(ByteArrayInputStream(
            "{\"schema_version\":1,\"export_meta\":{}} trailing".toByteArray())))
    }

    @Test fun chatHeaderMatchesTheOldObjectRules() {
        val header = MemoryExporter.chatHeader(
            linkedMapOf("name" to "N", "first_message" to "secret", "pinned" to "true", "gone" to null),
            "derived-id"
        )!!
        assertEquals(listOf("name", "chat_id", "pinned"), header.keys.toList())
        assertEquals("derived-id", header["chat_id"])
        // A stored chat_id replaces the derived one, as JSONObject.put did.
        assertEquals("stored", MemoryExporter.chatHeader(mapOf("name" to "N", "chat_id" to "stored"), "derived")!!["chat_id"])
        assertNull(MemoryExporter.chatHeader(mapOf("pinned" to "true"), "id"))
    }
}
