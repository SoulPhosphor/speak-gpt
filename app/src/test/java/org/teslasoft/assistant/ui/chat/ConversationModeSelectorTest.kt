package org.teslasoft.assistant.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationModeSelectorTest {
    private val source = File(
        "src/main/java/org/teslasoft/assistant/ui/chat/ConversationModeSelector.kt"
    ).readText()

    @Test
    fun defaultsToChatAndChangesImmediately() {
        assertTrue(source.contains("private var mode = ConversationMode.CHAT"))
        assertTrue(source.contains("fun setMode(value: ConversationMode"))
        assertTrue(source.contains("mode = value"))
    }

    @Test
    fun colorsComeFromCentralThemeRoles() {
        assertTrue(source.contains("colorSurfaceContainerHigh"))
        assertTrue(source.contains("colorSecondaryContainer"))
        assertTrue(source.contains("colorOnSecondaryContainer"))
        assertTrue(source.contains("colorOnSurfaceVariant"))
    }
}
