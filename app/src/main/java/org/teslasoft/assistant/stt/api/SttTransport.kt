/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.stt.api

import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.teslasoft.assistant.preferences.tts.TtsRoutingMode
import org.teslasoft.assistant.tts.api.OkHttpTtsExecutor
import org.teslasoft.assistant.tts.api.ResolvedTtsSource
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
 * OpenRouter takes a JSON body with base64 audio and provider routing; the
 * vocabulary hint rides in provider.options for the providers this request
 * names (or the model's own author in Automatic), since OpenRouter has no
 * top-level hint field. Every other endpoint gets the OpenAI-style multipart
 * upload, where the hint is the `prompt` field.
 */
class SttTransport(private val http: TtsHttpExecutor = OkHttpTtsExecutor()) {
    private val op = TtsOperation.TRANSCRIPTION

    fun request(source: ResolvedTtsSource, audio: ByteArray, format: String, language: String?,
        vocabulary: List<String>, encode: (ByteArray) -> String = ::base64): Request {
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
                    addProperty("data", encode(audio)); addProperty("format", format)
                })
                if (language != null) addProperty("language", language)
            }
            val options = JsonObject()
            if (hint != null) hintProviders(source).forEach { slug ->
                options.add(slug, JsonObject().apply { addProperty("prompt", hint) })
            }
            val composed = TtsRouting.compose(body, t.routing, options)
            return builder.post(composed.toString().toRequestBody("application/json".toMediaType())).build()
        }
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "recording.$format", audio.toRequestBody("audio/$format".toMediaType()))
            .addFormDataPart("model", t.modelId)
            .addFormDataPart("response_format", "json")
        if (language != null) form.addFormDataPart("language", language)
        if (hint != null) form.addFormDataPart("prompt", hint)
        return builder.post(form.build()).build()
    }

    fun transcribe(source: ResolvedTtsSource, audio: ByteArray, format: String, language: String?,
        vocabulary: List<String>, token: TtsRequestToken): String {
        val response = http.execute(source.endpoint, source.target, op,
            request(source, audio, format, language, vocabulary), token)
        token.check()
        response.requireSuccess(source, op, if (source.endpoint.openRouter &&
            (source.target.routing.mode != TtsRoutingMode.AUTOMATIC || vocabulary.isNotEmpty())) listOf("provider") else emptyList())
        return parseText(response.bytes.toString(Charsets.UTF_8))
            ?: fail(source, TtsFailureKind.MALFORMED, responseReceived = true)
    }

    private fun hintProviders(source: ResolvedTtsSource): List<String> {
        val r = source.target.routing
        val ids = when (r.mode) {
            TtsRoutingMode.ONLY -> listOf(r.selectedProvider)
            TtsRoutingMode.PREFERRED -> r.providerOrder.ifEmpty { listOf(r.selectedProvider) }
            TtsRoutingMode.AUTOMATIC -> listOf(source.target.modelId.substringBefore('/', ""))
        }
        return ids.map { it.substringBefore('/') }.filter(String::isNotBlank).distinct()
    }

    private fun fail(source: ResolvedTtsSource, kind: TtsFailureKind, responseReceived: Boolean = false): Nothing =
        throw TtsException(TtsFailure(op, source.target, source.endpoint.label, kind, responseReceived = responseReceived))

    companion object {
        fun parseText(body: String): String? = runCatching {
            JsonParser.parseString(body).asJsonObject.get("text")?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()

        private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
