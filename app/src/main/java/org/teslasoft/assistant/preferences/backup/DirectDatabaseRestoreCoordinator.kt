/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.backup

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase
import org.teslasoft.assistant.preferences.SnapshotRegistry
import org.teslasoft.assistant.preferences.backup.DirectDatabaseRestoreJournal.FileEvidence
import org.teslasoft.assistant.preferences.backup.DirectDatabaseRestoreJournal.KeyEvidence
import org.teslasoft.assistant.preferences.backup.DirectDatabaseRestoreJournal.KeyKind
import org.teslasoft.assistant.preferences.backup.DirectDatabaseRestoreJournal.Phase
import org.teslasoft.assistant.preferences.lorebook.LoreBookEncryption
import org.teslasoft.assistant.preferences.lorebook.LoreBookStore
import org.teslasoft.assistant.preferences.memory.DatabaseKeys
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.profileimages.ProfileImageDb

/** Crash-safe publisher for direct Memory, Lorebook, and Profile Image DBs. */
internal object DirectDatabaseRestoreCoordinator {
    private val activeTypes: MutableSet<BackupType> =
        Collections.newSetFromMap(ConcurrentHashMap<BackupType, Boolean>())
    private val recoveryThread = ThreadLocal<Boolean>()
    private val sidecars = listOf("-wal", "-shm", "-journal")

    enum class Boundary {
        AFTER_PREPARED,
        AFTER_KEY_WRITE,
        AFTER_KEY_SWITCHED,
        AFTER_SIDECAR_REMOVAL,
        AFTER_FILE_RENAME,
        AFTER_FILE_INSTALLED,
        AFTER_LOW_LEVEL_VERIFY,
        AFTER_NORMAL_VERIFY,
        AFTER_VERIFIED,
        AFTER_COMMITTED,
        AFTER_JOURNAL_DELETE
    }

    fun interface FaultInjector { fun after(boundary: Boundary) }

    fun hasPending(context: Context): Boolean = DirectDatabaseRestoreJournal.hasAny(context)

    /** Store constructors call this before opening a live handle. */
    fun blocksStore(context: Context, type: BackupType): Boolean =
        recoveryThread.get() != true &&
            (type in activeTypes || DirectDatabaseRestoreJournal.root(context, type).exists())

    fun allowsRecoveryOpen(type: BackupType): Boolean =
        recoveryThread.get() == true && type in activeTypes

    fun install(
        context: Context,
        type: BackupType,
        verifiedSnapshot: File,
        sourceKey: ByteArray?,
        sourcePlaintext: Boolean,
        sourceKind: DirectDatabaseRestoreJournal.SourceKind =
            DirectDatabaseRestoreJournal.SourceKind.DATABASE_SNAPSHOT,
        faultInjector: FaultInjector = FaultInjector { }
    ): DatabaseRepairManager.Outcome = RecoveryOperationGate.runExclusive {
        require(type != BackupType.CHATS) { "chats are not a database" }
        val app = context.applicationContext
        if (DirectDatabaseRestoreJournal.root(app, type).exists()) {
            if (!recoverOne(app, type)) {
                return@runExclusive DatabaseRepairManager.Outcome(
                    false, null, "pending database restore recovery could not be completed"
                )
            }
        }
        activeTypes.add(type)
        recoveryThread.set(true)
        try {
            installLocked(
                app, type, verifiedSnapshot, sourceKey, sourcePlaintext, sourceKind,
                faultInjector
            )
        } finally {
            recoveryThread.remove()
            activeTypes.remove(type)
        }
    }

    /** Must run before any affected store is made available in a new process. */
    fun recoverAll(context: Context): Boolean = RecoveryOperationGate.runExclusive {
        val app = context.applicationContext
        for (type in DirectDatabaseRestoreJournal.databaseTypes()) {
            if (!DirectDatabaseRestoreJournal.root(app, type).exists()) {
                clearRecoveryKeys(app, type)
                continue
            }
            activeTypes.add(type)
            recoveryThread.set(true)
            try {
                if (!recoverOne(app, type)) return@runExclusive false
            } finally {
                recoveryThread.remove()
                activeTypes.remove(type)
            }
        }
        true
    }

