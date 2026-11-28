package org.teslasoft.assistant.ui.activities

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android activity owns the live client and cannot be instantiated in the
 * JVM test suite. Keep the two transport paths structurally pinned here: ON
 * must collect Aallam's stream, while OFF must call its completed-response API
 * and conditionally omit stream_options at request construction time.
 */
class StreamingRequestPathTest {

    private val source: String by lazy { chatActivitySource().readText() }
    private val runSource: String by lazy {
        mainSource("src/main/java/org/teslasoft/assistant/conversation/reply/ReplyRun.kt").readText()
    }
    private val hooksSource: String by lazy {
        mainSource("src/main/java/org/teslasoft/assistant/conversation/reply/ChatRequestHooks.kt").readText()
    }

    @Test
    fun normalChatHasDistinctStreamingAndCompletedResponseCalls() {
        val region = source.substringAfter(
            "private suspend fun regularGPTResponse(",
            missingDelimiterValue = ""
        )
        assertTrue("regularGPTResponse source not found", region.isNotEmpty())
        assertTrue(region.contains("val streamingEnabled = preferences?.getStreaming() ?: true"))
        assertTrue(region.contains("if (streamingEnabled)"))
        assertTrue(region.contains("ai!!.chatCompletions(chatCompletionRequest)"))
        // Both reply calls run through the reply's relay (ReplyRun).
        assertTrue(region.contains("replyRun.relay(ai!!.chatCompletions(chatCompletionRequest)) { ReplyPiece.of(it) }"))
        assertTrue(region.contains("replyRun.relayCall({ client.chatCompletion(chatCompletionRequest) }) { ReplyPiece.of(it) }"))
        assertTrue(
            region.contains(
                "streamOptions = if (streamingEnabled) StreamOptions(includeUsage = true) else null"
            )
        )
        // The reply marks a completed (non-streaming) response when it records it.
        assertTrue(runSource.contains("state.currentLifecycle?.markNonStreamingResponse()"))
        // The request hooks (and their attribute keys) live in ChatRequestHooks.
        assertTrue(hooksSource.contains("AttributeKey<Boolean>(\"GenerationRequest\")"))
        assertTrue(region.contains("generationRequestActive = true"))
    }

    @Test
    fun completedResponseErrorsAreCapturedOnTheirExactRequestAttempt() {
        // The response observer lives in ChatRequestHooks.
        assertTrue(hooksSource.contains("if (!response.status.isSuccess())"))
        assertTrue(hooksSource.contains("attrs[providerUsageAttemptAttribute]"))
        assertTrue(hooksSource.contains(".noteHttpResponse(response.status.value, body)"))
        assertTrue(!hooksSource.contains("capturedProviderErrorBody"))
        assertTrue(!source.contains("capturedProviderErrorBody"))
        assertTrue(source.contains("replyRun.relayCall({ client.chatCompletion(chatCompletionRequest) }) { ReplyPiece.of(it) }"))
        assertTrue(source.contains("stream_options"))
    }

    private fun chatActivitySource(): File =
        mainSource("src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")

    private fun mainSource(relative: String): File {
        val candidates = listOf(
            File(relative),
            File("app/$relative"),
            File(System.getProperty("user.dir"), relative),
            File(System.getProperty("user.dir"), "app/$relative")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Could not locate $relative from ${System.getProperty("user.dir")}")
    }
}
