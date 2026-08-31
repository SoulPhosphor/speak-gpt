package org.teslasoft.assistant.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase7NavigationCutoverTest {
    private fun source(relative: String): String {
        val roots = listOf(File("src/main"), File("app/src/main"), File("../app/src/main"))
        return File(roots.first { it.isDirectory }, relative).readText()
    }

    @Test fun launcherRoutesDirectlyToOnePendingChatWithoutInflatingTabs() {
        val launcher = source("java/org/teslasoft/assistant/ui/activities/MainActivity.kt")
        assertTrue(launcher.contains("createOrRestoreStartupPendingConversation()"))
        assertTrue(launcher.contains("ChatActivity.rootIntent("))
        assertFalse(launcher.contains("setContentView(R.layout.activity_main)"))
        assertFalse(launcher.contains("ChatsListFragment"))
        assertFalse(launcher.contains("PlaygroundFragment"))
        assertFalse(launcher.contains("BottomNavigationView"))
    }

    @Test fun conversationNavigationReplacesTheTaskInsteadOfStackingOldSurfaces() {
        val chat = source("java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")
        val drawer = source("java/org/teslasoft/assistant/ui/drawer/ChatDrawerController.kt")
        val search = source("java/org/teslasoft/assistant/ui/activities/SearchActivity.kt")
        val gallery = source("java/org/teslasoft/assistant/ui/activities/ImageGalleryActivity.kt")
        assertTrue(chat.contains("Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK"))
        listOf(drawer, search, gallery).forEach { assertTrue(it.contains("ChatActivity.rootIntent(")) }
    }

    @Test fun launcherAndRecoveryOrderRemainExplicit() {
        val manifest = source("AndroidManifest.xml")
        val app = source("java/org/teslasoft/assistant/app/MainApplication.kt")
        assertTrue(manifest.contains(".ui.activities.MainActivity"))
        assertTrue(manifest.contains("android.intent.category.LAUNCHER"))
        assertOrdered(
            app,
            "SecurePrefs.reconcileOutageAtStartup(this)",
            "ChatDeletionCoordinator.get(this).recover()",
            "NewConversationCoordinator(this).recoverPendingCommits()",
            "ChatNavigationRepository.get(this).migrateSchema()",
            "ChatSearchIndexManager.get(this).ensureReady()"
        )
    }

    private fun assertOrdered(text: String, vararg markers: String) {
        var previous = -1
        markers.forEach { marker ->
            val next = text.indexOf(marker, previous + 1)
            assertTrue("Missing or out-of-order marker: $marker", next > previous)
            previous = next
        }
    }
}
