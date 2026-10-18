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

package org.teslasoft.assistant.preferences.backup.portable

import java.io.StringReader
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.teslasoft.assistant.imagegen.GeneratedImageMetadata
import org.teslasoft.assistant.preferences.includes.ChatInclude
import org.teslasoft.assistant.preferences.includes.IncludeForm
import org.teslasoft.assistant.preferences.includes.IncludeKind
import org.teslasoft.assistant.util.Hash

/**
 * The streaming chats reader must reach the same verdict as
 * [PortableChatRestorePlan.parse] on every artifact, and report the same chat
 * count, message count, required PDFs and generated-image references.
 */
class ChatsArtifactScannerTest {

    private val chatA = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
    private val chatB = "4f2504e0-4f89-41d3-9a0c-0305e82c3302"
    private val folderId = "7f2504e0-4f89-41d3-9a0c-0305e82c3301"
    private val messageId = "5f2504e0-4f89-41d3-9a0c-0305e82c3305"
    private val pdfHash = "a".repeat(64)
    private val pendingPdfHash = "b".repeat(64)

    private val v2 = ChatLogicalSerializer.FORMAT_V2
    private val v3 = ChatLogicalSerializer.FORMAT_V3

    private fun message(text: String, id: String? = null): JSONObject = JSONObject()
        .put("message", text)
        .put("isBot", false)
        .apply { id?.let { put("message_id", it) } }

    private fun chat(id: String, name: String, vararg messages: JSONObject, saved: Boolean? = true) =
        JSONObject()
            .put("name", name)
            .put("chat_id", id)
            .put("list_id", id)
            .put("messages", JSONArray().apply { messages.forEach(::put) })
            .put("settings", JSONArray())
            .apply { saved?.let { put(PortableChatRestorePlan.HISTORY_SAVED_KEY, it) } }

    /** Root fields in the order the app has always written them. */
    private fun artifact(
        format: String,
        vararg chats: JSONObject,
        folders: JSONArray = JSONArray(),
        complete: Boolean = true
    ): String =
        "{\"format\":${JSONObject.quote(format)},\"complete\":$complete," +
            "\"folders\":$folders,\"chats\":${JSONArray().apply { chats.forEach(::put) }}}"

    private fun pdf(hash: String) = ChatInclude(
        id = "inc-$hash".take(12),
        fileName = "doc.pdf",
        kind = IncludeKind.PDF,
        form = IncludeForm.FULL,
        fullText = "",
        pdfFileHash = hash,
        pdfMimeType = "application/pdf",
        pdfByteSize = 9,
        pdfPageCount = 1
    )

    private fun image(id: String, status: String) =
        JSONObject().put("imageId", id).put("status", status).toString()

    private fun samples(): List<String> {
        val withPdf = message("see file").put("includes", ChatInclude.listToJson(listOf(pdf(pdfHash))))
        val withImage = message("picture").put(GeneratedImageMetadata.KEY, image("img-1", "complete"))
        val pendingImage = message("pending").put(GeneratedImageMetadata.KEY, image("img-2", "generating"))
        val pending = chat(chatB, "Pending", message("hi")).apply {
            put(
                "settings", JSONArray().put(
                    JSONObject().put("k", "pending_includes").put("t", "s")
                        .put("v", ChatInclude.listToJson(listOf(pdf(pendingPdfHash))))
                )
            )
        }
        val folder = JSONObject().put("id", folderId).put("name", "Stories").put("pinned", false)
        return listOf(
            artifact(v2, chat(chatA, "One", message("hi", messageId), message("there"), saved = null)),
            artifact(v3, chat(chatA, "One", message("hi"), saved = true), chat(chatB, "Two", saved = false)),
            artifact(v3, chat(chatA, "One", message("hi"), saved = null)),
            // The same message ID in two chats (a Keep Both restore) is fine.
            artifact(v3, chat(chatA, "One", message("hi", messageId)), chat(chatB, "Two", message("hi", messageId))),
            artifact(v3, chat(chatA, "One", message("a", messageId), message("b", messageId))),
            artifact(v3, chat(chatA, "One", message("a", "not-a-uuid"))),
            artifact(v3, chat(chatA, "One").put("messages", JSONArray().put("text"))),
            artifact(v3, chat(chatA, "One").apply { remove("messages") }),
            artifact(v3, chat(chatA, "One", message("same")), chat(chatA, "One again", message("same"))),
            artifact(v3, chat(chatA, "One", message("first")), chat(chatA, "One again", message("other"))),
            artifact(v3, chat(Hash.hash("Old"), "Old").apply { remove("list_id") }),
            artifact(v3, chat(Hash.hash("Old"), "Renamed").apply { remove("list_id") }),
            artifact(v3, chat("not an id", "Bad")),
            artifact(v3, chat(chatA, "Foldered").put("list_folder_id", folderId), folders = JSONArray().put(folder)),
            artifact(v3, chat(chatA, "Foldered").put("list_folder_id", folderId)),
            artifact(v3, chat(chatA, "Plain"), folders = JSONArray().put(folder)),
            artifact(ChatLogicalSerializer.FORMAT_V1, chat(chatA, "Old v1").put("list_folder_id", folderId)),
            artifact(v3, chat(chatA, "Key").put("settings", JSONArray().put(JSONObject().put("k", "api_key").put("t", "s").put("v", "x")))),
            artifact(v3, chat(chatA, "Type").put("settings", JSONArray().put(JSONObject().put("k", "z").put("t", "q").put("v", 1)))),
            artifact(v3, chat(chatA, "Files", withPdf, withImage, pendingImage), pending),
            artifact("chat-logical-v9", chat(chatA, "One")),
            artifact(v3, chat(chatA, "One"), complete = false),
            "{\"complete\":true,\"chats\":[]}",
            "not json at all"
        )
    }

