package org.teslasoft.assistant.imagegen

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Guard the existing row conventions while adding secondary support explanations. */
class ImageSettingsLayoutTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private fun layout(name: String): Element {
        val relative = "src/main/res/layout/view_image_setting_$name.xml"
        val file = listOf(File(relative), File("app/$relative")).first { it.exists() }
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file).documentElement
    }
    private fun children(element: Element): List<Element> = (0 until element.childNodes.length)
        .mapNotNull { element.childNodes.item(it) as? Element }

    @Test fun everySettingKeepsTitleAndControlOppositeWithExistingSubtitleStyles() {
        for (name in listOf("dropdown", "number", "text", "boolean", "status")) {
            val row = layout(name)
            assertEquals("@style/Widget.App.ImageGeneration.SettingRow", row.getAttribute("style"))
            val content = children(row)
            val header = content.first()
            assertEquals("horizontal", header.getAttributeNS(android, "orientation"))
            val controls = children(header)
            assertEquals("@+id/image_setting_label", controls.first().getAttributeNS(android, "id"))
            assertEquals("@style/Widget.App.ImageGeneration.Label", controls.first().getAttribute("style"))
            assertEquals(2, controls.size)
            assertEquals(listOf("@+id/image_setting_previous", "@+id/image_setting_subtitle"),
                content.drop(1).map { it.getAttributeNS(android, "id") })
            assertTrue(content.drop(1).all { it.getAttribute("style") == "@style/Widget.App.Row.Subtitle" })
        }
    }

    @Test fun settingsControlsReuseMaterialAndThemeStylesWithoutLocalColorOverrides() {
        val dropdown = children(children(layout("dropdown")).first()).last()
        assertEquals("@style/Widget.App.ImageGeneration.Value", dropdown.getAttribute("style"))
        val checkbox = children(children(layout("boolean")).first()).last()
        assertEquals("com.google.android.material.checkbox.MaterialCheckBox", checkbox.tagName)
        for (name in listOf("dropdown", "number", "text", "boolean", "status")) {
            val root = layout(name)
            val elements = listOf(root) + (0 until root.getElementsByTagName("*").length)
                .map { root.getElementsByTagName("*").item(it) as Element }
            for (element in elements) for (attribute in listOf("textColor", "backgroundTint", "tint"))
                assertFalse("$name must use its existing theme style", element.hasAttributeNS(android, attribute))
        }
        assertEquals("end", children(children(layout("status")).first()).last().getAttributeNS(android, "gravity"))
    }
}
