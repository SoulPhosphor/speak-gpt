package org.teslasoft.assistant.preferences.tts

import java.io.IOException
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.teslasoft.assistant.preferences.models.ModelIdentity

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
}
