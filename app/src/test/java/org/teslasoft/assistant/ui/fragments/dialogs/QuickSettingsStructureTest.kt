package org.teslasoft.assistant.ui.fragments.dialogs

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickSettingsStructureTest {

    @Test
    fun blocksStayInTheApprovedOrder() {
        val xml = resource("layout/fragment_quick_settings.xml")
        val character = xml.indexOf("view_quick_settings_character_block")
        val provider = xml.indexOf("view_quick_settings_provider_block")
        val memory = xml.indexOf("view_quick_settings_memory_block")
        val roleplay = xml.indexOf("view_quick_settings_roleplay_block")
        val modelSettings = xml.indexOf("view_quick_settings_model_settings_block")
        val logitSeed = xml.indexOf("view_quick_settings_logit_seed_block")
        val usage = xml.indexOf("@+id/usage_cost")
        val save = xml.indexOf("@+id/btn_save_to_profile")

        assertTrue(
            listOf(character, provider, memory, roleplay, modelSettings, logitSeed, usage, save)
                .zipWithNext().all { (a, b) -> a >= 0 && a < b }
        )
    }

    @Test
    fun quickSettingsUsesSharedSegmentsAndSwitches() {
        val layouts = listOf(
            "view_quick_settings_character_block.xml",
            "view_quick_settings_provider_block.xml",
            "view_quick_settings_memory_block.xml",
            "view_quick_settings_roleplay_block.xml",
            "view_quick_settings_model_settings_block.xml",
            "view_quick_settings_logit_seed_block.xml"
        ).joinToString("\n") { resource("layout/$it") }

        assertTrue(layouts.contains("Widget.App.QuickSettings.Segment.Top"))
        assertTrue(layouts.contains("Widget.App.QuickSettings.Segment.Middle"))
        assertTrue(layouts.contains("Widget.App.QuickSettings.Segment.Bottom"))
        assertTrue(layouts.contains("materialswitch.MaterialSwitch"))
        assertFalse(layouts.contains("MaterialCheckBox"))
        assertFalse(layouts.contains("android:backgroundTint"))
        assertFalse(layouts.contains("@color/quick_tile_border"))
    }

    @Test
    fun lorebookRowsExpandInsideOneSegmentAndOpenExactBooks() {
        val memoryLayout = resource("layout/view_quick_settings_memory_block.xml")
        val rowLayout = resource("layout/view_quick_settings_lorebook_toggle_row.xml")
        val source = source(
            "src/main/java/org/teslasoft/assistant/ui/fragments/dialogs/" +
                "QuickSettingsBottomSheetDialogFragment.kt"
        )

        assertTrue(memoryLayout.contains("@+id/lorebook_check_list"))
        assertTrue(memoryLayout.contains("@+id/btn_add_lorebook"))
        assertTrue(rowLayout.contains("@+id/row_switch"))
        assertTrue(rowLayout.contains("@+id/row_btn_edit"))
        assertTrue(source.contains("hasAdditionalCompanionBook"))
        assertTrue(source.contains("state.extraBooks.isEmpty()"))
        assertTrue(source.contains("EXTRA_SELECTION_ONLY, true"))
        assertTrue(source.contains("EXTRA_EXCLUDED_IDS"))
        assertTrue(source.contains("LoreBookEntriesActivity::class.java"))
        assertTrue(source.contains("putExtra(\"lorebookId\", book.id)"))
    }

    private fun resource(relative: String): String {
        val candidates = listOf(
            File("src/main/res/$relative"),
            File("app/src/main/res/$relative")
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }
}
