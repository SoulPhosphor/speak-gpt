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
        assertTrue(region.contains("ai!!.chatCompletion(chatCompletionRequest)"))
        assertTrue(
            region.contains(
                "streamOptions = if (streamingEnabled) StreamOptions(includeUsage = true) else null"
            )
        )
        assertTrue(region.contains("currentLifecycle?.markNonStreamingResponse()"))
        assertTrue(source.contains("AttributeKey<Boolean>(\"GenerationRequest\")"))
        assertTrue(region.contains("generationRequestActive = true"))
    }

    @Test
    fun completedResponseErrorsAreCapturedOnTheirExactRequestAttempt() {
        assertTrue(source.contains("if (!response.status.isSuccess())"))
        assertTrue(source.contains("attrs[providerUsageAttemptAttribute]"))
        assertTrue(source.contains(".noteHttpResponse(response.status.value, body)"))
        assertTrue(!source.contains("capturedProviderErrorBody"))
        assertTrue(source.contains("ai!!.chatCompletion(chatCompletionRequest)"))
        assertTrue(source.contains("stream_options"))
    }

    private fun chatActivitySource(): File {
        val relative = "src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt"
        val candidates = listOf(
            File(relative),
            File("app/$relative"),
            File(System.getProperty("user.dir"), relative),
            File(System.getProperty("user.dir"), "app/$relative")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Could not locate ChatActivity.kt from ${System.getProperty("user.dir")}")
    }
}
