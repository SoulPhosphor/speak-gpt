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

    @Test fun independentStorageDoesNotShareHistory() {
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
    private val prefs = Preferences(FakeSharedPreferences(), raw, "test", null)
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
        assertEquals(api, Preferences(FakeSharedPreferences(), raw, "test", null).getSelectedTtsVoice())
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
    @Test fun selectedDefaultAndRecoveryRemainGlobalAcrossChatSwitchRenameAndDeletion() = runBlocking {
        val chatA = FakeSharedPreferences()
        val chatB = FakeSharedPreferences()
        chatA.edit().putString("voice", "stale-chat-a")
            .putString("selected_tts_voice", TtsVoiceSelectionCodec.encode(device)).commit()
        chatB.edit().putString("voice", "stale-chat-b").commit()
        val first = Preferences(chatA, raw, "first", null)
        val second = Preferences(chatB, raw, "second", null)
        service().activate(api).getOrThrow()
        assertEquals(api, first.getSelectedTtsVoice())
        assertEquals(api, second.getSelectedTtsVoice())
        assertEquals("", first.ttsPreferenceScope())
        assertEquals("", second.ttsPreferenceScope())
        chatA.edit().clear().commit()
        chatB.edit().clear().commit()
        val reopened = Preferences(FakeSharedPreferences(), raw, "renamed", null)
        assertEquals(api, reopened.getSelectedTtsVoice())
        assertEquals(device, history.load().getOrThrow())
        reopened.markTtsVoicePermanentlyUnavailable(api)
        assertTrue(second.isTtsVoicePermanentlyUnavailable(api))
        sources = emptyList()
        val recovery = TtsSelectionService(second, resolver, history) { _, _ -> true }
        assertEquals(device, recovery.reconcile(TtsRequestGate().begin()).getOrThrow())
        assertEquals(device, first.getSelectedTtsVoice())
        assertEquals(device, reopened.getSelectedTtsVoice())
        assertTrue(chatA.all.isEmpty())
        assertTrue(chatB.all.isEmpty())
    }

    @Test fun lastKnownGoodIsGlobalAndSeparateFromTheDefault() = runBlocking {
        val good = org.teslasoft.assistant.tts.voices.LastKnownGoodVoiceSelection("google", "working-voice")
        val registry = org.teslasoft.assistant.tts.voices.LastKnownGoodVoiceRegistry(raw)
        registry.save(good)
        service().activate(api).getOrThrow()
        assertEquals(good, org.teslasoft.assistant.tts.voices.LastKnownGoodVoiceRegistry(raw).load())
        assertEquals(api, prefs.getSelectedTtsVoice())
    }

    @Test fun historyFactoryUsesOneGlobalFileAcrossReopening() {
        val directory = java.nio.file.Files.createTempDirectory("global-voice-history").toFile()
        try {
            val file = PreviousTtsVoicePreferences.storageFile(directory)
            val first = PreviousTtsVoicePreferences(TtsFileStorage(file))
            first.recordActivation(device, api).getOrThrow()
            val reopened = PreviousTtsVoicePreferences(TtsFileStorage(
                PreviousTtsVoicePreferences.storageFile(directory)))
            assertEquals(device, reopened.load().getOrThrow())
            assertEquals(1, file.parentFile.listFiles()!!.size)
        } finally { directory.deleteRecursively() }
    }
    private val cleanupBytes = MemoryTtsStorage()
    private val cleanupStore = SavedTtsSourcesPreferences(cleanupBytes)
    private var usabilityChecks = 0
    private fun cleanupService(usable: Boolean = true) = TtsSelectionService(prefs,
        TtsSourceResolver(cleanupStore::load, { profiles }), history) { selected, _ ->
            usabilityChecks++
            usable && (selected.kind == TtsVoiceKind.DEVICE ||
                cleanupStore.load().getOrThrow().any { it.sourceId == selected.sourceId })
        }
    private val cleanupTarget = setOf(org.teslasoft.assistant.preferences.models.ModelIdentity("endpoint", "vendor/model:free"))
    private fun savedVoice(source: SavedTtsSource) = api.copy(sourceId = source.sourceId, modelId = source.modelId)

    @Test fun cleanupRemovesAllMatchingRoutesAndRecoversGlobalSelectionExactlyOnce() = runBlocking {
        val first = cleanupStore.add("endpoint", api.modelId!!, TtsRoutingSettings()).getOrThrow()
        cleanupStore.add("endpoint", api.modelId!!, TtsRoutingSettings(TtsRoutingMode.ONLY, "route")).getOrThrow()
        val other = cleanupStore.add("other-endpoint", api.modelId!!, TtsRoutingSettings()).getOrThrow()
        val chatData = FakeSharedPreferences().apply { edit().putString("unrelated", "keep").commit() }
        val anotherChat = Preferences(chatData, raw, "another-chat", null)
        val good = org.teslasoft.assistant.tts.voices.LastKnownGoodVoiceSelection("google", "known-good")
        val registry = org.teslasoft.assistant.tts.voices.LastKnownGoodVoiceRegistry(raw)
        registry.save(good)
        cleanupService().activate(device).getOrThrow()
        cleanupService().activate(savedVoice(first)).getOrThrow()
        val changes = mutableListOf<Set<String>>()
        val unsubscribe = SavedTtsSourcesPreferences.observeChanges { changes += it }
        try {
            val result = cleanupService().removeUnavailableSources(cleanupStore, cleanupTarget, TtsRequestGate().begin()).getOrThrow()
            assertEquals(2, result.removed)
            assertNull(result.recoveryFailure)
            assertEquals(1, usabilityChecks)
            assertEquals(device, prefs.getSelectedTtsVoice())
            assertEquals(device, anotherChat.getSelectedTtsVoice())
            assertEquals(device, history.load().getOrThrow())
            assertEquals(good, registry.load())
            assertEquals("keep", chatData.getString("unrelated", null))
            assertEquals(listOf(other), cleanupStore.load().getOrThrow())
            assertEquals(2, changes.single().size)
        } finally { unsubscribe() }
    }

    @Test fun cleanupInactiveSourceDoesNotTouchSelectionHistoryOrValidateFallback() = runBlocking {
        cleanupStore.add("endpoint", api.modelId!!, TtsRoutingSettings()).getOrThrow()
        val kept = cleanupStore.add("endpoint", "another-model", TtsRoutingSettings()).getOrThrow()
        cleanupService().activate(savedVoice(kept)).getOrThrow()
        val selectionBefore = raw.all
        val historyBefore = storage.content
        val result = cleanupService().removeUnavailableSources(cleanupStore, cleanupTarget, TtsRequestGate().begin()).getOrThrow()
        assertEquals(1, result.removed)
        assertNull(result.recoveryFailure)
        assertEquals(0, usabilityChecks)
        assertEquals(selectionBefore, raw.all)
        assertEquals(historyBefore, storage.content)
    }

    @Test fun cleanupRecoveryFailureDoesNotMisreportCommittedDeletionAsFailed() = runBlocking {
        val entry = cleanupStore.add("endpoint", api.modelId!!, TtsRoutingSettings()).getOrThrow()
        cleanupService().activate(savedVoice(entry)).getOrThrow()
        val result = cleanupService(false).removeUnavailableSources(cleanupStore, cleanupTarget, TtsRequestGate().begin()).getOrThrow()
        assertEquals(1, result.removed)
        assertTrue(cleanupStore.load().getOrThrow().isEmpty())
        assertEquals(TtsMessage("Selected Voice Is Permanently Unavailable", "Please select a new voice.",
            listOf("Okay", "Select New Voice")), TtsFailures.message(result.recoveryFailure!!))
        assertEquals(savedVoice(entry), prefs.getSelectedTtsVoice())
    }

    @Test fun cleanupDoesNotResurrectPreviousSourceRemovedInSameBatch() = runBlocking {
        val a = cleanupStore.add("endpoint", api.modelId!!, TtsRoutingSettings()).getOrThrow()
        val b = cleanupStore.add("endpoint", api.modelId!!, TtsRoutingSettings(TtsRoutingMode.ONLY, "route")).getOrThrow()
        cleanupService().activate(savedVoice(a)).getOrThrow()
        cleanupService().activate(savedVoice(b)).getOrThrow()
        val result = cleanupService().removeUnavailableSources(cleanupStore, cleanupTarget, TtsRequestGate().begin()).getOrThrow()
        assertEquals(2, result.removed)
        assertEquals(TtsFailureKind.PERMANENT_UNAVAILABLE, result.recoveryFailure?.kind)
        assertTrue(cleanupStore.load().getOrThrow().isEmpty())
    }

    @Test fun failedCleanupWriteLeavesSourcesSelectionAndRecoveryRecordsUnchanged() = runBlocking {
        val entry = cleanupStore.add("endpoint", api.modelId!!, TtsRoutingSettings()).getOrThrow()
        cleanupService().activate(savedVoice(entry)).getOrThrow()
        val before = cleanupBytes.content
        val selectedBefore = raw.all
        val historyBefore = storage.content
        cleanupBytes.failWrite = true
        val result = cleanupService().removeUnavailableSources(cleanupStore, cleanupTarget, TtsRequestGate().begin())
        assertEquals(TtsFailureKind.REMOVE_FAILED, (result.exceptionOrNull() as TtsException).failure.kind)
        assertEquals(before, cleanupBytes.content)
        assertEquals(selectedBefore, raw.all)
        assertEquals(historyBefore, storage.content)
        assertEquals(0, usabilityChecks)
    }

    @Test fun canceledOrUnreadableCleanupDoesNotMutateOrReportSuccess() = runBlocking {
        cleanupStore.add("endpoint", api.modelId!!, TtsRoutingSettings()).getOrThrow()
        val before = cleanupBytes.content
        val token = TtsRequestGate().begin().also { it.cancel() }
        try {
            cleanupService().removeUnavailableSources(cleanupStore, cleanupTarget, token)
            fail("Cancellation expected")
        } catch (_: java.util.concurrent.CancellationException) { }
        assertEquals(before, cleanupBytes.content)
        cleanupBytes.failRead = true
        val result = cleanupService().removeUnavailableSources(cleanupStore, cleanupTarget, TtsRequestGate().begin())
        assertEquals(TtsFailureKind.STORAGE, (result.exceptionOrNull() as TtsException).failure.kind)
        assertEquals(before, cleanupBytes.content)
        assertEquals(0, usabilityChecks)
    }

    @Test fun failedRestorationWriteStillReportsTheRemovedVoiceAsPermanentlyUnavailable() = runBlocking {
        var rejectWrites = false
        val backing = FakeSharedPreferences()
        val global = object : android.content.SharedPreferences by backing {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = backing.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean = if (rejectWrites) false else editor.commit()
                }
            }
        }
        val preferences = Preferences(FakeSharedPreferences(), global, "", null)
        val selection = TtsSelectionService(preferences, TtsSourceResolver(cleanupStore::load, { profiles }), history) { _, _ -> true }
        val entry = cleanupStore.add("endpoint", api.modelId!!, TtsRoutingSettings()).getOrThrow()
        selection.activate(device).getOrThrow()
        selection.activate(savedVoice(entry)).getOrThrow()
        rejectWrites = true
        val result = selection.removeUnavailableSources(cleanupStore, cleanupTarget, TtsRequestGate().begin()).getOrThrow()
        assertEquals(1, result.removed)
        assertTrue(cleanupStore.load().getOrThrow().isEmpty())
        assertEquals(TtsFailureKind.PERMANENT_UNAVAILABLE, result.recoveryFailure?.kind)
        assertEquals(savedVoice(entry), preferences.getSelectedTtsVoice())
        assertEquals(listOf("Okay", "Select New Voice"), TtsFailures.message(result.recoveryFailure!!).actions)
    }

}
