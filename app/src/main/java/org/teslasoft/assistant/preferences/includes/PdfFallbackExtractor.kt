/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.includes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.pdf.SandboxedPdfLoader
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/** Incremental embedded-text/OCR fallback creation. No page bitmap survives its iteration. */
object PdfFallbackExtractor {
    private const val MIN_MEANINGFUL_CHARS = 12
    private const val MAX_RENDER_EDGE = 2200

    data class Completed(
        val text: String,
        val provenance: PdfFallbackProvenance,
        val pageCount: Int,
        val cacheHit: Boolean
    )

    suspend fun extract(context: Context, chatId: String, include: ChatInclude): Completed =
        withContext(Dispatchers.IO) {
            val hash = requireNotNull(include.pdfFileHash) { "PDF bytes are no longer live" }
            readCache(context, chatId, hash)?.let { return@withContext it.copy(cacheHit = true) }
            val file = PdfAttachmentStore.pdfFile(context, chatId, include)
                ?.takeIf { it.isFile } ?: error("The original PDF is unavailable")

            val embedded = embeddedPages(context, file, include.pdfPageCount)
            val pageCount = max(include.pdfPageCount, embedded.size)
            val output = ArrayList<String>(pageCount)
            var usedEmbedded = false
            var usedOcr = false
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        for (index in 0 until renderer.pageCount) {
                            coroutineContext.ensureActive()
                            val nativeText = embedded.getOrNull(index).orEmpty().trim()
                            val pageText = if (isMeaningful(nativeText)) {
                                usedEmbedded = true
                                nativeText
                            } else {
                                usedOcr = true
                                renderer.openPage(index).use { page ->
                                    val scale = minOf(
                                        3f,
                                        MAX_RENDER_EDGE.toFloat() / max(page.width, page.height).coerceAtLeast(1)
                                    ).coerceAtLeast(1f)
                                    val bitmap = Bitmap.createBitmap(
                                        max(1, (page.width * scale).toInt()),
                                        max(1, (page.height * scale).toInt()),
                                        Bitmap.Config.ARGB_8888
                                    )
                                    try {
                                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text.trim()
                                    } finally {
                                        bitmap.recycle()
                                    }
                                }
                            }
                            output += pageBlock(index + 1, pageText)
                        }
                    }
                }
            } finally {
                recognizer.close()
            }
            val provenance = when {
                usedEmbedded && usedOcr -> PdfFallbackProvenance.MIXED
                usedEmbedded -> PdfFallbackProvenance.EMBEDDED_TEXT
                else -> PdfFallbackProvenance.OCR
            }
            if (output.none { block -> block.substringAfter('\n').any { it.isLetterOrDigit() } }) {
                error("No readable text was found in the PDF")
            }
            val completed = Completed(output.joinToString("\n\n"), provenance, output.size, false)
            writeCacheAtomically(context, chatId, hash, completed)
            completed
        }

    /** Stable page markers make mixed embedded/OCR output deterministic and explicit. */
    internal fun pageBlock(pageNumber: Int, text: String): String =
        "--- Page $pageNumber ---\n" + text.trim()

    internal fun isMeaningful(text: String): Boolean =
        text.count { it.isLetterOrDigit() } >= MIN_MEANINGFUL_CHARS

    private suspend fun embeddedPages(context: Context, file: File, expected: Int): List<String> {
        val out = MutableList(expected.coerceAtLeast(0)) { "" }
        return try {
            val loader = SandboxedPdfLoader(context, Dispatchers.IO)
            loader.openDocument(Uri.fromFile(file), null).use { document ->
                while (out.size < document.pageCount) out += ""
                for (index in 0 until document.pageCount) {
                    coroutineContext.ensureActive()
                    out[index] = document.getPageContent(index)?.textContents
                        ?.joinToString("\n") { it.text }.orEmpty()
                }
            }
            out
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Page-content APIs require a sufficiently new platform extension.
            // OCR remains the correct local fallback on older supported devices.
            out
        }
    }

    private fun readCache(context: Context, chatId: String, hash: String): Completed? {
        return try {
            val metadataFile = PdfAttachmentStore.fallbackMetadataFile(context, chatId, hash)
            if (!metadataFile.isFile) return null
            val metadata = JSONObject(metadataFile.readText())
            Completed(
                text = metadata.getString("text"),
                provenance = PdfFallbackProvenance.fromKey(metadata.optString("provenance")) ?: return null,
                pageCount = metadata.optInt("pageCount", 0),
                cacheHit = true
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCacheAtomically(context: Context, chatId: String, hash: String, completed: Completed) {
        val metadataFile = PdfAttachmentStore.fallbackMetadataFile(context, chatId, hash)
        val metadataTemp = File.createTempFile("$hash-", ".json.tmp", metadataFile.parentFile)
        try {
            FileOutputStream(metadataTemp).use {
                it.write(JSONObject().put("provenance", completed.provenance.key)
                    .put("pageCount", completed.pageCount)
                    .put("text", completed.text).toString().toByteArray())
                it.fd.sync()
            }
            try {
                Files.move(
                    metadataTemp.toPath(), metadataFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                Files.move(
                    metadataTemp.toPath(), metadataFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            metadataTemp.delete()
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
        addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }
}
