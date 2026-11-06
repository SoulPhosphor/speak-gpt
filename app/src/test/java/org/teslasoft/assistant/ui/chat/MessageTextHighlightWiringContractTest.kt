package org.teslasoft.assistant.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTextHighlightWiringContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    private val chatActivity = "src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt"

    @Test
    fun messageAndThinkingTextUseTheChatsOwnHighlighting() {
        val adapter = source("src/main/java/org/teslasoft/assistant/ui/adapters/chat/ChatAdapter.kt")

        assertTrue(adapter.contains("MessageTextHighlight.prepare(message)"))
        assertTrue(adapter.contains("reasoningText?.let(MessageTextHighlight::prepare)"))
    }

    @Test
    fun highlightingIsAttachedBeforeSelectionModeSoATapThatClearsItIsNotAToggle() {
        val activity = source(chatActivity)
        val highlight = activity.indexOf("textHighlight.attach(it)")
        val selection = activity.indexOf("selectionMode.attach(findViewById(R.id.chat_selection_bar), it)")

        assertTrue(highlight >= 0)
        assertTrue(selection > highlight)
    }

    @Test
    fun backClearsAHighlightBeforeLeavingSelectionModeOrTheChat() {
        val activity = source(chatActivity)

        assertEquals(2, Regex("""else if \(textHighlight\.isActive\) \{\s+textHighlight\.clear\(\)\s+\} else if \(selectionMode\.isActive\)""").findAll(activity).count())
    }

    @Test
    fun aTouchOutsideTheMessageListClearsTheHighlight() {
        val activity = source(chatActivity)

        assertTrue(activity.contains("textHighlight.onWindowTouchDown(event.rawX, event.rawY)"))
    }

    @Test
    fun theHighlightIsDrawnBehindTheMessageText() {
        val view = source("src/main/java/org/teslasoft/assistant/ui/chat/PortraitAwareMessageTextView.kt")

        assertTrue(
            Regex("""override fun onDraw\(canvas: Canvas\) \{\s+highlightPainter\?\.invoke\(canvas\)\s+super\.onDraw\(canvas\)""")
                .containsMatchIn(view)
        )
    }
}