    @Test
    fun scannerReachesTheSameVerdictAsTheWholeReader() {
        for (json in samples()) {
            val whole = PortableChatRestorePlan.parse(json)
            val scanned = ChatsArtifactScanner.scan(StringReader(json))
            when (whole) {
                is PortableChatRestorePlan.Result.Rejected -> {
                    if (scanned !is ChatsArtifactScanner.Result.Rejected) fail("accepted what parse rejects: $json")
                    assertEquals(json, whole.reason, (scanned as ChatsArtifactScanner.Result.Rejected).reason)
                }
                is PortableChatRestorePlan.Result.Ok -> {
                    if (scanned !is ChatsArtifactScanner.Result.Ok) fail("rejected what parse accepts: $json")
                    val summary = (scanned as ChatsArtifactScanner.Result.Ok).summary
                    val plan = whole.plan
                    assertEquals(json, plan.chatCount, summary.chatCount)
                    assertEquals(json, plan.messageCount.toLong(), summary.messageCount)
                    assertEquals(json, PdfAttachmentPortableBackup.requiredHashes(plan), summary.requiredPdfHashes)
                    val images = PortableRestoreFinalState.scanChatImageReferences(plan)
                        as PortableRestoreDependencyRead.Available
                    assertEquals(json, images.snapshot.requiredAssetIds, summary.imageIdsRequired)
                }
            }
        }
    }

    @Test
    fun filesSampleFindsEveryLivePdfAndImage() {
        val summary = (ChatsArtifactScanner.scan(StringReader(samples()[19])) as ChatsArtifactScanner.Result.Ok).summary
        assertEquals(setOf(pdfHash, pendingPdfHash), summary.requiredPdfHashes)
        assertEquals(setOf(chatA to pdfHash, chatB to pendingPdfHash), summary.pdfRefs.map { it.chatId to it.hash }.toSet())
        assertEquals(setOf("img-1"), summary.imageIdsRequired)
        assertEquals(setOf("img-1", "img-2"), summary.imageIdsAll)
    }

    @Test
    fun v3KeepsMissingAndEmptyHistoriesDistinct() {
        val plan = (PortableChatRestorePlan.parse(samples()[1]) as PortableChatRestorePlan.Result.Ok).plan
        assertTrue(plan.chats.first { it.chatId == chatA }.historySaved)
        assertFalse(plan.chats.first { it.chatId == chatB }.historySaved)
        assertEquals(
            PortableChatRestorePlan.Reason.MALFORMED,
            (PortableChatRestorePlan.parse(samples()[2]) as PortableChatRestorePlan.Result.Rejected).reason
        )
    }

    @Test
    fun aMessageIdMayRepeatAcrossChatsButNotWithinOne() {
        assertTrue(PortableChatRestorePlan.parse(samples()[3]) is PortableChatRestorePlan.Result.Ok)
        assertEquals(
            PortableChatRestorePlan.Reason.DUPLICATE_MESSAGE_ID,
            (PortableChatRestorePlan.parse(samples()[4]) as PortableChatRestorePlan.Result.Rejected).reason
        )
    }

    @Test
    fun spaceEstimateCountsEveryCopyThatCoexistsDuringVerification() {
        assertEquals(
            5L * 100L + 4L * 50L + PortableBackupSpaceEstimate.RESERVE_BYTES,
            PortableBackupSpaceEstimate.required(100L, 50L)
        )
        assertEquals(0L, PortableBackupSpaceEstimate.Check(10L, 20L).shortfallBytes)
        assertEquals(5L, PortableBackupSpaceEstimate.Check(25L, 20L).shortfallBytes)
    }
}
