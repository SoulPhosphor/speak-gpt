/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.teslasoft.assistant.preferences.includes.ChatInclude
import org.teslasoft.assistant.preferences.includes.IncludeForm
import org.teslasoft.assistant.preferences.includes.IncludeKind

class PdfAttachmentPortableBackupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `only FULL PDFs require original bytes in backup`() {
        val hash = "a".repeat(64)
        val full = pdf("full", IncludeForm.FULL, hash)
        val condensed = pdf("condensed", IncludeForm.CONDENSED, null)
        val removed = pdf("removed", IncludeForm.ARTIFACT, null)
        val message = JSONObject().put(
            "includes", ChatInclude.listToJson(listOf(full, condensed, removed))
        )
        val chat = ChatLogicalImportPlan.ChatPlan(
            chatId = "chat-1",
            listRow = emptyMap(),
            messagesJson = JSONArray().put(message).toString(),
            messageCount = 1,
            settings = emptyList()
        )
        val plan = PortableChatRestorePlan.Plan(listOf(chat), emptyList())

        assertEquals(setOf(hash), PdfAttachmentPortableBackup.requiredHashes(plan))
    }

    @Test fun `restore accepts content addressed PDF and rejects a hash mismatch`() {
        val file = temporary.newFile("asset.pdf").apply { writeText("%PDF-test") }
        val hash = sha256(file)
        val valid = artifact(file, hash)
        assertEquals(setOf(hash), PdfAttachmentPortableBackup.prepareRestore(listOf(valid))?.keys)

        assertNull(PdfAttachmentPortableBackup.prepareRestore(listOf(artifact(file, "b".repeat(64)))))
    }

    private fun pdf(id: String, form: IncludeForm, hash: String?) = ChatInclude(
        id = id,
        fileName = "$id.pdf",
        kind = IncludeKind.PDF,
        form = form,
        fullText = "",
        condensedText = if (form == IncludeForm.CONDENSED) "summary" else null,
        artifactLine = if (form == IncludeForm.ARTIFACT) "bookmark" else null,
        pdfFileHash = hash,
        pdfMimeType = hash?.let { "application/pdf" },
        pdfByteSize = if (hash == null) 0 else 9,
        pdfPageCount = if (hash == null) 0 else 1
    )

    private fun artifact(file: File, hash: String) = PortablePackage.ValidatedArtifact(
        entryName = "chat_pdfs/chat-1/$hash.pdf",
        type = PortablePackage.TYPE_CHAT_PDF_ASSET,
        stagedFile = file,
        databaseKeyHex = null,
        keySemantics = null,
        schemaVersion = 1
    )

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
