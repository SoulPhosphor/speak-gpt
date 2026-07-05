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

package org.teslasoft.assistant.preferences.memory.librarian

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive

/**
 * Streams an embedding model's files (transformer + ONNX external data +
 * tokenizer) into its private directory — the Whisper downloader
 * (LocalWhisperDownloader) pattern, extended to a multi-part download so a
 * model is "installed" only when every required file landed. Each file writes
 * to `<name>.part` and renames on success; canceling the coroutine removes
 * the in-flight partial. SHA-256 is checked only when the catalog carries a
 * known hash.
 *
 * Repair/resume semantics (a device shipped a partial install once — model +
 * tokenizer present, external data missing): files that already exist
 * complete (and match their hash, when known) are SKIPPED, and a failure
 * keeps the files that did land instead of wiping the directory. So a
 * partial install shows as "not installed", and tapping Download fetches
 * only what's missing. Catalog [EmbeddingModels.ExtraFile.optional] files may
 * 404 upstream without failing the install (absent by design); any other
 * error fails as usual.
 *
 * Progress is reported as combined bytes across the files being transferred.
 */
object EmbeddingModelDownloader {

    sealed class Result {
        object Success : Result()
        object Canceled : Result()
        data class Failed(val reason: String) : Result()
    }

    fun interface ProgressListener {
        fun onProgress(bytesDownloaded: Long, totalBytes: Long)
    }

    private class PlannedFile(
        val url: String,
        val file: File,
        val sha256: String?,
        val optional: Boolean
    )

    suspend fun download(
        context: Context,
        model: EmbeddingModels.Model,
        progress: ProgressListener
    ): Result = withContext(Dispatchers.IO) {
        // A fresh download must re-earn the self-check pass — the files are
        // about to change under the marker.
        EmbeddingModelStorage.selfCheckMarker(context, model).delete()

        val plan = buildList {
            add(PlannedFile(model.modelUrl, EmbeddingModelStorage.modelFile(context, model), model.modelSha256, false))
            for (extra in model.extraFiles) {
                add(PlannedFile(extra.url, EmbeddingModelStorage.extraFile(context, model, extra), extra.sha256, extra.optional))
            }
            add(PlannedFile(model.tokenizerUrl, EmbeddingModelStorage.tokenizerFile(context, model), model.tokenizerSha256, false))
        }

        // Head requests would let us total everything up front, but many CDNs
        // omit Content-Length; report per-file totals as they arrive instead.
        var carried = 0L
        for (item in plan) {
            if (isAlreadyComplete(item)) {
                carried += item.file.length()
                continue
            }
            val result = downloadOne(item.url, item.file, item.sha256) { done, total ->
                progress.onProgress(carried + done, carried + total)
            }
            when {
                result is Result.Success -> carried += item.file.length()
                // Optional file absent upstream: fine, the install proceeds.
                result is Result.Failed && item.optional && result.reason == "HTTP 404" -> { }
                // Keep whatever landed — isInstalled stays false until every
                // required file is present, and the next attempt resumes.
                else -> return@withContext result
            }
        }
        Result.Success
    }

    /** A finished file only exists under its final name (rename-on-success),
     *  so present + non-empty means complete; verify the hash when known. */
    private fun isAlreadyComplete(item: PlannedFile): Boolean {
        if (!item.file.exists() || item.file.length() == 0L) return false
        if (item.sha256 == null) return true
        return sha256Hex(item.file).equals(item.sha256, ignoreCase = true)
    }

    private suspend fun downloadOne(
        url: String,
        finalFile: File,
        expectedSha: String?,
        progress: ProgressListener
    ): Result {
        val partFile = File(finalFile.parentFile, "${finalFile.name}.part")
        if (partFile.exists()) partFile.delete()

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) return Result.Failed("HTTP $responseCode")

            val totalBytes = connection.contentLengthLong
            connection.inputStream.use { input ->
                partFile.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastReported = -1L
                    while (coroutineContext.isActive) {
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        downloaded += read
                        if (downloaded - lastReported >= 256 * 1024 || read < buf.size) {
                            lastReported = downloaded
                            progress.onProgress(downloaded, totalBytes)
                        }
                    }
                    if (!coroutineContext.isActive) {
                        partFile.delete()
                        return Result.Canceled
                    }
                }
            }

            if (expectedSha != null) {
                val actual = sha256Hex(partFile)
                if (!actual.equals(expectedSha, ignoreCase = true)) {
                    partFile.delete()
                    return Result.Failed("SHA-256 mismatch")
                }
            }

            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.delete()
                return Result.Failed("Rename failed")
            }
            return Result.Success
        } catch (_: InterruptedException) {
            partFile.delete()
            return Result.Canceled
        } catch (e: IOException) {
            partFile.delete()
            return Result.Failed(e.message ?: "Network error")
        } catch (e: Exception) {
            partFile.delete()
            return Result.Failed(e.message ?: "Download failed")
        } finally {
            connection?.disconnect()
        }
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
