package org.teslasoft.assistant.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drawables loaded from resources share one state, so tinting one copy from
 * code without mutate() recolors that drawable on every screen. These spots
 * used to do that in normal mode; their colors now live in XML, and only the
 * paused AMOLED mode still tints from code.
 */
class SharedDrawableTintContractTest {

    @Test
    fun modelListsUseTheirOwnRowDrawables() {
        listOf(
            "ui/adapters/ModelListAdapter.kt",
            "ui/adapters/FavoriteModelListAdapter.kt",
            "ui/activities/TtsModelPickerActivity.kt"
        ).forEach { path ->
            val code = source(path)
            assertFalse("$path tints from code", code.contains("setTint"))
            assertTrue(path, code.contains("R.drawable.bg_model_row_selected"))
            assertTrue(path, code.contains("R.drawable.bg_model_row)"))
        }
    }

    @Test
    fun normalModeNoLongerComputesSurfaceTints() {
        listOf(
            "ui/fragments/TileFragment.kt",
            "ui/fragments/tabs/PlaygroundFragment.kt",
            "ui/fragments/tabs/ChatsListFragment.kt",
            "theme/ThemeManager.kt"
        ).forEach { path ->
            assertFalse("$path computes a surface tint", source(path).contains("SurfaceColors"))
        }
        val tiles = source("ui/fragments/TileFragment.kt")
        assertFalse(tiles.contains("R.drawable.tile_active)"))
        assertFalse(tiles.contains("R.drawable.tile_inactive)"))
        assertFalse(source("ui/activities/DocumentationActivity.kt").contains("SURFACE_5"))
    }

    private fun source(relative: String): String {
        val path = "src/main/java/org/teslasoft/assistant/$relative"
        val candidates = listOf(File(path), File("app/$path"), File("../$path"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }
}
