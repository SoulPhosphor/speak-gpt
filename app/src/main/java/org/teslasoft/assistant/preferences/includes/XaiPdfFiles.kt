/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.includes

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.util.Hash

/**
 * xAI Files API lifecycle for native PDF delivery.
 *
 * xAI's Chat Completions accepts a PDF only as a reference to a file uploaded
 * to `POST /v1/files`. The uploaded copy is transport state, never the user's
 * attachment: the local PDF stays canonical, the remote copy expires on its
 * own after [TTL_SECONDS], is reused by id while valid, and is deleted when
 * the local bytes are released (Condense, Remove, or the last reference).
 */
object XaiPdfFiles {
    /** xAI's documented per-file upload limit. */
    const val MAX_BYTES = 50L * 1024L * 1024L

    /** Remote lifetime (7 days); xAI accepts 3,600 to 2,592,000 seconds. */
    const val TTL_SECONDS = 604_800L

    /** Upload again when a reused copy would expire within this window. */
    private const val REFRESH_MARGIN_MS = 60L * 60L * 1000L

    private val lock = Any()

    class UploadException(message: String) : Exception(message)

    internal data class Record(
        val endpointKey: String,
        val endpointId: String,
        val hash: String,
        val fileId: String,
        val expiresAtMs: Long
    )

    /** Identifies the account a file id belongs to; ids do not cross accounts. */
    internal fun endpointKey(endpoint: ApiEndpointObject): String =
        Hash.hash(endpoint.host.trim().trimEnd('/').lowercase() + "\n" + endpoint.apiKey)

    /** The xAI file id for this PDF, reusing a valid upload or uploading now. */
    suspend fun fileIdFor(
        context: Context,
        chatId: String,
        endpoint: ApiEndpointObject,
        include: ChatInclude,
        file: File,
        nowMs: Long = System.currentTimeMillis()
    ): String = withContext(Dispatchers.IO) {
        val hash = requireNotNull(include.pdfFileHash) { "PDF bytes are no longer live" }
        reuseOrUpload(
            PdfAttachmentStore.remoteFilesRecord(context, chatId),
            endpointKey(endpoint),
            endpoint.id,
            hash,
            nowMs
        ) { upload(endpoint, file, include.fileName) }
    }

    /** Reuses a recorded copy that stays valid past the refresh margin, else uploads one. */
    internal fun reuseOrUpload(
        recordFile: File,
        key: String,
        endpointId: String,
        hash: String,
        nowMs: Long,
        upload: () -> String
    ): String {
        synchronized(lock) {
            readRecords(recordFile).firstOrNull {
                it.endpointKey == key && it.hash == hash && it.expiresAtMs - REFRESH_MARGIN_MS > nowMs
            }
        }?.let { return it.fileId }

        val fileId = upload()
        synchronized(lock) {
            val kept = readRecords(recordFile).filterNot { it.endpointKey == key && it.hash == hash }
            writeRecords(recordFile, kept + Record(key, endpointId, hash, fileId, nowMs + TTL_SECONDS * 1000L))
        }
        return fileId
    }

    /**
     * Deletes every remote copy of [hash] recorded for this chat. Best effort:
     * a copy that cannot be deleted still expires on its own.
     */
    suspend fun deleteForHash(
        context: Context,
        chatId: String,
        hash: String,
        endpointFor: (String) -> ApiEndpointObject?
    ) = withContext(Dispatchers.IO) {
        val recordFile = PdfAttachmentStore.remoteFilesRecord(context, chatId)
        val removed = synchronized(lock) {
            val records = readRecords(recordFile)
            val (matching, kept) = records.partition { it.hash == hash }
            if (matching.isNotEmpty()) writeRecords(recordFile, kept)
            matching
        }
        removed.forEach { record ->
            try {
                val endpoint = endpointFor(record.endpointId)
                    ?.takeIf { endpointKey(it) == record.endpointKey } ?: return@forEach
                val request = authorized(Request.Builder(), endpoint)
                    .url(filesUrl(endpoint) + "/" + record.fileId)
                    .delete()
                    .build()
                client().newCall(request).execute().close()
            } catch (_: Exception) {
            }
        }
    }

    private fun upload(endpoint: ApiEndpointObject, file: File, fileName: String): String {
        val response = client().newCall(uploadRequest(endpoint, file, fileName)).execute()
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw UploadException("xAI file upload failed (HTTP ${it.code})")
            return parseFileId(body) ?: throw UploadException("xAI file upload returned no file id")
        }
    }

    /** `expires_after` must precede `file` in the multipart body. */
    internal fun uploadRequest(endpoint: ApiEndpointObject, file: File, fileName: String): Request {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("expires_after", TTL_SECONDS.toString())
            .addFormDataPart(
                "file",
                fileName.ifBlank { file.name },
                file.asRequestBody("application/pdf".toMediaType())
            )
            .build()
        return authorized(Request.Builder(), endpoint)
            .url(filesUrl(endpoint))
            .post(body)
            .build()
    }

    /**
     * The Files API beside the chat endpoint, composed the same way the chat
     * request's base URL is: `<base>/chat/completions` becomes `<base>/files`.
     */
    internal fun filesUrl(endpoint: ApiEndpointObject): String {
        var base = endpoint.host.trim()
        if (!base.endsWith("/")) base += "/"
        val chat = endpoint.chatEndpoint.ifBlank { ApiEndpointObject.DEFAULT_CHAT_ENDPOINT }
            .trim().trimStart('/')
        val full = base + chat
        val apiBase = if (full.endsWith("chat/completions")) full.removeSuffix("chat/completions") else base
        return apiBase + "files"
    }

    internal fun parseFileId(body: String): String? = try {
        JSONObject(body).optString("id").takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    private fun authorized(builder: Request.Builder, endpoint: ApiEndpointObject): Request.Builder =
        builder.apply {
            when (endpoint.authType) {
                ApiEndpointObject.AUTH_X_API_KEY -> header("x-api-key", endpoint.apiKey)
                ApiEndpointObject.AUTH_API_KEY -> header("api-key", endpoint.apiKey)
                ApiEndpointObject.AUTH_XI_API_KEY -> header("xi-api-key", endpoint.apiKey)
                else -> header("Authorization", "Bearer ${endpoint.apiKey}")
            }
        }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    internal fun readRecords(file: File): List<Record> = try {
        if (!file.isFile) emptyList() else {
            val array = JSONObject(file.readText()).optJSONArray("files")
            (0 until (array?.length() ?: 0)).mapNotNull { index ->
                val o = array!!.optJSONObject(index) ?: return@mapNotNull null
                Record(
                    o.optString("endpointKey"),
                    o.optString("endpointId"),
                    o.optString("hash"),
                    o.optString("fileId").takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                    o.optLong("expiresAtMs")
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    internal fun writeRecords(file: File, records: List<Record>) {
        if (records.isEmpty()) {
            file.delete()
            return
        }
        val array = org.json.JSONArray()
        records.forEach {
            array.put(
                JSONObject()
                    .put("endpointKey", it.endpointKey)
                    .put("endpointId", it.endpointId)
                    .put("hash", it.hash)
                    .put("fileId", it.fileId)
                    .put("expiresAtMs", it.expiresAtMs)
            )
        }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(JSONObject().put("files", array).toString())
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }
}
