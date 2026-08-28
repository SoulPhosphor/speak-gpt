package org.teslasoft.assistant.preferences.tts

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.FakeSharedPreferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.tts.api.*

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

    private val raw = FakeSharedPreferences()
    private val prefs = Preferences(raw, FakeSharedPreferences(), "test", null)
    private val profile = ApiEndpointObject("Speech", "https://speech.example/v1/", "key", id = "endpoint")
    private var sources = listOf(SavedTtsSource("tts-a", "endpoint", "vendor/model:free", TtsRoutingSettings()))
    private var profiles = listOf(profile)
    private var loadFailure = false
    private val resolver = TtsSourceResolver({ if (loadFailure) Result.failure(Exception("unreadable")) else Result.success(sources) }, { profiles })
    private fun service(usable: Boolean = true) = TtsSelectionService(prefs, resolver, history) { selection, _ ->
        usable && (selection.kind == TtsVoiceKind.DEVICE || sources.any { it.sourceId == selection.sourceId })
    }

    @Test fun completeApiSelectionWinsOverStaleEngineFlagAndUnrelatedChatEndpoint() = runBlocking {
        service().activate(api).getOrThrow()
        raw.edit().putString("tts_engine", "google").putString("api_endpoint_id", "chat-only").commit()
        assertEquals("openai", prefs.getTtsEngine())
        assertEquals(api, prefs.getSelectedTtsVoice())
        assertEquals("endpoint", resolver.saved(api.sourceId, api.voiceId).getOrThrow().target.endpointId)
        assertEquals(api, Preferences(raw, FakeSharedPreferences(), "test", null).getSelectedTtsVoice())
    }

    @Test fun existingDeviceSelectionAndDeviceRecoveryKeepCompatibilityValuesInSync() = runBlocking {
        prefs.setVoice(device.voiceId)
        service().activate(api).getOrThrow()
        sources = emptyList()
        assertEquals(device, service().reconcile(TtsRequestGate().begin()).getOrThrow())
        assertEquals("google", prefs.getTtsEngine())
        assertEquals(device.voiceId, prefs.getVoice())
        // Recovery must not turn the removed API source into the predecessor.
        assertEquals(device, history.load().getOrThrow())
    }

    @Test fun previousApiVoiceRestoresItsExactSourceNotSameVoiceIdElsewhere() = runBlocking {
        val other = api.copy(sourceId = "api-tts:tts-b")
        sources += sources.single().copy(id = "tts-b", routing = TtsRoutingSettings(TtsRoutingMode.ONLY, "other"))
        service().activate(api).getOrThrow()
        service().activate(other).getOrThrow()
        sources = sources.filterNot { it.sourceId == other.sourceId }
        assertEquals(api, service().reconcile(TtsRequestGate().begin()).getOrThrow())
        assertEquals(api, prefs.getSelectedTtsVoice())
    }

    @Test fun removingBothSourcesDoesNotInventOrRecreateAReplacement() = runBlocking {
        val other = api.copy(sourceId = "api-tts:tts-b")
        sources += sources.single().copy(id = "tts-b")
        service().activate(api).getOrThrow()
        service().activate(other).getOrThrow()
        sources = emptyList()
        val failure = (service().reconcile(TtsRequestGate().begin()).exceptionOrNull() as TtsException).failure
        assertEquals(TtsFailureKind.PERMANENT_UNAVAILABLE, failure.kind)
        assertEquals(TtsMessage("Selected Voice Is Permanently Unavailable", "Please select a new voice.",
            listOf("Okay", "Select New Voice")), TtsFailures.message(failure))
        assertEquals(other, prefs.getSelectedTtsVoice())
        assertTrue(sources.isEmpty())
    }

    @Test fun missingProfileTriggersRecoveryButUnreadableSourceStoreDoesNot() = runBlocking {
        prefs.setVoice(device.voiceId)
        service().activate(api).getOrThrow()
        loadFailure = true
        assertEquals(TtsFailureKind.STORAGE,
            (service().reconcile(TtsRequestGate().begin()).exceptionOrNull() as TtsException).failure.kind)
        assertEquals(api, prefs.getSelectedTtsVoice())
        loadFailure = false
        profiles = emptyList()
        assertEquals(device, service().reconcile(TtsRequestGate().begin()).getOrThrow())
    }

    @Test fun everyTransientOrInconclusiveFailureLeavesCurrentAndHistoryAlone() = runBlocking {
        service().activate(api).getOrThrow()
        val before = history.load().getOrThrow()
        for (kind in listOf(TtsFailureKind.EMPTY, TtsFailureKind.DISCOVERY_UNAVAILABLE,
            TtsFailureKind.MALFORMED, TtsFailureKind.NOT_FOUND, TtsFailureKind.VOICE_UNSUPPORTED,
            TtsFailureKind.MODEL_UNAVAILABLE, TtsFailureKind.PROVIDER_UNAVAILABLE, TtsFailureKind.CONNECTION)) {
            val failure = TtsFailure(TtsOperation.VOICES, resolver.saved(api.sourceId, api.voiceId).getOrThrow().target,
                "Speech", kind)
            assertEquals(api, service().reconcile(TtsRequestGate().begin(), failure).getOrThrow())
            assertEquals(before, history.load().getOrThrow())
        }
    }

    @Test fun confirmedDeletionIsRememberedButOnlyForTheExactCurrentVoice() = runBlocking {
        service().activate(api).getOrThrow()
        val failure = TtsFailure(TtsOperation.SPEECH, resolver.saved(api.sourceId, api.voiceId).getOrThrow().target,
            "Speech", TtsFailureKind.VOICE_DELETED)
        assertEquals(api, service().reconcile(TtsRequestGate().begin(), failure.copy(target = failure.target.copy(voiceId = "other"))).getOrThrow())
        val unavailable = service(usable = false).reconcile(TtsRequestGate().begin(), failure)
        assertTrue(unavailable.isFailure)
        assertTrue(prefs.isTtsVoicePermanentlyUnavailable(api))
        assertTrue(service(usable = false).reconcile(TtsRequestGate().begin()).isFailure)
        assertEquals(api, prefs.getSelectedTtsVoice())
    }

    @Test fun failedHistoryWriteCannotActivateAndFailedCurrentWriteRestoresHistory() = runBlocking {
        service().activate(api).getOrThrow()
        val before = history.load().getOrThrow()
        storage.failWrite = true
        assertTrue(service().activate(device).isFailure)
        assertEquals(api, prefs.getSelectedTtsVoice())
        storage.failWrite = false
        assertTrue(history.activate(api, device) { false }.isFailure)
        assertEquals(before, history.load().getOrThrow())
        assertEquals(api, prefs.getSelectedTtsVoice())
    }

    @Test fun canceledRecoveryCannotRestoreASelection() = runBlocking {
        service().activate(api).getOrThrow()
        sources = emptyList()
        val token = TtsRequestGate().begin().also { it.cancel() }
        try { service().reconcile(token); fail("Cancellation expected") }
        catch (_: java.util.concurrent.CancellationException) { }
        assertEquals(api, prefs.getSelectedTtsVoice())
    }

    @Test fun legacyApiFlagCannotInventASourceAndDevicePreferenceSurvives() = runBlocking {
        raw.edit().putString("voice", "old-google-id").commit()
        assertEquals("old-google-id", prefs.getSelectedTtsVoice()?.voiceId)
        raw.edit().putString("tts_engine", "openai").putString("openai_voice", "old-api-voice").commit()
        assertEquals(TtsFailureKind.SOURCE_MISSING,
            (service().reconcile(TtsRequestGate().begin()).exceptionOrNull() as TtsException).failure.kind)
        assertEquals("old-api-voice", prefs.getOpenAIVoice())
        assertEquals("old-google-id", prefs.getVoice())
    }
}
