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

package org.teslasoft.assistant.stt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Streams a model file from its remote URL into the app's private whisper
 * directory. Writes to `<model>.part` first and renames on success so a
 * canceled/failed download never leaves a half-written file looking
 * "installed."
 *
 * Cancel by canceling the calling coroutine — the partial file is removed.
 *
 * SHA-256 verification runs only when the model registry has a known hash;
 * see LocalWhisperModels for the rationale.
 */
object LocalWhisperDownloader {

    sealed class Result {
        object Success : Result()
        object Canceled : Result()
        data class Failed(val reason: String) : Result()
    }

    /** Progress callback fires on the IO dispatcher. */
    fun interface ProgressListener {
        fun onProgress(bytesDownloaded: Long, totalBytes: Long)
    }

    suspend fun download(
        context: Context,
        model: LocalWhisperModels.Model,
        progress: ProgressListener
    ): Result = withContext(Dispatchers.IO) {
        val finalFile = LocalWhisperStorage.fileFor(context, model)
        val partFile = File(finalFile.parentFile, "${finalFile.name}.part")

        if (partFile.exists()) partFile.delete()

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return@withContext Result.Failed("HTTP $responseCode")
            }

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

                        // Throttle progress callbacks to ~once per 256 KB to
                        // keep the UI thread responsive on slow phones.
                        if (downloaded - lastReported >= 256 * 1024 || read < buf.size) {
                            lastReported = downloaded
                            progress.onProgress(downloaded, totalBytes)
                        }
                    }

                    if (!coroutineContext.isActive) {
                        // Coroutine was canceled mid-stream.
                        partFile.delete()
                        return@withContext Result.Canceled
                    }
                }
            }

            // Optional SHA-256 verification.
            val expected = model.sha256
            if (expected != null) {
                val actual = sha256Hex(partFile)
                if (!actual.equals(expected, ignoreCase = true)) {
                    partFile.delete()
                    return@withContext Result.Failed("SHA-256 mismatch")
                }
            }

            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.delete()
                return@withContext Result.Failed("Rename failed")
            }

            Result.Success
        } catch (_: InterruptedException) {
            partFile.delete()
            Result.Canceled
        } catch (e: IOException) {
            partFile.delete()
            Result.Failed(e.message ?: "Network error")
        } catch (e: Exception) {
            partFile.delete()
            Result.Failed(e.message ?: "Download failed")
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
