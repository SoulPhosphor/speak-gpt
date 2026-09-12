/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.backup.BackupType
import org.teslasoft.assistant.preferences.backup.DatabaseHealthState
import org.teslasoft.assistant.preferences.memory.DatabaseKeys
import org.teslasoft.assistant.preferences.memory.MemorySharedRestoreRowFormat
import org.teslasoft.assistant.preferences.memory.MemorySharedRestoreRows
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.util.AtomicFileWriter

/**
 * The sole selected-category transaction participant allowed to write
 * companion_memory.db. Identity, Memory, and Model Rules remain independent
 * logical selections, but their final rows commit and roll back together.
 */
class CompanionMemoryRestoreParticipant internal constructor(
    private val stagingRoot: File,
    private val backend: Backend,
    private val precomputed: PreparedPlan? = null
) : SelectedCategoryRestoreTransaction.Participant {

    data class PreparedPlan(
        val current: MemorySharedRestoreRows,
        val desired: MemorySharedRestoreRows,
        val affectedTables: Set<String>,
        val memoryReport: MemoryCategoryPlanner.Report? = null,
        val modelRulesReport: MemoryCategoryPlanner.Report? = null
    )

    interface Backend {
        fun isProvisioned(): Boolean
        fun snapshot(): MemorySharedRestoreRows?
        fun replace(
            rows: MemorySharedRestoreRows,
            affectedTables: Set<String>,
            requireExisting: Boolean
        ): Boolean
        fun removeProvisionedStore(): Boolean
    }

    internal constructor(
        context: Context,
        plan: PreparedPlan,
        stagingRoot: File
    ) : this(stagingRoot, AndroidBackend(context.applicationContext), plan)

    internal constructor(context: Context, stagingRoot: File) : this(
        stagingRoot, AndroidBackend(context.applicationContext), null
    )

    override val categoryKey: String = CATEGORY_KEY

    val memoryReport: MemoryCategoryPlanner.Report? get() = precomputed?.memoryReport
    val modelRulesReport: MemoryCategoryPlanner.Report? get() = precomputed?.modelRulesReport

    override fun validate(): Boolean = precomputed?.let { plan ->
        MemorySharedRestoreRowFormat.valid(plan.current) &&
            MemorySharedRestoreRowFormat.valid(plan.desired) &&
            plan.affectedTables.isNotEmpty() &&
            plan.affectedTables.all { it in MemorySharedRestoreRowFormat.tableNames }
    } == true

    override fun stage(): Boolean {
        val plan = precomputed ?: return false
        return try {
            if (stagingRoot.exists()) {
                if (!stagingRoot.isDirectory || !stagingRoot.listFiles().isNullOrEmpty()) return false
            } else if (!stagingRoot.mkdirs()) return false

            val provisionedBefore = backend.isProvisioned()
            val exactCurrent = backend.snapshot() ?: return false
            if (backend.isProvisioned() != provisionedBefore) return false
            if (exactCurrent != plan.current) return false
            if (!RestoreProvisioningState.write(stagingRoot, provisionedBefore)) return false
            if (!writeRows(CURRENT_JSON, exactCurrent) || !writeRows(DESIRED_JSON, plan.desired)) {
                return false
            }
            if (!writeAffected(plan.affectedTables)) return false
            readRows(CURRENT_JSON) == exactCurrent &&
                readRows(DESIRED_JSON) == plan.desired &&
                readAffected() == plan.affectedTables
        } catch (_: Exception) {
            false
        }
    }

    override fun apply(): Boolean {
        val desired = readRows(DESIRED_JSON) ?: return false
        val affected = readAffected() ?: return false
        val wasProvisioned = RestoreProvisioningState.read(stagingRoot) ?: return false
        if (!wasProvisioned && !backend.isProvisioned() && !requiresStore(desired, affected)) {
            return true
        }
        return backend.replace(desired, affected, requireExisting = false)
    }

    override fun rollback(): Boolean {
        val current = readRows(CURRENT_JSON) ?: return false
        val affected = readAffected() ?: return false
        val wasProvisioned = RestoreProvisioningState.read(stagingRoot) ?: return false

        // A process may die after the outer journal marks this participant as
        // started but before the first database open. Recovery must not create
        // a new empty database merely to restore the absence of one.
        if (!backend.isProvisioned()) {
            return if (wasProvisioned) false else backend.removeProvisionedStore()
        }
        if (!backend.replace(current, affected, requireExisting = true)) return false
        return wasProvisioned || backend.removeProvisionedStore()
    }

    override fun cleanup() {
        stagingRoot.deleteRecursively()
    }

    private fun writeRows(name: String, rows: MemorySharedRestoreRows): Boolean =
        AtomicFileWriter.writeAndVerify(
            File(stagingRoot, name),
            MemorySharedRestoreRowFormat.toJson(rows)
        )

    private fun readRows(name: String): MemorySharedRestoreRows? {
        val file = File(stagingRoot, name)
        if (!file.isFile || file.length() > MAX_JSON_BYTES) return null
        return try {
            MemorySharedRestoreRowFormat.parse(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun writeAffected(tables: Set<String>): Boolean = AtomicFileWriter.writeAndVerify(
        File(stagingRoot, AFFECTED_JSON),
        JSONObject()
            .put("version", 1)
            .put("tables", JSONArray().apply { tables.sorted().forEach(::put) })
            .toString()
    )

    private fun readAffected(): Set<String>? {
        return try {
            val file = File(stagingRoot, AFFECTED_JSON)
            if (!file.isFile || file.length() > MAX_AFFECTED_BYTES) return null
            val root = JSONObject(file.readText(Charsets.UTF_8))
            if (root.optInt("version", -1) != 1) return null
            val array = root.getJSONArray("tables")
            val result = LinkedHashSet<String>()
            repeat(array.length()) {
                val table = array.getString(it)
                if (table !in MemorySharedRestoreRowFormat.tableNames || !result.add(table)) return null
            }
            result.takeIf(Set<String>::isNotEmpty)
        } catch (_: Exception) {
            null
        }
    }

    private fun requiresStore(rows: MemorySharedRestoreRows, affected: Set<String>): Boolean =
        affected.any { table -> table != "app_state" && rows.tables.getValue(table).isNotEmpty() }

    private class AndroidBackend(context: Context) : Backend {
        private val app = context.applicationContext

        override fun isProvisioned(): Boolean = MemoryStore.isProvisioned(app)

        override fun snapshot(): MemorySharedRestoreRows? {
            if (!isProvisioned()) return MemorySharedRestoreRowFormat.empty()
            if (DatabaseHealthState.isDegraded(app, BackupType.MEMORY)) return null
            return try {
                MemoryStore.getInstance(app).exportSharedRestoreRows()
            } catch (_: Exception) {
                null
            }
        }

        override fun replace(
            rows: MemorySharedRestoreRows,
            affectedTables: Set<String>,
            requireExisting: Boolean
        ): Boolean {
            if (requireExisting && !isProvisioned()) return false
            if (isProvisioned() && DatabaseHealthState.isDegraded(app, BackupType.MEMORY)) return false
            return try {
                MemoryStore.getInstance(app).replaceSharedRestoreRows(rows, affectedTables)
            } catch (_: Exception) {
                false
            }
        }

        override fun removeProvisionedStore(): Boolean {
            MemoryStore.invalidateInstance()
            val database = app.getDatabasePath(MemoryStore.DATABASE_NAME)
            val files = listOf(
                database,
                File(database.path + "-wal"),
                File(database.path + "-shm"),
                File(database.path + "-journal")
            )
            if (files.any { it.exists() && !it.delete() }) return false
            return DatabaseKeys.clearExisting(app, DatabaseKeys.KEY_MEMORY)
        }
    }

    companion object {
        const val CATEGORY_KEY = "companion_memory_store"
        private const val CURRENT_JSON = "current.json"
        private const val DESIRED_JSON = "desired.json"
        private const val AFFECTED_JSON = "affected.json"
        private const val MAX_JSON_BYTES = 256L * 1024L * 1024L
        private const val MAX_AFFECTED_BYTES = 16L * 1024L
    }
}
