package org.teslasoft.assistant.preferences.tts

import java.io.IOException
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.teslasoft.assistant.preferences.models.ModelIdentity
import org.teslasoft.assistant.tts.api.*

internal class MemoryTtsStorage(var content: String? = null) : TtsStorage {
    var failRead = false
    var failWrite = false
    var writes = 0
    override fun read(): String? {
        if (failRead) throw IOException("read failure")
        return content
    }
    override fun write(content: String) {
        if (failWrite) throw IOException("write failure")
        this.content = content
        writes++
    }
}

class SavedTtsSourcesPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val storage = MemoryTtsStorage()
    private val store = SavedTtsSourcesPreferences(storage)
    private fun only(provider: String) = TtsRoutingSettings(TtsRoutingMode.ONLY, selectedProvider = provider)
    private fun assertFailure(reason: TtsStorageFailure, result: Result<*>) {
        assertEquals(reason, (result.exceptionOrNull() as? TtsStorageException)?.reason)
    }

    @Test fun absentCollectionIsEmptyAndDoesNotCreateAnything() {
        assertEquals(emptyList<SavedTtsSource>(), store.load().getOrThrow())
        assertNull(storage.content)
    }

    @Test fun sameModelWithDifferentProvidersKeepsIndependentIdsAndOrderAcrossReopen() {
        val first = store.add("endpoint", "vendor/model:free", only("provider/a")).getOrThrow()
        val second = store.add("endpoint", "vendor/model:free", only("provider/b")).getOrThrow()
        assertNotEquals(first.id, second.id)
        assertNotEquals(first.sourceId, second.sourceId)
        assertEquals(listOf(first, second), SavedTtsSourcesPreferences(storage).load().getOrThrow())
    }

    @Test fun routingEditPreservesSourceIdEndpointModelAndPosition() {
        val first = store.add("ep", "model", only("a")).getOrThrow()
        val second = store.add("ep", "model", only("b")).getOrThrow()
        val route = TtsRoutingSettings(TtsRoutingMode.PREFERRED, providerOrder = listOf("c", "d"), allowFallbacks = false)
        val changed = store.replaceRouting(first.id, route).getOrThrow()
        assertEquals(first.copy(routing = route), changed)
        assertEquals(first.sourceId, changed.sourceId)
        assertEquals(listOf(changed, second), store.load().getOrThrow())
    }

    @Test fun duplicatesRejectAddAndEditWithoutChangingBytes() {
        val first = store.add("ep", "model", only("a")).getOrThrow()
        val second = store.add("ep", "model", only("b")).getOrThrow()
        val before = storage.content
        assertFailure(TtsStorageFailure.DUPLICATE, store.add("ep", "model", only("a")))
        assertFailure(TtsStorageFailure.DUPLICATE, store.replaceRouting(second.id, first.routing))
        // The same provider in Preferred is not a new combination solely because its mode changed.
        assertFailure(TtsStorageFailure.DUPLICATE, store.add("ep", "model",
            TtsRoutingSettings(TtsRoutingMode.PREFERRED, providerOrder = listOf("a"))))
        // A mode-only edit must not bypass duplicate detection through inactive routing fields.
        assertFailure(TtsStorageFailure.DUPLICATE, store.add("ep", "model",
            first.routing.copy(mode = TtsRoutingMode.AUTOMATIC)))
        assertFailure(TtsStorageFailure.DUPLICATE, store.replaceRouting(second.id,
            first.routing.copy(mode = TtsRoutingMode.PREFERRED)))
        assertEquals(before, storage.content)
    }

    @Test fun preferredPriorityAndFallbackAreStoredButDoNotMakeDuplicateCombinationsDistinct() {
        store.add("ep", "model", TtsRoutingSettings(TtsRoutingMode.PREFERRED,
            providerOrder = listOf("b", "a"), allowFallbacks = false)).getOrThrow()
        val loaded = store.load().getOrThrow().single().routing
        assertEquals(listOf("b", "a"), loaded.providerOrder)
        assertFalse(loaded.allowFallbacks)
        assertFailure(TtsStorageFailure.DUPLICATE, store.add("ep", "model",
            loaded.copy(providerOrder = listOf("a", "b"), allowFallbacks = true)))
    }

    @Test fun automaticAndIncompleteOnlyAreNotConfused() {
        assertFailure(TtsStorageFailure.INVALID_SELECTION, store.add("ep", "model", only("")))
        assertNull(storage.content)
        store.add("ep", "model", TtsRoutingSettings()).getOrThrow()
        assertEquals(TtsRoutingMode.AUTOMATIC, store.load().getOrThrow().single().routing.mode)
        assertFailure(TtsStorageFailure.DUPLICATE, store.add("ep", "model", TtsRoutingSettings(TtsRoutingMode.PREFERRED)))
    }

    @Test fun removeTargetsIsExactAndRemovesEveryMatchingCombinationOnly() {
        store.add("ep1", "vendor/model:free", only("a")).getOrThrow()
        store.add("ep1", "vendor/model:free", only("b")).getOrThrow()
        val otherEndpoint = store.add("ep2", "vendor/model:free", only("a")).getOrThrow()
        val otherModel = store.add("ep1", "vendor/model", only("a")).getOrThrow()
        assertEquals(2, store.removeTargets(setOf(ModelIdentity("ep1", "vendor/model:free"))).getOrThrow())
        assertEquals(listOf(otherEndpoint, otherModel), store.load().getOrThrow())
        assertEquals(1, store.removeEntryIds(setOf(otherEndpoint.id, "missing")).getOrThrow())
        assertEquals(listOf(otherModel), store.load().getOrThrow())
    }

    @Test fun failedMutationsLeaveCollectionIntactAndNeverReportSuccess() {
        val first = store.add("ep", "model", only("a")).getOrThrow()
        val before = storage.content
        storage.failWrite = true
        assertFailure(TtsStorageFailure.WRITE_FAILED, store.add("ep", "model", only("b")))
        assertFailure(TtsStorageFailure.WRITE_FAILED, store.replaceRouting(first.id, only("c")))
        assertFailure(TtsStorageFailure.WRITE_FAILED, store.removeEntryIds(setOf(first.id)))
        assertFailure(TtsStorageFailure.WRITE_FAILED, store.removeTargets(setOf(ModelIdentity("ep", "model"))))
        assertEquals(before, storage.content)
        assertEquals(listOf(first), SavedTtsSourcesPreferences(storage).load().getOrThrow())
    }

    @Test fun malformedOrFutureCollectionsNeverBecomeEmptyWritableCollections() {
        for (bytes in listOf("", "[", "null", "{}", "{\"version\":2,\"entries\":[]}",
                "{\"version\":1,\"entries\":[{}]}")) {
            storage.content = bytes
            assertFailure(TtsStorageFailure.INVALID_DATA, store.load())
            assertFailure(TtsStorageFailure.INVALID_DATA, store.add("ep", "model", only("a")))
            assertFailure(TtsStorageFailure.INVALID_DATA, store.replaceRouting("missing", only("a")))
            assertFailure(TtsStorageFailure.INVALID_DATA, store.removeEntryIds(setOf("missing")))
            assertEquals(bytes, storage.content)
        }
        assertEquals(0, storage.writes)
    }

    @Test fun trailingDamageAfterAValidCollectionBlocksMutation() {
        val entry = store.add("ep", "model", only("a")).getOrThrow()
        storage.content += " damaged suffix"
        val before = storage.content
        assertFailure(TtsStorageFailure.INVALID_DATA, store.removeEntryIds(setOf(entry.id)))
        assertEquals(before, storage.content)
    }

    @Test fun multipleInstancesReloadBeforeEveryMutation() {
        val other = SavedTtsSourcesPreferences(storage)
        val first = store.add("ep", "model", only("a")).getOrThrow()
        val second = other.add("ep", "model", only("b")).getOrThrow()
        val changed = store.replaceRouting(first.id, only("c")).getOrThrow()
        assertEquals(listOf(changed, second), other.load().getOrThrow())
    }

    @Test fun invalidEntryTypesAndRepeatedIdsProtectTheWholeCollection() {
        store.add("ep", "model", only("a")).getOrThrow()
        val root = JSONObject(storage.content!!)
        root.getJSONArray("entries").put(root.getJSONArray("entries").getJSONObject(0))
        storage.content = root.toString()
        assertFailure(TtsStorageFailure.INVALID_DATA, store.load())
        root.getJSONArray("entries").getJSONObject(0).put("modelId", 123)
        storage.content = root.toString()
        assertFailure(TtsStorageFailure.INVALID_DATA, store.load())
    }

    @Test fun readFailureIsNotAnEmptyCollectionAndMissingEditDoesNotAppend() {
        storage.failRead = true
        assertFailure(TtsStorageFailure.READ_FAILED, store.load())
        assertFailure(TtsStorageFailure.READ_FAILED, store.add("ep", "model", only("a")))
        storage.failRead = false
        assertFailure(TtsStorageFailure.NOT_FOUND, store.replaceRouting("absent", only("a")))
        assertEquals(0, storage.writes)
    }

    @Test fun fileStorageRoundTripsAndPreservesUnreadableContent() {
        val file = File(temporary.root, "tts/saved_sources.json")
        val fileStore = SavedTtsSourcesPreferences(TtsFileStorage(file))
        val entry = fileStore.add("ep", "model", only("a")).getOrThrow()
        assertEquals(listOf(entry), SavedTtsSourcesPreferences(TtsFileStorage(file)).load().getOrThrow())
        file.writeText("broken bytes")
        assertFailure(TtsStorageFailure.INVALID_DATA, fileStore.removeEntryIds(setOf(entry.id)))
        assertEquals("broken bytes", file.readText())
    }

    @Test fun fileWriteFailureDoesNotDeleteTheExistingTarget() {
        val target = temporary.newFolder("existing")
        val sentinel = File(target, "keep").apply { writeText("old data") }
        assertTrue(runCatching { TtsFileStorage(target).write("new data") }.isFailure)
        assertEquals("old data", sentinel.readText())
    }

    @Test fun callerOwnedRoutingListCannotMutateSavedData() {
        val order = mutableListOf("a", "b")
        val source = store.add("ep", "model", TtsRoutingSettings(TtsRoutingMode.PREFERRED, providerOrder = order)).getOrThrow()
        order.clear()
        assertEquals(listOf("a", "b"), source.routing.providerOrder)
        assertEquals(source, store.load().getOrThrow().single())
    }

    private fun managerDraft(manager: TtsManagerState, endpoint: String = "ep", model: String = "vendor/model:exact",
        routing: TtsRoutingSettings = only("Provider/A")) {
        manager.endpoint(endpoint)
        val request = manager.openModel()
        manager.acceptModel(request.target.copy(modelId = model))
        manager.openProvider()
        manager.acceptProvider(manager.draft.copy(routing = routing))
    }

    @Test fun managerStartsNeutralAndSuccessfulAddResetsEveryUpperFieldOnce() {
        val manager = TtsManagerState(store)
        assertEquals(TtsTarget(""), manager.draft)
        assertEquals("Select", TtsManagerProviderDisplay.label(manager.draft.routing, "Select"))
        managerDraft(manager)
        val captured = manager.draft
        manager.add(captured) { assertEquals("ep", it.endpointId) }
        assertEquals(TtsTarget(""), manager.draft)
        assertEquals(1, manager.rows.size)
        assertEquals("vendor/model:exact", manager.rows.single().modelId)
        assertEquals(captured.routing, manager.rows.single().routing)
        manager.add(captured) { fail("A stale add must not run") }
        assertEquals(1, storage.writes)
    }

    @Test fun managerFailedAddPreservesDraftAndRowsAndRetryUsesSameRoute() {
        val manager = TtsManagerState(store)
        managerDraft(manager)
        val captured = manager.draft
        storage.failWrite = true
        val error = assertThrows(TtsException::class.java) { manager.add(captured) {} }
        assertEquals(TtsFailureKind.SAVE_FAILED, error.failure.kind)
        assertEquals(listOf("Cancel", "Retry"), TtsFailures.message(error.failure).actions)
        assertEquals(captured, manager.draft)
        assertTrue(manager.rows.isEmpty())
        storage.failWrite = false
        manager.add(captured) {}
        assertEquals(captured.routing, manager.rows.single().routing)
    }

    @Test fun managerDuplicateAddAndEditUseExactSingleOkayMessageAndPreserveData() {
        val first = store.add("ep", "vendor/model:exact", only("Provider/A")).getOrThrow()
        val second = store.add("ep", "vendor/model:exact", only("Provider/B")).getOrThrow()
        val manager = TtsManagerState(store)
        manager.refresh()
        managerDraft(manager, routing = TtsRoutingSettings(TtsRoutingMode.PREFERRED, providerOrder = listOf("Provider/A")))
        val draft = manager.draft
        val bytes = storage.content
        val add = assertThrows(TtsException::class.java) { manager.add(draft) {} }
        val edit = assertThrows(TtsException::class.java) { manager.edit(second.target().copy(routing = first.routing)) {} }
        for (error in listOf(add, edit)) {
            assertEquals(TtsMessage("Combination Already Exists", "endpoint model and provider combination already exists.",
                listOf("Okay")), TtsFailures.message(error.failure))
        }
        assertEquals(bytes, storage.content)
        assertEquals(draft, manager.draft)
        assertEquals(listOf(first, second), manager.rows)
    }

    @Test fun managerProviderSaveUpdatesInlineModeAndReopeningKeepsRouting() {
        val manager = TtsManagerState(store)
        managerDraft(manager)
        val first = manager.openProvider()
        assertEquals("Provider/A", TtsManagerProviderDisplay.label(first.target.routing, "Select"))
        val preferred = TtsRoutingSettings(TtsRoutingMode.PREFERRED, providerOrder = listOf("Provider/B", "Provider/A"), allowFallbacks = false)
        assertNull(manager.acceptProvider(first.target.copy(routing = preferred)))
        assertEquals(preferred, manager.draft.routing)
        assertEquals("Provider/B", TtsManagerProviderDisplay.label(preferred, "Select"))
        assertEquals(preferred, manager.openProvider().target.routing)
        manager.acceptProvider(null)
        assertEquals(preferred, manager.draft.routing)
        assertEquals(0, storage.writes)
    }

    @Test fun managerEndpointAndModelChangesInvalidateDependentRoutesAndLateResults() {
        val manager = TtsManagerState(store)
        managerDraft(manager)
        val stale = manager.openProvider()
        manager.endpoint("another-endpoint")
        manager.acceptProvider(stale.target)
        assertEquals(TtsTarget("another-endpoint"), manager.draft)
        managerDraft(manager)
        val model = manager.openModel()
        manager.acceptModel(model.target.copy(modelId = "other-model"))
        assertEquals(TtsRoutingSettings(), manager.draft.routing)
        assertEquals("other-model", manager.draft.modelId)
    }

    @Test fun managerOnlyIsNeverDowngradedAndBlankPreferredRequiresFallbacks() {
        val manager = TtsManagerState(store)
        managerDraft(manager, routing = TtsRoutingSettings(TtsRoutingMode.ONLY))
        assertEquals(TtsFailureKind.PROVIDER_REQUIRED,
            assertThrows(TtsException::class.java) { manager.add(manager.draft) {} }.failure.kind)
        assertEquals(TtsRoutingMode.ONLY, manager.draft.routing.mode)
        managerDraft(manager, routing = TtsRoutingSettings(TtsRoutingMode.PREFERRED, allowFallbacks = false))
        assertThrows(TtsException::class.java) { manager.add(manager.draft) {} }
        managerDraft(manager, routing = TtsRoutingSettings(TtsRoutingMode.PREFERRED))
        manager.add(manager.draft) {}
        assertEquals(TtsRoutingSettings(), manager.rows.single().routing)
        val row = manager.rows.single()
        manager.edit(row.target().copy(routing = only("chosen"))) {}
        manager.edit(row.target().copy(routing = TtsRoutingSettings(TtsRoutingMode.PREFERRED))) {}
        assertEquals(TtsRoutingSettings(), manager.rows.single().routing)
    }

    @Test fun savedRowEditAndRemovalKeepUpperDraftAndOtherEndpointRowsUntouched() {
        val first = store.add("ep", "model", only("a")).getOrThrow()
        val other = store.add("other", "model", only("a")).getOrThrow()
        val manager = TtsManagerState(store)
        manager.refresh()
        managerDraft(manager, endpoint = "draft-only")
        val draft = manager.draft
        manager.openProvider(first)
        val result = manager.acceptProvider(first.target().copy(routing = only("b")))!!
        manager.edit(result) {}
        assertEquals(first.copy(routing = only("b")), manager.rows.first())
        assertEquals(draft, manager.draft)
        manager.remove(manager.rows.first())
        assertEquals(listOf(other), manager.rows)
        assertEquals(draft, manager.draft)
    }

    @Test fun restoredSavedRowResultDoesNotNeedAlreadyLoadedRowsAndCannotResurrectDeletion() {
        val first = store.add("ep", "model", only("a")).getOrThrow()
        val original = TtsManagerState(store)
        managerDraft(original)
        original.openProvider(first)
        val restored = TtsManagerState(store)
        restored.restore(TtsPickerCodec.decode(TtsPickerCodec.encode(original.draft)), null,
            TtsPickerCodec.decode(TtsPickerCodec.encode(original.providerRequest!!.target)), null)
        val edit = restored.acceptProvider(first.target().copy(routing = only("b")))!!
        store.removeEntryIds(setOf(first.id)).getOrThrow()
        assertEquals(TtsFailureKind.SAVED_SOURCE_MISSING,
            assertThrows(TtsException::class.java) { restored.edit(edit) {} }.failure.kind)
        assertTrue(store.load().getOrThrow().isEmpty())
        assertEquals(original.draft, restored.draft)
    }

    @Test fun managerRejectsWrongPickerTargetsAndCancelNeverWrites() {
        val manager = TtsManagerState(store)
        managerDraft(manager)
        val before = manager.draft
        manager.openProvider()
        assertNull(manager.acceptProvider(before.copy(endpointId = "wrong")))
        manager.openModel()
        manager.acceptModel(before.copy(endpointId = "wrong", modelId = "wrong"))
        manager.openProvider(); manager.acceptProvider(null)
        assertEquals(before, manager.draft)
        assertEquals(0, storage.writes)
    }

    @Test fun postCommitRefreshFailureRetriesOnlyReadAcrossRecreation() {
        var bytes: String? = null
        var writes = 0
        var failReads = false
        val delayed = SavedTtsSourcesPreferences(object : TtsStorage {
            override fun read(): String? { if (failReads) throw IOException("Read failed"); return bytes }
            override fun write(content: String) { bytes = content; writes++; failReads = true }
        })
        val manager = TtsManagerState(delayed)
        managerDraft(manager)
        assertEquals(TtsFailureKind.STORAGE,
            assertThrows(TtsException::class.java) { manager.add(manager.draft) {} }.failure.kind)
        assertNotNull(manager.committedAdd)
        val restored = TtsManagerState(delayed)
        restored.restore(manager.draft, null, null, manager.committedAdd)
        failReads = false
        restored.add(restored.draft) { fail("Committed Add must not repeat") }
        assertEquals(1, writes)
        assertEquals(1, restored.rows.size)
        assertEquals(TtsTarget(""), restored.draft)
    }

    @Test fun failedManagerDeleteKeepsExactRowAndDraftWithRetryMessage() {
        val source = store.add("ep", "model", only("a")).getOrThrow()
        val manager = TtsManagerState(store)
        manager.refresh(); managerDraft(manager)
        val before = manager.draft
        storage.failWrite = true
        val error = assertThrows(TtsException::class.java) { manager.remove(source) }
        assertEquals(TtsFailureKind.REMOVE_FAILED, error.failure.kind)
        assertEquals(listOf("Cancel", "Retry"), TtsFailures.message(error.failure).actions)
        assertEquals(listOf(source), manager.rows)
        assertEquals(before, manager.draft)
    }
}