    private fun installLocked(
        context: Context,
        type: BackupType,
        source: File,
        sourceKey: ByteArray?,
        sourcePlaintext: Boolean,
        sourceKind: DirectDatabaseRestoreJournal.SourceKind,
        faultInjector: FaultInjector
    ): DatabaseRepairManager.Outcome {
        val active = context.getDatabasePath(databaseName(type))
        val operationId = SnapshotRegistry.uniqueSuffix().replace('_', '-')
        val staged = File(active.parentFile, ".${active.name}.direct-restore-$operationId.stage")
        val quarantine = quarantineFile(context, active, operationId)
        val originalDegraded = DatabaseHealthState.isDegraded(context, type)

        val originalState = keyState(context, type)
        if (originalState is DatabaseKeys.StoredKeyState.Unavailable) {
            return DatabaseRepairManager.Outcome(false, null, "database key state unavailable")
        }
        val originalKey = (originalState as? DatabaseKeys.StoredKeyState.Present)?.value
        val intendedKey = intendedKey(type, sourceKey, sourcePlaintext, originalKey)
            ?: run {
                originalKey?.fill(0)
                return DatabaseRepairManager.Outcome(false, null, "backup key unavailable")
            }
        val originalKeyEvidence = keyEvidence(type, originalState)
        val intendedKeyEvidence = keyEvidence(type, intendedKey)

        try {
            verifySnapshot(type, source, sourceKey, sourcePlaintext)
            DatabaseRepairManager.invalidateStore(context, type)

            val originalFiles = stageQuarantine(context, active, quarantine)
                ?: return DatabaseRepairManager.Outcome(false, null, "quarantine failed — restore refused")
            if (!stageIncoming(type, source, staged, intendedKey, sourcePlaintext)) {
                return DatabaseRepairManager.Outcome(false, quarantine.takeIf { active.exists() }?.path, "database staging failed")
            }
            val installedFiles = DirectDatabaseRestoreJournal.evidence(staged)
                ?: return DatabaseRepairManager.Outcome(false, quarantine.takeIf { active.exists() }?.path, "database staging verification failed")
            val sourceIdentity = sha256(source)

            if (!persistRecoveryKeys(context, type, originalKey, intendedKey)) {
                return DatabaseRepairManager.Outcome(false, quarantine.takeIf { active.exists() }?.path, "could not preserve database key state")
            }
            var record = DirectDatabaseRestoreJournal.Record(
                operationId = operationId,
                type = type,
                sourceKind = sourceKind,
                sourceIdentity = sourceIdentity,
                activePath = active.canonicalPath,
                stagedPath = staged.canonicalPath,
                quarantinePath = quarantine.canonicalPath,
                originalExisted = originalFiles.any { it.suffix.isEmpty() },
                originalDegraded = originalDegraded,
                originalKey = originalKeyEvidence,
                intendedKey = intendedKeyEvidence,
                originalFiles = originalFiles,
                installedFiles = installedFiles,
                phase = Phase.PREPARED
            )
            if (!DirectDatabaseRestoreJournal.write(context, record)) {
                DirectDatabaseRestoreJournal.delete(context, type)
                return DatabaseRepairManager.Outcome(false, quarantine.takeIf { active.exists() }?.path, "restore journal could not be stored")
            }
            faultInjector.after(Boundary.AFTER_PREPARED)

            if (!installKey(context, type, intendedKeyEvidence, intendedKey)) {
                return failAndRecover(context, record, "could not store the restored database key")
            }
            faultInjector.after(Boundary.AFTER_KEY_WRITE)
            record = record.copy(phase = Phase.KEY_SWITCHED)
            if (!DirectDatabaseRestoreJournal.write(context, record)) {
                return failAndRecover(context, record, "restore journal could not record the key switch")
            }
            faultInjector.after(Boundary.AFTER_KEY_SWITCHED)

            if (!removeSidecars(active)) {
                return failAndRecover(context, record, "active database sidecars could not be retired")
            }
            faultInjector.after(Boundary.AFTER_SIDECAR_REMOVAL)
            if (!DurableRecoveryFileOps.atomicReplace(staged, active)) {
                return failAndRecover(context, record, "atomic database publication failed")
            }
            faultInjector.after(Boundary.AFTER_FILE_RENAME)
            record = record.copy(phase = Phase.FILE_INSTALLED)
            if (!DirectDatabaseRestoreJournal.write(context, record)) {
                return failAndRecover(context, record, "restore journal could not record publication")
            }
            faultInjector.after(Boundary.AFTER_FILE_INSTALLED)

            verifyInstalledLowLevel(context, record, intendedKey)
            faultInjector.after(Boundary.AFTER_LOW_LEVEL_VERIFY)
            record = record.copy(
                installedFiles = DirectDatabaseRestoreJournal.evidence(active)
                    ?: return failAndRecover(context, record, "installed database evidence unavailable"),
                phase = Phase.VERIFIED
            )
            if (!DirectDatabaseRestoreJournal.write(context, record)) {
                return failAndRecover(context, record, "restore journal could not record verification")
            }

            verifyThroughNormalStore(context, type)
            faultInjector.after(Boundary.AFTER_NORMAL_VERIFY)
            DatabaseRepairManager.invalidateStore(context, type)
            val finalEvidence = DirectDatabaseRestoreJournal.evidence(active)
                ?: return failAndRecover(context, record, "installed database evidence unavailable")
            record = record.copy(installedFiles = finalEvidence, phase = Phase.VERIFIED)
            if (!DirectDatabaseRestoreJournal.write(context, record)) {
                return failAndRecover(context, record, "restore journal could not retain final evidence")
            }
            faultInjector.after(Boundary.AFTER_VERIFIED)
            record = record.copy(phase = Phase.COMMITTED)
            if (!DirectDatabaseRestoreJournal.write(context, record)) {
                return failAndRecover(context, record, "restore journal could not commit")
            }
            faultInjector.after(Boundary.AFTER_COMMITTED)
            if (!retireCommitted(context, record)) {
                return DatabaseRepairManager.Outcome(
                    false, quarantine.takeIf { record.originalExisted }?.path,
                    "database restored; cleanup remains pending"
                )
            }
            faultInjector.after(Boundary.AFTER_JOURNAL_DELETE)
            return DatabaseRepairManager.Outcome(
                true, quarantine.takeIf { record.originalExisted }?.path, null
            )
        } catch (e: Exception) {
            val record = DirectDatabaseRestoreJournal.read(context, type)
            if (record != null) {
                return failAndRecover(context, record, e.javaClass.simpleName)
            }
            return DatabaseRepairManager.Outcome(
                false, quarantine.takeIf { it.exists() }?.path, e.javaClass.simpleName
            )
        } finally {
            originalKey?.fill(0)
            intendedKey.fill(0)
            if (!DirectDatabaseRestoreJournal.root(context, type).exists()) {
                cleanupFileSet(staged)
                clearRecoveryKeys(context, type)
            }
        }
    }

