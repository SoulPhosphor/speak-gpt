package org.teslasoft.assistant.preferences.backup.portable

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableChatRestoreCoordinatorBoundaryTest {

    @Test
    fun coordinatorUsesThePhase9EngineAndNoActivityWritesChatsDirectly() {
        val coordinator = source("preferences/backup/portable/PortableChatRestoreCoordinator.kt")
        assertTrue(coordinator.contains("ChatRestoreManager.restoreFromArchive"))
        assertTrue(coordinator.contains("ConvertedChatRecoveryArchive.write"))
        assertFalse(coordinator.contains("SecurePrefs"))

        val activities = mainRoot().resolve("ui/activities").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        assertFalse(activities.contains("PortableChatRestoreCoordinator.restore"))
    }

    private fun source(relative: String): String = mainRoot().resolve(relative).readText()

    private fun mainRoot(): File = listOf(
        File("src/main/java/org/teslasoft/assistant"),
        File("app/src/main/java/org/teslasoft/assistant")
    ).firstOrNull(File::isDirectory) ?: error("main source root unavailable")
}
