package org.teslasoft.assistant.imagegen

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry holds attached chat screens strongly, so every screen must be
 * able to leave it — including a screen whose chat was deleted.
 */
class ImageGenerationJobRegistryAttachmentTest {

    private class Screen : ImageGenerationJobRegistry.Listener {
        override fun onImageJobFinished(
            job: ImageGenerationJobRegistry.ActiveJob,
            terminal: ImageGenerationJobRegistry.Terminal
        ) = Unit
    }

    private val screens = mutableListOf<Pair<String, Screen>>()

    private fun attach(chatId: String): Screen {
        val screen = Screen()
        screens.add(chatId to screen)
        ImageGenerationJobRegistry.attach(chatId, screen)
        return screen
    }

    @After fun detachEverything() {
        for ((chatId, screen) in screens) ImageGenerationJobRegistry.detach(chatId, screen)
    }

    @Test fun deletedChatScreenLeavesTheRegistry() {
        val before = ImageGenerationJobRegistry.attachedScreenCount()
        val screen = attach("attachment-test-deleted")
        ImageGenerationJobRegistry.markChatsDeleted(setOf("attachment-test-deleted"))
        ImageGenerationJobRegistry.detach("attachment-test-deleted", screen)
        assertEquals(before, ImageGenerationJobRegistry.attachedScreenCount())
    }

    @Test fun destroyedScreenCannotDetachItsReplacement() {
        val old = attach("attachment-test-replaced")
        val before = ImageGenerationJobRegistry.attachedScreenCount()
        attach("attachment-test-replaced")
        ImageGenerationJobRegistry.detach("attachment-test-replaced", old)
        assertEquals(before, ImageGenerationJobRegistry.attachedScreenCount())
    }

    @Test fun leavingAndReturningReattaches() {
        val before = ImageGenerationJobRegistry.attachedScreenCount()
        val first = attach("attachment-test-return")
        ImageGenerationJobRegistry.detach("attachment-test-return", first)
        assertEquals(before, ImageGenerationJobRegistry.attachedScreenCount())
        attach("attachment-test-return")
        assertEquals(before + 1, ImageGenerationJobRegistry.attachedScreenCount())
    }

    @Test fun markingDeletedChatsWithNoJobIsHarmless() {
        ImageGenerationJobRegistry.markChatsDeleted(setOf("attachment-test-none"))
        assertEquals(null, ImageGenerationJobRegistry.activeJob("attachment-test-none"))
    }

    @Test fun chatScreenAlwaysDetachesWhenDestroyed() {
        // The leak was a deleted chat's onDestroy skipping detach. Pin that the
        // detach is unconditional inside onDestroy.
        val source = chatActivitySource().readText()
        val start = source.indexOf("public override fun onDestroy()")
        assertTrue("ChatActivity.onDestroy not found", start >= 0)
        val end = source.indexOf("\n    }\n", start)
        val body = source.substring(start, end)
        assertTrue(body.contains("ImageGenerationJobRegistry.detach(chatId, this)"))
        assertFalse(body.contains("if (!deletingChat)"))
    }

    private fun chatActivitySource(): File {
        val relative = "src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt"
        return listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?: error("ChatActivity.kt not found from ${System.getProperty("user.dir")}")
    }
}