    private fun recoverOne(context: Context, type: BackupType): Boolean {
        val record = DirectDatabaseRestoreJournal.read(context, type) ?: run {
            // Atomic journal publication means absence of state.json proves
            // the destructive boundary was never crossed. A present but
            // undecodable state file remains a hard, fail-closed stop.
            if (DirectDatabaseRestoreJournal.stateFile(context, type).exists()) return false
            val root = DirectDatabaseRestoreJournal.root(context, type)
            if (root.exists() && !root.deleteRecursively()) return false
            clearRecoveryKeys(context, type)
            cleanupUnpublishedStages(context, type)
            return true
        }
        val active = File(record.activePath)
        val staged = File(record.stagedPath)
        val quarantine = File(record.quarantinePath)
        val currentKey = currentKeyClassification(context, record)
        val activeState = when {
            DirectDatabaseRestoreJournal.matches(active, record.installedFiles) ->
                DirectDatabaseRestoreRecoveryPlanner.FileState.INSTALLED
            DirectDatabaseRestoreJournal.matches(active, record.originalFiles) ->
                DirectDatabaseRestoreRecoveryPlanner.FileState.ORIGINAL
            !active.exists() && sidecars.none { File(active.path + it).exists() } ->
                DirectDatabaseRestoreRecoveryPlanner.FileState.ABSENT
            else -> DirectDatabaseRestoreRecoveryPlanner.FileState.UNKNOWN
        }
        val decision = DirectDatabaseRestoreRecoveryPlanner.decide(
            DirectDatabaseRestoreRecoveryPlanner.Evidence(
                phase = record.phase,
                originalExisted = record.originalExisted,
                active = activeState,
                stagedInstalledCopyValid = DirectDatabaseRestoreJournal.matches(
                    staged, record.installedFiles
                ),
                quarantineOriginalCopyValid = DirectDatabaseRestoreJournal.matches(
                    quarantine, record.originalFiles
                ),
                key = currentKey
            )
        )
        return when (decision) {
            DirectDatabaseRestoreRecoveryPlanner.Decision.FINISH_INSTALL ->
                finishInstall(context, record)
            DirectDatabaseRestoreRecoveryPlanner.Decision.ROLLBACK ->
                rollback(context, record)
            DirectDatabaseRestoreRecoveryPlanner.Decision.CLEAN_COMMITTED -> {
                val intended = readRequiredSecret(context, record, intended = true) ?: return false
                try {
                    verifyInstalledLowLevel(context, record, intended)
                    verifyThroughNormalStore(context, record.type)
                    retireCommitted(context, record)
                } catch (_: Exception) {
                    false
                } finally {
                    intended.fill(0)
                }
            }
            DirectDatabaseRestoreRecoveryPlanner.Decision.BLOCK -> false
        }
    }

