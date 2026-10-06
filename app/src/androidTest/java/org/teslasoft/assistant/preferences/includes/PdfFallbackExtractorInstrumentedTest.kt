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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfFallbackExtractorInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val chatId = "pdf-extract-test"

    @After fun cleanUp() {
        PdfAttachmentStore.deleteChatPdfs(context, chatId)
    }

    private fun attach(file: File): ChatInclude =
        (PdfImporter.import(context, Uri.fromFile(file), chatId) as PdfImporter.Result.Success).include

    private fun cacheFile(include: ChatInclude): File =
        PdfAttachmentStore.fallbackMetadataFile(context, chatId, include.pdfFileHash!!)

    @Test fun embeddedTextIsReadWithoutOcr() = runBlocking {
        val include = attach(PdfTestDocuments.embeddedText(context, "Quarterly revenue grew"))

        val result = PdfFallbackExtractor.extract(context, chatId, include)

        assertEquals(PdfFallbackProvenance.EMBEDDED_TEXT, result.provenance)
        assertTrue(result.text.startsWith("--- Page 1 ---"))
        assertTrue(result.text.contains("Quarterly revenue grew"))
    }

    @Test fun pageWithoutTextIsReadByOcrOnAWhiteBackground() = runBlocking {
        val include = attach(PdfTestDocuments.shapesOnly(context, "INVOICE TOTAL"))

        val result = PdfFallbackExtractor.extract(context, chatId, include)

        assertEquals(PdfFallbackProvenance.OCR, result.provenance)
        assertTrue(result.text, result.text.uppercase().contains("INVOICE"))
    }

    @Test fun mixedPagesKeepOriginalPageOrder() = runBlocking {
        val include = attach(PdfTestDocuments.mixed(context, "First page words", "SECOND PAGE"))

        val result = PdfFallbackExtractor.extract(context, chatId, include)

        assertEquals(PdfFallbackProvenance.MIXED, result.provenance)
        val first = result.text.indexOf("--- Page 1 ---")
        val second = result.text.indexOf("--- Page 2 ---")
        assertTrue(first in 0 until second)
        assertTrue(result.text.indexOf("First page words") in first until second)
        assertTrue(result.text.substring(second).uppercase().contains("SECOND"))
    }

    @Test fun savedExtractionIsReusedWithoutTheOriginal() = runBlocking {
        val include = attach(PdfTestDocuments.embeddedText(context, "Cache this text"))
        val first = PdfFallbackExtractor.extract(context, chatId, include)
        assertFalse(first.cacheHit)

        // The saved result alone answers the next request.
        PdfAttachmentStore.pdfFile(context, chatId, include)!!.delete()
        val second = PdfFallbackExtractor.extract(context, chatId, include)

        assertTrue(second.cacheHit)
        assertEquals(first.text, second.text)
        assertEquals(first.provenance, second.provenance)
    }

    @Test fun cancelledExtractionSavesNothing() = runBlocking {
        val include = attach(PdfTestDocuments.manyShapePages(context, 12))
        val job = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
            PdfFallbackExtractor.extract(context, chatId, include)
        }
        job.start()
        job.cancel()
        try {
            job.await()
            fail("A cancelled extraction must not complete")
        } catch (_: CancellationException) {
        }

        assertFalse(cacheFile(include).exists())
    }
}
