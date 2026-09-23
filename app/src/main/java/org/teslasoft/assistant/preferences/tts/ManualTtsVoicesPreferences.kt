package org.teslasoft.assistant.preferences.tts

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** A provider Voice ID the user entered for one saved TTS source, stored exactly as entered. */
data class ManualTtsVoice(val savedSourceId: String, val voiceId: String)

/**
 * Manually entered Voice IDs, each belonging to one saved TTS source (its [SavedTtsSource.id]).
 * Display names and genders stay in VoiceIdentityRegistry; this store only keeps the identity.
 * Same conventions as [SavedTtsSourcesPreferences]: explicit failures, atomic writes, strict reads.
 */
class ManualTtsVoicesPreferences internal constructor(private val storage: TtsStorage) {
    companion object {
        const val RELATIVE_PATH = "tts/manual_voices.json"

        fun getPreferences(context: Context): ManualTtsVoicesPreferences =
            ManualTtsVoicesPreferences(TtsFileStorage(File(context.applicationContext.filesDir, RELATIVE_PATH)))
    }

    fun load(): Result<List<ManualTtsVoice>> = synchronized(TtsStorageLock) { runCatching { read() } }

    fun voicesFor(savedSourceId: String): Result<List<String>> =
        load().map { voices -> voices.filter { it.savedSourceId == savedSourceId }.map { it.voiceId } }

    /** Only outside whitespace is removed; the ID is otherwise kept exactly as typed. */
    fun add(savedSourceId: String, enteredVoiceId: String): Result<ManualTtsVoice> = synchronized(TtsStorageLock) {
        runCatching {
            val voice = ManualTtsVoice(savedSourceId, enteredVoiceId.trim())
            if (voice.savedSourceId.isBlank() || voice.voiceId.isEmpty())
                throw TtsStorageException(TtsStorageFailure.INVALID_SELECTION)
            val entries = read()
            if (voice in entries) throw TtsStorageException(TtsStorageFailure.DUPLICATE)
            write(entries + voice)
            voice
        }
    }

    fun remove(savedSourceId: String, voiceId: String): Result<Unit> = synchronized(TtsStorageLock) {
        runCatching {
            val entries = read()
            val voice = ManualTtsVoice(savedSourceId, voiceId)
            if (voice !in entries) throw TtsStorageException(TtsStorageFailure.NOT_FOUND)
            write(entries - voice)
        }
    }

    private fun read(): List<ManualTtsVoice> = readTts(storage, emptyList()) { ManualTtsVoicesCodec.decode(it) }

    private fun write(entries: List<ManualTtsVoice>) = writeTts(storage, ManualTtsVoicesCodec.encode(entries))
}

/** The one strict file format, shared by the store and the Settings and Preferences backup. */
object ManualTtsVoicesCodec {
    val EMPTY: String = encode(emptyList())

    fun encode(entries: List<ManualTtsVoice>): String {
        validate(entries)
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("savedSourceId", it.savedSourceId).put("voiceId", it.voiceId)) }
        return JSONObject().put("version", 1).put("entries", array).toString()
    }

    fun decode(content: String): List<ManualTtsVoice> {
        val root = parseTtsObject(content)
        require(root.keys().asSequence().toSet() == setOf("version", "entries"))
        root.requireVersionOne()
        val array = root.getJSONArray("entries")
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            require(item.keys().asSequence().toSet() == setOf("savedSourceId", "voiceId"))
            ManualTtsVoice(item.strictString("savedSourceId"), item.strictString("voiceId"))
        }.also(::validate)
    }

    private fun validate(entries: List<ManualTtsVoice>) {
        require(entries.all { it.savedSourceId.isNotBlank() && it.voiceId.isNotEmpty() && it.voiceId == it.voiceId.trim() })
        require(entries.distinct().size == entries.size)
    }
}
