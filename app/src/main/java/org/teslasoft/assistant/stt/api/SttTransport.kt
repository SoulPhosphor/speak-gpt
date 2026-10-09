/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.stt.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.BufferedSink
import java.io.File
import java.io.FilterOutputStream
import org.teslasoft.assistant.preferences.tts.TtsRoutingMode
import org.teslasoft.assistant.tts.api.OkHttpTtsExecutor
import org.teslasoft.assistant.tts.api.ResolvedTtsSource
import org.teslasoft.assistant.tts.api.TtsDiscoveryClient
import org.teslasoft.assistant.tts.api.TtsException
import org.teslasoft.assistant.tts.api.TtsFailure
import org.teslasoft.assistant.tts.api.TtsFailureKind
import org.teslasoft.assistant.tts.api.TtsHttpExecutor
import org.teslasoft.assistant.tts.api.TtsOperation
import org.teslasoft.assistant.tts.api.TtsRequestToken
import org.teslasoft.assistant.tts.api.TtsRouting
import org.teslasoft.assistant.tts.api.requestBuilder
import org.teslasoft.assistant.tts.api.requireSuccess

/**
 * Sends one recording to the configured API Voice Service and returns its text.
 *
 * OpenRouter takes a JSON body with base64 audio and the user's provider
 * routing (sent as chosen; OpenRouter currently documents that it does not
 * apply order/only on this endpoint). It forwards provider.options only to the
 * provider that actually serves the request, so the vocabulary hint is keyed
 * to every provider serving the model, read from OpenRouter, plus any the
 * routing names. Every other endpoint gets the OpenAI-style multipart upload,
 * where the hint is the `prompt` field.
 */
class SttTransport(
    private val http: TtsHttpExecutor = OkHttpTtsExecutor(),
    private val servingProviders: (ResolvedTtsSource, TtsRequestToken) -> List<String> = { source, token ->
        TtsDiscoveryClient(http).providers(source, token).providers.map { it.id }
    }
) {
    private val op = TtsOperation.TRANSCRIPTION

    /** [audio] is streamed from disk, never loaded whole, so recording length cannot exhaust memory. */
    fun request(source: ResolvedTtsSource, audio: File, format: String, language: String?,
        vocabulary: List<String>, hintProviders: List<String> = emptyList()): Request {
        val t = source.target
        if (t.endpointId.isBlank()) fail(source, TtsFailureKind.ENDPOINT_REQUIRED)
        if (t.modelId.isBlank()) fail(source, TtsFailureKind.MODEL_REQUIRED)
        if (t.routing.mode == TtsRoutingMode.ONLY && t.routing.selectedProvider.isBlank())
            fail(source, TtsFailureKind.PROVIDER_REQUIRED)
        val url = source.endpoint.baseUrl.trim().trimEnd('/') + "/audio/transcriptions"
        val hint = SttVocabulary.prompt(vocabulary)
        val builder = requestBuilder(source.endpoint, t, op, url).header("Accept", "application/json")
        if (source.endpoint.openRouter) {
            val body = JsonObject().apply {
                addProperty("model", t.modelId)
                add("input_audio", JsonObject().apply {
                    addProperty("data", AUDIO_SLOT); addProperty("format", format)
                })
                if (language != null) addProperty("language", language)
            }
            val options = JsonObject()
            if (hint != null) hintKeys(source, hintProviders).forEach { slug ->
                options.add(slug, JsonObject().apply { addProperty("prompt", hint) })
            }
            val composed = TtsRouting.compose(body, t.routing, options)
            return builder.post(StreamedJsonAudioBody(composed.toString(), audio)).build()
        }
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "recording.$format", audio.asRequestBody("audio/$format".toMediaType()))
            .addFormDataPart("model", t.modelId)
            .addFormDataPart("response_format", "json")
        if (language != null) form.addFormDataPart("language", language)
        if (hint != null) form.addFormDataPart("prompt", hint)
        return builder.post(form.build()).build()
    }

    fun transcribe(source: ResolvedTtsSource, audio: File, format: String, language: String?,
        vocabulary: List<String>, token: TtsRequestToken): String {
        // Only needed when there is a hint to deliver; a failed lookup still
        // sends the recording, keyed to the providers the routing names.
        val serving = if (source.endpoint.openRouter && vocabulary.isNotEmpty())
            try { servingProviders(source, token) } catch (_: TtsException) { token.check(); emptyList() }
            else emptyList()
        val response = http.execute(source.endpoint, source.target, op,
            request(source, audio, format, language, vocabulary, hintProviders = serving), token)
        token.check()
        response.requireSuccess(source, op, if (source.endpoint.openRouter &&
            (source.target.routing.mode != TtsRoutingMode.AUTOMATIC || vocabulary.isNotEmpty())) listOf("provider") else emptyList())
        return parseText(response.bytes.toString(Charsets.UTF_8))
            ?: fail(source, TtsFailureKind.MALFORMED, responseReceived = true)
    }

    private fun hintKeys(source: ResolvedTtsSource, serving: List<String>): List<String> {
        val r = source.target.routing
        val named = when (r.mode) {
            TtsRoutingMode.ONLY -> listOf(r.selectedProvider)
            TtsRoutingMode.PREFERRED -> r.providerOrder.ifEmpty { listOf(r.selectedProvider) }
            TtsRoutingMode.AUTOMATIC -> listOf(source.target.modelId.substringBefore('/', ""))
        }
        return (named + serving).map { it.substringBefore('/') }.filter(String::isNotBlank).distinct()
    }

    private fun fail(source: ResolvedTtsSource, kind: TtsFailureKind, responseReceived: Boolean = false): Nothing =
        throw TtsException(TtsFailure(op, source.target, source.endpoint.label, kind, responseReceived = responseReceived))

    companion object {
        fun parseText(body: String): String? = runCatching {
            JsonParser.parseString(body).asJsonObject.get("text")?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()

        /** Placeholder for the audio inside the composed JSON; replaced by streamed base64. */
        private const val AUDIO_SLOT = "__stt_audio_base64__"
    }

    /** The composed JSON with the audio's base64 written straight from the file. */
    private class StreamedJsonAudioBody(json: String, private val audio: File) : RequestBody() {
        private val prefix = json.substringBefore("\"$AUDIO_SLOT\"") + "\""
        private val suffix = "\"" + json.substringAfter("\"$AUDIO_SLOT\"")

        override fun contentType() = "application/json".toMediaType()

        override fun contentLength(): Long {
            val n = audio.length()
            return prefix.toByteArray().size + 4 * ((n + 2) / 3) + suffix.toByteArray().size
        }

        override fun writeTo(sink: BufferedSink) {
            sink.writeUtf8(prefix)
            // The encoder must not close the sink, which OkHttp still owns.
            val keepOpen = object : FilterOutputStream(sink.outputStream()) { override fun close() = flush() }
            java.util.Base64.getEncoder().wrap(keepOpen).use { out -> audio.inputStream().use { it.copyTo(out) } }
            sink.writeUtf8(suffix)
        }
    }
}
