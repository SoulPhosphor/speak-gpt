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
        if (evidence.errorEvents.isNotEmpty() || result.httpStatus?.let { it >= 400 } == true)
            return Owner.EXTERNAL
        // Concrete transport causes, not status-like prose in a local exception.
        val chain = generateSequence(error) { it.cause }.take(16).toList()
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

    fun localDetail(operation: String, error: Throwable, privateValues: List<String> = emptyList()): String =
        "Function/Operation: $operation\n" +
            SummarizerDetailSanitizer.sanitize(error.stackTraceToString(), privateValues, maxChars = 16000)
}