    private fun finishInstall(
        context: Context,
        initial: DirectDatabaseRestoreJournal.Record
    ): Boolean {
        var record = initial
        val active = File(record.activePath)
        val staged = File(record.stagedPath)
        val intended = readRequiredSecret(context, record, intended = true) ?: return false
        return try {
            if (!installKey(context, record.type, record.intendedKey, intended)) return false
            record = record.copy(phase = Phase.KEY_SWITCHED)
            if (!DirectDatabaseRestoreJournal.write(context, record)) return false
            if (!DirectDatabaseRestoreJournal.matches(active, record.installedFiles)) {
                if (!DirectDatabaseRestoreJournal.matches(staged, record.installedFiles)) return false
                if (!removeSidecars(active) || !DurableRecoveryFileOps.atomicReplace(staged, active)) return false
            }
            record = record.copy(phase = Phase.FILE_INSTALLED)
            if (!DirectDatabaseRestoreJournal.write(context, record)) return false
            verifyInstalledLowLevel(context, record, intended)
            record = record.copy(
                installedFiles = DirectDatabaseRestoreJournal.evidence(active) ?: return false,
                phase = Phase.VERIFIED
            )
            if (!DirectDatabaseRestoreJournal.write(context, record)) return false
            verifyThroughNormalStore(context, record.type)
            DatabaseRepairManager.invalidateStore(context, record.type)
            record = record.copy(
                installedFiles = DirectDatabaseRestoreJournal.evidence(active) ?: return false,
                phase = Phase.VERIFIED
            )
            if (!DirectDatabaseRestoreJournal.write(context, record)) return false
            record = record.copy(phase = Phase.COMMITTED)
            DirectDatabaseRestoreJournal.write(context, record) && retireCommitted(context, record)
        } catch (_: Exception) {
            rollback(context, record)
        } finally {
            intended.fill(0)
        }
    }

