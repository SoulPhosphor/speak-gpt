/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.includes

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfAttachmentStoreInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val chatId = "pdf-store-test"

    @After fun cleanUp() {
        PdfAttachmentStore.deleteChatPdfs(context, chatId)
    }

    @Test fun duplicateHashBytesRemainUntilTheLastLiveReferenceIsGone() {
        val include = ChatInclude(
            id = "one", fileName = "same.pdf", kind = IncludeKind.PDF,
            form = IncludeForm.FULL, fullText = "", pdfFileHash = "a".repeat(64),
            pdfMimeType = "application/pdf", pdfByteSize = 9, pdfPageCount = 1
        )
        val file = requireNotNull(PdfAttachmentStore.pdfFile(context, chatId, include))
        file.parentFile?.mkdirs()
        file.writeText("%PDF-test")

        PdfAttachmentStore.deletePdfIfUnreferenced(
            context, chatId, include, stillReferenced = true
        )
        assertTrue(file.isFile)

        PdfAttachmentStore.deletePdfIfUnreferenced(
            context, chatId, include, stillReferenced = false
        )
        assertFalse(file.exists())
    }

    @Test fun duplicateImportSharesBytesThatSurviveWhileStillReferenced() {
        val source = PdfTestDocuments.embeddedText(context, "Shared bytes")
        val first = PdfImporter.import(context, Uri.fromFile(source), chatId)
            as PdfImporter.Result.Success
        val second = PdfImporter.import(context, Uri.fromFile(source), chatId)
            as PdfImporter.Result.Success

        // Same content, same content-addressed file, separate includes.
        assertEquals(first.onDiskFile, second.onDiskFile)
        assertNotEquals(first.include.id, second.include.id)

        // Dropping the second include must not delete bytes the first needs.
        PdfAttachmentStore.deletePdfIfUnreferenced(
            context, chatId, second.include, stillReferenced = true
        )
        assertTrue(first.onDiskFile.isFile)
    }

    @Test fun attachedPdfReloadsFromItsSavedRecord() {
        val source = PdfTestDocuments.embeddedText(context, "Reload me")
        val imported = PdfImporter.import(context, Uri.fromFile(source), chatId)
            as PdfImporter.Result.Success

        val reloaded = ChatInclude.fromJson(imported.include.toJson())
        val file = requireNotNull(PdfAttachmentStore.pdfFile(context, chatId, reloaded))

        assertTrue(reloaded.hasLivePdfBytes())
        assertEquals(IncludeKind.PDF, reloaded.kind)
        assertEquals(1, reloaded.pdfPageCount)
        assertTrue(file.isFile)
        assertTrue(source.readBytes().contentEquals(file.readBytes()))
        // No working copy is left behind after a finished import.
        assertTrue(file.parentFile!!.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun pdfHeaderAfterLeadingBytesIsAccepted() {
        val original = PdfTestDocuments.embeddedText(context, "Leading bytes")
        val padded = File(context.cacheDir, "padded-${System.nanoTime()}.pdf")
        padded.writeBytes("junk before header\n".toByteArray() + original.readBytes())

        val result = PdfImporter.import(context, Uri.fromFile(padded), chatId)

        assertTrue(result is PdfImporter.Result.Success)
    }

    @Test fun stagedRestoreReplacesLiveTreeAndClearsDiscardedBytes() {
        val old = File(PdfAttachmentStore.chatPdfsDir(context, chatId), "old.pdf")
            .apply { writeText("old") }
        val staging = File(context.cacheDir, "pdf-stage-${System.nanoTime()}")
        val stagedChat = File(staging, chatId).apply { mkdirs() }
        File(stagedChat, "new.pdf").writeText("new")

        try {
            assertTrue(PdfAttachmentStore.replaceAllFromStaging(context, staging))
            assertFalse(old.exists())
            assertTrue(File(PdfAttachmentStore.chatPdfsDir(context, chatId), "new.pdf").isFile)
        } finally {
            staging.deleteRecursively()
        }
    }
}
