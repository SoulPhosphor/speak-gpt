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

package org.teslasoft.assistant.imagegen

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.util.ProviderErrorInfo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The provider-neutral image-generation coordinator
 * (image-generation-rebuild-plan.md §9), the single engine behind both
 * `/imagine` and model-initiated generation. It loads the SELECTED
 * GENERATOR endpoint — which may differ from any conversation endpoint —
 * applies that endpoint's own authentication mode and timeouts, runs the
 * provider-specific adapter over one OkHttp layer, and returns one
 * normalized outcome with §13 diagnostics. Cancellation propagates as
 * [CancellationException]; every other ending is a classified Outcome.
 */
object ImageGeneratorCoordinator {

    /** Ceiling for final image bytes — a corrupt or hostile response must
     *  not exhaust the phone's memory ("download too large" in §13). */
    const val MAX_IMAGE_BYTES: Int = 32 * 1024 * 1024

    /** Ceiling for the provider's API response body (Base64 image data
     *  runs ~4/3 the image size, so this is deliberately larger). */
    const val MAX_RESPONSE_BYTES: Int = 64 * 1024 * 1024

    sealed class Outcome {
        class Success(
            val image: GeneratedImageResult,
            val diagnostics: ImageRequestDiagnostics
        ) : Outcome()

        class Failure(
            val errorCause: ImageErrorCause,
            val sanitizedDetail: String?,
            val reportedProvider: String?,
            val diagnostics: ImageRequestDiagnostics
        ) : Outcome()
    }

    suspend fun generate(context: Context, request: ImageGenerationRequest): Outcome =
        withContext(Dispatchers.IO) { generateInternal(context, request) }

