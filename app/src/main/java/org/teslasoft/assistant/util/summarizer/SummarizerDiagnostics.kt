package org.teslasoft.assistant.util.summarizer

import com.google.gson.JsonParser
import org.teslasoft.assistant.providers.ProviderDiagnosticParser
import org.teslasoft.assistant.providers.ProviderDiagnosticSnapshot
import org.teslasoft.assistant.providers.ReportedProviderParser
import org.teslasoft.assistant.util.GenErrorCode
import org.teslasoft.assistant.util.GenErrorResult
import java.util.UUID

/** Small request-local diagnostic policy; response arrival alone never assigns fault. */
object SummarizerDiagnostics {
    enum class Owner { EXTERNAL, LOCAL, CONFIGURATION, CANCELLED }

    /** Exactly one diagnostic channel; status UI is handled separately. */
    fun record(owner: Owner, logProviders: Boolean, external: () -> Unit, local: () -> Unit) {
        when (owner) {
            Owner.EXTERNAL -> if (logProviders) external()
            Owner.LOCAL -> local()
            Owner.CONFIGURATION, Owner.CANCELLED -> Unit
        }
    }

    class RequestEvidence {
        val attemptId: String = UUID.randomUUID().toString()
        @Volatile var dispatched = false
        @Volatile var status: Int? = null
        @Volatile var body: String? = null

        fun snapshot(): ProviderDiagnosticSnapshot {
            val events = if (status?.let { it >= 400 } == true)
                ProviderDiagnosticParser.parseHttpBody(body, status!!)
            else body?.let { ProviderDiagnosticParser.parseSsePayload(it) }.orEmpty()
            val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            return ProviderDiagnosticSnapshot(
                attemptId = attemptId,
                outerHttpStatus = status,
                actualServingProvider = events.firstNotNullOfOrNull { it.actualServingProvider }
                    ?: root?.let { ReportedProviderParser.providerFromRoot(it) },
                events = events
            )
        }
    }

    fun owner(error: Throwable, result: GenErrorResult, request: RequestEvidence): Owner {
        if (result.code == GenErrorCode.C1) return Owner.CANCELLED
        if (!request.dispatched) return Owner.LOCAL
        val evidence = request.snapshot()
        val chain = generateSequence(error) { it.cause }.take(16).toList()
        // The shared classifier also understands status-like prose. Diagnostic
        // ownership needs actual HTTP/provider evidence, not just that prose.
        val typedHttpFailure = chain.any { cause ->
            (cause.javaClass.name.startsWith("com.aallam.openai.") || cause.javaClass.name.startsWith("io.ktor.")) &&
                cause.javaClass.methods.any { method ->
                    method.parameterCount == 0 && method.name in listOf("getStatusCode", "getStatus") &&
                        runCatching {
                            val value = method.invoke(cause)
                            val status = (value as? Number)?.toInt()
                                ?: value?.javaClass?.methods?.firstOrNull { it.name == "getValue" && it.parameterCount == 0 }
                                    ?.invoke(value)?.let { (it as? Number)?.toInt() }
                            status?.let { it >= 400 } == true
                        }.getOrDefault(false)
                }
        }
        if (evidence.errorEvents.isNotEmpty() || request.status?.let { it >= 400 } == true || typedHttpFailure)
            return Owner.EXTERNAL
        // Concrete transport causes, not status-like prose in a local exception.
        if (chain.any {
                it is java.net.SocketTimeoutException || it is java.net.UnknownHostException ||
                    it is java.net.ConnectException || it is java.net.SocketException ||
                    it is java.io.IOException && request.status == null ||
                    it.javaClass.name.startsWith("io.ktor.") &&
                    (it.javaClass.simpleName.contains("Timeout") || it.javaClass.simpleName.contains("Connect"))
            }) return Owner.EXTERNAL
        // Only assign a decoder failure externally when the captured wire response
        // demonstrably violates the chat-completion envelope. A valid response
        // followed by an app parser/state exception is a local bug.
        if (result.code == GenErrorCode.S2 && request.status != null && unusableResponse(request.body))
            return Owner.EXTERNAL
        return Owner.LOCAL
    }

    fun unusableResponse(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return true
        return try {
            val root = JsonParser.parseString(raw).asJsonObject
            val choices = root.getAsJsonArray("choices")
            root.get("id")?.isJsonPrimitive != true || root.get("created")?.isJsonPrimitive != true ||
                root.get("model")?.isJsonPrimitive != true || choices == null || choices.size() == 0 ||
                choices.any { choice ->
                    val message = choice.asJsonObject.getAsJsonObject("message")
                    message == null || message.get("role")?.isJsonPrimitive != true ||
                        message.get("content")?.takeUnless { it.isJsonNull }?.isJsonPrimitive != true
                }
        } catch (_: Exception) { true }
    }

    /** Exception messages and frames are sanitized separately so a malformed
     * payload in a message cannot remove the diagnostic stack/cause chain. */
    fun localDetail(operation: String, error: Throwable, privateValues: List<String> = emptyList()): String {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val chain = generateSequence(error) { it.cause }.takeWhile { seen.add(it) }.take(16).toList()
        val messages = chain.joinToString("\nCaused by: ") {
            it.javaClass.name + ": " + (SummarizerDetailSanitizer.sanitize(it.message, privateValues) ?: "(no message)")
        }
        val frames = chain.joinToString("\n") { cause ->
            cause.javaClass.name + "\n" + cause.stackTrace.take(40).joinToString("\n") { "    at $it" }
        }
        return "Function/Operation: $operation\n$messages\n$frames".take(16000)
    }

    /** Storage errors can quote the value being written. Keep only text values
     * in memory for redaction, never as diagnostic context. */
    fun privateSectionValues(json: String): List<String> = try {
        listOf(json) + JsonParser.parseString(json).asJsonArray.mapNotNull { element ->
            element.asJsonObject.get("text")?.takeIf { it.isJsonPrimitive }?.asString
        }
    } catch (_: Exception) { listOf(json) }
}
