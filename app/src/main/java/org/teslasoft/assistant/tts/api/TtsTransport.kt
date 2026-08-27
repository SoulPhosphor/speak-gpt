package org.teslasoft.assistant.tts.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.tts.TtsRoutingMode
import org.teslasoft.assistant.preferences.tts.TtsRoutingSettings
import org.teslasoft.assistant.providers.ProviderDiagnosticParser
import org.teslasoft.assistant.providers.ProviderDiagnosticSnapshot
import org.teslasoft.assistant.providers.ProviderRoutingSerializer
import org.teslasoft.assistant.providers.RoutingDecision
import org.teslasoft.assistant.providers.RoutingBlock
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Endpoint adapters can change wire shape without changing saved routing or the chat serializer. */
fun interface TtsRequestAdapter {
    fun compose(body: JsonObject, routing: TtsRoutingSettings, providerOptions: JsonObject): JsonObject
}

object TtsRouting : TtsRequestAdapter {
    fun requestedProvider(r: TtsRoutingSettings): String? = when(r.mode) {
        TtsRoutingMode.AUTOMATIC -> null
        TtsRoutingMode.PREFERRED -> r.providerOrder.firstOrNull() ?: r.selectedProvider.ifBlank { null }
        TtsRoutingMode.ONLY -> r.selectedProvider.ifBlank { null }
    }

    override fun compose(body: JsonObject, routing: TtsRoutingSettings, providerOptions: JsonObject): JsonObject {
        routing.validate()
        val order = routing.providerOrder.ifEmpty { listOfNotNull(routing.selectedProvider.ifBlank { null }) }
        val decision = RoutingDecision(
            block = RoutingBlock.NONE,
            only = routing.selectedProvider.takeIf { routing.mode == TtsRoutingMode.ONLY },
            order = order.takeIf { routing.mode == TtsRoutingMode.PREFERRED }.orEmpty(),
            allowFallbacks = routing.allowFallbacks
        )
        val root = body.deepCopy()
        val provider = root.get("provider").objectOrNull()?.deepCopy() ?: JsonObject()
        listOf("order", "only", "allow_fallbacks", "ignore").forEach(provider::remove)
        if (providerOptions.size() > 0) {
            val options = provider.get("options").objectOrNull()?.deepCopy() ?: JsonObject()
            providerOptions.entrySet().forEach { (key, value) -> options.add(key, value.deepCopy()) }
            provider.add("options", options)
        }
        ProviderRoutingSerializer.providerObject(decision)?.entrySet()?.forEach { (key, value) ->
            provider.add(key, value.deepCopy())
        }
        if (provider.size() > 0) root.add("provider", provider) else root.remove("provider")
        return root
    }
}

/** One operation generation per UI consumer. Replacing it cancels sockets and invalidates queued callbacks. */
class TtsRequestGate {
    private var current: TtsRequestToken? = null
    fun begin(): TtsRequestToken {
        val next = TtsRequestToken()
        val previous = synchronized(this) { current.also { current = next } }
        // Never hold the gate lock while acquiring a token's delivery lock.
        previous?.cancel()
        return next
    }
    fun cancel() {
        val previous = synchronized(this) { current.also { current = null } }
        previous?.cancel()
    }
}

class TtsRequestToken internal constructor() {
    private var cancelled = false
    private val calls = mutableSetOf<Call>()
    @Synchronized fun check() { if (cancelled) throw CancellationException() }
    @Synchronized internal fun attach(call: Call) { check(); calls += call }
    @Synchronized internal fun detach(call: Call) { calls -= call }
    @Synchronized fun cancel() { cancelled = true; calls.forEach(Call::cancel); calls.clear() }
    /** Call on the delivery thread immediately around UI updates or playback start. */
    @Synchronized fun deliver(block: () -> Unit): Boolean {
        if (cancelled) return false
        block()
        return true
    }
}

data class TtsHttpResponse(val status: Int, val bytes: ByteArray, val contentType: String?,
    val generationId: String? = null)

/** Injectable boundary for tests; production always closes responses and disables automatic retries. */
interface TtsHttpExecutor {
    fun execute(endpoint: TtsEndpoint, target: TtsTarget, operation: TtsOperation,
        request: Request, token: TtsRequestToken): TtsHttpResponse
}

