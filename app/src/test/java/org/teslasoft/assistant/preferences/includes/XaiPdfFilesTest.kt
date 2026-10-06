package org.teslasoft.assistant.preferences.includes

import java.io.File
import okhttp3.MultipartBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject

class XaiPdfFilesTest {
    @get:Rule val temp = TemporaryFolder()

    private val endpoint = ApiEndpointObject("xAI", "https://api.x.ai/v1/", "secret", id = "ep-1")

    @Test fun `upload posts the PDF to the Files API with its lifetime before the file`() {
        val pdf = temp.newFile("report.pdf").apply { writeText("%PDF-1.7") }

        val request = XaiPdfFiles.uploadRequest(endpoint, pdf, "Quarterly report.pdf")

        assertEquals("https://api.x.ai/v1/files", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("Bearer secret", request.header("Authorization"))
        val body = request.body as MultipartBody
        assertEquals(2, body.parts.size)
        val text = Buffer().also { body.writeTo(it) }.readUtf8()
        val lifetime = text.indexOf("name=\"expires_after\"")
        val file = text.indexOf("name=\"file\"; filename=\"Quarterly report.pdf\"")
        assertTrue(lifetime in 0 until file)
        assertTrue(text.contains(XaiPdfFiles.TTL_SECONDS.toString()))
        assertTrue(text.contains("Content-Type: application/pdf"))
        assertTrue(XaiPdfFiles.TTL_SECONDS in 3_600L..2_592_000L)
    }

    @Test fun `file id is read from the upload response`() {
        assertEquals("file_abc", XaiPdfFiles.parseFileId("""{"id":"file_abc","object":"file"}"""))
        assertNull(XaiPdfFiles.parseFileId("""{"object":"file"}"""))
        assertNull(XaiPdfFiles.parseFileId("not json"))
    }

    @Test fun `a valid uploaded copy is reused and an expiring one is replaced`() {
        val record = File(temp.root, "remote-files.json")
        var uploads = 0
        val upload = { uploads++; "file_$uploads" }
        val start = 1_000_000L

        val first = XaiPdfFiles.reuseOrUpload(record, "acct", "ep-1", "hash", start, upload)
        val reused = XaiPdfFiles.reuseOrUpload(record, "acct", "ep-1", "hash", start + 60_000L, upload)
        assertEquals("file_1", first)
        assertEquals(first, reused)
        assertEquals(1, uploads)

        // A different account never reuses another account's file id.
        assertEquals("file_2", XaiPdfFiles.reuseOrUpload(record, "other", "ep-2", "hash", start, upload))

        // Within the last hour of its lifetime the copy is uploaded again.
        val nearExpiry = start + XaiPdfFiles.TTL_SECONDS * 1000L - 30L * 60L * 1000L
        assertEquals("file_3", XaiPdfFiles.reuseOrUpload(record, "acct", "ep-1", "hash", nearExpiry, upload))
        assertEquals(2, XaiPdfFiles.readRecords(record).size)
    }

    @Test fun `records survive a reload and an empty list removes the record file`() {
        val record = File(temp.root, "remote-files.json")
        val saved = XaiPdfFiles.Record("acct", "ep-1", "hash", "file_1", 42L)
        XaiPdfFiles.writeRecords(record, listOf(saved))
        assertEquals(listOf(saved), XaiPdfFiles.readRecords(record))
        XaiPdfFiles.writeRecords(record, emptyList())
        assertTrue(!record.exists())
    }

    @Test fun `chat request references the uploaded copy in the PDF's slot`() {
        val marker = StableAttachmentReference.serialize(
            ChatInclude(
                id = "inc-1", fileName = "report.pdf", kind = IncludeKind.PDF,
                form = IncludeForm.FULL, fullText = "", pdfFileHash = "hash"
            )
        )
        val body = JSONObject().put(
            "messages", org.json.JSONArray().put(
                JSONObject().put("role", "user").put(
                    "content", org.json.JSONArray()
                        .put(JSONObject().put("type", "text").put("text", "Uploaded PDF (report.pdf):"))
                        .put(JSONObject().put("type", "text").put("text", marker))
                )
            )
        ).toString()
        val payload = NativePdfPayload("inc-1", "report.pdf", "", originalByteSize = 10, fileId = "file_abc")

        val parts = JSONObject(PdfRequestSerializer.augmentOpenAiChatBody(body, listOf(payload), false))
            .getJSONArray("messages").getJSONObject(0).getJSONArray("content")

        val file = parts.getJSONObject(1)
        assertEquals("file", file.getString("type"))
        assertEquals("file_abc", file.getJSONObject("file").getString("file_id"))
        assertTrue(!file.getJSONObject("file").has("file_data"))
    }
}
