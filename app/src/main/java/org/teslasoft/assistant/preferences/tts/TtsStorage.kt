package org.teslasoft.assistant.preferences.tts

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/** Typed failures let callers distinguish unreadable data from an empty collection. */
enum class TtsStorageFailure { READ_FAILED, INVALID_DATA, WRITE_FAILED, INVALID_SELECTION, DUPLICATE, NOT_FOUND }

class TtsStorageException(val reason: TtsStorageFailure, cause: Exception? = null) :
    Exception(reason.name, cause)

/** Serializes read/modify/write across store instances in this application process. */
internal object TtsStorageLock

internal interface TtsStorage {
    /** Null means absent. Unreadable content must throw, never return null. */
    fun read(): String?
    /** Return only after atomic persistence. Failure must leave the old bytes intact. */
    fun write(content: String)
}

/** No SharedPreferences memory-first commit: a failed disk write must not appear saved. */
internal class TtsFileStorage(private val file: File) : TtsStorage {
    override fun read(): String? = if (file.exists()) file.readText(Charsets.UTF_8) else null

    override fun write(content: String) {
        val parent = file.absoluteFile.parentFile ?: throw IOException("Missing parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create storage directory")
        val temporary = File.createTempFile(file.name, ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            if (temporary.readText(Charsets.UTF_8) != content) throw IOException("Write verification failed")
            // Same filesystem, atomic replacement. Never delete the original as a fallback.
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}

internal fun <T> readTts(storage: TtsStorage, absent: T, decode: (String) -> T): T {
    val content = try { storage.read() } catch (error: Exception) {
        throw TtsStorageException(TtsStorageFailure.READ_FAILED, error)
    } ?: return absent
    return try { decode(content) } catch (error: Exception) {
        throw TtsStorageException(TtsStorageFailure.INVALID_DATA, error)
    }
}

internal fun writeTts(storage: TtsStorage, content: String) {
    try { storage.write(content) } catch (error: Exception) {
        throw TtsStorageException(TtsStorageFailure.WRITE_FAILED, error)
    }
}

internal fun JSONObject.strictString(key: String): String =
    get(key).let { require(it is String); it }

internal fun JSONObject.strictBoolean(key: String): Boolean =
    get(key).let { require(it is Boolean); it }

internal fun JSONObject.strictStrings(key: String): List<String> {
    val values = getJSONArray(key)
    return (0 until values.length()).map { index ->
        values.get(index).let { require(it is String && it.isNotBlank()); it }
    }.also { require(it.distinct().size == it.size) }
}

internal fun JSONObject.requireVersionOne() {
    require(get("version") == 1)
}

internal fun stringsJson(values: List<String>): JSONArray = JSONArray(values)
