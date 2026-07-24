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
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.teslasoft.assistant.preferences.backup.portable.PackageCrypto
import org.teslasoft.assistant.preferences.backup.portable.PortablePackage
import org.teslasoft.assistant.preferences.backup.portable.PortablePackageFormat
import org.teslasoft.assistant.preferences.backup.portable.PortableStaging
import org.teslasoft.assistant.preferences.backup.portable.RecoveryCode
import org.teslasoft.assistant.preferences.memory.DatabaseKeys
import org.teslasoft.assistant.preferences.memory.MemorySeedCodec
import java.io.File
import java.time.Instant

/**
 * One restore coordinator with format-specific readers. The UI deliberately
 * never exposes these formats:
 *
 *  - automatic per-type artifacts (`memory-<millis>.dbbackup`, etc.) are raw
 *    same-install snapshots and require this installation's database keys;
 *  - portable v2 Recovery Backups are combined packages, protected or
 *    unencrypted, and carry the database keys needed on another installation;
 *  - the older internal Memory JSON chain remains a Memory-only fallback.
 *
 * Every result is prepared and integrity-checked in private staging. Nothing
 * in this object touches a live database until [restore] is called after the
 * user's final confirmation.
 */
object DatabaseRestoreManager {

    enum class Failure {
        NO_APPROPRIATE_DATABASE,
        NO_VALID_DATABASES,
        /** Raw automatic artifacts have no authenticated key fingerprint.
         *  A SQLCipher open failure therefore cannot be honestly split into
         *  "different installation" versus "damaged file." Never label it
         *  corrupt without proof. */
        AUTOMATIC_KEY_OR_DAMAGE,
        DAMAGED_OR_ALTERED,
        UNSUPPORTED_PROTECTION,
        INVALID_RECOVERY_CODE,
        WRONG_RECOVERY_KEY,
        SOURCE_UNAVAILABLE,
        RESTORE_FAILED
    }

    enum class Kind { DATABASE_SNAPSHOT, MEMORY_JSON }

    data class Prepared(
        val type: BackupType,
        val kind: Kind,
        val stagedFile: File,
        val sourceKey: ByteArray?,
        val sourcePlaintext: Boolean,
        val backupAtMillis: Long,
        private val stagingRoot: File
    ) {
        fun discard() {
            sourceKey?.fill(0)
            PortableStaging.delete(stagingRoot)
        }
    }

    data class PendingCode(
        val requestedType: BackupType,
        val packageFile: File,
        val backupAtMillis: Long,
        internal val stagingRoot: File
    ) {
        fun discard() = PortableStaging.delete(stagingRoot)
    }

    sealed class PrepareResult {
        data class Ready(val prepared: Prepared) : PrepareResult()
        data class NeedsRecoveryCode(val pending: PendingCode) : PrepareResult()
        /** The selected source contains exactly one different database.
         *  [prepared] is already verified, but is not restored unless the user
         *  accepts the mismatch and then accepts the normal final confirmation. */
        data class Mismatch(val actualType: BackupType, val prepared: Prepared) : PrepareResult()
        data class Failed(val reason: Failure) : PrepareResult()
    }

    private data class TreeEntry(val uri: Uri, val name: String, val atMillis: Long)

    /** Find the newest usable automatic artifact in the current configured
     *  folder. Memory also falls back to the existing internal JSON chain.
     *  A damaged candidate is skipped; if none work, the most informative
     *  failure is returned instead of silently offering an empty database. */
    fun prepareLastGood(context: Context, type: BackupType): PrepareResult {
        val autoUri = RecoveryBackupState.getAutoFolderUri(context)?.let {
            try { Uri.parse(it) } catch (_: Exception) { null }
        }
        val automatic = if (autoUri != null) listAutomaticEntries(context, autoUri, type) else emptyList()
        val json = if (type == BackupType.MEMORY) {
            DatabaseRevertManager.listCandidates(context, type).map {
                it.backupAtMillis to it.file
            }
        } else emptyList()

        data class Candidate(val at: Long, val automatic: TreeEntry?, val json: File?)
        val candidates = ArrayList<Candidate>()
        automatic.forEach { candidates.add(Candidate(it.atMillis, it, null)) }
        json.forEach { candidates.add(Candidate(it.first, null, it.second)) }

        var sawAutomaticKeyOrDamage = false
        for (candidate in candidates.sortedByDescending { it.at }) {
            if (candidate.automatic != null) {
                when (val result = prepareAutomaticUri(context, type, candidate.automatic.uri, candidate.at)) {
                    is PrepareResult.Ready -> return result
                    is PrepareResult.Failed -> {
                        if (result.reason == Failure.AUTOMATIC_KEY_OR_DAMAGE) {
                            sawAutomaticKeyOrDamage = true
                        }
                    }
                    else -> Unit
                }
            } else if (candidate.json != null) {
                val root = PortableStaging.newRunDir(context)
                val staged = File(root, "memory.json")
                try {
                    candidate.json.copyTo(staged, overwrite = true)
                    MemorySeedCodec.parse(staged.readText())
                    return PrepareResult.Ready(
                        Prepared(type, Kind.MEMORY_JSON, staged, null, false, candidate.at, root)
                    )
                } catch (_: Exception) {
                    PortableStaging.delete(root)
                }
            }
        }
        return PrepareResult.Failed(
            if (sawAutomaticKeyOrDamage) Failure.AUTOMATIC_KEY_OR_DAMAGE
            else Failure.NO_VALID_DATABASES
        )
    }

