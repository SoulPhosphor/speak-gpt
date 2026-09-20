package org.teslasoft.assistant.ui.drawer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FlatChatRowBindingTest {
    private fun source(relative: String): String {
        val roots = listOf(File("src/main"), File("app/src/main"), File("../app/src/main"))
        return File(roots.first { it.isDirectory }, relative).readText()
    }

    @Test fun drawerBindResetsEveryRecyclableOptionalState() {
        val adapter = source("java/org/teslasoft/assistant/ui/drawer/DrawerHierarchyAdapter.kt")
        val binder = source("java/org/teslasoft/assistant/ui/drawer/FlatChatIdentityBinder.kt")
        listOf("model", "memory", "imageFrame", "bookmarkOverlay", "leading")
            .forEach { view -> assertTrue("Missing reset for $view", binder.contains("$view.visibility = View.GONE")) }
        listOf("snippet", "date", "chevron")
            .forEach { view -> assertTrue("Missing reset for $view", adapter.contains("$view.visibility = View.GONE")) }
        assertTrue(adapter.contains("identity.reset()"))
        assertTrue(adapter.contains("root.updatePadding(left = baseStart, right = baseStart)"))
        assertTrue(adapter.contains("root.setOnClickListener"))
        assertTrue(adapter.contains("root.setOnLongClickListener"))
    }

    @Test fun drawerAndSearchUseTheSameIdentityBinder() {
        val drawer = source("java/org/teslasoft/assistant/ui/drawer/DrawerHierarchyAdapter.kt")
        val search = source("java/org/teslasoft/assistant/ui/adapters/SearchResultAdapter.kt")
        assertTrue(drawer.contains("FlatChatIdentityBinder("))
        assertTrue(search.contains("FlatChatIdentityBinder("))
    }

    @Test fun drawerDestinationsAndRollbackBoundaryStayExplicit() {
        val controller = source("java/org/teslasoft/assistant/ui/drawer/ChatDrawerController.kt")
        val manifest = source("AndroidManifest.xml")
        listOf("ImageGalleryActivity::class.java", "SearchActivity::class.java", "SettingsActivity::class.java")
            .forEach { assertTrue(controller.contains(it)) }
        assertTrue(controller.contains("NewConversationCoordinator(activity)"))
        assertTrue(manifest.contains(".ui.activities.MainActivity"))
        assertTrue(manifest.contains("android.intent.category.LAUNCHER"))
    }
}
