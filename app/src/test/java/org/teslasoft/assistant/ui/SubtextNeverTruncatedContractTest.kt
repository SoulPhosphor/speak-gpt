package org.teslasoft.assistant.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Owner ruling (Oct 12 2026): explanatory subtext always wraps in full. */
class SubtextNeverTruncatedContractTest {

    @Test
    fun sharedSubtitleStyleAndItsChildrenNeverLimitLines() {
        val themes = File(resRoot(), "values/themes.xml").readText()
        val styles = Regex("<style name=\"([^\"]+)\"[^>]*>(.*?)</style>", RegexOption.DOT_MATCHES_ALL)
            .findAll(themes)
            .filter { it.groupValues[1] == "Widget.App.Row.Subtitle" || it.value.contains("parent=\"Widget.App.Row.Subtitle\"") }
            .toList()
        assertTrue(styles.isNotEmpty())
        for (style in styles) {
            assertFalse(style.groupValues[1], style.value.contains("maxLines"))
            assertFalse(style.groupValues[1], style.value.contains("ellipsize"))
        }
    }

    @Test
    fun noLayoutLimitsSubtitleLines() {
        val layouts = File(resRoot(), "layout").listFiles { f -> f.extension == "xml" }.orEmpty()
        for (file in layouts) {
            Regex("<TextView\\b.*?/?>", RegexOption.DOT_MATCHES_ALL).findAll(file.readText())
                .map { it.value }
                .filter { it.contains("Widget.App.Row.Subtitle\"") }
                .forEach { tag ->
                    assertFalse(file.name, tag.contains("maxLines") || tag.contains("ellipsize"))
                }
        }
    }

    private fun resRoot(): File = listOf(
        File("src/main/res"),
        File("app/src/main/res")
    ).firstOrNull(File::isDirectory) ?: error("res root unavailable")
}
