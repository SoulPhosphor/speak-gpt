package org.teslasoft.assistant.ui.activities

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

/** Protects the browser's style boundary and approved header/action ordering. */
class MemoryBrowserUiContractTest {
    private fun file(path: String): File = listOf(File("src/$path"), File("app/src/$path"))
        .first { it.isFile }
    private fun xml(name: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file("main/res/layout/$name.xml"))
    private val android = "http://schemas.android.com/apk/res/android"
    private val app = "http://schemas.android.com/apk/res-auto"
    private fun element(name: String, id: String): Element {
        val nodes = xml(name).getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .first { it.getAttributeNS(android, "id") == "@+id/$id" }
    }
    @Test fun headerOrderPlacesWarningBeforeCompanionsAndLegendBeforeAdd() {
        val layout = "activity_memory_list"
        assertEquals("@+id/btn_action_secondary", element(layout, "btn_embedding_warning")
            .getAttributeNS(app, "layout_constraintEnd_toStartOf"))
        assertEquals("@+id/btn_icon_legend", element(layout, "btn_action_secondary")
            .getAttributeNS(app, "layout_constraintEnd_toStartOf"))
        assertEquals("@+id/btn_action", element(layout, "btn_icon_legend")
            .getAttributeNS(app, "layout_constraintEnd_toStartOf"))
    }
    @Test fun newLayoutsDoNotDefineInlineAppearance() {
        val forbidden = Regex("""android:(?:padding\w*|layout_margin\w*|textSize|textColor|textStyle|tint|background|gravity|elevation|minHeight|scaleType)=|android:layout_(?:width|height)="[1-9][0-9]*(?:dp|sp)""")
        for (name in listOf("view_memory_browser_row", "dialog_memory_icon_legend", "dialog_memory_embedding_warning",
            "view_memory_attachment_strip", "view_memory_selection_controls", "activity_memory_browser_settings")) {
            val source = file("main/res/layout/$name.xml").readText()
            assertFalse("$name must take appearance from styles", forbidden.containsMatchIn(source))
        }
    }
    @Test fun pendingMemoryStripHasDistinctRemoveAndSettingsIcons() {
        assertEquals("@drawable/ic_close", element("view_memory_attachment_strip", "memory_attachment_remove")
            .getAttributeNS(android, "src"))
        assertEquals("@drawable/ic_settings", element("view_memory_attachment_strip", "memory_attachment_settings")
            .getAttributeNS(android, "src"))
    }
    @Test fun embeddingActionsAreInTheApprovedOrderAndUseSharedButtonStyles() {
        val doc = xml("dialog_memory_embedding_warning")
        val flow = doc.getElementsByTagName("androidx.constraintlayout.helper.widget.Flow").item(0) as Element
        assertEquals("btn_embedding_add,btn_embedding_okay", flow.getAttributeNS(app, "constraint_referenced_ids"))
        for (id in listOf("btn_embedding_add", "btn_embedding_okay")) {
            assertEquals("@style/AppButton.Primary.DialogAction", element("dialog_memory_embedding_warning", id).getAttribute("style"))
        }
    }
}
