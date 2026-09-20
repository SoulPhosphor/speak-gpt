package org.teslasoft.assistant.preferences.generatedimages

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedImageGalleryAdapterContractTest {
    @Test fun bindResetsEveryRecycledVisualAndUsesBoundedLoads() {
        val source = source("src/main/java/org/teslasoft/assistant/ui/adapters/generatedimages/GeneratedImageGalleryAdapter.kt")
        val text = source.readText()
        assertTrue(text.contains("fun reset()"))
        assertTrue(text.contains("Glide.with(itemView).clear(image)"))
        assertTrue(text.contains("labelBlock.visibility = View.GONE"))
        assertTrue(text.contains("badge.visibility = View.GONE"))
        assertTrue(text.contains("itemView.setOnLongClickListener(null)"))
        assertTrue(text.contains("override(512, 512)"))
    }

    private fun source(relative: String): File = listOf(
        File(relative), File("app/$relative"), File(System.getProperty("user.dir"), relative),
        File(System.getProperty("user.dir"), "app/$relative")
    ).firstOrNull { it.isFile } ?: error("Could not locate $relative")
}
