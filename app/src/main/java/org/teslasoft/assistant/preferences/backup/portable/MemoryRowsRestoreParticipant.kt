/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.teslasoft.assistant.preferences.memory.DatabaseKeys
import org.teslasoft.assistant.preferences.memory.MemoryPortableGroup
import org.teslasoft.assistant.preferences.memory.MemoryPortableRowFormat
import org.teslasoft.assistant.preferences.memory.MemoryPortableRows
import org.teslasoft.assistant.preferences.memory.MemoryStore

/** Shared implementation for the independently selectable Memories and Model
 * Rules categories. Each participant mutates only its own logical tables. */
class MemoryRowsRestoreParticipant internal constructor(
    private val group: MemoryPortableGroup,
    private val mode: PortableRestoreMode,
    private val stagingRoot: File,
    private val backend: Backend,
    private val referenceProvider: (() -> MemoryReferenceIds)? = null
) : SelectedCategoryRestoreTransaction.Participant {

    interface Backend {
        fun incoming(group: MemoryPortableGroup): MemoryPortableRows?
        fun snapshot(group: MemoryPortableGroup): MemoryPortableRows?
        fun references(): MemoryReferenceIds
        fun replace(group: MemoryPortableGroup, rows: MemoryPortableRows): Boolean
        fun restoreOriginal(group: MemoryPortableGroup, rows: MemoryPortableRows): Boolean =
            replace(group, rows)
    }

    constructor(
        context: Context,
        artifacts: List<PortablePackage.ValidatedArtifact>,
        category: PortableRestoreCategory,
        mode: PortableRestoreMode,
        stagingRoot: File
    ) : this(
        groupFor(category),
        mode,
        stagingRoot,
        AndroidBackend(context.applicationContext, artifacts),
        null
    )

    constructor(
        context: Context,
        artifacts: List<PortablePackage.ValidatedArtifact>,
        category: PortableRestoreCategory,
        mode: PortableRestoreMode,
        stagingRoot: File,
        referenceProvider: () -> MemoryReferenceIds
    ) : this(
        groupFor(category),
        mode,
        stagingRoot,
        AndroidBackend(context.applicationContext, artifacts),
        referenceProvider
    )

    override val categoryKey: String = when (group) {
        MemoryPortableGroup.MEMORIES -> PortableRestoreCategory.MEMORIES.key
        MemoryPortableGroup.MODEL_RULES -> PortableRestoreCategory.MODEL_RULES.key
    }

    var report: MemoryCategoryPlanner.Report? = null
        private set
    private var backup: MemoryPortableRows? = null

    override fun validate(): Boolean {
        backup = backend.incoming(group)
        return backup != null
    }

    override fun stage(): Boolean {
        val incoming = backup ?: return false
        val current = backend.snapshot(group) ?: return false
        val planned = MemoryCategoryPlanner.plan(
            group, current, incoming, mode, referenceProvider?.invoke() ?: backend.references()
        ) as? MemoryCategoryPlanner.Result.Ready ?: return false
        report = planned.report
        return try {
            if (stagingRoot.exists()) {
                if (!stagingRoot.isDirectory || !stagingRoot.listFiles().isNullOrEmpty()) return false
            } else if (!stagingRoot.mkdirs()) return false
            write(CURRENT_JSON, current) && write(DESIRED_JSON, planned.rows) &&
                read(CURRENT_JSON) != null && read(DESIRED_JSON) != null
        } catch (_: Exception) {
            false
        }
    }

    override fun apply(): Boolean = read(DESIRED_JSON)?.let { backend.replace(group, it) } == true

    override fun rollback(): Boolean =
        read(CURRENT_JSON)?.let { backend.restoreOriginal(group, it) } == true

    override fun cleanup() {
        stagingRoot.deleteRecursively()
    }

    private fun write(name: String, rows: MemoryPortableRows): Boolean = try {
        File(stagingRoot, name).writeText(
            MemoryPortableRowFormat.toJson(group, rows), Charsets.UTF_8
        )
        true
    } catch (_: Exception) {
        false
    }

    private fun read(name: String): MemoryPortableRows? {
        val file = File(stagingRoot, name)
        if (!file.isFile || file.length() > MAX_JSON_BYTES) return null
        return try { MemoryPortableRowFormat.parse(file.readText(Charsets.UTF_8), group) }
        catch (_: Exception) { null }
    }

    private class AndroidBackend(
        context: Context,
        private val artifacts: List<PortablePackage.ValidatedArtifact>
    ) : Backend {
        private val app = context.applicationContext
        private val initiallyProvisioned = MemoryStore.isProvisioned(app)

        override fun incoming(group: MemoryPortableGroup): MemoryPortableRows? {
            val matches = artifacts.filter {
                it.type == PortablePackage.TYPE_SQLCIPHER_DB && it.entryName == "memory.db"
            }
            if (matches.size != 1) return null
            val artifact = matches.single()
            if (artifact.keySemantics != PortablePackage.KEY_SEMANTICS_PASSPHRASE) return null
            val key = decodeHex(artifact.databaseKeyHex ?: return null) ?: return null
            var database: SQLiteDatabase? = null
            return try {
                val opened = SQLiteDatabase.openDatabase(
                    artifact.stagedFile.absolutePath, key, null,
                    SQLiteDatabase.OPEN_READONLY, null, null
                )
                database = opened
                if (!opened.isDatabaseIntegrityOk) return null
                MemoryPortableRowFormat.read(opened, group)
            } catch (_: Exception) {
                null
            } finally {
                runCatching { database?.close() }
                key.fill(0)
            }
        }

        override fun snapshot(group: MemoryPortableGroup): MemoryPortableRows? {
            if (!initiallyProvisioned) return emptyRows(group)
            return try { MemoryStore.getInstance(app).exportPortableRows(group) }
            catch (_: Exception) { null }
        }

        override fun references(): MemoryReferenceIds {
            if (!MemoryStore.isProvisioned(app)) return MemoryReferenceIds()
            return try {
                val tables = MemoryStore.getInstance(app).exportRoleplayTables()
                MemoryReferenceIds(
                    companions = ids(tables, "companions", "companion_id"),
                    worlds = ids(tables, "worlds", "world_id"),
                    campaigns = ids(tables, "campaigns", "campaign_id"),
                    roleplayCharacters = ids(tables, "roleplay_characters", "roleplay_character_id"),
                    userPersonas = ids(tables, "user_personas", "persona_id"),
                    roleplayTags = ids(tables, "rp_tags", "tag_id")
                )
            } catch (_: Exception) {
                MemoryReferenceIds()
            }
        }

        override fun replace(group: MemoryPortableGroup, rows: MemoryPortableRows): Boolean = try {
            MemoryStore.getInstance(app).replacePortableRows(group, rows)
        } catch (_: Exception) {
            false
        }

        override fun restoreOriginal(
            group: MemoryPortableGroup,
            rows: MemoryPortableRows
        ): Boolean {
            if (!replace(group, rows)) return false
            if (initiallyProvisioned) return true
            MemoryStore.invalidateInstance()
            val database = app.getDatabasePath(MemoryStore.DATABASE_NAME)
            val files = listOf(
                database,
                File(database.path + "-wal"), File(database.path + "-shm"),
                File(database.path + "-journal")
            )
            if (files.any { it.exists() && !it.delete() }) return false
            return DatabaseKeys.clearExisting(app, DatabaseKeys.KEY_MEMORY)
        }

        private fun ids(
            tables: Map<String, List<Map<String, Any?>>>, table: String, column: String
        ): Set<String> = tables[table].orEmpty().mapNotNull { it[column] as? String }.toSet()

        private fun decodeHex(value: String): ByteArray? {
            if (value.length != 64 || !value.matches(Regex("^[0-9a-fA-F]+$"))) return null
            return try { ByteArray(32) { index ->
                value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            } } catch (_: Exception) { null }
        }
    }

    companion object {
        private fun groupFor(category: PortableRestoreCategory): MemoryPortableGroup = when (category) {
            PortableRestoreCategory.MEMORIES -> MemoryPortableGroup.MEMORIES
            PortableRestoreCategory.MODEL_RULES -> MemoryPortableGroup.MODEL_RULES
            else -> throw IllegalArgumentException("category is not memory-backed")
        }

        private fun emptyRows(group: MemoryPortableGroup) = MemoryPortableRows(
            MemoryPortableRowFormat.specs(group).associate { it.table to emptyList() }
        )

        const val CURRENT_JSON = "current.json"
        const val DESIRED_JSON = "desired.json"
        const val MAX_JSON_BYTES = 512L * 1024L * 1024L
    }
}
