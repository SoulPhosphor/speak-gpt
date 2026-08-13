/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.imagegen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Structural guards for the generated-image capability polish. These
 * contracts span both sides of the current message presentation, the adapter,
 * and the process-level job owner, so a later one-file cleanup must not
 * silently split them again. */
class ImageCapabilityPolishContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    private val layouts = listOf(
        "src/main/res/layout/view_assistant_bot_message.xml",
        "src/main/res/layout/view_assistant_user_message.xml"
    )

    @Test
    fun everyMessageLayoutUsesTheProviderNeutralImageContract() {
        for (path in layouts) {
            val xml = source(path)
            assertTrue("$path lacks generated image", xml.contains("@+id/generated_image"))
            assertTrue("$path lacks Prompt", xml.contains("@+id/btn_image_prompt"))
            assertTrue("$path lacks save arrow", xml.contains("@+id/btn_image_download"))
            assertTrue("$path lacks loading state", xml.contains("@+id/generated_image_loading"))
        }
    }

    @Test
    fun generatedImagesAreReadOnlyAndExposePromptAndSaveActions() {
        val adapter = source(
            "src/main/java/org/teslasoft/assistant/ui/adapters/chat/ChatAdapter.kt"
        )
        assertTrue(adapter.contains("btnEdit.visibility = if (isGeneratedImage) View.GONE"))
        assertTrue(adapter.contains("showGeneratedImagePrompt(chatMessage)"))
        assertTrue(adapter.contains("onGeneratedImageSaveClick(url, mimeType)"))
        assertTrue(adapter.contains("ValueAnimator.ofInt(0, 3)"))
    }

    @Test
    fun imageJobOwnsBackgroundKeepAliveAndPublishesBeforeContinuation() {
        val registry = source(
            "src/main/java/org/teslasoft/assistant/imagegen/ImageGenerationJobRegistry.kt"
        )
        assertTrue(registry.contains("ImageGenerationForegroundService.begin"))
        assertTrue(registry.contains("ImageGenerationForegroundService.end"))
        val notify = registry.indexOf("listener.onImageJobFinished(record, terminal)")
        val complete = registry.indexOf("record.terminal.complete(terminal)")
        assertTrue("image publication must precede model continuation", notify in 0 until complete)
    }

    @Test
    fun fullscreenSaveUsesTheDownArrow() {
        val xml = source("src/main/res/layout/activity_imageview.xml")
        assertTrue(xml.contains("android:src=\"@drawable/ic_download\""))
        assertFalse(xml.contains("android:src=\"@drawable/ic_save\""))
    }
}
