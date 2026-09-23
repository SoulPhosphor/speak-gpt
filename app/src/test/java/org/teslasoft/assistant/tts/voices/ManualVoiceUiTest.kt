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
        assertEquals("Voice Already Saved", values["voice_browser_already_saved_title"])
        assertEquals("This Voice ID is already saved for this model.", values["voice_browser_already_saved_message"])
        assertEquals("Voice ID Could Not Be Saved", values["voice_browser_save_failed_title"])
        assertEquals("The Voice ID could not be saved. Your saved voices have not changed.", values["voice_browser_save_failed_message"])
        assertEquals("Saved Voice Could Not Be Removed", values["voice_browser_remove_failed_title"])
        assertEquals("The saved voice could not be removed. It is still saved.", values["voice_browser_remove_failed_message"])
        assertEquals("Saved Voices Could Not Be Read", values["voice_browser_read_failed_title"])
        assertEquals("The saved Voice IDs for this model could not be read. They have not been replaced or cleared.\\n\\n" +
            "The provider\\'s discovered voices may still be shown if they can be loaded normally.", values["voice_browser_read_failed_message"])
        assertEquals("Storage Error", values["storage_error_heading"])
    }

    @Test fun addKeepsTheTypedIdOnFailureAndIgnoresBlankInput() {
        val activity = file("java/org/teslasoft/assistant/ui/activities/VoiceBrowserActivity.kt").readText()
        assertTrue(activity.contains("addButton.isEnabled = false"))
        assertTrue(activity.contains("manualVoiceId.doAfterTextChanged { addButton.isEnabled = !it.isNullOrBlank() }"))
        val add = activity.substringAfter("private fun addManualVoice()").substringBefore("private fun isActiveVoice")
        val failure = add.substringAfter(".onFailure")
        assertFalse(failure.contains("manualVoiceId.text = null"))
        assertTrue(failure.contains("ManualVoiceDialogs.showAlreadySaved"))
        assertTrue(failure.contains("StorageAction.SAVE, error"))
        val remove = activity.substringAfter("private fun removeManualVoice(").substringBefore("private fun showState")
        assertTrue(remove.contains("StorageAction.REMOVE, error"))
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