    /** A user-picked folder may contain either automatic per-type artifacts,
     *  portable Recovery Backups, or moved internal Memory JSON exports.
     *  Recognize all actual database-backup outputs and walk newest-to-oldest.
     *  A valid backup for another database is skipped rather than producing a
     *  mismatch dialog: the user picked a folder, not that individual file. */
    fun prepareFolder(context: Context, requestedType: BackupType, treeUri: Uri): PrepareResult {
        val all = listTreeEntries(context, treeUri)
        if (all.isEmpty()) return PrepareResult.Failed(Failure.NO_VALID_DATABASES)

        var sawRecognizedDatabase = false
        var sawRequestedDatabase = false
        var sawKeyOrDamage = false
        var sawDamaged = false
        for (entry in all.sortedByDescending { it.atMillis }) {
            val namedType = automaticTypeFromName(entry.name)
            val isPortable = namedType == null && hasPortableMagic(context, entry.uri)
            val isMemoryJson = namedType == null &&
                entry.name.endsWith(".json", ignoreCase = true)
            if (namedType == null && !isPortable && !isMemoryJson) continue

            if (namedType != null) {
                sawRecognizedDatabase = true
                if (namedType != requestedType) continue
                sawRequestedDatabase = true
            }

            val result = if (namedType != null) {
                prepareAutomaticUri(context, requestedType, entry.uri, entry.atMillis)
            } else {
                prepareFile(context, requestedType, entry.uri)
            }
            when (result) {
                is PrepareResult.Ready,
                is PrepareResult.NeedsRecoveryCode -> return result
                is PrepareResult.Mismatch -> {
                    sawRecognizedDatabase = true
                    result.prepared.discard()
                }
                is PrepareResult.Failed -> {
                    when (result.reason) {
                        Failure.NO_APPROPRIATE_DATABASE -> sawRecognizedDatabase = true
                        Failure.AUTOMATIC_KEY_OR_DAMAGE -> {
                            sawRequestedDatabase = true
                            sawKeyOrDamage = true
                        }
                        Failure.DAMAGED_OR_ALTERED -> {
                            sawRequestedDatabase = true
                            sawDamaged = true
                        }
                        else -> Unit
                    }
                }
            }
        }
        return PrepareResult.Failed(
            when {
                sawKeyOrDamage -> Failure.AUTOMATIC_KEY_OR_DAMAGE
                sawDamaged -> Failure.DAMAGED_OR_ALTERED
                sawRecognizedDatabase && !sawRequestedDatabase ->
                    Failure.NO_APPROPRIATE_DATABASE
                else -> Failure.NO_VALID_DATABASES
            }
        )
    }

