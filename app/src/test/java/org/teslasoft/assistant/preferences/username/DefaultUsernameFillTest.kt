package org.teslasoft.assistant.preferences.username

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultUsernameFillTest {

    private fun chat(): List<MutableMap<String, Any>> = listOf(
        hashMapOf("message" to "nameless", "isBot" to false),
        hashMapOf("message" to "named", "isBot" to false, "userName" to "Robin"),
        hashMapOf("message" to "blank name", "isBot" to false, "userName" to "  "),
        hashMapOf("message" to "reply", "isBot" to true),
        hashMapOf("message" to "string flag reply", "isBot" to "true")
    )

    @Test
    fun fillsOnlyUserMessagesThatHaveNoName() {
        val messages = chat()
        assertTrue(DefaultUsernameFill.fillNameless(messages, " Mary "))
        assertEquals("Mary", messages[0]["userName"])
        assertEquals("Robin", messages[1]["userName"])
        assertEquals("Mary", messages[2]["userName"])
        assertFalse(messages[3].containsKey("userName"))
        assertFalse(messages[4].containsKey("userName"))
        assertFalse(messages[0].containsKey("userNameSource"))
    }

    @Test
    fun alreadyFilledMessagesKeepTheirNameWhenTheDefaultChanges() {
        val messages = chat()
        DefaultUsernameFill.fillNameless(messages, "Mary")
        assertFalse(DefaultUsernameFill.fillNameless(messages, "Wren"))
        assertEquals("Mary", messages[0]["userName"])
    }

    @Test
    fun blankNameChangesNothing() {
        val messages = chat()
        assertFalse(DefaultUsernameFill.fillNameless(messages, "   "))
        assertFalse(messages[0].containsKey("userName"))
    }
}
