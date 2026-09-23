package org.teslasoft.assistant.tts.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.tts.TtsRoutingMode
import org.teslasoft.assistant.providers.ProviderDiscoveryResolver

data class TtsVoiceDiscovery(val catalog: TtsVoiceCatalog, val evidence: TtsVoiceEvidence?)

/** Separate, uncached model/provider/voice operations. Reopening always rechecks discovery. */
class TtsDiscoveryClient(private val http: TtsHttpExecutor = OkHttpTtsExecutor()) {
    fun models(source: ResolvedTtsSource, token: TtsRequestToken): TtsModelCatalog =
        loadModels(source, TtsOperation.MODELS, token).first

    private fun loadModels(source: ResolvedTtsSource, op: TtsOperation,
        token: TtsRequestToken): Pair<TtsModelCatalog, TtsHttpResponse> {
        var url = path(source, "models")
        if (source.endpoint.openRouter) url = checkedUrl(source, op, url).newBuilder()
            .addQueryParameter("output_modalities", "speech").build().toString()
        val response = get(source, op, url, token)
        response.requireSuccess(source, op)
        return parse(source, op, response) { TtsCatalogParser.models(response.text()) } to response
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
                var catalog = parse(source, op, response) { TtsProviderParser.parse(response.text()) }
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

    fun voices(source: ResolvedTtsSource, token: TtsRequestToken): TtsVoiceCatalog =
        voiceDiscovery(source, token).catalog

    /** The catalog plus the response it was read from, so an unusable result can be shown as it arrived. */
    fun voiceDiscovery(source: ResolvedTtsSource, token: TtsRequestToken): TtsVoiceDiscovery {
        val op = TtsOperation.VOICES
        requireModel(source, op)
        var model: TtsModel? = null
        var failure: TtsException? = null
        var evidence: TtsVoiceEvidence? = null
        try {
            // Reclassify the operation because this catalog request is part of loading voices.
            val (catalog, response) = loadModels(source, op, token)
            model = catalog.models.singleOrNull { it.id == source.target.modelId }
            if (model != null) evidence = voiceEvidence(source, response, modelEntry(response, source.target.modelId))
        } catch (e: TtsException) { failure = TtsException(e.failure.copy(operation = op)) }
        if (model == null && source.endpoint.openRouter) {
            try {
                val lookup = get(source, op, path(source, "model/${encodedModel(source.target.modelId)}"), token)
                lookup.requireSuccess(source, op)
                model = parse(source, op, lookup) { TtsCatalogParser.exact(lookup.text(), source.target.modelId) }
                evidence = voiceEvidence(source, lookup)
            } catch (e: TtsException) { failure = e }
        }
        // Provider-specific metadata, when supplied, is narrower than a model-wide list.
        var providerFailure: TtsException? = null
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
                    return TtsVoiceDiscovery(TtsVoiceCatalog.Known(common), null)
                }
            } catch (e: TtsException) {
                token.check()
                // Kept so a later "no voices" result cannot hide why the provider list failed.
                providerFailure = TtsException(e.failure.copy(operation = op))
            }
        }
        model?.voices?.takeUnless { it == TtsVoiceCatalog.Unavailable }?.let {
            if (it is TtsVoiceCatalog.Known && it.voices.isEmpty()) providerFailure?.let { p -> throw p }
            token.check(); return TtsVoiceDiscovery(it, evidence)
        }
        // OpenRouter's model metadata is its supported discovery source. No invented OpenAI fallback.
        if (source.endpoint.openRouter) {
            token.check()
            if (model == null && failure != null) throw failure
            providerFailure?.let { throw it }
            return TtsVoiceDiscovery(TtsVoiceCatalog.Unavailable, evidence)
        }
        for (probe in listOf("audio/voices", "voices")) {
            try {
                val url = checkedUrl(source, op, path(source, probe)).newBuilder()
                    .addQueryParameter("model", source.target.modelId).apply {
                        val routing = TtsRouting.compose(JsonObject(), source.target.routing, JsonObject()).get("provider")
                        if (routing != null) addQueryParameter("provider", routing.toString())
                    }.build().toString()
                val response = get(source, op, url, token)
                evidence = voiceEvidence(source, response)
                if (response.status in setOf(404, 405, 501)) {
                    preserveSpecificFailure(response, source, op)
                    continue
                }
                response.requireSuccess(source, op)
                val voices = TtsCatalogParser.voiceResponse(response.text())
                token.check()
                if (voices is TtsVoiceCatalog.Known) {
                    if (voices.voices.isEmpty()) providerFailure?.let { throw it }
                    return TtsVoiceDiscovery(voices, evidence)
                }
                if (voices is TtsVoiceCatalog.Invalid) failure = TtsException(TtsFailure(op,
                    source.target, source.endpoint.label, voices.kind, responseReceived = true,
                    voiceEvidence = evidence?.copy(parsingError = voices.parsingError)))
            } catch (e: TtsException) { failure = e }
        }
        token.check()
        // A successful model catalog with missing voices is not itself a failed request.
        if (failure != null) throw failure
        providerFailure?.let { throw it }
        return TtsVoiceDiscovery(TtsVoiceCatalog.Unavailable, evidence)
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
    private inline fun <T> parse(source: ResolvedTtsSource, op: TtsOperation, response: TtsHttpResponse,
        block: () -> T): T =
        try { block() } catch (e: Exception) {
            throw TtsException(TtsFailure(op, source.target, source.endpoint.label,
                (e as? TtsCatalogDataException)?.kind ?: TtsFailureKind.MALFORMED, responseReceived = true,
                voiceEvidence = voiceEvidence(source, response).copy(parsingError = e.message ?: e.javaClass.simpleName)))
        }
    private fun TtsHttpResponse.text() = bytes.toString(Charsets.UTF_8)
    /** Only the response body is kept: no request headers, and the saved key is redacted. */
    private fun voiceEvidence(source: ResolvedTtsSource, response: TtsHttpResponse, body: String? = null) =
        TtsVoiceEvidence(response.status, (body ?: response.text()).replaceSecret(source.endpoint.apiKey)
            .trim().ifBlank { null }?.let { if (it.length > RAW_LIMIT) it.take(RAW_LIMIT) + "…" else it })
    /** The requested model's own entry is the relevant part of a whole-catalog response. */
    private fun modelEntry(response: TtsHttpResponse, id: String): String? = try {
        JsonParser.parseString(response.text()).objectOrNull()?.getAsJsonArrayOrNull("data")
            ?.firstOrNull { it.objectOrNull()?.text("id") == id }?.toString()
    } catch (_: Exception) { null }

    private companion object { const val RAW_LIMIT = 4000 }
}
