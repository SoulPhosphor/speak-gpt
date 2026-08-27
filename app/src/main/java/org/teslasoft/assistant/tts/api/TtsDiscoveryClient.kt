package org.teslasoft.assistant.tts.api

import com.google.gson.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.tts.TtsRoutingMode
import org.teslasoft.assistant.providers.ProviderDiscoveryResolver

/** Separate, uncached model/provider/voice operations. Reopening always rechecks discovery. */
class TtsDiscoveryClient(private val http: TtsHttpExecutor = OkHttpTtsExecutor()) {
    fun models(source: ResolvedTtsSource, token: TtsRequestToken): TtsModelCatalog {
        val op = TtsOperation.MODELS
        var url = path(source, "models")
        if (source.endpoint.openRouter) url = checkedUrl(source, op, url).newBuilder()
            .addQueryParameter("output_modalities", "speech").build().toString()
        val response = get(source, op, url, token)
        response.requireSuccess(source, op)
        return parse(source, op) { TtsCatalogParser.models(response.bytes.toString(Charsets.UTF_8)) }
    }

    fun providers(source: ResolvedTtsSource, token: TtsRequestToken): TtsProviderCatalog {
        requireModel(source, TtsOperation.PROVIDERS)
        val op = TtsOperation.PROVIDERS
        val endpoint = source.endpoint
        val configured = endpoint.discoveryPath
        val fallback = path(source, configured.replace("{model}", encodedModel(source.target.modelId)))
        var canonical: String? = null
        if (endpoint.openRouter && configured == ApiEndpointObject.DEFAULT_PROVIDER_DISCOVERY_PATH) {
            // Optional alias resolution cannot mask a later successful provider response.
            try {
                val lookup = get(source, op, path(source, "model/${encodedModel(source.target.modelId)}"), token)
                if (lookup.status in 200..299) canonical = ProviderDiscoveryResolver.detailsUrl(
                    endpoint.baseUrl, lookup.bytes.toString(Charsets.UTF_8))
            } catch (_: TtsException) { token.check() }
        }
        var failure: TtsException? = null
        for (url in listOfNotNull(canonical, fallback).distinct()) {
            try {
                val response = get(source, op, url, token)
                if (response.status in setOf(404, 405, 501)) {
                    preserveSpecificFailure(response, source, op)
                    if (failure == null) failure = unavailable(source, op)
                    continue
                }
                response.requireSuccess(source, op)
                var catalog = parse(source, op) { TtsProviderParser.parse(response.bytes.toString(Charsets.UTF_8)) }
                if (endpoint.openRouter) {
                    try {
                        val zdr = get(source, op, path(source, "endpoints/zdr"), token)
                        if (zdr.status in 200..299) catalog = TtsProviderParser.overlayZdr(catalog,
                            zdr.bytes.toString(Charsets.UTF_8), source.target.modelId)
                    } catch (_: TtsException) { token.check() }
                    catch (_: IllegalArgumentException) { token.check() }
                }
                token.check()
                return catalog
            } catch (e: TtsException) { failure = e }
        }
        token.check()
        throw failure ?: unavailable(source, op)
    }

