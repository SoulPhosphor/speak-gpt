package org.teslasoft.assistant.imagegen

import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.FakeSharedPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.RecentModelsPreferences

class ImageModelPickerSelectionTest {
    @Test fun switchingImageProfilesKeepsOptionsAndDoesNotChangeChatOrItsRecentList() {
        val chat = FakeSharedPreferences()
        val global = FakeSharedPreferences()
        val preferences = Preferences(chat, global, "test-chat")
        val imageRecents = RecentModelsPreferences(FakeSharedPreferences())
        val textRecents = RecentModelsPreferences(FakeSharedPreferences())
        preferences.setModel("invented-text")
        preferences.setApiEndpointId("text-profile")
        ImageModelPickerSelection.select(preferences, imageRecents, "image-profile-a", "invented-image-a")
        preferences.setImageGeneratorParameters(mapOf("output_compression" to "67"))
        ImageModelPickerSelection.select(preferences, imageRecents, "image-profile-b", "invented-image-b")
        preferences.setImageGeneratorParameters(mapOf("output_compression" to "83"))
        ImageModelPickerSelection.select(preferences, imageRecents, "image-profile-a", "invented-image-a")
        assertEquals("image-profile-a", preferences.getImageGeneratorEndpointId())
        assertEquals("invented-image-a", preferences.getImageGeneratorModel())
        assertEquals(mapOf("output_compression" to "67"), preferences.getImageGeneratorParameters())
        assertEquals("invented-text", preferences.getModel())
        assertEquals("text-profile", preferences.getApiEndpointId())
        assertTrue(textRecents.models().isEmpty())
        assertEquals(listOf("invented-image-a", "invented-image-b"), imageRecents.models().map { it.modelId })
        ImageModelPickerSelection.select(preferences, imageRecents, "image-profile-b", "invented-image-b")
        assertEquals(mapOf("output_compression" to "83"), preferences.getImageGeneratorParameters())
    }
}
