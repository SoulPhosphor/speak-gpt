package org.teslasoft.assistant.ui.activities

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstTurnEntryPointContractTest {
    private val chat = source("ui/activities/ChatActivity.kt")
    private val playground = source("ui/fragments/tabs/PlaygroundFragment.kt")

    @Test
    fun typedAndImagineCommitBeforeUiClearOrDispatch() {
        val parse = chat.substringAfter("private fun parseMessage(")
            .substringBefore("private fun handleImagineRequest(")
        assertOrdered(
            parse,
            "commitPendingConversation(",
            "if (!result.succeeded)",
            "clearComposerAfterCommittedSend()",
            "handleImagineRequest(imagineParse)",
            "generateResponse(m, false, preparedTurn)"
        )
    }

    @Test
    fun voiceAndHandsFreeReuseThePreparedTypedBoundary() {
        val voice = chat.substringAfter("private fun submitRecognizedText(")
            .substringBefore("override fun onResume()")
        assertTrue(voice.contains("prepareTypedTurn(recognizedText)"))
        assertTrue(voice.indexOf("messageInput?.setText(recognizedText)") <
            voice.indexOf("prepareTypedTurn(recognizedText)"))
    }

    @Test
    fun playgroundRunCommitsBeforeBusyUiAndRequest() {
        val runClick = playground.substringAfter("btnRun?.setOnClickListener")
            .substringBefore("btnReport?.setOnClickListener")
        assertOrdered(
            runClick,
            "commitPendingPlaygroundTurn(input)",
            "runLoader?.visibility = View.VISIBLE",
            "runAIRequest()"
        )
    }

    private fun assertOrdered(text: String, vararg markers: String) {
        var prior = -1
        markers.forEach { marker ->
            val next = text.indexOf(marker, prior + 1)
            assertTrue("Missing or out-of-order marker: $marker", next > prior)
            prior = next
        }
    }

    private fun source(relative: String): String {
        val path = "src/main/java/org/teslasoft/assistant/$relative"
        return listOf(File(path), File("app/$path"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")
    }
}