    private fun rollback(
        context: Context,
        record: DirectDatabaseRestoreJournal.Record
    ): Boolean {
        val active = File(record.activePath)
        val quarantine = File(record.quarantinePath)
        DatabaseRepairManager.invalidateStore(context, record.type)
        if (record.originalExisted) {
            if (!DirectDatabaseRestoreJournal.matches(quarantine, record.originalFiles)) return false
            val rollback = File(active.parentFile, ".${active.name}.direct-restore-${record.operationId}.rollback")
            cleanupFileSet(rollback)
            for (file in record.originalFiles) {
                if (!DurableRecoveryFileOps.copyAndSync(
                        File(quarantine.path + file.suffix), File(rollback.path + file.suffix)
                    )
                ) return false
            }
            if (!removeSidecars(active)) return false
            for (file in record.originalFiles.filter { it.suffix.isNotEmpty() }) {
                if (!DurableRecoveryFileOps.atomicReplace(
                        File(rollback.path + file.suffix), File(active.path + file.suffix)
                    )
                ) return false
            }
            if (!DurableRecoveryFileOps.atomicReplace(rollback, active)) return false
            if (!DirectDatabaseRestoreJournal.matches(active, record.originalFiles)) return false
        } else if (!cleanupFileSet(active)) {
            return false
        }
        if (!restoreOriginalKey(context, record)) return false
        DatabaseRepairManager.invalidateStore(context, record.type)
        return retireRolledBack(context, record)
    }

    private fun failAndRecover(
        context: Context,
        record: DirectDatabaseRestoreJournal.Record,
        detail: String
    ): DatabaseRepairManager.Outcome {
        val rolledBack = rollback(context, record)
        return DatabaseRepairManager.Outcome(
            false,
            File(record.quarantinePath).takeIf { record.originalExisted }?.path,
            if (rolledBack) detail else "$detail; startup recovery required"
        )
    }

    private fun retireCommitted(
        context: Context,
        record: DirectDatabaseRestoreJournal.Record
    ): Boolean {
        DatabaseHealthState.clearDegraded(
            context,
            record.type,
            when (record.sourceKind) {
                DirectDatabaseRestoreJournal.SourceKind.DATABASE_SNAPSHOT ->
                    "restored from verified backup"
                DirectDatabaseRestoreJournal.SourceKind.MEMORY_JSON ->
                    "damaged file replaced for restore"
            }
        )
        if (!DirectDatabaseRestoreJournal.delete(context, record.type)) return false
        cleanupFileSet(File(record.stagedPath))
        clearRecoveryKeys(context, record.type)
        return true
    }

    private fun retireRolledBack(
        context: Context,
        record: DirectDatabaseRestoreJournal.Record
    ): Boolean {
        if (!DirectDatabaseRestoreJournal.delete(context, record.type)) return false
        cleanupFileSet(File(record.stagedPath))
        clearRecoveryKeys(context, record.type)
        return true
    }

    private fun stageQuarantine(
        context: Context,
        active: File,
        quarantine: File
    ): List<FileEvidence>? {
        if (!active.exists()) return emptyList()
        cleanupFileSet(quarantine)
        val sources = listOf("") + sidecars
        for (suffix in sources) {
            val source = File(active.path + suffix)
            if (!source.exists()) continue
            if (!DurableRecoveryFileOps.copyAndSync(source, File(quarantine.path + suffix))) {
                cleanupFileSet(quarantine)
                return null
            }
        }
        val original = DirectDatabaseRestoreJournal.evidence(active) ?: return null
        val copied = DirectDatabaseRestoreJournal.evidence(quarantine) ?: return null
        if (original != copied || copied.none { it.suffix.isEmpty() }) {
            cleanupFileSet(quarantine)
            return null
        }
        for (entry in copied) {
            SnapshotRegistry.record(
                context,
                File(quarantine.path + entry.suffix).name,
                active.name + entry.suffix,
                SnapshotRegistry.ORIGIN_PRE_RESTORE,
                "pre_restore"
            )
        }
        return copied
    }