    fun voices(source: ResolvedTtsSource, token: TtsRequestToken): TtsVoiceCatalog {
        val op = TtsOperation.VOICES
        requireModel(source, op)
        var model: TtsModel? = null
        var failure: TtsException? = null
        try {
            // Reclassify the operation because this catalog request is part of loading voices.
            model = models(source, token).models.singleOrNull { it.id == source.target.modelId }
        } catch (e: TtsException) { failure = TtsException(e.failure.copy(operation = op)) }
        if (model == null && source.endpoint.openRouter) {
            try {
                val lookup = get(source, op, path(source, "model/${encodedModel(source.target.modelId)}"), token)
                lookup.requireSuccess(source, op)
                model = parse(source, op) { TtsCatalogParser.exact(lookup.bytes.toString(Charsets.UTF_8), source.target.modelId) }
            } catch (e: TtsException) { failure = e }
        }
        // Provider-specific metadata, when supplied, is narrower than a model-wide list.
        if (source.target.routing.mode != TtsRoutingMode.AUTOMATIC) {
            try {
                val catalog = providers(source, token)
                val wanted = if (source.target.routing.mode == TtsRoutingMode.ONLY)
                    listOf(source.target.routing.selectedProvider)
                else source.target.routing.providerOrder.ifEmpty { listOf(source.target.routing.selectedProvider) }
                val selected = wanted.mapNotNull { id -> catalog.providers.singleOrNull { it.id == id } }
                if (selected.size == wanted.size && selected.isNotEmpty() && selected.all { it.voices is TtsVoiceCatalog.Known }) {
                    val lists = selected.map { (it.voices as TtsVoiceCatalog.Known).voices }
                    val common = lists.first().filter { v -> lists.all { rows -> rows.any { it.id == v.id } } }
                    token.check()
                    return TtsVoiceCatalog.Known(common)
                }
            } catch (_: TtsException) { token.check() }
        }
        model?.voices?.takeUnless { it == TtsVoiceCatalog.Unavailable }?.let { token.check(); return it }
        // OpenRouter's model metadata is its supported discovery source. No invented OpenAI fallback.
        if (source.endpoint.openRouter) {
            token.check()
            if (model == null && failure != null) throw failure
            return TtsVoiceCatalog.Unavailable
        }
        for (probe in listOf("audio/voices", "voices")) {
            try {
                val url = checkedUrl(source, op, path(source, probe)).newBuilder()
                    .addQueryParameter("model", source.target.modelId).apply {
                        val routing = TtsRouting.compose(JsonObject(), source.target.routing, JsonObject()).get("provider")
                        if (routing != null) addQueryParameter("provider", routing.toString())
                    }.build().toString()
                val response = get(source, op, url, token)
                if (response.status in setOf(404, 405, 501)) {
                    preserveSpecificFailure(response, source, op)
                    continue
                }
                response.requireSuccess(source, op)
                val voices = TtsCatalogParser.voiceResponse(response.bytes.toString(Charsets.UTF_8))
                token.check()
                if (voices is TtsVoiceCatalog.Known) return voices
                if (voices is TtsVoiceCatalog.Invalid) failure = TtsException(TtsFailure(op,
                    source.target, source.endpoint.label, voices.kind))
            } catch (e: TtsException) { failure = e }
        }
        token.check()
        // A successful model catalog with missing voices is not itself a failed request.
        if (failure != null) throw failure
        return TtsVoiceCatalog.Unavailable
    }

    private fun get(source: ResolvedTtsSource, op: TtsOperation, url: String,
        token: TtsRequestToken): TtsHttpResponse {
        token.check()
        if (source.target.endpointId.isBlank()) throw TtsException(TtsFailure(op, source.target,
            source.endpoint.label, TtsFailureKind.ENDPOINT_REQUIRED))
        if (source.target.endpointId != source.endpoint.id) throw TtsException(TtsFailure(op, source.target,
            source.endpoint.label, TtsFailureKind.SOURCE_MISSING))
        val request = requestBuilder(source.endpoint, source.target, op, url).header("Accept", "application/json").get().build()
        return http.execute(source.endpoint, source.target, op, request, token).also { token.check() }
    }

    private fun path(source: ResolvedTtsSource, path: String): String =
        source.endpoint.baseUrl.trim().trimEnd('/') + "/" + path.trimStart('/')
    private fun checkedUrl(source: ResolvedTtsSource, op: TtsOperation, url: String) =
        url.toHttpUrlOrNull() ?: throw TtsException(TtsFailure(op, source.target,
            source.endpoint.label, TtsFailureKind.INVALID_ADDRESS))
    private fun encodedModel(id: String): String = id.split('/').joinToString("/") {
        java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
    }
    private fun requireModel(source: ResolvedTtsSource, op: TtsOperation) {
        if (source.target.modelId.isBlank()) throw TtsException(TtsFailure(op, source.target,
            source.endpoint.label, TtsFailureKind.MODEL_REQUIRED))
    }
    private fun unavailable(source: ResolvedTtsSource, op: TtsOperation) =
        TtsException(TtsFailure(op, source.target, source.endpoint.label, TtsFailureKind.DISCOVERY_UNAVAILABLE))
    private fun preserveSpecificFailure(response: TtsHttpResponse, source: ResolvedTtsSource, op: TtsOperation) {
        try { response.requireSuccess(source, op) } catch (e: TtsException) {
            if (e.failure.kind !in setOf(TtsFailureKind.NOT_FOUND, TtsFailureKind.REJECTED, TtsFailureKind.SERVER)) throw e
        }
    }
    private inline fun <T> parse(source: ResolvedTtsSource, op: TtsOperation, block: () -> T): T =
        try { block() } catch (e: Exception) {
            throw TtsException(TtsFailure(op, source.target, source.endpoint.label,
                (e as? TtsCatalogDataException)?.kind ?: TtsFailureKind.MALFORMED, responseReceived = true))
        }
}
