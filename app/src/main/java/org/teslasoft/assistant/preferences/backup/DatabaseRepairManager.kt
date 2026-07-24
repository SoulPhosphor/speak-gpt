/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup

import android.content.Context
import org.teslasoft.assistant.preferences.SnapshotRegistry
import org.teslasoft.assistant.preferences.StartupHealth
import org.teslasoft.assistant.preferences.lorebook.LoreBookEncryption
import org.teslasoft.assistant.preferences.lorebook.LoreBookStore
import org.teslasoft.assistant.preferences.memory.DatabaseKeys
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.profileimages.ProfileImageDb
import org.teslasoft.assistant.preferences.profileimages.ProfileImageFileNaming
import org.teslasoft.assistant.preferences.profileimages.ProfileImageStore
import java.io.File
import java.time.LocalDate

/**
 * The Build Phase 3 repair/replace engine for the three DATABASES (§15.2,
 * §15.6, §15.16). Chats are not a database and never pass through here — their
 * recovery is [ChatRestoreManager] plus the existing Round-4 lock machinery.
 *
 * LAWS (build plan Build Phase 3 item 2 — do not weaken):
 *  - PRESERVE THE ORIGINAL before any repair: the damaged database AND its
 *    WAL/journal sidecars are copied into files/storage_recovery/, renamed
 *    with a date + "corrupt" marker, and indexed in [SnapshotRegistry]. The
 *    preserved copy is KEPT even when repair succeeds, and no cleanup pass
 *    may ever touch it.
 *  - Repair works on a SEPARATE staged file ([SqlcipherSalvage]) — never
 *    in-place on the only copy — and the staged result is integrity-verified
 *    BEFORE it replaces the active database.
 *  - Repair failure is a normal, designed outcome that routes to the revert
 *    path; nothing here promises more than salvage.
 *
 * All entry points do disk + Keystore + SQLCipher work: call OFF the main
 * thread. Every outcome writes its once-per-event §15.15 health line.
 */
object DatabaseRepairManager {

    data class Outcome(
        val ok: Boolean,
        /** Absolute path of the preserved (quarantined) damaged database, for
         *  the A6 dialog's "saved here" line. Null when quarantine failed. */
        val quarantinePath: String?,
        val detail: String?
    )

    private val SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")

    private fun dbFileName(type: BackupType): String = when (type) {
        BackupType.MEMORY -> MemoryStore.DATABASE_NAME
        BackupType.LOREBOOK -> "lorebook.db"
        BackupType.USER_IMAGE -> "profile_images.db"
        BackupType.CHATS -> throw IllegalArgumentException("chats are not a database")
    }

    /** The key for a cipher store, without minting anything. Empty = legacy
     *  plaintext lorebook (legal); null = unavailable. */
    private fun keyFor(context: Context, type: BackupType): ByteArray? = when (type) {
        BackupType.MEMORY -> DatabaseKeys.getOrCreate(context, DatabaseKeys.KEY_MEMORY, databaseExists = true)
        BackupType.LOREBOOK -> LoreBookEncryption.obtainPassword(context, "lorebook.db")
        else -> null
    }

    /** Close and forget every cached handle to [type]'s database so the file
     *  can be safely replaced, and so the next open sees the new file. Cached
     *  references other objects still hold are best-effort casualties — the
     *  degraded gate has been refusing new ones since damage was confirmed. */
    fun invalidateStore(context: Context, type: BackupType) {
        when (type) {
            BackupType.MEMORY -> MemoryStore.invalidateInstance()
            BackupType.LOREBOOK -> LoreBookStore.invalidateInstance()
            BackupType.USER_IMAGE -> {
                ProfileImageStore.invalidateInstance()
                ProfileImageDb.invalidateInstance()
            }
            BackupType.CHATS -> { /* not a database */ }
        }
    }

