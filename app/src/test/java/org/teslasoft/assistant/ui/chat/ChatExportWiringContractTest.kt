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
                "btn_summarizer_errors,btn_active_profile,btn_quick_settings,btn_settings,btn_conversation_summary,btn_chat_menu"
            )
        )
        assertFalse(layout.contains("btn_debug_log"))
    }

    @Test
    fun thinkingAndTokenOptionsAreSlideSwitchesAndOnlyTokensStartOff() {
        val layout = source("src/main/res/layout/dialog_chat_export.xml")

        assertEquals(3, Regex("materialswitch.MaterialSwitch").findAll(layout).count())
        assertEquals(3, Regex("Widget.App.Row.Toggle").findAll(layout).count())
        assertEquals(3, Regex("Widget.App.Row.Switch").findAll(layout).count())
        assertEquals(2, Regex("android:checked=\"false\"").findAll(layout).count())
        assertTrue(
            layout.indexOf("@+id/chat_export_format_value") < layout.indexOf("@+id/chat_export_include_thinking") &&
                layout.indexOf("@+id/chat_export_include_thinking") < layout.indexOf("@+id/chat_export_include_user_tokens")
        )
        assertTrue(layout.contains("@+id/chat_export_include_user_tokens"))
        assertTrue(layout.contains("@+id/chat_export_include_companion_tokens"))
        assertFalse(layout.contains("chat_export_include_date"))
        assertFalse(layout.contains("chat_export_include_time"))
        assertFalse(layout.contains("chat_export_include_model"))
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

        assertTrue(activity.contains("PopupMenu(this@ChatActivity, anchor)"))
        assertTrue(activity.contains("R.string.chat_menu_unpin else R.string.chat_menu_pin"))
        assertTrue(activity.contains("R.string.chat_menu_export"))
        assertTrue(activity.contains("R.string.usage_cost_title"))
        assertTrue(activity.contains("R.string.alert_debug_section_logs"))
        assertTrue(activity.contains("R.string.btn_delete"))
        assertOrdered(
            activity,
            "R.string.chat_menu_unpin else R.string.chat_menu_pin",
            "menu.add(Menu.NONE, 1, 1, R.string.chat_menu_export)",
            "menu.add(Menu.NONE, 5, 2, R.string.usage_cost_title)",
            "menu.add(Menu.NONE, 2, 3, R.string.alert_debug_section_logs)",
            "menu.add(Menu.NONE, 7, 4, R.string.chat_menu_change_username)",
            "menu.add(Menu.NONE, 3, 5, R.string.btn_delete)"
        )
        assertTrue(activity.contains("Intent(this@ChatActivity, LogCabinActivity::class.java)"))
        assertFalse(activity.contains("updateDebugLogButtonVisibility"))
        assertTrue(activity.contains("ChatExportDialog.show(this)"))
        assertTrue(activity.contains("ChatDeletionRequestCoordinator.requestChats("))
        assertTrue(activity.contains("chatIds = setOf(chatId)"))
        assertFalse(activity.contains("deleteChatById("))
        assertTrue(activity.contains("chatExportFileSaveIntentLauncher"))
        assertTrue(dialog.contains("MaterialSwitch"))
        assertTrue(deleteDialog.contains("R.string.chat_delete_title"))
        assertTrue(speakerNames.contains("const val USER_NAME_KEY = \"userName\""))
        assertTrue(speakerNames.contains("context.getString(R.string.chat_role_user)"))
        assertTrue(strings.contains("<string name=\"chat_menu_export\">Export Chat</string>"))
        assertTrue(strings.contains("<string name=\"chat_export_cancel\">Cancel</string>"))
        assertTrue(strings.contains("<string name=\"chat_export_action\">Export</string>"))
        assertTrue(strings.contains("<string name=\"chat_export_include_thinking\">Include Thinking</string>"))
        assertTrue(strings.contains("<string name=\"chat_menu_change_username\">Change Username</string>"))
        assertTrue(strings.contains("<string name=\"chat_change_username_title\">Change Your Username</string>"))
        assertTrue(strings.contains("<string name=\"chat_delete_title\">Delete Chat</string>"))
    }

    private fun assertOrdered(source: String, vararg markers: String) {
        val positions = markers.map(source::indexOf)
        assertTrue("Missing marker in ${markers.toList()}", positions.all { it >= 0 })
        assertTrue(
            "Markers are out of order: ${markers.toList()}",
            positions.zipWithNext().all { it.first < it.second }
        )
    }

    @Test
    fun usesTheStandardDialogFamilyAndRendersMarkdownInPdf() {
        val exportDialog = source(
            "src/main/java/org/teslasoft/assistant/ui/util/ChatExportDialog.kt"
        )
        val deleteDialog = source(
            "src/main/java/org/teslasoft/assistant/ui/util/ChatDeleteDialog.kt"
        )
        val pdfWriter = source(
            "src/main/java/org/teslasoft/assistant/ui/chat/ChatExportPdfWriter.kt"
        )
        val markdownRenderer = source(
            "src/main/java/org/teslasoft/assistant/ui/chat/ChatMarkdownRenderer.kt"
        )
        val cancelFirstLayout = source(
            "src/main/res/layout/dialog_two_actions_cancel_first.xml"
        )
        val styleGuide = source("ui-style-guide.md")
        val strings = source("src/main/res/values/strings.xml")

        assertTrue(exportDialog.contains("R.layout.dialog_two_actions_cancel_first"))
        assertFalse(exportDialog.contains("dialog_two_actions_end"))
        assertTrue(deleteDialog.contains("R.layout.dialog_two_actions_cancel_first"))
        assertFalse(deleteDialog.contains("dialog_two_actions_end"))
        assertTrue(
            exportDialog.contains(
                "val cancel = actions.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action)"
            )
        )
        assertTrue(
            exportDialog.contains(
                "val export = actions.findViewById<MaterialButton>(R.id.btn_dialog_primary_action)"
            )
        )
        assertTrue(
            deleteDialog.contains(
                "val cancel = actions.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action)"
            )
        )
        assertTrue(
            deleteDialog.contains(
                "val okay = actions.findViewById<MaterialButton>(R.id.btn_dialog_primary_action)"
            )
        )
        assertTrue(
            exportDialog.indexOf("cancel.setText(R.string.chat_export_cancel)") <
                exportDialog.indexOf("export.setText(R.string.chat_export_action)")
        )
        assertTrue(
            deleteDialog.indexOf("cancel.setText(R.string.btn_cancel)") <
            deleteDialog.indexOf("okay.setText(R.string.btn_ok)")
        )
        val destructivePosition = cancelFirstLayout.indexOf(
            "android:id=\"@+id/btn_dialog_destructive_action\""
        )
        val primaryPosition = cancelFirstLayout.indexOf(
            "android:id=\"@+id/btn_dialog_primary_action\""
        )
        assertTrue(destructivePosition >= 0 && destructivePosition < primaryPosition)
        assertTrue(cancelFirstLayout.contains("style=\"@style/AppButton.Destructive.DialogAction\""))
        assertTrue(cancelFirstLayout.contains("style=\"@style/AppButton.Primary.DialogAction\""))
        assertTrue(cancelFirstLayout.contains("app:layout_constraintHorizontal_chainStyle=\"packed\""))
        assertTrue(styleGuide.contains("Two-button dialog actions should be centered as a pair by default."))
        assertTrue(styleGuide.contains("Button order is fixed by role"))
        assertTrue(styleGuide.contains("action button is always on the RIGHT"))
        assertTrue(styleGuide.contains("back-out action is always on the LEFT"))
        assertTrue(styleGuide.contains("Cancel/back-out actions use the\nDestructive"))
        assertTrue(styleGuide.contains("affirmative actions use the Primary"))
        assertTrue(styleGuide.contains("not the general default"))
        assertTrue(styleGuide.contains("`contentDescription` values, tooltip text"))
        assertTrue(pdfWriter.contains("ChatMarkdownRenderer.prepare"))
        assertTrue(pdfWriter.contains("markwon.toMarkdown"))
        assertTrue(pdfWriter.contains("StaticLayout"))
        assertTrue(markdownRenderer.contains("TablePlugin.create(context)"))
        assertTrue(markdownRenderer.contains("JLatexMathPlugin.create"))
        assertTrue(strings.contains("<string name=\"chat_options\">Chat Options</string>"))
    }

    @Test
    fun exportOnlyReportsSaveFailures() {
        val activity = source(
            "src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt"
        )
        val exportWriter = activity
            .substringAfter("private fun writeChatExportToFile(uri: Uri) {")
            .substringBefore("\n    @Suppress(\"DEPRECATION\")")

        assertFalse(exportWriter.contains("Toast.makeText(this, \"Saved\""))
        assertFalse(exportWriter.contains("\"Save failed\""))
        assertTrue(exportWriter.contains("Toast.makeText(this, \"Save Failed\""))
    }
}
