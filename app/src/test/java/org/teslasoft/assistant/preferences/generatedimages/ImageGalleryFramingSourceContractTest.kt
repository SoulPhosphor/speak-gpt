package org.teslasoft.assistant.preferences.generatedimages

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGalleryFramingSourceContractTest {
    @Test fun generatedSourceIsCopiedBeforeEditingAndSavedAsProfileDerivative() {
        val framing = source("src/main/java/org/teslasoft/assistant/ui/activities/ProfileImageFramingActivity.kt").readText()
        val gallery = source("src/main/java/org/teslasoft/assistant/ui/activities/ImageGalleryActivity.kt").readText()
        assertTrue(framing.contains("resolveCatalogImage"))
        assertTrue(framing.contains("copyAndDecodeFile"))
        assertTrue(framing.contains("File(sessionDir, \"source\")"))
        assertFalse(framing.contains("source.delete("))
        assertTrue(gallery.contains("ProfileImageStore.getInstance"))
        assertTrue(gallery.contains(".save(it)"))
    }

    private fun source(relative: String): File = listOf(
        File(relative), File("app/$relative"), File(System.getProperty("user.dir"), relative),
        File(System.getProperty("user.dir"), "app/$relative")
    ).firstOrNull { it.isFile } ?: error("Could not locate $relative")
}