    /**
     * Quarantine the current database file + sidecars: COPY (never move —
     * the original stays where salvage can read it) into
     * files/storage_recovery/ under `<stem>.corrupt-<yyyy-MM-dd>-<uniq><ext>`,
     * each copy indexed in [SnapshotRegistry]. Returns the main copy's path,
     * or null when the database file does not exist or the copy failed.
     * Failure to quarantine ABORTS destructive callers (they check for null).
     */
    fun quarantine(
        context: Context,
        type: BackupType,
        forRestore: Boolean = false
    ): String? {
        return try {
            val src = context.getDatabasePath(dbFileName(type))
            if (!src.exists()) return null
            val dir = File(context.filesDir, "storage_recovery")
            if (!dir.exists() && !dir.mkdirs()) return null
            val stem = src.name.removeSuffix(".db")
            val marker = (if (forRestore) "pre-restore" else "corrupt") +
                "-${LocalDate.now()}-${SnapshotRegistry.uniqueSuffix()}"
            val origin = if (forRestore) {
                SnapshotRegistry.ORIGIN_PRE_RESTORE
            } else {
                SnapshotRegistry.ORIGIN_DB_CORRUPTION
            }
            val mainCopy = File(dir, "$stem.$marker.db")
            src.copyTo(mainCopy, overwrite = false)
            SnapshotRegistry.record(
                context, mainCopy.name, src.name,
                origin, if (forRestore) "pre_restore" else "db_corruption"
            )
            for (suffix in SIDECAR_SUFFIXES) {
                val sidecar = File(src.parentFile, src.name + suffix)
                if (sidecar.exists()) {
                    try {
                        val copy = File(dir, "$stem.$marker.db$suffix")
                        sidecar.copyTo(copy, overwrite = false)
                        SnapshotRegistry.record(
                            context, copy.name, sidecar.name,
                            origin, if (forRestore) "pre_restore" else "db_corruption"
                        )
                    } catch (_: Exception) { /* a sidecar copy failing does not lose the main copy */ }
                }
            }
            mainCopy.absolutePath
        } catch (e: Exception) {
            DatabaseHealthState.logHealth(context, "error",
                "Quarantine of the ${DatabaseHealthState.displayNoun(type)} database failed (${e.javaClass.simpleName}) — no repair will run without a preserved original.")
            null
        }
    }

    private fun deleteActiveFiles(context: Context, type: BackupType) {
        val db = context.getDatabasePath(dbFileName(type))
        try { if (db.exists()) db.delete() } catch (_: Exception) { }
        for (suffix in SIDECAR_SUFFIXES) {
            try {
                val sidecar = File(db.parentFile, db.name + suffix)
                if (sidecar.exists()) sidecar.delete()
            } catch (_: Exception) { }
        }
    }

