package org.teslasoft.assistant.ui.activities

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the request-ordering invariant that keeps the retained
 * conversation history cacheable across turns.
 *
 * ChatActivity is an Android Activity with a large amount of UI/runtime state,
 * so a local JVM test cannot safely instantiate it just to inspect the private
 * request builders. Instead this test checks the production source ordering
 * directly. If either builder moves memory/lore back ahead of retained history,
 * or puts the newest user turn ahead of them, this test fails in ordinary unit
 * CI before the invisible prompt/caching regression can ship.
 */
class ChatRequestOrderingSourceTest {

    private val source: String by lazy { chatActivitySource().readText() }

    @Test
    fun frozenTypedRequestKeepsVolatileLayersImmediatelyBeforeNewestMessage() {
        val body = between(
            source,
            "private suspend fun buildFrozenRegularRequest(",
            "// streamOptions (include-usage) is beta-gated"
        )

        assertOrdered(
            body,
            "if (summaryInjection != null)",
            "val resolvedHistory = resolveImagePartsForSend(requestMessages, requestIncludes)",
            "msgs.addAll(resolvedHistory.dropLast(1))",
            "msgs.add(ChatMessage(role = ChatRole.System, content = memoryAssemblyResult.prompt))",
            "val loreText = StringBuilder(getString(R.string.lorebook_injection_header))",
            "resolvedHistory.lastOrNull()?.let { msgs.add(it) }"
        )
    }

    @Test
    fun legacyRetryVoiceRequestKeepsVolatileLayersImmediatelyBeforeNewestMessage() {
        val body = source.substringAfter(
            "private suspend fun regularGPTResponse(",
            missingDelimiterValue = ""
        )
        assertTrue("regularGPTResponse source not found", body.isNotEmpty())

        assertOrdered(
            body,
            "if (legacySummaryInjection != null)",
            "val legacyResolvedHistory = resolveImagePartsForSend(",
            "msgs.addAll(legacyResolvedHistory.dropLast(1))",
            "content = memoryAssemblyResult.prompt",
            "for (match in loreBudget.kept)",
            "legacyResolvedHistory.lastOrNull()?.let { msgs.add(it) }"
        )
    }

    @Test
    fun attachmentResolutionPreservesOneOutgoingMessagePerLogicalTurn() {
        val body = between(
            source,
            "private suspend fun resolveImagePartsForSend(",
            "private fun buildMultiPartUserMessage("
        )

        assertTrue(
            "Attachment resolution must map one logical turn to one outgoing ChatMessage; " +
                "changing this to flatMap would invalidate the dropLast(1) split.",
            body.contains("textMessages.mapIndexed") && !body.contains("textMessages.flatMap")
        )
    }

    private fun assertOrdered(text: String, vararg markers: String) {
        var previous = -1
        for (marker in markers) {
            val index = text.indexOf(marker, startIndex = previous + 1)
            assertTrue("Expected marker in production request builder: $marker", index >= 0)
            assertTrue(
                "Request-ordering regression: '$marker' appeared out of order",
                index > previous
            )
            previous = index
        }
    }

    private fun between(text: String, start: String, end: String): String {
        val afterStart = text.substringAfter(start, missingDelimiterValue = "")
        assertTrue("Start marker not found: $start", afterStart.isNotEmpty())
        val beforeEnd = afterStart.substringBefore(end, missingDelimiterValue = "")
        assertTrue("End marker not found: $end", beforeEnd.isNotEmpty())
        return beforeEnd
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
            ?: error(
                "Could not locate ChatActivity.kt from ${System.getProperty("user.dir")}; " +
                    "checked: ${candidates.joinToString { it.path }}"
            )
    }
}