    private fun stageIncoming(
        type: BackupType,
        source: File,
        staged: File,
        intendedKey: ByteArray,
        sourcePlaintext: Boolean
    ): Boolean {
        cleanupFileSet(staged)
        if (type == BackupType.LOREBOOK && sourcePlaintext) {
            if (!encryptPlaintextLorebook(source, staged, intendedKey)) return false
        } else if (!DurableRecoveryFileOps.copyAndSync(source, staged)) {
            return false
        }
        if (!removeSidecars(staged)) return false
        return try {
            verifySnapshot(type, staged, intendedKey.takeIf { type != BackupType.USER_IMAGE }, false)
            removeSidecars(staged) && DurableRecoveryFileOps.syncFile(staged)
        } catch (_: Exception) {
            false
        }
    }

    private fun encryptPlaintextLorebook(source: File, staged: File, key: ByteArray): Boolean {
        if (key.isEmpty()) return false
        LoreBookEncryption.loadLibrary()
        var encrypted: CipherDatabase? = null
        return try {
            val plaintext = CipherDatabase.openDatabase(
                source.path, "", null, CipherDatabase.OPEN_READONLY, null, null
            )
            val version = try { plaintext.version } finally { plaintext.close() }
            encrypted = CipherDatabase.openDatabase(
                staged.path, key, null, CipherDatabase.CREATE_IF_NECESSARY, null, null
            )
            encrypted.rawExecSQL("ATTACH DATABASE ? AS plaintext KEY ''", source.path)
            encrypted.rawExecSQL("SELECT sqlcipher_export('main', 'plaintext')")
            try { encrypted.rawExecSQL("DETACH DATABASE plaintext") } catch (_: Exception) { }
            encrypted.version = version
            encrypted.close()
            encrypted = null
            DurableRecoveryFileOps.syncFile(staged)
        } catch (_: Exception) {
            false
        } finally {
            runCatching { encrypted?.close() }
        }
    }

    private fun verifySnapshot(
        type: BackupType,
        file: File,
        key: ByteArray?,
        plaintext: Boolean
    ) {
        when (type) {
            BackupType.MEMORY -> RecoveryBackupManager.integrityCheckCipher(
                file, key ?: throw IllegalStateException("memory key unavailable"), "meta"
            )
            BackupType.LOREBOOK -> if (plaintext) {
                RecoveryBackupManager.integrityCheckPlain(file, "memory_entries")
            } else {
                RecoveryBackupManager.integrityCheckCipher(
                    file, key ?: throw IllegalStateException("lorebook key unavailable"),
                    "memory_entries"
                )
            }
            BackupType.USER_IMAGE -> RecoveryBackupManager.integrityCheckPlain(
                file, "profile_images"
            )
            BackupType.CHATS -> throw IllegalArgumentException("chats are not a database")
        }
    }

    private fun verifyInstalledLowLevel(
        context: Context,
        record: DirectDatabaseRestoreJournal.Record,
        intendedKey: ByteArray
    ) {
        val active = File(record.activePath)
        verifySnapshot(
            record.type,
            active,
            intendedKey.takeIf { record.type != BackupType.USER_IMAGE },
            false
        )
        DatabaseRepairManager.invalidateStore(context, record.type)
        if (!removeSidecars(active)) throw IllegalStateException("database sidecars could not be normalized")
    }

    private fun verifyThroughNormalStore(context: Context, type: BackupType) {
        val problem = when (type) {
            BackupType.MEMORY -> MemoryStore.getInstance(context).integrityCheck()
            BackupType.LOREBOOK -> LoreBookStore.getInstance(context).integrityCheck()
            BackupType.USER_IMAGE -> ProfileImageDb.getInstance(context).integrityCheck()
            BackupType.CHATS -> throw IllegalArgumentException("chats are not a database")
        }
        if (problem != null) throw IllegalStateException("normal store integrity check failed")
    }

