package org.teslasoft.assistant.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject

class EndpointProfileDropdownTest {

    @Test
    fun choicesPreserveEveryProfileAndItsStableId() {
        val endpoints = listOf(
            ApiEndpointObject(
                label = "OpenRouter",
                host = "https://openrouter.example/v1/",
                apiKey = "first",
                id = "openrouter-primary"
            ),
            ApiEndpointObject(
                label = "OpenRouter",
                host = "https://openrouter.example/v1/",
                apiKey = "second",
                id = "openrouter-secondary"
            )
        )

        assertEquals(
            listOf(
                EndpointProfileDropdown.Choice("openrouter-primary", "OpenRouter"),
                EndpointProfileDropdown.Choice("openrouter-secondary", "OpenRouter")
            ),
            EndpointProfileDropdown.choices(endpoints)
        )
    }
}
