/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException
import org.teslasoft.assistant.preferences.memory.DatabaseKeys

/**
 * Access to the index database: ordinary work shares it, recovery has it to
 * itself (Step 11). While recovery waits, a running vector pass stops at its
 * next vector, so recovery never replaces the file under an open connection.
 */
object ConversationIndexAccess {
    private val lock = ReentrantReadWriteLock()

    /** Recovery is waiting for, or holds, the database. */
    @Volatile var recovering: Boolean = false
        private set

    fun <T> shared(block: () -> T): T {
        lock.readLock().lock()
        try {
            return block()
        } finally {
            lock.readLock().unlock()
        }
    }

    fun <T> exclusive(block: () -> T): T {
        recovering = true
        lock.writeLock().lock()
        try {
            return block()
        } finally {
            lock.writeLock().unlock()
            recovering = false
        }
    }
}

/**
 * Tells damage apart from failures that pass (Owner, 2026-11-24): only a
 * corrupt or unreadable database file, or a database whose key is gone, is
 * damage. A key store that cannot be read now, a locked or busy database, a
 * full disk or a file that cannot be opened for now are not, and never lead
 * to a rebuild.
 */
object IndexFailures {
    fun isDamage(e: Throwable): Boolean {
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth++ < 8) {
            // SQLCipher reports a file it cannot read as a database with its own type (SQLITE_NOTADB).
            if (current is SQLiteDatabaseCorruptException || current is SQLiteNotADatabaseException) return true
            if (current is IndexKeyUnavailableException) return current.missing
            current = current.cause?.takeIf { it !== current }
        }
        return false
    }
}

/** The files and key a recovery works on; the real ones are [AppIndexRecoveryFiles]. */
interface IndexRecoveryFiles {
    enum class Key { PRESENT, ABSENT, UNAVAILABLE }

    fun keyState(): Key

    /** Reads the made vectors that can still be read from the damaged database, if it exists. */
    fun readKeptVectors(): KeptVectors

    /** Closes the index and moves the damaged database aside; nothing is opened on it again. */
    fun setDamagedAside()

    /**
     * Opens a new, empty index (with a new key when the old one is gone),
     * adds [vectors], checks the new database and returns how many vectors
     * it holds. Throws when any of it fails.
     */
    fun createWith(vectors: List<KeptVector>): Int

    /** Removes the database set aside, once the new one is verified. */
    fun discardDamaged()

    /** Removes a new database that failed its check, so a later attempt starts clean. */
    fun discardNew()
}

/**
 * The automatic recovery of a damaged index (G.13a, Owner 2026-11-24). The
 * index is derived from the stored chats, so recovery rebuilds it; the
 * chats are never read for writing or changed. Vectors that can still be
 * read are kept, so a rebuild does not mean reading everything again.
 *
 *  - A key store that cannot be read now is a passing failure: nothing is
 *    removed ([Result.Postponed]).
 *  - Otherwise the readable vectors are read, the damaged file is set aside,
 *    a new index is created with them and checked, and only then is the old
 *    file removed ([Result.Rebuilt]).
 *  - Any failure on the way is [Result.Failed]; a new database that failed
 *    its check is removed, so the next attempt starts clean.
 */
class IndexRecovery(private val files: IndexRecoveryFiles) {

    sealed interface Result {
        /** [notRead]: why some or all vectors could not be kept, or null. */
        data class Rebuilt(val retained: Int, val notRead: String?) : Result
        data class Postponed(val cause: String) : Result
        data class Failed(val cause: Exception) : Result
    }

    fun recover(): Result {
        val key = try { files.keyState() } catch (_: Exception) { IndexRecoveryFiles.Key.UNAVAILABLE }
        if (key == IndexRecoveryFiles.Key.UNAVAILABLE) {
            return Result.Postponed(IndexKeyUnavailableException(missing = false).message ?: "key unavailable")
        }
        val kept = if (key == IndexRecoveryFiles.Key.PRESENT) {
            try { files.readKeptVectors() } catch (e: Exception) { KeptVectors(emptyList(), e) }
        } else {
            KeptVectors(emptyList(), IndexKeyUnavailableException(missing = true))
        }
        return try {
            files.setDamagedAside()
            val retained = try {
                files.createWith(kept.vectors)
            } catch (e: Exception) {
                try { files.discardNew() } catch (_: Exception) { }
                throw e
            }
            try { files.discardDamaged() } catch (_: Exception) { }
            Result.Rebuilt(retained, kept.stoppedBy?.let { ConversationIndexErrorLog.cause(it) })
        } catch (e: Exception) {
            Result.Failed(e)
        }
    }
}

/**
 * The app's index files: `conversation_index.db`, its journal files and its
 * key. Tests pass their own database and key names, and how the index is
 * opened and closed.
 */
class AppIndexRecoveryFiles(
    context: Context,
    private val databaseName: String = ConversationIndexStore.DATABASE_NAME,
    private val keyName: String = DatabaseKeys.KEY_CONVERSATION_INDEX,
    private val closeIndex: () -> Unit = { ConversationIndexStore.closeShared() },
    private val openIndex: (Context) -> ConversationIndexStore = { ConversationIndexStore.get(it) }
) : IndexRecoveryFiles {
    private val app = context.applicationContext
    private val database: File get() = app.getDatabasePath(databaseName)
    private val damaged: File get() = File(database.path + DAMAGED_SUFFIX)

    override fun keyState(): IndexRecoveryFiles.Key =
        when (DatabaseKeys.readState(app, keyName)) {
            is DatabaseKeys.StoredKeyState.Present -> IndexRecoveryFiles.Key.PRESENT
            DatabaseKeys.StoredKeyState.Absent -> IndexRecoveryFiles.Key.ABSENT
            DatabaseKeys.StoredKeyState.Unavailable -> IndexRecoveryFiles.Key.UNAVAILABLE
        }

    override fun readKeptVectors(): KeptVectors {
        val key = DatabaseKeys.readExisting(app, keyName)
            ?: return KeptVectors(emptyList(), IndexKeyUnavailableException(missing = false))
        closeIndex()
        // A file set aside by an earlier attempt that did not finish holds the same vectors.
        val source = if (database.exists()) database else damaged
        if (!source.exists()) return KeptVectors(emptyList(), null)
        return ConversationIndexStore.readableVectors(source.path, key)
    }

    override fun setDamagedAside() {
        closeIndex()
        if (database.exists()) {
            if (damaged.exists() && !damaged.delete()) error("could not remove ${damaged.name}")
            if (!database.renameTo(damaged)) error("could not move ${database.name} aside")
        }
        for (suffix in JOURNAL_SUFFIXES) {
            val journal = File(database.path + suffix)
            if (journal.exists() && !journal.delete()) error("could not remove ${journal.name}")
        }
    }

    override fun createWith(vectors: List<KeptVector>): Int {
        val store = openIndex(app)
        store.restoreVectors(vectors)
        store.integrityProblem()?.let { error("the new index failed its check: $it") }
        return store.madeVectorCount()
    }

    override fun discardDamaged() {
        File(damaged.path).delete()
        for (suffix in JOURNAL_SUFFIXES) File(damaged.path + suffix).delete()
    }

    /** Whether a damaged file set aside by an unfinished recovery is still there. */
    fun hasDamaged(): Boolean = damaged.exists()

    override fun discardNew() {
        closeIndex()
        database.delete()
        for (suffix in JOURNAL_SUFFIXES) File(database.path + suffix).delete()
    }

    private companion object {
        const val DAMAGED_SUFFIX = ".damaged"
        val JOURNAL_SUFFIXES = listOf("-journal", "-wal", "-shm")
    }
}