    private fun intendedKey(
        type: BackupType,
        sourceKey: ByteArray?,
        sourcePlaintext: Boolean,
        originalKey: ByteArray?
    ): ByteArray? = when (type) {
        BackupType.MEMORY -> sourceKey?.copyOf()
        BackupType.LOREBOOK -> when {
            !sourcePlaintext -> sourceKey?.copyOf()
            originalKey != null -> originalKey.copyOf()
            else -> ByteArray(32).also(SecureRandom()::nextBytes)
        }
        BackupType.USER_IMAGE -> ByteArray(0)
        BackupType.CHATS -> null
    }

    private fun keyState(context: Context, type: BackupType): DatabaseKeys.StoredKeyState =
        when (type) {
            BackupType.MEMORY -> DatabaseKeys.readStoredState(context, DatabaseKeys.KEY_MEMORY)
            BackupType.LOREBOOK -> DatabaseKeys.readStoredState(context, DatabaseKeys.KEY_LOREBOOK)
            BackupType.USER_IMAGE -> DatabaseKeys.StoredKeyState.Absent
            BackupType.CHATS -> DatabaseKeys.StoredKeyState.Unavailable
        }

    private fun keyEvidence(type: BackupType, state: DatabaseKeys.StoredKeyState): KeyEvidence =
        if (type == BackupType.USER_IMAGE) {
            KeyEvidence(KeyKind.NOT_APPLICABLE)
        } else when (state) {
            DatabaseKeys.StoredKeyState.Absent -> KeyEvidence(KeyKind.ABSENT)
            is DatabaseKeys.StoredKeyState.Present -> DirectDatabaseRestoreJournal.keyEvidence(state.value)
            DatabaseKeys.StoredKeyState.Unavailable -> throw IllegalStateException("key unavailable")
        }

    private fun keyEvidence(type: BackupType, key: ByteArray): KeyEvidence =
        if (type == BackupType.USER_IMAGE) KeyEvidence(KeyKind.NOT_APPLICABLE)
        else DirectDatabaseRestoreJournal.keyEvidence(key)

    private fun persistRecoveryKeys(
        context: Context,
        type: BackupType,
        original: ByteArray?,
        intended: ByteArray
    ): Boolean {
        if (type == BackupType.USER_IMAGE) return true
        clearRecoveryKeys(context, type)
        if (original != null && !DatabaseKeys.persistRestoreSecret(context, slot(type, false), original)) {
            return false
        }
        return DatabaseKeys.persistRestoreSecret(context, slot(type, true), intended)
    }

    private fun clearRecoveryKeys(context: Context, type: BackupType) {
        if (type == BackupType.USER_IMAGE) return
        DatabaseKeys.clearRestoreSecret(context, slot(type, false))
        DatabaseKeys.clearRestoreSecret(context, slot(type, true))
    }

    private fun installKey(
        context: Context,
        type: BackupType,
        evidence: KeyEvidence,
        key: ByteArray
    ): Boolean = when (evidence.kind) {
        KeyKind.NOT_APPLICABLE -> true
        KeyKind.ABSENT -> DatabaseKeys.clearExisting(context, keyName(type))
        KeyKind.PRESENT -> DirectDatabaseRestoreJournal.keyMatches(key, evidence) &&
            DatabaseKeys.replaceExisting(context, keyName(type), key)
    }

    private fun restoreOriginalKey(
        context: Context,
        record: DirectDatabaseRestoreJournal.Record
    ): Boolean {
        return when (record.originalKey.kind) {
            KeyKind.NOT_APPLICABLE -> true
            KeyKind.ABSENT -> DatabaseKeys.clearExisting(context, keyName(record.type))
            KeyKind.PRESENT -> {
                val original = DatabaseKeys.readRestoreSecret(context, slot(record.type, false))
                    ?: return false
                try {
                    DirectDatabaseRestoreJournal.keyMatches(original, record.originalKey) &&
                        DatabaseKeys.replaceExisting(context, keyName(record.type), original)
                } finally {
                    original.fill(0)
                }
            }
        }
    }

