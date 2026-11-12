package org.teslasoft.assistant.usage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUsageAlertPolicyTest {
    private fun alert(
        amount: String = "1.25",
        total: Double = 1.25,
        previous: String = "",
        enabled: Boolean = true,
        changedAt: Long = 10L,
        usedAt: Long = 11L
    ) = ChatUsageAlertPolicy.shouldAlert(enabled, amount, total, previous, changedAt, usedAt)

    @Test fun `off or blank or invalid amounts do not alert`() {
        assertFalse(alert(enabled = false))
        listOf("", ".", "-1", "garbage").forEach { assertFalse(alert(amount = it)) }
        assertFalse(alert(total = Double.NaN))
        assertFalse(alert(total = Double.POSITIVE_INFINITY))
    }

    @Test fun `combined total triggers at or above amount including decimal amounts`() {
        assertFalse(alert(total = 1.249))
        assertTrue(alert(total = 1.25))
        assertTrue(alert(total = 2.0))
        assertTrue(alert(total = 0.50 + 0.75))
        assertTrue(alert(amount = "1,25"))
        assertFalse(alert(amount = "1.2501", total = 1.25))
        assertTrue(alert(amount = "0", total = 0.0))
    }

    @Test fun `saved warning cannot repeat at same or lower threshold`() {
        assertFalse(alert(previous = "1.25", total = 100.0))
        assertFalse(alert(previous = "1.2500", total = 100.0))
        assertFalse(alert(amount = "0.50", previous = "1.25", total = 100.0))
        // Turning the switch off and back on does not clear the conversation's marker.
        assertFalse(alert(previous = "1.25", enabled = false))
        assertFalse(alert(previous = "1.25", enabled = true))
    }

    @Test fun `higher threshold requires subsequent usage and reaching the new amount`() {
        assertFalse(alert(amount = "2", previous = "1.25", total = 3.0, usedAt = 9L))
        assertFalse(alert(amount = "2", previous = "1.25", total = 3.0, usedAt = 10L))
        assertFalse(alert(amount = "2", previous = "1.25", total = 1.99))
        assertTrue(alert(amount = "2", previous = "1.25", total = 2.0))
        assertFalse(alert(amount = "2", previous = "2", total = 3.0))
    }

    @Test fun `each conversation uses its own saved warning`() {
        assertFalse(alert(previous = "1.25"))
        assertTrue(alert(previous = ""))
    }
}
