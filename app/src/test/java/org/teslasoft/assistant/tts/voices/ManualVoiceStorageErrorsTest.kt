package org.teslasoft.assistant.tts.voices

import java.io.FileNotFoundException
import java.io.IOException
import org.json.JSONException
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.tts.TtsStorageException
import org.teslasoft.assistant.preferences.tts.TtsStorageFailure
import org.teslasoft.assistant.preferences.tts.TtsWriteVerificationException
import org.teslasoft.assistant.tts.voices.ManualVoiceStorageErrors.Kind
import org.teslasoft.assistant.tts.voices.ManualVoiceStorageErrors.Operation

class ManualVoiceStorageErrorsTest {
    private fun storage(reason: TtsStorageFailure, cause: Exception? = null) = TtsStorageException(reason, cause)
    private val full = IOException("write failed: ENOSPC (No space left on device)")

    @Test fun aFullDeviceIsReportedAsNoSpaceForEveryWrite() {
        for (operation in listOf(Operation.SAVE, Operation.REMOVE))
            assertEquals(Kind.NO_SPACE, ManualVoiceStorageErrors.classify(operation, storage(TtsStorageFailure.WRITE_FAILED, full)))
    }

    @Test fun accessFailuresAreNotConfusedWithDamagedData() {
        val denied = FileNotFoundException("/data/user/0/app/files/tts/manual_voices.json: open failed: EACCES (Permission denied)")
        assertEquals(Kind.ACCESS, ManualVoiceStorageErrors.classify(Operation.READ, storage(TtsStorageFailure.READ_FAILED, denied)))
        assertEquals(Kind.ACCESS, ManualVoiceStorageErrors.classify(Operation.SAVE,
            storage(TtsStorageFailure.WRITE_FAILED, IOException("Cannot create storage directory"))))
        assertEquals(Kind.DAMAGED, ManualVoiceStorageErrors.classify(Operation.READ,
            storage(TtsStorageFailure.INVALID_DATA, JSONException("Unterminated object at character 12"))))
    }

    @Test fun unknownFailuresFallBackToTheOperation() {
        val odd = IOException("I/O error")
        assertEquals(Kind.READ_FAILED, ManualVoiceStorageErrors.classify(Operation.READ, storage(TtsStorageFailure.READ_FAILED, odd)))
        // A save that failed while reading the existing list reports the read.
        assertEquals(Kind.READ_FAILED, ManualVoiceStorageErrors.classify(Operation.SAVE, storage(TtsStorageFailure.READ_FAILED, odd)))
        assertEquals(Kind.SAVE_FAILED, ManualVoiceStorageErrors.classify(Operation.SAVE, storage(TtsStorageFailure.WRITE_FAILED, odd)))
        assertEquals(Kind.REMOVE_FAILED, ManualVoiceStorageErrors.classify(Operation.REMOVE, storage(TtsStorageFailure.WRITE_FAILED, odd)))
    }

    @Test fun unverifiedWritesAreNeverReportedAsSaved() {
        val unverified = storage(TtsStorageFailure.WRITE_FAILED, TtsWriteVerificationException())
        assertEquals(Kind.SAVE_UNVERIFIED, ManualVoiceStorageErrors.classify(Operation.SAVE, unverified))
        assertEquals(Kind.REMOVE_UNVERIFIED, ManualVoiceStorageErrors.classify(Operation.REMOVE, unverified))
    }

    @Test fun missingRecordIsItsOwnCase() {
        assertEquals(Kind.NOT_FOUND, ManualVoiceStorageErrors.classify(Operation.REMOVE, storage(TtsStorageFailure.NOT_FOUND)))
    }

    @Test fun technicalDetailsShowWhatTheDeviceReported() {
        assertEquals("IOException: write failed: ENOSPC (No space left on device)",
            ManualVoiceStorageErrors.technicalDetails(storage(TtsStorageFailure.WRITE_FAILED, full)))
        // With no underlying cause the storage category itself is the only diagnostic.
        assertEquals("TtsStorageException: NOT_FOUND",
            ManualVoiceStorageErrors.technicalDetails(storage(TtsStorageFailure.NOT_FOUND)))
    }
}