    private fun readRequiredSecret(
        context: Context,
        record: DirectDatabaseRestoreJournal.Record,
        intended: Boolean
    ): ByteArray? {
        val evidence = if (intended) record.intendedKey else record.originalKey
        if (evidence.kind == KeyKind.NOT_APPLICABLE) return ByteArray(0)
        if (evidence.kind == KeyKind.ABSENT) return ByteArray(0)
        val secret = DatabaseKeys.readRestoreSecret(context, slot(record.type, intended)) ?: return null
        return secret.takeIf { DirectDatabaseRestoreJournal.keyMatches(it, evidence) }
            ?: run { secret.fill(0); null }
    }

    private fun currentKeyClassification(
        context: Context,
        record: DirectDatabaseRestoreJournal.Record
    ): DirectDatabaseRestoreRecoveryPlanner.KeyState {
        if (record.intendedKey.kind == KeyKind.NOT_APPLICABLE) {
            return DirectDatabaseRestoreRecoveryPlanner.KeyState.NOT_APPLICABLE
        }
        val state = keyState(context, record.type)
        val current = (state as? DatabaseKeys.StoredKeyState.Present)?.value
        return try {
            when {
                state is DatabaseKeys.StoredKeyState.Unavailable ->
                    DirectDatabaseRestoreRecoveryPlanner.KeyState.UNKNOWN
                record.intendedKey.kind == KeyKind.ABSENT && current == null ->
                    DirectDatabaseRestoreRecoveryPlanner.KeyState.INTENDED
                DirectDatabaseRestoreJournal.keyMatches(current, record.intendedKey) ->
                    DirectDatabaseRestoreRecoveryPlanner.KeyState.INTENDED
                record.originalKey.kind == KeyKind.ABSENT && current == null ->
                    DirectDatabaseRestoreRecoveryPlanner.KeyState.ORIGINAL
                DirectDatabaseRestoreJournal.keyMatches(current, record.originalKey) ->
                    DirectDatabaseRestoreRecoveryPlanner.KeyState.ORIGINAL
                else -> DirectDatabaseRestoreRecoveryPlanner.KeyState.UNKNOWN
            }
        } finally {
            current?.fill(0)
        }
    }

    private fun removeSidecars(base: File): Boolean = sidecars.all {
        DurableRecoveryFileOps.deleteAndSync(File(base.path + it))
    }

    private fun cleanupFileSet(base: File): Boolean =
        removeSidecars(base) && DurableRecoveryFileOps.deleteAndSync(base)

    private fun cleanupUnpublishedStages(context: Context, type: BackupType) {
        val active = context.getDatabasePath(databaseName(type))
        val prefix = ".${active.name}.direct-restore-"
        active.parentFile?.listFiles()?.filter {
            it.name.startsWith(prefix) &&
                (it.name.endsWith(".stage") || it.name.contains(".stage-"))
        }?.forEach { runCatching { it.delete() } }
    }

    private fun quarantineFile(context: Context, active: File, operationId: String): File {
        val dir = File(context.filesDir, "storage_recovery")
        val stem = active.name.removeSuffix(".db")
        return File(dir, "$stem.pre-restore-${LocalDate.now()}-$operationId.db")
    }

    private fun keyName(type: BackupType): String = when (type) {
        BackupType.MEMORY -> DatabaseKeys.KEY_MEMORY
        BackupType.LOREBOOK -> DatabaseKeys.KEY_LOREBOOK
        else -> throw IllegalArgumentException("database has no key")
    }

    private fun slot(type: BackupType, intended: Boolean): String =
        type.key + if (intended) "_intended" else "_original"

    private fun databaseName(type: BackupType): String = when (type) {
        BackupType.MEMORY -> MemoryStore.DATABASE_NAME
        BackupType.LOREBOOK -> LoreBookStore.DATABASE_NAME
        BackupType.USER_IMAGE -> ProfileImageDb.DATABASE_NAME
        BackupType.CHATS -> throw IllegalArgumentException("chats are not a database")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