    /** Put a pre-restore snapshot back after a failed swap. The preserved copy
     *  remains in storage_recovery; rollback copies it rather than consuming
     *  the user's safety copy. */
    internal fun restoreQuarantinedFiles(
        context: Context,
        type: BackupType,
        quarantinePath: String
    ): Boolean = try {
        val active = context.getDatabasePath(dbFileName(type))
        val preserved = File(quarantinePath)
        if (!preserved.exists()) return false
        deleteActiveFiles(context, type)
        preserved.copyTo(active, overwrite = true)
        for (suffix in SIDECAR_SUFFIXES) {
            val preservedSidecar = File(quarantinePath + suffix)
            if (preservedSidecar.exists()) {
                preservedSidecar.copyTo(File(active.path + suffix), overwrite = true)
            }
        }
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Install an already-staged and verified database snapshot. This is shared
     * by same-install automatic artifacts and portable Recovery Packages.
     *
     * Portable SQLCipher snapshots carry their source key. The snapshot is
     * verified under that key before the current database or key is touched.
     * The old key is retained for rollback until the installed file passes a
     * second integrity check. Automatic snapshots pass the current key, so no
     * key transition occurs.
     */
    fun restoreSnapshot(
        context: Context,
        type: BackupType,
        verifiedSnapshot: File,
        sourceKey: ByteArray?,
        sourcePlaintext: Boolean = false
    ): Outcome {
        require(type != BackupType.CHATS) { "chats are not a database" }
        val appContext = context.applicationContext
        val active = appContext.getDatabasePath(dbFileName(type))

        // Verify again immediately before the destructive boundary.
        try {
            when (type) {
                BackupType.MEMORY -> {
                    val key = sourceKey ?: return Outcome(false, null, "backup key unavailable")
                    RecoveryBackupManager.integrityCheckCipher(
                        verifiedSnapshot, key, "meta"
                    )
                }
                BackupType.LOREBOOK -> if (sourcePlaintext) {
                    RecoveryBackupManager.integrityCheckPlain(
                        verifiedSnapshot, "memory_entries"
                    )
                } else {
                    val key = sourceKey ?: return Outcome(false, null, "backup key unavailable")
                    RecoveryBackupManager.integrityCheckCipher(
                        verifiedSnapshot, key, "memory_entries"
                    )
                }
                BackupType.USER_IMAGE -> RecoveryBackupManager.integrityCheckPlain(
                    verifiedSnapshot, "profile_images"
                )
                BackupType.CHATS -> Unit
            }
        } catch (e: Exception) {
            return Outcome(false, null, "backup verification failed: ${e.javaClass.simpleName}")
        }

        // Close cached handles before making the safety copy. This path is
        // also available for a healthy database, so unlike repair we cannot
        // assume the degraded gate already stopped every ordinary store use.
        invalidateStore(appContext, type)
        val quarantinePath = if (active.exists()) {
            quarantine(appContext, type, forRestore = true)
                ?: return Outcome(false, null, "quarantine failed — restore refused")
        } else null

        val staged = File(active.parentFile, "${active.name}.restore-${SnapshotRegistry.uniqueSuffix()}.tmp")
        val keyName = when (type) {
            BackupType.MEMORY -> DatabaseKeys.KEY_MEMORY
            BackupType.LOREBOOK -> DatabaseKeys.KEY_LOREBOOK
            else -> null
        }
        val oldKey = keyName?.let { DatabaseKeys.readExisting(appContext, it) }
        val keyChanged = keyName != null && sourceKey != null &&
            (oldKey == null || !oldKey.contentEquals(sourceKey))
        var liveFilesTouched = false

        return try {
            verifiedSnapshot.copyTo(staged, overwrite = true)
            when (type) {
                BackupType.MEMORY ->
                    RecoveryBackupManager.integrityCheckCipher(staged, sourceKey, "meta")
                BackupType.LOREBOOK -> if (sourcePlaintext) {
                    RecoveryBackupManager.integrityCheckPlain(staged, "memory_entries")
                } else {
                    RecoveryBackupManager.integrityCheckCipher(
                        staged, sourceKey, "memory_entries"
                    )
                }
                BackupType.USER_IMAGE ->
                    RecoveryBackupManager.integrityCheckPlain(staged, "profile_images")
                BackupType.CHATS -> Unit
            }

            if (keyChanged && !DatabaseKeys.replaceExisting(appContext, keyName!!, sourceKey!!)) {
                staged.delete()
                return Outcome(false, quarantinePath, "could not store the restored database key")
            }

            invalidateStore(appContext, type)
            liveFilesTouched = true
            deleteActiveFiles(appContext, type)
            if (!staged.renameTo(active)) {
                staged.copyTo(active, overwrite = true)
                staged.delete()
            }

            when (type) {
                BackupType.MEMORY ->
                    RecoveryBackupManager.integrityCheckCipher(active, sourceKey, "meta")
                BackupType.LOREBOOK -> if (sourcePlaintext) {
                    RecoveryBackupManager.integrityCheckPlain(active, "memory_entries")
                } else {
                    RecoveryBackupManager.integrityCheckCipher(
                        active, sourceKey, "memory_entries"
                    )
                }
                BackupType.USER_IMAGE ->
                    RecoveryBackupManager.integrityCheckPlain(active, "profile_images")
                BackupType.CHATS -> Unit
            }

            invalidateStore(appContext, type)
            DatabaseHealthState.clearDegraded(appContext, type, "restored from verified backup")
            DatabaseHealthState.logHealth(
                appContext, "info",
                "${DatabaseHealthState.displayNoun(type)} database restored from a verified backup. " +
                    (quarantinePath?.let { "Previous database preserved at $it." }
                        ?: "No previous database file existed.")
            )
            Outcome(true, quarantinePath, null)
        } catch (e: Exception) {
            try { if (staged.exists()) staged.delete() } catch (_: Exception) { }
            if (keyChanged && keyName != null) {
                if (oldKey != null) DatabaseKeys.replaceExisting(appContext, keyName, oldKey)
                else DatabaseKeys.clearExisting(appContext, keyName)
            }
            val rolledBack = if (!liveFilesTouched) {
                true
            } else if (quarantinePath != null) {
                restoreQuarantinedFiles(appContext, type, quarantinePath)
            } else {
                deleteActiveFiles(appContext, type)
                true
            }
            invalidateStore(appContext, type)
            DatabaseHealthState.logHealth(
                appContext, "error",
                "Restore of the ${DatabaseHealthState.displayNoun(type)} database failed " +
                    "(${e.javaClass.simpleName}). " +
                    (if (rolledBack) {
                        "The previous database was put back and remains preserved."
                    } else {
                        "The previous database remains preserved but could not be put back automatically."
                    })
            )
            Outcome(false, quarantinePath, e.javaClass.simpleName)
        } finally {
            oldKey?.fill(0)
        }
    }

    /**
     * Attempt the automatic repair of [type] (§15.2 step 2). Quarantine →
     * staged salvage (databases) or rebuild-from-files (user image catalog,
     * §15.16 — the safe path: the catalog only records what already exists on
     * disk) → verified swap → singleton invalidation → degraded flag cleared.
     * On success the caller shows the `Database Repaired` dialog — a repair is
     * ALWAYS disclosed, never silent (owner ruling).
     */
    fun attemptRepair(context: Context, type: BackupType): Outcome {
        val appContext = context.applicationContext
        DatabaseHealthState.logHealth(appContext, "warning",
            "Repair of the ${DatabaseHealthState.displayNoun(type)} database attempted.")
        val quarantinePath = quarantine(appContext, type)
            ?: return Outcome(false, null, "quarantine failed or database missing")
        return when (type) {
            BackupType.USER_IMAGE -> rebuildUserImageCatalog(appContext, quarantinePath)
            BackupType.MEMORY, BackupType.LOREBOOK -> salvageCipherStore(appContext, type, quarantinePath)
            BackupType.CHATS -> Outcome(false, quarantinePath, "chats are not a database")
        }
    }

    private fun salvageCipherStore(context: Context, type: BackupType, quarantinePath: String): Outcome {
        val src = context.getDatabasePath(dbFileName(type))
        val key = keyFor(context, type)
            ?: return failed(context, type, quarantinePath, "store key unavailable")
        // Close any open handle BEFORE reading: a live WAL connection could
        // otherwise checkpoint underneath the salvage read.
        invalidateStore(context, type)
        val staged = File(src.parentFile, "${src.name}.salvage-${SnapshotRegistry.uniqueSuffix()}.tmp")
        val result = SqlcipherSalvage.salvage(src.path, key, staged)
        if (!result.ok) {
            return failed(context, type, quarantinePath, result.detail ?: "salvage failed")
        }
        return try {
            deleteActiveFiles(context, type)
            if (!staged.renameTo(src)) {
                // Same directory, so rename should not fail; if it does, fall
                // back to copy + delete.
                staged.copyTo(src, overwrite = true)
                staged.delete()
            }
            invalidateStore(context, type)
            DatabaseHealthState.clearDegraded(context, type, "repaired")
            DatabaseHealthState.logHealth(context, "info",
                "Repair of the ${DatabaseHealthState.displayNoun(type)} database succeeded " +
                    "(${result.tablesCopied} tables, ${result.rowsCopied} rows recovered; " +
                    "${result.tablesLost} tables, ${result.rowsLost} rows unrecoverable). " +
                    "Damaged original preserved at $quarantinePath.")
            Outcome(true, quarantinePath, null)
        } catch (e: Exception) {
            try { if (staged.exists()) staged.delete() } catch (_: Exception) { }
            failed(context, type, quarantinePath, e.javaClass.simpleName)
        }
    }

    /**
     * §15.16: the user image catalog's safe auto-repair — rebuild it by
     * rescanning the permanent image files (nothing is guessed or invented;
     * a record per `profile_<hash>.jpg` on disk, created_at from the file).
     * The damaged catalog is still preserved first, like every other repair.
     */
    private fun rebuildUserImageCatalog(context: Context, quarantinePath: String): Outcome {
        return try {
            invalidateStore(context, BackupType.USER_IMAGE)
            deleteActiveFiles(context, BackupType.USER_IMAGE)
            val db = ProfileImageDb.getInstance(context) // fresh file, fresh schema
            var restored = 0
            val dir = context.getExternalFilesDir("profile_images")
            val files = dir?.listFiles() ?: emptyArray()
            for (file in files) {
                val hash = ProfileImageFileNaming.hashFromPermanentFilename(file.name) ?: continue
                db.insertOrIgnore(hash, file.lastModified())
                restored++
            }
            val problem = db.integrityCheck()
            if (problem != null) return failed(context, BackupType.USER_IMAGE, quarantinePath, problem)
            DatabaseHealthState.clearDegraded(context, BackupType.USER_IMAGE, "rebuilt from image files")
            DatabaseHealthState.logHealth(context, "info",
                "User image database rebuilt from the image files on disk ($restored records). " +
                    "Damaged original preserved at $quarantinePath.")
            Outcome(true, quarantinePath, null)
        } catch (e: Exception) {
            failed(context, BackupType.USER_IMAGE, quarantinePath, e.javaClass.simpleName)
        }
    }

    /**
     * The honest last resort (§15.6 step 4): start [type] over EMPTY after
     * repair failed and no usable backup existed. The damaged file was already
     * quarantined ([quarantinePath] from that step, re-quarantined here if the
     * caller has none). The caller MUST show the A6 `Database Recovery Failed`
     * dialog — a fresh empty database is never presented as if nothing
     * happened.
     */
    fun startFresh(context: Context, type: BackupType, priorQuarantinePath: String?): Outcome {
        val appContext = context.applicationContext
        val quarantinePath = priorQuarantinePath ?: quarantine(appContext, type)
            ?: return Outcome(false, null, "quarantine failed — fresh start refused")
        return try {
            invalidateStore(appContext, type)
            deleteActiveFiles(appContext, type)
            DatabaseHealthState.clearDegraded(appContext, type, "started fresh — no usable backup")
            DatabaseHealthState.setPendingNotice(
                appContext, type, DatabaseHealthState.NOTICE_RECOVERY_FAILED, quarantinePath
            )
            DatabaseHealthState.logHealth(appContext, "error",
                "${DatabaseHealthState.displayNoun(type)} database could not be repaired and no usable backup was available; " +
                    "a new empty database was started. Damaged original preserved at $quarantinePath.")
            Outcome(true, quarantinePath, null)
        } catch (e: Exception) {
            Outcome(false, quarantinePath, e.javaClass.simpleName)
        }
    }

    /**
     * Confirm-or-clear pass for a store whose damage was signalled indirectly
     * (a SOURCE backup failure, Build Phase 2 items 5/7): run the store's own
     * integrity check and mark degraded ONLY when the check confirms damage.
     * Returns true when damage was confirmed. Never called for chats.
     */
    fun confirmDamage(context: Context, type: BackupType): Boolean {
        val result = when (type) {
            BackupType.MEMORY -> DatabaseHealthChecker.checkMemory(context)
            BackupType.LOREBOOK -> DatabaseHealthChecker.checkLorebook(context)
            BackupType.USER_IMAGE -> DatabaseHealthChecker.checkUserImage(context)
            BackupType.CHATS -> return false
        }
        return if (result.status == DatabaseHealthChecker.Status.DAMAGED) {
            DatabaseHealthState.markDegraded(context, type, result.detail ?: "integrity check failed")
            DatabaseHealthState.setPendingNotice(context, type, DatabaseHealthState.NOTICE_PROBLEM)
            StartupHealth.setIntegrityCheckPending(context, true)
            true
        } else {
            false
        }
    }

    private fun failed(context: Context, type: BackupType, quarantinePath: String?, detail: String): Outcome {
        DatabaseHealthState.logHealth(context, "error",
            "Repair of the ${DatabaseHealthState.displayNoun(type)} database failed ($detail). " +
                "The store stays disabled; the damaged original is preserved" +
                (quarantinePath?.let { " at $it" } ?: "") + ".")
        return Outcome(false, quarantinePath, detail)
    }
}
