package org.teslasoft.assistant.tts.voices

import java.io.FileNotFoundException
import java.nio.file.AccessDeniedException
import java.nio.file.ReadOnlyFileSystemException
import org.teslasoft.assistant.preferences.tts.TtsStorageException
import org.teslasoft.assistant.preferences.tts.TtsStorageFailure
import org.teslasoft.assistant.preferences.tts.TtsWriteVerificationException

/**
 * Picks the most specific explanation for a saved Voice ID storage failure. A known cause
 * (no space, no access, damaged data, missing record) wins over the operation's fallback.
 */
object ManualVoiceStorageErrors {
    enum class Operation { READ, SAVE, REMOVE }

    enum class Kind {
        NO_SPACE, ACCESS, DAMAGED, READ_FAILED, NOT_FOUND,
        SAVE_UNVERIFIED, REMOVE_UNVERIFIED, SAVE_FAILED, REMOVE_FAILED
    }

    fun classify(operation: Operation, error: Throwable): Kind {
        val reason = (error as? TtsStorageException)?.reason
        val chain = chain(error)
        val text = chain.mapNotNull { it.message }.joinToString("\n")
        return when {
            reason == TtsStorageFailure.NOT_FOUND -> Kind.NOT_FOUND
            reason == TtsStorageFailure.INVALID_DATA -> Kind.DAMAGED
            listOf("ENOSPC", "No space left on device").any { text.contains(it, ignoreCase = true) } -> Kind.NO_SPACE
            chain.any { it is AccessDeniedException || it is FileNotFoundException || it is SecurityException ||
                it is ReadOnlyFileSystemException } ||
                listOf("EACCES", "EPERM", "EROFS", "Permission denied", "Read-only file system",
                    "Cannot create storage directory", "Missing parent directory")
                    .any { text.contains(it, ignoreCase = true) } -> Kind.ACCESS
            reason == TtsStorageFailure.READ_FAILED || operation == Operation.READ -> Kind.READ_FAILED
            chain.any { it is TtsWriteVerificationException } ->
                if (operation == Operation.REMOVE) Kind.REMOVE_UNVERIFIED else Kind.SAVE_UNVERIFIED
            operation == Operation.REMOVE -> Kind.REMOVE_FAILED
            else -> Kind.SAVE_FAILED
        }
    }

    /**
     * What the device reported, cause by cause. The app's own wrapper is left out when it only
     * repeats a category, so the underlying message is what the user sees.
     */
    fun technicalDetails(error: Throwable): String {
        val chain = chain(error)
        val reported = chain.filterNot { it is TtsStorageException && it.cause != null }.ifEmpty { chain }
        return reported.joinToString("\n") { e ->
            e.javaClass.simpleName + (e.message?.takeIf(String::isNotBlank)?.let { ": $it" } ?: "")
        }
    }

    private fun chain(error: Throwable): List<Throwable> =
        generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(8).toList()
}
