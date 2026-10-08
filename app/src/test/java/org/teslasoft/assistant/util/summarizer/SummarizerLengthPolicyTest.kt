package org.teslasoft.assistant.util.summarizer

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SummarizerLengthPolicyTest {
    private fun source(path: String): String = listOf(File(path), File("app/$path"), File("../$path"))
        .first { it.exists() }.readText()

    @Test fun sectionAndCompactRequestsDoNotReadLegacyLengthStateOrCapResponses() {
        val controller = source("src/main/java/org/teslasoft/assistant/util/summarizer/SummarizerController.kt")
        assertFalse(controller.contains("getSummarizerLength()"))
        assertFalse(controller.contains("getSummarizerOverLength()"))
        assertFalse(controller.contains("SummarizerLengthPolicy"))
        assertFalse(controller.contains("priorOverLength"))
        assertEquals(1, Regex("maxTokens =").findAll(controller).count())
        val image = controller.substring(controller.indexOf("suspend fun summarizeImagePrompt"),
            controller.indexOf("private suspend fun compactSnapshot"))
        assertTrue(image.contains("maxTokens = 200"))
        assertTrue(controller.contains("commitManualCompaction(summary, target, false, target)"))
    }

    @Test fun theUnusedControlIsRemovedWhileTheStoredPreferenceRemainsCompatible() {
        assertFalse(source("src/main/res/layout/activity_summarizer_settings.xml").contains("field_summary_length"))
        assertFalse(source("src/main/java/org/teslasoft/assistant/ui/activities/SummarizerSettingsActivity.kt")
            .contains("getSummarizerLength()"))
        assertTrue(source("src/main/java/org/teslasoft/assistant/preferences/Preferences.kt")
            .contains("fun getSummarizerLength()"))
    }
}
