package org.teslasoft.assistant.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatExportWiringContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    @Test
    fun chatHeaderPutsOverflowMenuAfterSettingsAndKeepsItInTheTitleBarrier() {
        val layout = source("src/main/res/layout/activity_chat.xml")

        assertTrue(layout.contains("android:id=\"@+id/btn_chat_menu\""))
        assertTrue(layout.contains("app:srcCompat=\"@drawable/ic_more_vert\""))
        assertTrue(layout.contains("app:layout_constraintEnd_toStartOf=\"@+id/btn_chat_menu\""))
        assertTrue(
            layout.contains(
                "btn_summarizer_errors,btn_summary,btn_debug_log,btn_quick_settings,btn_settings,btn_chat_menu"
            )
        )
    }

    @Test
    fun exportOptionsAreSlideSwitchesAndStartOff() {
        val layout = source("src/main/res/layout/dialog_chat_export.xml")

        assertEquals(5, Regex("materialswitch.MaterialSwitch").findAll(layout).count())
        assertEquals(5, Regex("Widget.App.Row.Toggle").findAll(layout).count())
        assertEquals(5, Regex("Widget.App.Row.Switch").findAll(layout).count())
        assertEquals(5, Regex("android:checked=\"false\"").findAll(layout).count())
        assertFalse(layout.contains("MaterialCheckBox"))
    }

    @Test
    fun menuUsesExactActionsAndDeleteUsesTheChatId() {
        val activity = source(
            "src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt"
        )
        val dialog = source(
            "src/main/java/org/teslasoft/assistant/ui/util/ChatExportDialog.kt"
        )
        val deleteDialog = source(
            "src/main/java/org/teslasoft/assistant/ui/util/ChatDeleteDialog.kt"
        )
        val speakerNames = source(
            "src/main/java/org/teslasoft/assistant/ui/chat/ChatSpeakerNames.kt"
        )
        val strings = source("src/main/res/values/strings.xml")

        assertTrue(activity.contains("PopupMenu(this, anchor)"))
        assertTrue(activity.contains("R.string.chat_menu_export"))
        assertTrue(activity.contains("R.string.btn_delete"))
        assertTrue(activity.contains("ChatExportDialog.show(this)"))
        assertTrue(activity.contains("ChatDeleteDialog.show(this)"))
        assertTrue(activity.contains("deleteChatById(this, chatId)"))
        assertTrue(activity.contains("chatExportFileSaveIntentLauncher"))
        assertTrue(dialog.contains("MaterialSwitch"))
        assertTrue(deleteDialog.contains("R.string.chat_delete_title"))
        assertTrue(speakerNames.contains("const val USER_NAME_KEY = \"userName\""))
        assertTrue(speakerNames.contains("context.getString(R.string.chat_role_user)"))
        assertTrue(strings.contains("<string name=\"chat_menu_export\">Export Chat</string>"))
        assertTrue(strings.contains("<string name=\"chat_export_cancel\">Cancel Export</string>"))
        assertTrue(strings.contains("<string name=\"chat_export_action\">Export</string>"))
        assertTrue(strings.contains("<string name=\"chat_delete_title\">Delete Chat</string>"))
    }
}
