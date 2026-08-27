package org.teslasoft.assistant.preferences.tts

import org.junit.Assert.*
import org.junit.Test

class PreviousTtsVoicePreferencesTest {
    private val storage = MemoryTtsStorage()
    private val history = PreviousTtsVoicePreferences(storage)
    private val device = TtsVoiceSelection(TtsVoiceKind.DEVICE, "google", "en-us-x-iom-network")
    private val api = TtsVoiceSelection(TtsVoiceKind.API, "api-tts:tts-a", "alloy", "vendor/model:free")

    @Test fun oldPreferencesHaveNoInventedPreviousVoice() {
        assertNull(history.load().getOrThrow())
        assertNull(storage.content)
    }

    @Test fun previousSelectionSurvivesReopeningWithoutReplacingCurrent() {
        history.recordActivation(device, api).getOrThrow()
        assertEquals(device, PreviousTtsVoicePreferences(storage).load().getOrThrow())
        history.recordActivation(api, device).getOrThrow()
        assertEquals(api, PreviousTtsVoicePreferences(storage).load().getOrThrow())
    }

    @Test fun sameVoiceIdOnDifferentSavedSourcesIsADifferentSelection() {
        history.recordActivation(api, api.copy(sourceId = "api-tts:tts-b")).getOrThrow()
        assertEquals(api, history.load().getOrThrow())
    }

    @Test fun reselectingCurrentVoiceDoesNotOverwriteItsPredecessor() {
        history.recordActivation(device, api).getOrThrow()
        val before = storage.content
        history.recordActivation(api, api).getOrThrow()
        assertEquals(before, storage.content)
        assertEquals(1, storage.writes)
    }

    @Test fun onlyImmediatelyPreviousSelectionIsStoredAndMissingCurrentClearsHistory() {
        history.recordActivation(device, api).getOrThrow()
        history.recordActivation(api, device).getOrThrow()
        assertEquals(api, history.load().getOrThrow())
        history.recordActivation(null, api).getOrThrow()
        assertNull(history.load().getOrThrow())
    }

    @Test fun separateScopesDoNotShareHistory() {
        val other = PreviousTtsVoicePreferences(MemoryTtsStorage())
        history.recordActivation(device, api).getOrThrow()
        assertNull(other.load().getOrThrow())
    }

    @Test fun failedWritePreservesHistory() {
        history.recordActivation(device, api).getOrThrow()
        val before = storage.content
        storage.failWrite = true
        val result = history.recordActivation(api, device)
        assertEquals(TtsStorageFailure.WRITE_FAILED, (result.exceptionOrNull() as TtsStorageException).reason)
        assertEquals(before, storage.content)
        assertEquals(device, history.load().getOrThrow())
    }

    @Test fun malformedHistoryCannotBeSilentlyOverwritten() {
        for (bytes in listOf("broken", "", "{\"version\":1}", "{\"version\":2,\"previous\":null}")) {
            storage.content = bytes
            val result = history.recordActivation(device, api)
            assertEquals(TtsStorageFailure.INVALID_DATA, (result.exceptionOrNull() as TtsStorageException).reason)
            assertEquals(bytes, storage.content)
        }
    }

    @Test fun invalidApiIdentityIsNotPersisted() {
        val result = history.recordActivation(device, api.copy(sourceId = "google"))
        assertEquals(TtsStorageFailure.INVALID_SELECTION, (result.exceptionOrNull() as TtsStorageException).reason)
        assertNull(storage.content)
    }
}
