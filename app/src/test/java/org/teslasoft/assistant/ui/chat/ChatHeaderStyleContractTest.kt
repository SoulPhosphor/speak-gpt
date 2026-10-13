package org.teslasoft.assistant.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat header's colors and sizes live in XML styles so the app can be
 * recolored from one place. The level-4 tonal surface that code used to
 * compute (SurfaceColors.SURFACE_4) is drawn in XML by bg_surface_level4*.
 */
class ChatHeaderStyleContractTest {

    @Test
    fun headerAppearanceComesFromStylesNotCode() {
        val layout = source("src/main/res/layout/activity_chat.xml")
        val header = layout.substringAfter("android:id=\"@+id/action_bar\"")
            .substringBefore("<include layout=\"@layout/view_bookmark_navigation\"")
        assertTrue(layout.contains("style=\"@style/Widget.App.Chat.HeaderBar\""))
        assertTrue(header.contains("style=\"@style/Widget.App.Chat.HeaderTitle\""))
        assertTrue(header.contains("style=\"@style/Widget.App.Chat.HeaderAction.Leading\""))
        listOf(
            "btn_chat_menu", "btn_conversation_summary", "btn_settings", "btn_new_chat",
            "btn_quick_settings", "btn_active_profile", "btn_summarizer_errors"
        ).forEach { id ->
            val button = header.substringAfter("android:id=\"@+id/$id\"").substringBefore("/>")
            assertTrue("$id is not on the shared header style",
                button.contains("style=\"@style/Widget.App.Chat.HeaderAction\""))
        }
        listOf("android:background=", "android:tint=", "android:textColor=", "android:textSize=",
            "android:layout_height=\"48dp\"", "android:layout_height=\"64dp\"").forEach { attribute ->
            assertFalse("Header carries $attribute inline", header.contains(attribute))
        }

        val activity = source("src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")
        assertFalse(activity.contains("getDarkAccentDrawable"))
        assertFalse(activity.contains("SURFACE_4"))
        val themeManager = source("src/main/java/org/teslasoft/assistant/theme/ThemeManager.kt")
        assertFalse(themeManager.contains("SurfaceColors"))
    }

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }
}
