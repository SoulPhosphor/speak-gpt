package org.teslasoft.assistant.conversation.index

import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteReadOnlyDatabaseException

enum class IndexReadOperation { CHECK, COUNTS, UPKEEP, VECTOR_START }

/** Safe categories for screen messages; exception details belong only in the logs. */
enum class IndexFailureReason {
    BUSY, STORAGE_FULL, STORAGE_IO, KEY_UNAVAILABLE, ACCESS_DENIED, READ_ONLY, CANNOT_OPEN, UNKNOWN;

    companion object {
        fun from(failure: Throwable): IndexFailureReason {
            var current: Throwable? = failure
            var fallback = UNKNOWN
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            while (current != null && seen.add(current)) {
                when (current) {
                    is SQLiteDatabaseLockedException -> return BUSY
                    is SQLiteFullException -> return STORAGE_FULL
                    is SQLiteDiskIOException -> return STORAGE_IO
                    is IndexKeyUnavailableException -> return KEY_UNAVAILABLE
                    is SecurityException -> return ACCESS_DENIED
                    is SQLiteReadOnlyDatabaseException -> return READ_ONLY
                    is SQLiteCantOpenDatabaseException -> fallback = CANNOT_OPEN
                }
                current = current.cause
            }
            return fallback
        }
    }
}

enum class IndexDeleteStage(val description: String) {
    INDEX_DATA("removing index data"),
    DAMAGED_FILES("removing damaged index files"),
    RECOVERY_FILES("removing files kept by index recovery")
}

data class IndexDeleteResult(val stage: IndexDeleteStage? = null, val cause: Exception? = null) {
    val deleted: Boolean get() = cause == null
    val reason: IndexFailureReason get() = cause?.let(IndexFailureReason::from) ?: IndexFailureReason.UNKNOWN
}

/** Retains both the failed cleanup step and the original database failure, when present. */
internal object IndexDeletion {
    fun finish(
        dataFailure: Exception?,
        removeDamagedFiles: () -> Unit,
        removeRecoveryFiles: () -> Unit
    ): IndexDeleteResult {
        if (dataFailure != null) {
            if (!IndexFailures.isDamage(dataFailure)) return IndexDeleteResult(IndexDeleteStage.INDEX_DATA, dataFailure)
            try {
                removeDamagedFiles()
            } catch (e: Exception) {
                if (e !== dataFailure) e.addSuppressed(dataFailure)
                return IndexDeleteResult(IndexDeleteStage.DAMAGED_FILES, e)
            }
        }
        return try {
            removeRecoveryFiles()
            IndexDeleteResult()
        } catch (e: Exception) {
            if (dataFailure != null && e !== dataFailure) e.addSuppressed(dataFailure)
            IndexDeleteResult(IndexDeleteStage.RECOVERY_FILES, e)
        }
    }
}
