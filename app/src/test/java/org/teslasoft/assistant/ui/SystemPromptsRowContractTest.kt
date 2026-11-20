package org.teslasoft.assistant.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** System Prompts is the last row of Characters, title only: it must never
 *  show the name of a system prompt (owner instruction). */
class SystemPromptsRowContractTest {

    @Test fun systemPromptsIsTheLastTitleOnlyRowOfCharacters() {
        val layout = layout("activity_characters.xml")
        val row = layout.substringAfter("android:id=\"@+id/row_system_prompts\"")
        assertTrue(row.contains("@style/Widget.App.Row.TitleOnly"))
        assertFalse(row.contains("Row.Subtitle"))
        assertFalse(row.contains("<LinearLayout\n                android:id"))
    }

    @Test fun aiSettingsNoLongerHasTheRow() {
        assertFalse(layout("activity_ai_system_settings.xml").contains("row_system_prompt"))
    }

    private fun layout(name: String): String = listOf(
        "src/main/res/layout/$name", "app/src/main/res/layout/$name"
    ).map(::File).first(File::isFile).readText()
}
