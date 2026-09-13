/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.backup

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Durable, non-secret evidence for one direct database replacement. */
internal object DirectDatabaseRestoreJournal {
    enum class Phase { PREPARED, KEY_SWITCHED, FILE_INSTALLED, VERIFIED, COMMITTED }
    enum class KeyKind { NOT_APPLICABLE, ABSENT, PRESENT }
    enum class SourceKind { DATABASE_SNAPSHOT, MEMORY_JSON }

    data class KeyEvidence(val kind: KeyKind, val sha256: String? = null) {
        init {
            require((kind == KeyKind.PRESENT) == (sha256 != null && HASH.matches(sha256)))
        }
    }

    data class FileEvidence(
        val suffix: String,
        val size: Long,
        val sha256: String
    ) {
        init {
            require(suffix in ALLOWED_SUFFIXES)
            require(size >= 0L)
            require(HASH.matches(sha256))
        }
    }

    data class Record(
        val operationId: String,
        val type: BackupType,
        val sourceKind: SourceKind,
        val sourceIdentity: String,
        val activePath: String,
        val stagedPath: String,
        val quarantinePath: String,
        val originalExisted: Boolean,
        val originalDegraded: Boolean,
        val originalKey: KeyEvidence,
        val intendedKey: KeyEvidence,
        val originalFiles: List<FileEvidence>,
        val installedFiles: List<FileEvidence>,
        val phase: Phase
    ) {
        init {
            require(SAFE_ID.matches(operationId))
            require(HASH.matches(sourceIdentity))
            require(activePath.isNotBlank() && stagedPath.isNotBlank() && quarantinePath.isNotBlank())
            require(originalFiles.map { it.suffix }.size == originalFiles.map { it.suffix }.toSet().size)
            require(installedFiles.map { it.suffix }.size == installedFiles.map { it.suffix }.toSet().size)
            require(originalExisted == originalFiles.any { it.suffix.isEmpty() })
            require(installedFiles.any { it.suffix.isEmpty() })
        }
    }

    fun root(context: Context, type: BackupType): File =
        File(File(context.filesDir, ROOT_DIR), type.key)

    fun stateFile(context: Context, type: BackupType): File =
        File(root(context, type), STATE_FILE)

    fun hasAny(context: Context): Boolean = DATABASE_TYPES.any { root(context, it).exists() }

