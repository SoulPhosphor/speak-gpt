package org.teslasoft.assistant.tts.voices

import android.app.Application
import android.view.View
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.ui.adapters.VoiceListAdapter
import org.w3c.dom.Element

/**
 * App resources are not loaded in unit tests, so layout and dialog wiring is checked
 * against the source files, as the other Voice Browser and picker tests do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ManualVoiceUiTest {
    private fun file(relative: String): File = listOf(File("src/main/$relative"), File("app/src/main/$relative"))
        .first { it.isFile }
    private fun elements(relative: String): List<Element> =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file(relative))
            .getElementsByTagName("*").let { list -> (0 until list.length).map { list.item(it) as Element } }
    private fun strings(): Map<String, String> = elements("res/values/strings.xml")
        .filter { it.tagName == "string" }.associate { it.getAttribute("name") to it.textContent }

    @Test fun onlyManuallySavedRowsShowTheRemoveControl() {
        val discovered = BrowserVoice("api-tts:tts-1", "alloy", "Alloy", requiresNetwork = true, canPreview = true)
        assertEquals(View.GONE, VoiceListAdapter.removeVisibility(discovered))
        assertEquals(View.GONE, VoiceListAdapter.removeVisibility(discovered.copy(providerId = "google")))
        assertEquals(View.VISIBLE, VoiceListAdapter.removeVisibility(discovered.copy(manuallySaved = true)))
    }

    @Test fun removeControlSitsBeforePreviewWithoutMovingIt() {
        val nodes = elements("res/layout/view_voice.xml").associateBy { it.getAttribute("android:id") }
        val remove = nodes.getValue("@+id/voice_remove")
        val action = nodes.getValue("@+id/voice_action")
        assertEquals("gone", remove.getAttribute("android:visibility"))
        assertEquals("@id/voice_action", remove.getAttribute("app:layout_constraintEnd_toStartOf"))
        assertEquals("parent", action.getAttribute("app:layout_constraintEnd_toEndOf"))
        assertEquals("@id/voice_remove", nodes.getValue("@+id/voice_text").getAttribute("app:layout_constraintEnd_toStartOf"))
    }

    @Test fun voiceIdEntrySitsBelowTheHintAndAboveVoices() {
        val nodes = elements("res/layout/activity_voice_browser.xml")
        val hint = nodes.indexOfFirst { it.getAttribute("android:text") == "@string/voice_browser_long_press_hint" }
        val entry = nodes.indexOfFirst { it.getAttribute("android:id") == "@+id/manual_voice_entry" }
        val voices = nodes.indexOfFirst { it.getAttribute("android:text") == "@string/voice_browser_voices" }
        assertTrue(hint in 0 until entry && entry < voices)
        assertEquals("gone", nodes[entry].getAttribute("android:visibility"))
        assertTrue(nodes.any { it.getAttribute("android:text") == "@string/voice_browser_voice_id" })
    }

    @Test fun approvedRemovalWording() {
        val values = strings()
        assertEquals("Voice ID", values["voice_browser_voice_id"])
        assertEquals("Remove Saved Voice", values["voice_browser_remove_title"])
        assertEquals("Remove this saved voice from the app?\\n\\nThis does not delete the voice from the provider.",
            values["voice_browser_remove_message"])
        assertEquals("Remove", values["voice_browser_remove"])
        assertEquals("Voice Is Currently Selected", values["voice_browser_remove_selected_title"])
        assertEquals("Select another voice before removing this saved voice.", values["voice_browser_remove_selected_message"])
        val exact = mapOf(
            "voice_browser_voice_id_required" to "Enter a Voice ID.",
            "voice_browser_already_saved_title" to "Voice ID Already Saved",
            "voice_browser_already_saved_message" to "This Voice ID is already saved for this text-to-speech source.",
            "voice_storage_technical_details" to "Technical Details",
            "voice_storage_no_space_title" to "Not Enough Storage Space",
            "voice_storage_no_space_message" to "There is not enough available storage space to save this change.\\n\\nFree some space on your device and try again.",
            "voice_storage_access_title" to "Storage Could Not Be Accessed",
            "voice_storage_access_message" to "The app could not access the storage needed for your saved voices.\\n\\nYour existing saved voices have not been changed.",
            "voice_storage_damaged_title" to "Saved Voice Data Is Damaged",
            "voice_storage_damaged_message" to "The saved Voice ID data could not be read because it is damaged or invalid.\\n\\nThe app has not replaced, cleared, or repaired the saved data.\\n\\nProvider voices may still be available if the provider can load them normally.",
            "voice_storage_read_failed_title" to "Saved Voices Could Not Be Read",
            "voice_storage_read_failed_message" to "The app could not read your saved Voice IDs.\\n\\nThe saved data has not been replaced or cleared.\\n\\nProvider voices may still be available if the provider can load them normally.",
            "voice_storage_save_failed_title" to "Voice ID Could Not Be Saved",
            "voice_storage_save_failed_message" to "The Voice ID could not be saved.\\n\\nYour previously saved voices have not changed.",
            "voice_storage_save_unverified_message" to "The Voice ID could not be saved because the app could not verify that the new data was written correctly.\\n\\nYour previously saved voices have not changed.",
            "voice_storage_remove_failed_title" to "Saved Voice Could Not Be Removed",
            "voice_storage_remove_failed_message" to "The saved voice could not be removed.\\n\\nIt is still saved.",
            "voice_storage_remove_unverified_message" to "The saved voice could not be removed because the app could not verify that the updated data was written correctly.\\n\\nIt is still saved.",
            "voice_storage_not_found_title" to "Saved Voice Was Not Found",
            "voice_storage_not_found_message" to "This saved voice is no longer in the saved Voice ID list.\\n\\nThe voice list will be refreshed."
        )
        exact.forEach { (name, text) -> assertEquals(name, text, values[name]) }
    }

    @Test fun addKeepsTheTypedIdOnFailureAndIgnoresBlankInput() {
        val activity = file("java/org/teslasoft/assistant/ui/activities/VoiceBrowserActivity.kt").readText()
        val add = activity.substringAfter("private fun addManualVoice()").substringBefore("private fun isActiveVoice")
        val blank = add.substringAfter("if (entered.isBlank()) {").substringBefore("}")
        assertTrue(blank.contains("manualVoiceId.error = getString(R.string.voice_browser_voice_id_required)"))
        assertTrue(blank.contains("return"))
        assertTrue(add.indexOf("if (entered.isBlank())") < add.indexOf("manualVoices.add("))
        val failure = add.substringAfter(".onFailure")
        assertFalse(failure.contains("manualVoiceId.text = null"))
        assertTrue(failure.contains("ManualVoiceDialogs.showAlreadySaved"))
        assertTrue(failure.contains("Operation.SAVE, error"))
        val remove = activity.substringAfter("private fun removeManualVoice(").substringBefore("private fun showState")
        assertTrue(remove.contains("Operation.REMOVE, error)"))
        assertTrue(remove.contains("Operation.REMOVE, error, onDismiss = refresh)"))
    }

    @Test fun onlyTheConfirmedRemoveButtonRemoves() {
        val source = file("java/org/teslasoft/assistant/tts/voices/ManualVoiceDialogs.kt").readText()
        val selected = source.substringAfter("if (selected) {").substringBefore("} else {")
        val confirm = source.substringAfter("} else {")
        assertFalse(selected.contains("onRemove()"))
        assertTrue(selected.contains("R.string.btn_ok"))
        val cancel = confirm.substringAfter("R.string.btn_cancel").substringBefore("R.string.voice_browser_remove")
        assertFalse(cancel.contains("onRemove()"))
        assertTrue(confirm.substringAfter("R.string.voice_browser_remove)").contains("onRemove()"))
        // The Voice Browser asks for confirmation instead of removing directly.
        val activity = file("java/org/teslasoft/assistant/ui/activities/VoiceBrowserActivity.kt").readText()
        assertTrue(activity.contains("onRemove = ::confirmRemoveManualVoice"))
        assertTrue(activity.contains("ManualVoiceDialogs.showRemoval(this, selected = isActiveVoice(voice)) { removeManualVoice(voice) }"))
    }
}
