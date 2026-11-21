package org.teslasoft.assistant.conversation.parallel

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.teslasoft.assistant.imagegen.ImageErrorSanitizer
import org.teslasoft.assistant.imagegen.StreamedToolCallAssembler
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.providers.RawSseInspector
import org.teslasoft.assistant.providers.ReportedProviderParser
import org.teslasoft.assistant.reasoning.ReasoningStreamAccumulator
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One HTTP call per execute. Credentials are resolved separately and never become durable prompt data.
 * The immutable assignment supplies path/auth/timeouts; no activity client, settings or callbacks are reused.
 */
internal class ParallelChatTransport(
    private val credential: (org.teslasoft.assistant.conversation.responsegroups.ResponseModelAssignment) -> String
) : ParallelTransport {
    override suspend fun execute(request: ParallelRequest, output: ParallelOutput): Unit = coroutineScope {
        if (request.assignment.protocol != "chat_completions" || request.assignment.modelId.contains(":ft") ||
            request.assignment.modelId.contains("ft:")) throw ParallelTransportFailure("protocol_preparation_required")
        val a = request.assignment
        val body = ParallelChatSnapshot.body(request)
        val key = credential(a)
        val auth = a.capabilities.getValue("parallel_auth_type")
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(ApiEndpointObject.coerceConnectTimeoutSeconds(a.parameters.getValue("parallel_connect_seconds").toInt()).toLong(), TimeUnit.SECONDS)
            .readTimeout(ApiEndpointObject.coerceResponseTimeoutSeconds(a.parameters.getValue("parallel_response_seconds").toInt()).toLong(), TimeUnit.SECONDS)
            .writeTimeout(ApiEndpointObject.coerceResponseTimeoutSeconds(a.parameters.getValue("parallel_response_seconds").toInt()).toLong(), TimeUnit.SECONDS)
            .build()
        val path = a.parameters.getValue("parallel_chat_path").trimStart('/')
        val builder = Request.Builder().url(a.endpoint.trimEnd('/') + "/" + path)
            .post(body.toRequestBody("application/json".toMediaType())).header("Accept", "application/json, text/event-stream")
        if (key.isNotBlank()) builder.header(when (auth) {
            ApiEndpointObject.AUTH_X_API_KEY -> "x-api-key"
            ApiEndpointObject.AUTH_API_KEY -> "api-key"
            ApiEndpointObject.AUTH_BEARER -> "Authorization"
            else -> throw ParallelTransportFailure("unsupported_auth_type")
        }, if (auth == ApiEndpointObject.AUTH_BEARER) "Bearer $key" else key)
        request.usage.noteOutboundFieldNames(JsonParser.parseString(body).asJsonObject.keySet().toList())
        val call = client.newCall(builder.build())
        val cancelWatch = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        val inspector = RawSseInspector()
        val reasoning = ReasoningStreamAccumulator()
        val calls = StreamedToolCallAssembler()
        val content = StringBuilder()
        var reasonLength = 0
        var inspectingStream = false
        var failure: Throwable? = null
        var terminal = false
        var doneEvent = false
        var sawChoice = false
        suspend fun frame(json: JsonObject, streaming: Boolean) {
            reasoning.acceptObject(json)
            val normalized = reasoning.snapshot().text
            if (a.parameters[ParallelChatSnapshot.SHOW_REASONING] != "false" && normalized.length > reasonLength) {
                output.reasoning(normalized.substring(reasonLength)); reasonLength = normalized.length
            }
            val choice = json.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject ?: return
            sawChoice = true
            val node = choice.getAsJsonObject(if (streaming) "delta" else "message")
                ?: throw ParallelTransportFailure("malformed_response")
            val finish = choice.get("finish_reason")?.takeUnless { it.isJsonNull }?.asString
            if (finish != null) terminal = true
            val delta = node.get("content")?.takeUnless { it.isJsonNull }?.let {
                if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) throw ParallelTransportFailure("malformed_response")
                it.asString
            }.orEmpty()
            if (delta.isNotEmpty()) { content.append(delta); output.text(delta) }
            request.usage.noteTypedChunk(finish, json.get("id")?.takeUnless { it.isJsonNull }?.asString, delta.length)
            node.getAsJsonArray("tool_calls")?.forEachIndexed { index, el ->
                val c = el.asJsonObject
                val fn = c.getAsJsonObject("function")
                calls.accept(if (streaming) c.get("index")?.asInt else index,
                    c.get("id")?.asString, fn?.get("name")?.asString, fn?.get("arguments")?.asString)
            }
        }
        try {
            val response = suspendCancellableCoroutine<Response> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                    override fun onResponse(call: Call, response: Response) {
                        if (continuation.isActive) continuation.resume(response) else response.close()
                    }
                })
            }
            response.use {
                request.usage.beginObservation(it.code)
                if (!it.isSuccessful) {
                    val raw = it.body?.string()
                    request.usage.noteHttpResponse(it.code, raw)
                    ReportedProviderParser.observeCompletedBody(raw)?.let(request.usage::noteRawObservation)
                    ImageErrorSanitizer.sanitize(raw, key)?.let { warning -> output.warning(warning) }
                    throw ParallelTransportFailure(if (JsonParser.parseString(body).asJsonObject.has("tools") &&
                        org.teslasoft.assistant.imagegen.ToolSupportClassifier.isToolsNotSupportedError(raw)) "tools_not_supported" else "http_error")
                }
                val streaming = JsonParser.parseString(body).asJsonObject.get("stream")?.asBoolean == true
                if (streaming) {
                    inspectingStream = true
                    val source = it.body?.source() ?: throw ParallelTransportFailure("empty_response")
                    val event = StringBuilder()
                    suspend fun consume() {
                        if (event.isEmpty()) return
                        val payload = event.toString(); event.setLength(0)
                        inspector.acceptLine("data: $payload")
                        if (payload == "[DONE]") { terminal = true; doneEvent = true; return }
                        val root = try { JsonParser.parseString(payload).asJsonObject }
                        catch (_: Exception) { throw ParallelTransportFailure("malformed_response") }
                        frame(root, true)
                    }
                    while (!doneEvent && !source.exhausted()) {
                        currentCoroutineContext().ensureActive()
                        val line = source.readUtf8Line() ?: break
                        if (line.isEmpty()) consume()
                        else if (line.startsWith("data:")) {
                            if (event.isNotEmpty()) event.append('\n')
                            event.append(line.substring(5).trimStart())
                        }
                    }
                    consume()
                    val observation = inspector.finishNormally()
                    observation.providerDiagnostics.filter { event -> event.isWarning }.flatMap { event ->
                        listOfNotNull(event.message) + event.additionalMessages
                    }.distinct().mapNotNull { warning -> ImageErrorSanitizer.sanitize(warning, key) }
                        .takeIf { warnings -> warnings.isNotEmpty() }?.let { warnings -> output.warning(warnings.joinToString("\n")) }
                    if (observation.providerErrorReceived) {
                        ImageErrorSanitizer.sanitize(observation.providerErrorSummary, key)?.let { warning -> output.warning(warning) }
                        throw ParallelTransportFailure(if (JsonParser.parseString(body).asJsonObject.has("tools") &&
                            org.teslasoft.assistant.imagegen.ToolSupportClassifier.isToolsNotSupportedError(observation.providerErrorSummary))
                            "tools_not_supported" else "provider_error")
                    }
                    if (!terminal || !sawChoice) throw ParallelTransportFailure("incomplete_stream")
                } else {
                    val raw = it.body?.string() ?: throw ParallelTransportFailure("empty_response")
                    val observation = ReportedProviderParser.observeCompletedBody(raw)
                        ?: throw ParallelTransportFailure("malformed_response")
                    request.usage.noteRawObservation(observation)
                    observation.providerDiagnostics.filter { event -> event.isWarning }.flatMap { event ->
                        listOfNotNull(event.message) + event.additionalMessages
                    }.distinct().mapNotNull { warning -> ImageErrorSanitizer.sanitize(warning, key) }
                        .takeIf { warnings -> warnings.isNotEmpty() }?.let { warnings -> output.warning(warnings.joinToString("\n")) }
                    if (observation.providerErrorReceived) throw ParallelTransportFailure("provider_error")
                    frame(JsonParser.parseString(raw).asJsonObject, false)
                    if (!sawChoice) throw ParallelTransportFailure("malformed_response")
                }
                val assembled = calls.assembled()
                if (content.isEmpty() && assembled.isEmpty()) throw ParallelTransportFailure("empty_response")
                if (calls.hasAnyFragments() && assembled.isEmpty()) throw ParallelTransportFailure("malformed_tool_call")
                if (assembled.isNotEmpty()) {
                    val assistant = JsonObject().apply {
                        addProperty("role", "assistant"); addProperty("content", content.toString())
                        add("tool_calls", JsonArray().also { out -> assembled.forEachIndexed { i, c -> out.add(JsonObject().apply {
                            addProperty("id", c.id ?: "call_$i"); addProperty("type", "function")
                            add("function", JsonObject().apply { addProperty("name", c.name); addProperty("arguments", c.arguments) })
                        }) } })
                        reasoning.reasoningDetails()?.let { details -> add("reasoning_details", details) }
                    }
                    output.tools(assistant.toString())
                }
            }
        } catch (e: Exception) {
            failure = e
            currentCoroutineContext().ensureActive()
            if (e is ParallelTransportFailure) throw e
            throw ParallelTransportFailure("transport_failure")
        } finally {
            if (inspectingStream) request.usage.noteRawObservation(failure?.let(inspector::finishByException) ?: inspector.finishNormally())
            request.usage.finishObservation()
            withContext(NonCancellable) { cancelWatch.cancelAndJoin() }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
