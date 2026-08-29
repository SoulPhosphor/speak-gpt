package org.teslasoft.assistant.preferences.tts

import android.content.Context
import java.io.File
import org.json.JSONObject
import org.teslasoft.assistant.util.Hash

/** API sourceId is SavedTtsSource.sourceId; device sourceId is its existing provider ID. */
enum class TtsVoiceKind { DEVICE, API }

data class TtsVoiceSelection(
    val kind: TtsVoiceKind,
    val sourceId: String,
    val voiceId: String,
    val modelId: String? = null
) {
    internal fun validate() {
        require(sourceId.isNotBlank() && voiceId.isNotBlank())
        require(modelId == null || modelId.isNotBlank())
        require(kind != TtsVoiceKind.API ||
            (sourceId.startsWith("api-tts:") && sourceId.removePrefix("api-tts:").isNotBlank()))
    }
}

/**
 * Immediately previous app-wide selection. Kept separate from the selected default
 * and LastKnownGoodVoiceRegistry; browsing and previewing never change this history.
 */
class PreviousTtsVoicePreferences internal constructor(private val storage: TtsStorage) {
    companion object {
        fun getPreferences(context: Context): PreviousTtsVoicePreferences =
            PreviousTtsVoicePreferences(TtsFileStorage(storageFile(context.applicationContext.filesDir)))

        // Retain the existing global/default history file; never consult a chat ID.
        internal fun storageFile(filesDir: File) = File(filesDir,
            "tts/previous_voice_${Hash.hash("")}.json")
    }

    fun load(): Result<TtsVoiceSelection?> = synchronized(TtsStorageLock) { runCatching { read() } }

    /**
     * Call only for a user activation of a different voice, never browse/preview or automatic
     * restoration. current comes from the authoritative active-voice resolver, not history.
     * A missing old selection clears history instead of inventing a predecessor.
     */
    fun recordActivation(current: TtsVoiceSelection?, next: TtsVoiceSelection): Result<Unit> =
        synchronized(TtsStorageLock) {
            runCatching {
                read() // Refuse to overwrite unreadable history even on the first activation.
                try {
                    current?.validate()
                    next.validate()
                } catch (error: Exception) {
                    throw TtsStorageException(TtsStorageFailure.INVALID_SELECTION, error)
                }
                if (current != next) {
                    val previous = current?.let {
                        JSONObject().put("kind", it.kind.name).put("sourceId", it.sourceId)
                            .put("voiceId", it.voiceId).put("modelId", it.modelId ?: JSONObject.NULL)
                    } ?: JSONObject.NULL
                    writeTts(storage, JSONObject().put("version", 1).put("previous", previous).toString())
                }
            }
        }

    /** A rejected current-selection write must not consume its predecessor. */
    internal fun activate(current: TtsVoiceSelection?, next: TtsVoiceSelection,
        saveCurrent: () -> Boolean): Result<Unit> = synchronized(TtsStorageLock) {
        runCatching {
            val previous = read()
            recordActivation(current, next).getOrThrow()
            try {
                if (!saveCurrent()) throw TtsStorageException(TtsStorageFailure.WRITE_FAILED)
            } catch (error: Exception) {
                if (current != next) {
                    val value = previous?.let { JSONObject().put("kind", it.kind.name)
                        .put("sourceId", it.sourceId).put("voiceId", it.voiceId)
                        .put("modelId", it.modelId ?: JSONObject.NULL) } ?: JSONObject.NULL
                    writeTts(storage, JSONObject().put("version", 1).put("previous", value).toString())
                }
                throw error
            }
        }
    }

    private fun read(): TtsVoiceSelection? = readTts(storage, null) { content ->
        val root = parseTtsObject(content).apply { requireVersionOne() }
        require(root.has("previous"))
        if (root.isNull("previous")) null else {
            val item = root.getJSONObject("previous")
            require(item.has("modelId"))
            TtsVoiceSelection(
                TtsVoiceKind.valueOf(item.strictString("kind")), item.strictString("sourceId"),
                item.strictString("voiceId"),
                if (item.isNull("modelId")) null else item.strictString("modelId")
            ).also { it.validate() }
        }
    }
}