    private suspend fun generateInternal(
        context: Context,
        request: ImageGenerationRequest
    ): Outcome {
        val startedAt = System.currentTimeMillis()
        var provider = "Image generator"
        var endpointLabel = ""
        var httpStatus: Int? = null
        var providerRequestId: String? = null
        var reportedProvider: String? = null
        var generationMs: Long? = null
        var downloadMs: Long? = null
        var apiKeyForSanitizing: String? = null

        fun diagnostics() = ImageRequestDiagnostics(
            provider = provider,
            endpointLabel = endpointLabel,
            modelId = request.modelId,
            timestamp = startedAt,
            totalMs = System.currentTimeMillis() - startedAt,
            generationMs = generationMs,
            downloadMs = downloadMs,
            httpStatus = httpStatus,
            providerRequestId = providerRequestId
        )

        try {
            if (request.endpointId.isBlank() || request.modelId.isBlank()) {
                throw ImageGenerationException(ImageErrorCause.NO_GENERATOR_CONFIGURED)
            }
            val endpoint = ApiEndpointPreferences
                .getApiEndpointPreferences(context)
                .getApiEndpoint(context, request.endpointId)
            if (endpoint.host.isBlank()) {
                throw ImageGenerationException(ImageErrorCause.NO_GENERATOR_CONFIGURED)
            }
            apiKeyForSanitizing = endpoint.apiKey
            val adapter = ImageProviderAdapters.forEndpoint(endpoint)
            provider = endpoint.provider.ifBlank { adapter.providerName }
            endpointLabel = endpoint.label
            val client = buildClient(endpoint)

            val generationStart = System.currentTimeMillis()
            val parsed: AdapterImageResponse =
                executeCall(client, adapter.buildHttpRequest(request, endpoint)).use { response ->
                    httpStatus = response.code
                    providerRequestId = ImageRequestDiagnostics.sanitizeRequestId(
                        response.header("x-request-id")
                    )
                    val bodyBytes = readBounded(response.body?.byteStream(), MAX_RESPONSE_BYTES)
                        ?: throw ImageGenerationException(
                            ImageErrorCause.DOWNLOAD_INVALID,
                            "the provider response exceeded the size limit"
                        )
                    val bodyText = String(bodyBytes)
                    if (!response.isSuccessful) {
                        val providerError = ProviderErrorInfo.parse(bodyText)
                        reportedProvider = providerError.providerName
                        val providerMessage = providerError.message ?: bodyText
                        throw ImageGenerationException(
                            adapter.classifyHttpError(response.code, bodyText),
                            ImageErrorSanitizer.sanitize(providerMessage, endpoint.apiKey)
                        )
                    }
                    adapter.parseResponse(bodyText)
                }
            generationMs = System.currentTimeMillis() - generationStart

            val imageBytes: ByteArray = when (val payload = parsed.payload) {
                is ImagePayload.Bytes -> payload.bytes
                is ImagePayload.RemoteUrl -> {
                    val downloadStart = System.currentTimeMillis()
                    val bytes = downloadImage(client, payload.url)
                    downloadMs = System.currentTimeMillis() - downloadStart
                    bytes
                }
            }
            if (imageBytes.size > MAX_IMAGE_BYTES) {
                throw ImageGenerationException(
                    ImageErrorCause.DOWNLOAD_INVALID,
                    "the image exceeded the size limit"
                )
            }
            val format = ImageFormat.detect(imageBytes)
                ?: throw ImageGenerationException(
                    ImageErrorCause.NO_USABLE_IMAGE,
                    "the received bytes are not a supported image format"
                )

            return Outcome.Success(
                GeneratedImageResult(
                    bytes = imageBytes,
                    mimeType = format.mimeType,
                    fileExtension = format.fileExtension,
                    providerRequestId = providerRequestId,
                    providerUsage = parsed.usageSummary
                ),
                diagnostics()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImageGenerationException) {
            return Outcome.Failure(
                e.errorCause, e.sanitizedDetail, reportedProvider, diagnostics()
            )
        } catch (e: IOException) {
            return Outcome.Failure(
                classifyNetworkException(e),
                ImageErrorSanitizer.sanitize(e.message, apiKeyForSanitizing),
                reportedProvider,
                diagnostics()
            )
        } catch (e: Exception) {
            return Outcome.Failure(
                ImageErrorCause.PROVIDER_ERROR,
                ImageErrorSanitizer.sanitize(e.message, apiKeyForSanitizing),
                reportedProvider,
                diagnostics()
            )
        }
    }

    /** The endpoint's own connection and response timeouts (§9), the same
     *  values the chat funnel honors. Image generation can take far longer
     *  than chat, which is exactly why the configured response timeout is
     *  preserved rather than replaced with a shorter constant (§11). */
    private fun buildClient(endpoint: ApiEndpointObject): OkHttpClient {
        val connectSeconds = ApiEndpointObject
            .coerceConnectTimeoutSeconds(endpoint.connectTimeoutSeconds).toLong()
        val responseSeconds = ApiEndpointObject
            .coerceResponseTimeoutSeconds(endpoint.responseTimeoutSeconds).toLong()
        return OkHttpClient.Builder()
            .connectTimeout(connectSeconds, TimeUnit.SECONDS)
            .readTimeout(responseSeconds, TimeUnit.SECONDS)
            .writeTimeout(responseSeconds, TimeUnit.SECONDS)
            .build()
    }

    /** One temporary-URL download, size-capped; the URL itself is never
     *  persisted (§12). */
    private suspend fun downloadImage(client: OkHttpClient, url: String): ByteArray {
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            throw ImageGenerationException(
                ImageErrorCause.NO_USABLE_IMAGE,
                "the provider returned an unusable image URL"
            )
        }
        executeCall(client, Request.Builder().url(url).get().build()).use { response ->
            if (!response.isSuccessful) {
                throw ImageGenerationException(
                    ImageErrorCause.DOWNLOAD_INVALID,
                    "the image download failed with HTTP ${response.code}"
                )
            }
            return readBounded(response.body?.byteStream(), MAX_IMAGE_BYTES)
                ?: throw ImageGenerationException(
                    ImageErrorCause.DOWNLOAD_INVALID,
                    "the image exceeded the size limit"
                )
        }
    }

    /** OkHttp call as a cancellable suspension: cancelling the coroutine
     *  cancels the in-flight HTTP call. */
    private suspend fun executeCall(client: OkHttpClient, request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
        }

    /** Reads at most [max] bytes; null signals the stream went over. */
    private fun readBounded(stream: InputStream?, max: Int): ByteArray? {
        if (stream == null) return ByteArray(0)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                out.write(buffer, 0, read)
                if (out.size() > max) return null
            }
        }
        return out.toByteArray()
    }

    /** §13 network-failure split: a timeout and an unreachable endpoint
     *  need different explanations. SocketTimeoutException must be checked
     *  before its InterruptedIOException parent. */
    fun classifyNetworkException(e: IOException): ImageErrorCause = when (e) {
        is SocketTimeoutException -> ImageErrorCause.TIMED_OUT
        is UnknownHostException -> ImageErrorCause.ENDPOINT_UNREACHABLE
        is ConnectException -> ImageErrorCause.ENDPOINT_UNREACHABLE
        is SSLException -> ImageErrorCause.ENDPOINT_UNREACHABLE
        else ->
            if (e.message?.contains("timeout", ignoreCase = true) == true) {
                ImageErrorCause.TIMED_OUT
            } else {
                ImageErrorCause.ENDPOINT_UNREACHABLE
            }
    }
}