    /** Copy one user-selected document to private staging, identify it by
     *  contents where the format permits, and dispatch to the proper reader. */
    fun prepareFile(context: Context, requestedType: BackupType, uri: Uri): PrepareResult {
        val root = PortableStaging.newRunDir(context)
        val displayName = queryDisplayName(context, uri) ?: "selected.backup"
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        val local = File(root, safeName.ifEmpty { "selected.backup" })
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                local.outputStream().buffered().use { input.copyTo(it) }
            } ?: run {
                PortableStaging.delete(root)
                return PrepareResult.Failed(Failure.SOURCE_UNAVAILABLE)
            }
        } catch (_: Exception) {
            PortableStaging.delete(root)
            return PrepareResult.Failed(Failure.SOURCE_UNAVAILABLE)
        }
        return prepareLocalFile(context, requestedType, local, root)
    }

    private fun prepareLocalFile(
        context: Context,
        requestedType: BackupType,
        local: File,
        root: File
    ): PrepareResult {
        return when (val header = PortablePackageFormat.readHeader(local)) {
            is PortablePackageFormat.HeaderResult.Ok -> {
                val at = parseIsoMillis(header.header.createdAtIso) ?: local.lastModified()
                if (header.header.protection == PortablePackageFormat.PROTECTION_ENCRYPTED) {
                    PrepareResult.NeedsRecoveryCode(PendingCode(requestedType, local, at, root))
                } else {
                    preparePortableDecoded(context, requestedType, local, root, ByteArray(0), at)
                }
            }
            is PortablePackageFormat.HeaderResult.Invalid -> {
                if (header.error != PortablePackageFormat.RestoreError.NOT_A_V2_PACKAGE) {
                    PortableStaging.delete(root)
                    PrepareResult.Failed(mapPortableFailure(header.error))
                } else {
                    prepareNonPackageFile(context, requestedType, local, root)
                }
            }
        }
    }

    /** Retry a protected portable package with the complete Recovery Code. */
    fun unlockWithCode(context: Context, pending: PendingCode, code: String): PrepareResult {
        val decoded = RecoveryCode.decode(code)
        if (decoded !is RecoveryCode.DecodeResult.Ok) {
            return PrepareResult.Failed(Failure.INVALID_RECOVERY_CODE)
        }
        return try {
            val result = preparePortableDecoded(
                context, pending.requestedType, pending.packageFile,
                pending.stagingRoot, decoded.secret, pending.backupAtMillis
            )
            if (result is PrepareResult.Failed &&
                result.reason != Failure.INVALID_RECOVERY_CODE &&
                result.reason != Failure.WRONG_RECOVERY_KEY
            ) {
                pending.discard()
            }
            result
        } finally {
            PackageCrypto.wipe(decoded.secret)
        }
    }

    private fun preparePortableDecoded(
        context: Context,
        requestedType: BackupType,
        packageFile: File,
        root: File,
        recoverySecret: ByteArray,
        atMillis: Long
    ): PrepareResult {
        val decoded = PortablePackage.decodeWithSecret(packageFile, recoverySecret, root)
        if (decoded !is PortablePackage.DecodeResult.Ok) {
            val failure = mapPortableFailure(
                (decoded as PortablePackage.DecodeResult.Failed).error
            )
            if (failure != Failure.WRONG_RECOVERY_KEY) PortableStaging.delete(root)
            return PrepareResult.Failed(failure)
        }
        val validated = PortablePackage.validateAndExtract(decoded.innerZip, root)
        if (validated !is PortablePackage.ValidateResult.Ok) {
            PortableStaging.delete(root)
            return PrepareResult.Failed(
                mapPortableFailure((validated as PortablePackage.ValidateResult.Failed).error)
            )
        }

        val databases = validated.artifacts.mapNotNull { artifact ->
            typeForPortableEntry(artifact.entryName)?.let { it to artifact }
        }
        if (databases.isEmpty()) {
            PortableStaging.delete(root)
            return PrepareResult.Failed(Failure.NO_VALID_DATABASES)
        }
        val selected = databases.firstOrNull { it.first == requestedType }
        if (selected != null) {
            return preparePortableArtifact(selected.first, selected.second, atMillis, root)
        }
        if (databases.size == 1) {
            val only = databases.single()
            return when (val prepared = preparePortableArtifact(only.first, only.second, atMillis, root)) {
                is PrepareResult.Ready -> PrepareResult.Mismatch(only.first, prepared.prepared)
                else -> prepared
            }
        }
        PortableStaging.delete(root)
        return PrepareResult.Failed(Failure.NO_APPROPRIATE_DATABASE)
    }

    private fun preparePortableArtifact(
        type: BackupType,
        artifact: PortablePackage.ValidatedArtifact,
        atMillis: Long,
        root: File
    ): PrepareResult {
        val sourcePlaintext =
            artifact.keySemantics == PortablePackage.KEY_SEMANTICS_PLAINTEXT
        val key = if (artifact.type == "sqlcipher-db" && !sourcePlaintext) {
            artifact.databaseKeyHex?.let { decodeHex(it) }
                ?: run {
                    PortableStaging.delete(root)
                    return PrepareResult.Failed(Failure.DAMAGED_OR_ALTERED)
                }
        } else null
        return try {
            when (type) {
                BackupType.MEMORY ->
                    RecoveryBackupManager.integrityCheckCipher(artifact.stagedFile, key)
                BackupType.LOREBOOK -> if (sourcePlaintext) {
                    RecoveryBackupManager.integrityCheckPlain(artifact.stagedFile)
                } else {
                    RecoveryBackupManager.integrityCheckCipher(artifact.stagedFile, key)
                }
                BackupType.USER_IMAGE ->
                    RecoveryBackupManager.integrityCheckPlain(artifact.stagedFile)
                BackupType.CHATS ->
                    return PrepareResult.Failed(Failure.NO_APPROPRIATE_DATABASE)
            }
            PrepareResult.Ready(
                Prepared(
                    type, Kind.DATABASE_SNAPSHOT, artifact.stagedFile, key,
                    sourcePlaintext, atMillis, root
                )
            )
        } catch (_: Exception) {
            key?.fill(0)
            PortableStaging.delete(root)
            PrepareResult.Failed(Failure.DAMAGED_OR_ALTERED)
        }
    }

    private fun prepareNonPackageFile(
        context: Context,
        requestedType: BackupType,
        local: File,
        root: File
    ): PrepareResult {
        // A selected automatic artifact's generated name is helpful metadata,
        // but verification still decides whether it is usable.
        val namedType = automaticTypeFromName(local.name)
        if (namedType != null && namedType != requestedType) {
            return when (val actual = prepareAutomaticLocal(context, namedType, local, root, local.lastModified())) {
                is PrepareResult.Ready -> PrepareResult.Mismatch(namedType, actual.prepared)
                else -> actual
            }
        }

        // The remaining recognized standalone format is the internal Memory
        // JSON export. It can be selected manually even when restoring a
        // healthy database.
        if (looksLikeJson(local)) {
            try {
                MemorySeedCodec.parse(local.readText())
                val prepared = Prepared(
                    BackupType.MEMORY, Kind.MEMORY_JSON, local, null, false,
                    local.lastModified(), root
                )
                return if (requestedType == BackupType.MEMORY) PrepareResult.Ready(prepared)
                else PrepareResult.Mismatch(BackupType.MEMORY, prepared)
            } catch (_: Exception) {
                PortableStaging.delete(root)
                return PrepareResult.Failed(Failure.NO_VALID_DATABASES)
            }
        }
        return prepareAutomaticLocal(context, requestedType, local, root, local.lastModified())
    }

    private fun prepareAutomaticUri(
        context: Context,
        type: BackupType,
        uri: Uri,
        atMillis: Long
    ): PrepareResult {
        val root = PortableStaging.newRunDir(context)
        val local = File(root, "automatic.dbbackup")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                local.outputStream().buffered().use { input.copyTo(it) }
            } ?: throw IllegalStateException("unreadable source")
            prepareAutomaticLocal(context, type, local, root, atMillis)
        } catch (_: Exception) {
            PortableStaging.delete(root)
            PrepareResult.Failed(Failure.SOURCE_UNAVAILABLE)
        }
    }

    private fun prepareAutomaticLocal(
        context: Context,
        type: BackupType,
        local: File,
        root: File,
        atMillis: Long
    ): PrepareResult {
        return try {
            val key = when (type) {
                BackupType.MEMORY -> DatabaseKeys.readExisting(context, DatabaseKeys.KEY_MEMORY)
                BackupType.LOREBOOK -> DatabaseKeys.readExisting(context, DatabaseKeys.KEY_LOREBOOK)
                else -> null
            }
            when (type) {
                BackupType.MEMORY, BackupType.LOREBOOK -> {
                    if (key == null) {
                        PortableStaging.delete(root)
                        return PrepareResult.Failed(Failure.AUTOMATIC_KEY_OR_DAMAGE)
                    }
                    RecoveryBackupManager.integrityCheckCipher(local, key)
                }
                BackupType.USER_IMAGE -> RecoveryBackupManager.integrityCheckPlain(local)
                BackupType.CHATS -> {
                    PortableStaging.delete(root)
                    return PrepareResult.Failed(Failure.NO_APPROPRIATE_DATABASE)
                }
            }
            PrepareResult.Ready(
                Prepared(type, Kind.DATABASE_SNAPSHOT, local, key, false, atMillis, root)
            )
        } catch (_: Exception) {
            PortableStaging.delete(root)
            PrepareResult.Failed(
                if (type == BackupType.USER_IMAGE) Failure.DAMAGED_OR_ALTERED
                else Failure.AUTOMATIC_KEY_OR_DAMAGE
            )
        }
    }

    fun restore(context: Context, prepared: Prepared): DatabaseRepairManager.Outcome =
        when (prepared.kind) {
            Kind.MEMORY_JSON -> DatabaseRevertManager.restoreMemory(
                context,
                DatabaseRevertManager.Verified(
                    DatabaseRevertManager.Candidate(prepared.stagedFile, prepared.backupAtMillis)
                )
            )
            Kind.DATABASE_SNAPSHOT -> DatabaseRepairManager.restoreSnapshot(
                context, prepared.type, prepared.stagedFile,
                prepared.sourceKey, prepared.sourcePlaintext
            )
        }

    private fun listAutomaticEntries(
        context: Context,
        treeUri: Uri,
        requestedType: BackupType?
    ): List<TreeEntry> = listTreeEntries(context, treeUri).filter { entry ->
        val type = automaticTypeFromName(entry.name)
        type != null && type != BackupType.CHATS &&
            (requestedType == null || type == requestedType)
    }

    private fun listTreeEntries(
        context: Context,
        treeUri: Uri
    ): List<TreeEntry> {
        return try {
            val resolver = context.contentResolver
            val parentId = DocumentsContract.getTreeDocumentId(treeUri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val out = ArrayList<TreeEntry>()
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = if (cursor.isNull(3)) "" else cursor.getString(3)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val modified = if (cursor.isNull(2)) 0L else cursor.getLong(2)
                    val at = automaticMillisFromName(name) ?: modified
                    out.add(
                        TreeEntry(
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                            name,
                            at
                        )
                    )
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun hasPortableMagic(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val actual = ByteArray(PortablePackageFormat.MAGIC.size)
            var offset = 0
            while (offset < actual.size) {
                val count = input.read(actual, offset, actual.size - offset)
                if (count < 0) return@use false
                offset += count
            }
            actual.contentEquals(PortablePackageFormat.MAGIC)
        } ?: false
    } catch (_: Exception) {
        false
    }

    private fun automaticTypeFromName(name: String): BackupType? {
        if (!name.endsWith(".dbbackup", ignoreCase = true)) return null
        return when {
            name.startsWith("memory-") -> BackupType.MEMORY
            name.startsWith("lorebook-") -> BackupType.LOREBOOK
            name.startsWith("user_image-") -> BackupType.USER_IMAGE
            else -> null
        }
    }

    private fun automaticMillisFromName(name: String): Long? =
        name.substringAfter('-', "").substringBefore('.', "").toLongOrNull()

    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun looksLikeJson(file: File): Boolean = try {
        file.inputStream().buffered().use { input ->
            var b: Int
            do {
                b = input.read()
            } while (b >= 0 && b.toChar().isWhitespace())
            b == '{'.code || b == '['.code
        }
    } catch (_: Exception) {
        false
    }

    private fun typeForPortableEntry(name: String): BackupType? = when (name) {
        "memory.db" -> BackupType.MEMORY
        "lorebook.db" -> BackupType.LOREBOOK
        "user_images.db" -> BackupType.USER_IMAGE
        else -> null
    }

    private fun parseIsoMillis(value: String): Long? =
        try { Instant.parse(value).toEpochMilli() } catch (_: Exception) { null }

    private fun decodeHex(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0 || hex.any { it.digitToIntOrNull(16) == null }) return null
        return ByteArray(hex.length / 2) {
            ((hex[it * 2].digitToInt(16) shl 4) + hex[it * 2 + 1].digitToInt(16)).toByte()
        }
    }

    private fun mapPortableFailure(error: PortablePackageFormat.RestoreError): Failure = when (error) {
        PortablePackageFormat.RestoreError.MISTYPED_CODE -> Failure.INVALID_RECOVERY_CODE
        PortablePackageFormat.RestoreError.WRONG_KEY_OR_HEADER -> Failure.WRONG_RECOVERY_KEY
        PortablePackageFormat.RestoreError.UNSUPPORTED_PROTECTION -> Failure.UNSUPPORTED_PROTECTION
        PortablePackageFormat.RestoreError.NOT_A_V2_PACKAGE -> Failure.NO_VALID_DATABASES
        PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED,
        PortablePackageFormat.RestoreError.TOO_LARGE -> Failure.DAMAGED_OR_ALTERED
    }
}