class OkHttpTtsExecutor(
    private val client: OkHttpClient = OkHttpClient(),
    private val confirmedOffline: () -> Boolean = { false }
) : TtsHttpExecutor {
    override fun execute(endpoint: TtsEndpoint, target: TtsTarget, operation: TtsOperation,
        request: Request, token: TtsRequestToken): TtsHttpResponse {
        token.check()
        val attempt = UUID.randomUUID().toString()
        var connected = false
        var status: Int? = null
        val configured = client.newBuilder()
            .connectTimeout(endpoint.connectSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(endpoint.responseSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(endpoint.responseSeconds.toLong(), TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            // Prevent auth headers being forwarded to another origin and POST replay on redirects.
            .followRedirects(false).followSslRedirects(false)
            .eventListener(object : EventListener() {
                override fun requestHeadersStart(call: Call) { connected = true }
            }).build()
        val call = configured.newCall(request)
        token.attach(call)
        try {
            if (confirmedOffline()) throw TtsException(TtsFailure(operation, target, endpoint.label,
                TtsFailureKind.OFFLINE, ProviderDiagnosticSnapshot(attempt)))
            return call.execute().use { response ->
                status = response.code
                val bytes = response.body?.bytes() ?: byteArrayOf()
                token.check()
                TtsHttpResponse(response.code, bytes, response.header("Content-Type"),
                    response.header("X-Generation-Id"))
            }
        } catch (e: IOException) {
            token.check()
            val kind = when(e) {
                is UnknownHostException -> TtsFailureKind.DNS
                is SSLException -> TtsFailureKind.TLS
                is SocketTimeoutException -> if (connected) TtsFailureKind.RESPONSE_TIMEOUT else TtsFailureKind.CONNECT_TIMEOUT
                is ConnectException -> if (e.message.orEmpty().contains("refused", true)) TtsFailureKind.REFUSED else TtsFailureKind.CONNECTION
                else -> TtsFailureKind.CONNECTION
            }
            throw TtsException(TtsFailure(operation, target, endpoint.label, kind,
                ProviderDiagnosticSnapshot(attempt, outerHttpStatus = status)))
        } finally { token.detach(call) }
    }
}

internal fun requestBuilder(endpoint: TtsEndpoint, target: TtsTarget, operation: TtsOperation,
    url: String): Request.Builder {
    val address = url.toHttpUrlOrNull()
    val base = endpoint.baseUrl.toHttpUrlOrNull()
    if (address == null || base == null || address.scheme != base.scheme ||
        address.host != base.host || address.port != base.port ||
        address.username.isNotEmpty() || address.password.isNotEmpty()) {
        throw TtsException(TtsFailure(operation, target, endpoint.label, TtsFailureKind.INVALID_ADDRESS))
    }
    val builder = Request.Builder().url(address)
    val key = endpoint.apiKey.takeUnless { it.isBlank() || it == "null" }
    if (key == null && endpoint.openRouter && operation in setOf(TtsOperation.SPEECH, TtsOperation.PREVIEW)) {
        throw TtsException(TtsFailure(operation, target, endpoint.label, TtsFailureKind.KEY_MISSING))
    }
    if (key != null) when(endpoint.authType) {
        ApiEndpointObject.AUTH_BEARER -> builder.header("Authorization", "Bearer $key")
        ApiEndpointObject.AUTH_X_API_KEY -> builder.header("x-api-key", key)
        ApiEndpointObject.AUTH_API_KEY -> builder.header("api-key", key)
        // An explicit no-auth/unknown mode must not leak a saved key as Bearer.
    }
    return builder
}

internal fun TtsHttpResponse.requireSuccess(source: ResolvedTtsSource, operation: TtsOperation,
    fields: List<String> = emptyList()) {
    val text = bytes.toString(Charsets.UTF_8)
    val jsonError = runCatching { JsonParser.parseString(text).asJsonObject.get("error") }
        .getOrNull()?.let { !it.isJsonNull } == true
    if (status in 200..299 && !jsonError) return
    // Raw payloads never include outbound input, authentication, or another attempt's state.
    val events = ProviderDiagnosticParser.parseHttpBody(text.replaceSecret(source.endpoint.apiKey), status)
    val evidence = ProviderDiagnosticSnapshot(UUID.randomUUID().toString(), status,
        actualServingProvider = events.firstNotNullOfOrNull { it.actualServingProvider },
        generationId = generationId, outboundFieldNames = fields, events = events)
    val (kind, classification) = TtsFailures.serverKind(evidence)
    throw TtsException(TtsFailure(operation, source.target, source.endpoint.label, kind, evidence, classification))
}

private fun String.replaceSecret(secret: String): String =
    if (secret.isBlank() || secret == "null") this else replace(secret, "[redacted]")

/** A validated MP3 result, not a player. Phase 5 uses token.deliver around playback start. */
class TtsAudio(val bytes: ByteArray, val target: TtsTarget, val generationId: String?) {
    val mimeType = "audio/mpeg"
    val extension = ".mp3"
}

class TtsSpeechTransport(
    private val http: TtsHttpExecutor = OkHttpTtsExecutor(),
    private val adapterFor: (TtsEndpoint) -> TtsRequestAdapter = { TtsRouting }
) {
    fun request(source: ResolvedTtsSource, input: String, operation: TtsOperation = TtsOperation.SPEECH,
        options: JsonObject = JsonObject()): Request {
        require(operation == TtsOperation.SPEECH || operation == TtsOperation.PREVIEW)
        val t = source.target
        if (t.endpointId.isBlank()) fail(source, operation, TtsFailureKind.ENDPOINT_REQUIRED)
        if (t.endpointId != source.endpoint.id) fail(source, operation, TtsFailureKind.SOURCE_MISSING)
        if (t.modelId.isBlank()) fail(source, operation, TtsFailureKind.MODEL_REQUIRED)
        if (t.voiceId.isNullOrBlank()) fail(source, operation, TtsFailureKind.VOICE_REQUIRED)
        if (t.routing.mode == TtsRoutingMode.ONLY && t.routing.selectedProvider.isBlank())
            fail(source, operation, TtsFailureKind.PROVIDER_REQUIRED)
        if (t.routing.mode == TtsRoutingMode.PREFERRED && !t.routing.allowFallbacks &&
            t.routing.providerOrder.isEmpty() && t.routing.selectedProvider.isBlank())
            fail(source, operation, TtsFailureKind.PROVIDER_REQUIRED)
        val body = JsonObject().apply {
            addProperty("model", t.modelId); addProperty("voice", t.voiceId)
            addProperty("input", input); addProperty("response_format", "mp3")
        }
        val composed = adapterFor(source.endpoint).compose(body, t.routing, options)
        return requestBuilder(source.endpoint, t, operation,
            ApiEndpointObject.composeSpeechUrl(source.endpoint.baseUrl, source.endpoint.speechPath))
            .header("Accept", "audio/mpeg")
            .post(composed.toString().toRequestBody("application/json".toMediaType())).build()
    }

    fun synthesize(source: ResolvedTtsSource, input: String, token: TtsRequestToken,
        operation: TtsOperation = TtsOperation.SPEECH, options: JsonObject = JsonObject()): TtsAudio {
        token.check()
        val request = request(source, input, operation, options)
        val response = http.execute(source.endpoint, source.target, operation, request, token)
        token.check()
        response.requireSuccess(source, operation, listOf("model", "voice", "input", "response_format") +
            if (source.target.routing.mode != TtsRoutingMode.AUTOMATIC || options.size() > 0) listOf("provider") else emptyList())
        if (response.bytes.isEmpty()) fail(source, operation, TtsFailureKind.NO_AUDIO, responseReceived = true)
        val type = response.contentType?.substringBefore(';')?.lowercase()
        if (type == "application/json" || type?.endsWith("+json") == true)
            fail(source, operation, TtsFailureKind.NO_AUDIO, responseReceived = true)
        val b = response.bytes
        val mp3 = (b.size >= 3 && b[0] == 73.toByte() && b[1] == 68.toByte() && b[2] == 51.toByte()) ||
            (b.size >= 2 && b[0].toInt() and 255 == 255 && b[1].toInt() and 224 == 224)
        if (!mp3 || type !in setOf(null, "audio/mpeg", "audio/mp3", "application/octet-stream"))
            fail(source, operation, TtsFailureKind.AUDIO_FORMAT, responseReceived = true)
        token.check()
        return TtsAudio(b, source.target, response.generationId)
    }

    private fun fail(source: ResolvedTtsSource, operation: TtsOperation, kind: TtsFailureKind,
        responseReceived: Boolean = false): Nothing =
        throw TtsException(TtsFailure(operation, source.target, source.endpoint.label, kind,
            responseReceived = responseReceived))
}