    fun write(context: Context, record: Record): Boolean {
        val root = root(context, record.type)
        if (!root.exists() && !root.mkdirs()) return false
        val bytes = encode(record).toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) return false
        if (!DurableRecoveryFileOps.writeAtomic(stateFile(context, record.type), bytes)) return false
        return read(context, record.type) == record
    }

    fun read(context: Context, type: BackupType): Record? {
        return try {
            val file = stateFile(context, type)
            if (!file.isFile || file.length() !in 1L..MAX_BYTES) null
            else decode(JSONObject(file.readText(Charsets.UTF_8)))
                .takeIf { validatePaths(context, it) }
        } catch (_: Exception) {
            null
        }
    }

    fun delete(context: Context, type: BackupType): Boolean {
        val root = root(context, type)
        if (!root.exists()) return true
        val state = File(root, STATE_FILE)
        if (!DurableRecoveryFileOps.deleteAndSync(state)) return false
        return root.delete() || !root.exists()
    }

    fun evidence(base: File): List<FileEvidence>? {
        val result = ArrayList<FileEvidence>()
        for (suffix in ALLOWED_SUFFIXES) {
            val file = File(base.path + suffix)
            if (!file.exists()) continue
            if (!file.isFile) return null
            result.add(FileEvidence(suffix, file.length(), sha256(file)))
        }
        return result
    }

    fun matches(base: File, expected: List<FileEvidence>): Boolean {
        val actual = evidence(base) ?: return false
        return actual == expected
    }

    fun keyEvidence(key: ByteArray?): KeyEvidence = if (key == null) {
        KeyEvidence(KeyKind.ABSENT)
    } else {
        KeyEvidence(KeyKind.PRESENT, sha256(key))
    }

    fun keyMatches(key: ByteArray?, evidence: KeyEvidence): Boolean = when (evidence.kind) {
        KeyKind.NOT_APPLICABLE -> key == null
        KeyKind.ABSENT -> key == null
        KeyKind.PRESENT -> key != null && sha256(key) == evidence.sha256
    }

    fun databaseTypes(): List<BackupType> = DATABASE_TYPES

    private fun encode(record: Record): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("operation_id", record.operationId)
        .put("database_type", record.type.key)
        .put("source_kind", record.sourceKind.name)
        .put("source_identity_sha256", record.sourceIdentity)
        .put("active_path", record.activePath)
        .put("staged_path", record.stagedPath)
        .put("quarantine_path", record.quarantinePath)
        .put("original_existed", record.originalExisted)
        .put("original_degraded", record.originalDegraded)
        .put("original_key", encodeKey(record.originalKey))
        .put("intended_key", encodeKey(record.intendedKey))
        .put("original_files", encodeFiles(record.originalFiles))
        .put("installed_files", encodeFiles(record.installedFiles))
        .put("phase", record.phase.name)

    private fun decode(root: JSONObject): Record {
        require(root.getInt("version") == VERSION)
        val typeKey = root.getString("database_type")
        val type = DATABASE_TYPES.singleOrNull { it.key == typeKey }
            ?: throw IllegalArgumentException("unsupported database type")
        return Record(
            operationId = root.getString("operation_id"),
            type = type,
            sourceKind = SourceKind.valueOf(root.getString("source_kind")),
            sourceIdentity = root.getString("source_identity_sha256"),
            activePath = root.getString("active_path"),
            stagedPath = root.getString("staged_path"),
            quarantinePath = root.getString("quarantine_path"),
            originalExisted = root.getBoolean("original_existed"),
            originalDegraded = root.getBoolean("original_degraded"),
            originalKey = decodeKey(root.getJSONObject("original_key")),
            intendedKey = decodeKey(root.getJSONObject("intended_key")),
            originalFiles = decodeFiles(root.getJSONArray("original_files")),
            installedFiles = decodeFiles(root.getJSONArray("installed_files")),
            phase = Phase.valueOf(root.getString("phase"))
        )
    }

    private fun encodeKey(key: KeyEvidence): JSONObject = JSONObject()
        .put("state", key.kind.name)
        .also { if (key.sha256 != null) it.put("sha256", key.sha256) }

    private fun decodeKey(root: JSONObject): KeyEvidence {
        val kind = KeyKind.valueOf(root.getString("state"))
        return KeyEvidence(kind, root.optString("sha256").takeIf { it.isNotEmpty() })
    }

    private fun encodeFiles(files: List<FileEvidence>): JSONArray = JSONArray().apply {
        files.forEach {
            put(JSONObject().put("suffix", it.suffix).put("size", it.size).put("sha256", it.sha256))
        }
    }

    private fun decodeFiles(array: JSONArray): List<FileEvidence> = buildList {
        repeat(array.length()) {
            val item = array.getJSONObject(it)
            add(FileEvidence(item.getString("suffix"), item.getLong("size"), item.getString("sha256")))
        }
    }

    private fun validatePaths(context: Context, record: Record): Boolean {
        return try {
            val expectedActive = context.getDatabasePath(databaseName(record.type)).canonicalFile
            val active = File(record.activePath).canonicalFile
            val staged = File(record.stagedPath).canonicalFile
            val quarantine = File(record.quarantinePath).canonicalFile
            val recoveryRoot = File(context.filesDir, "storage_recovery").canonicalFile
            active == expectedActive &&
                staged.parentFile == expectedActive.parentFile &&
                staged.name.startsWith(".${expectedActive.name}.direct-restore-") &&
                quarantine.parentFile == recoveryRoot &&
                quarantine.name.contains(".pre-restore-")
        } catch (_: Exception) {
            false
        }
    }

    private fun databaseName(type: BackupType): String = when (type) {
        BackupType.MEMORY -> "companion_memory.db"
        BackupType.LOREBOOK -> "lorebook.db"
        BackupType.USER_IMAGE -> "profile_images.db"
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
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private const val ROOT_DIR = "direct_database_restore"
    private const val STATE_FILE = "state.json"
    private const val VERSION = 1
    private const val MAX_BYTES = 128L * 1024L
    private val DATABASE_TYPES = listOf(BackupType.MEMORY, BackupType.LOREBOOK, BackupType.USER_IMAGE)
    private val ALLOWED_SUFFIXES = listOf("", "-wal", "-shm", "-journal")
    private val SAFE_ID = Regex("[a-z0-9-]{1,80}")
    private val HASH = Regex("[0-9a-f]{64}")
}
