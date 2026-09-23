package org.teslasoft.assistant.preferences.tts

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManualTtsVoicesPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val storage = MemoryTtsStorage()
    private val store = ManualTtsVoicesPreferences(storage)
    private fun assertFailure(reason: TtsStorageFailure, result: Result<*>) {
        assertEquals(reason, (result.exceptionOrNull() as? TtsStorageException)?.reason)
    }

    @Test fun onlyOutsideWhitespaceIsRemovedFromTheEnteredId() {
        val saved = store.add("tts-1", "  Ab/Cd 12:Mixed-Case  ").getOrThrow()
        assertEquals("Ab/Cd 12:Mixed-Case", saved.voiceId)
        assertEquals(listOf("Ab/Cd 12:Mixed-Case"), store.voicesFor("tts-1").getOrThrow())
    }

    @Test fun blankIdsAreRejectedWithoutWriting() {
        for (entered in listOf("", "   ", "\t\n")) assertFailure(TtsStorageFailure.INVALID_SELECTION, store.add("tts-1", entered))
        assertNull(storage.content)
    }

    @Test fun duplicatesWithinOneSourceAreRejectedButOtherSourcesAreIndependent() {
        store.add("tts-1", "voice-a").getOrThrow()
        assertFailure(TtsStorageFailure.DUPLICATE, store.add("tts-1", " voice-a "))
        store.add("tts-2", "voice-a").getOrThrow()
        assertEquals(listOf("voice-a"), store.voicesFor("tts-1").getOrThrow())
        assertEquals(listOf("voice-a"), store.voicesFor("tts-2").getOrThrow())
        assertEquals(emptyList<String>(), store.voicesFor("tts-3").getOrThrow())
    }

    @Test fun removeDeletesOnlyThatSourcesRecord() {
        store.add("tts-1", "voice-a").getOrThrow()
        store.add("tts-2", "voice-a").getOrThrow()
        store.remove("tts-1", "voice-a").getOrThrow()
        assertEquals(emptyList<String>(), store.voicesFor("tts-1").getOrThrow())
        assertEquals(listOf("voice-a"), store.voicesFor("tts-2").getOrThrow())
        assertFailure(TtsStorageFailure.NOT_FOUND, store.remove("tts-1", "voice-a"))
    }

    @Test fun savedVoicesSurviveReloadFromDisk() {
        val file = File(temporary.root, ManualTtsVoicesPreferences.RELATIVE_PATH)
        ManualTtsVoicesPreferences(TtsFileStorage(file)).add("tts-1", "voice-a").getOrThrow()
        ManualTtsVoicesPreferences(TtsFileStorage(file)).add("tts-1", "voice-b").getOrThrow()
        assertEquals(listOf("voice-a", "voice-b"),
            ManualTtsVoicesPreferences(TtsFileStorage(file)).voicesFor("tts-1").getOrThrow())
    }

    @Test fun damagedStorageIsAnExplicitFailureNeverAnEmptyList() {
        storage.content = """{"version":1,"entries":[{"savedSourceId":"tts-1","voiceId":" padded "}]}"""
        assertFailure(TtsStorageFailure.INVALID_DATA, store.load())
        storage.content = """{"version":1,"entries":[]} trailing"""
        assertFailure(TtsStorageFailure.INVALID_DATA, store.voicesFor("tts-1"))
        storage.failRead = true
        assertFailure(TtsStorageFailure.READ_FAILED, store.load())
    }

    @Test fun failedWriteLeavesTheSavedListUnchanged() {
        store.add("tts-1", "voice-a").getOrThrow()
        storage.failWrite = true
        assertFailure(TtsStorageFailure.WRITE_FAILED, store.add("tts-1", "voice-b"))
        storage.failWrite = false
        assertEquals(listOf("voice-a"), store.voicesFor("tts-1").getOrThrow())
    }
}
