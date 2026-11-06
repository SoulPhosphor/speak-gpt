package org.teslasoft.assistant.ui.chat

import android.app.Application
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ChatSelectionModeTest {
    private val listModes = mutableListOf<Boolean>()
    private var deleteRequests = 0
    private val mode = ChatSelectionMode(
        RuntimeEnvironment.getApplication(),
        object : ChatSelectionMode.Host {
            override fun setListSelectionMode(active: Boolean) {
                listModes += active
            }

            override fun deleteSelected() {
                deleteRequests++
            }
        }
    )

    private fun message(text: String, isBot: Boolean) =
        hashMapOf<String, Any>("message" to text, "isBot" to isBot)

    private fun selected(): List<Int> = mode.entries.indices.filter { mode.isSelected(it) }

    @Test fun `entering starts with nothing selected and turns the list mode on`() {
        val messages = arrayListOf(message("a", false), message("b", true))
        mode.rebuild(messages)
        mode.enter()
        assertTrue(mode.isActive)
        assertEquals(emptyList<Int>(), selected())
        assertEquals(listOf(true), listModes)
    }

    @Test fun `deselecting the last message keeps Selection Mode on`() {
        mode.rebuild(arrayListOf(message("a", false)))
        mode.enter()
        mode.toggle(0)
        assertEquals(listOf(0), selected())
        mode.toggle(0)
        assertEquals(emptyList<Int>(), selected())
        assertTrue(mode.isActive)
    }

    @Test fun `select all and deselect all stay in Selection Mode`() {
        mode.rebuild(arrayListOf(message("a", false), message("b", true), message("c", false)))
        mode.enter()
        mode.selectAll()
        assertEquals(listOf(0, 1, 2), selected())
        mode.deselectAll()
        assertEquals(emptyList<Int>(), selected())
        assertTrue(mode.isActive)
    }

    @Test fun `exiting clears the selection and turns the list mode off`() {
        mode.rebuild(arrayListOf(message("a", false), message("b", true)))
        mode.enter()
        mode.selectAll()
        mode.exit()
        assertFalse(mode.isActive)
        assertEquals(emptyList<Int>(), selected())
        assertEquals(listOf(true, false), listModes)
    }

    @Test fun `a new message arriving keeps the existing selection`() {
        val first = message("a", false)
        val second = message("b", true)
        val messages = arrayListOf(first, second)
        mode.rebuild(messages)
        mode.enter()
        mode.toggle(1)

        messages.add(message("c", true))
        mode.rebuild(messages)

        assertTrue(mode.isActive)
        assertEquals(listOf(1), selected())
        assertEquals(3, mode.entries.size)
    }

    @Test fun `a removed message drops out and the others keep their places`() {
        val first = message("a", false)
        val second = message("b", true)
        val third = message("c", false)
        val messages = arrayListOf(first, second, third)
        mode.rebuild(messages)
        mode.enter()
        mode.toggle(0)
        mode.toggle(2)

        messages.remove(first)
        mode.rebuild(messages)

        assertEquals(listOf(1), selected())
    }

    @Test fun `outside Selection Mode a rebuild selects nothing`() {
        val messages = arrayListOf(message("a", false))
        mode.rebuild(messages)
        mode.onSelectionChanged(0, true)
        mode.rebuild(messages)
        assertEquals(emptyList<Int>(), selected())
    }

    @Test fun `the list's own click path updates the same selection`() {
        mode.rebuild(arrayListOf(message("a", false), message("b", true)))
        mode.enter()
        mode.onSelectionChanged(1, true)
        assertEquals(listOf(1), selected())
        mode.onSelectionChanged(1, false)
        assertEquals(emptyList<Int>(), selected())
    }
}
