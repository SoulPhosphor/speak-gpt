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

        /** Non-content reason for the most recent failed call, if known. */
        fun lastFailure(): String? = null
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

    private val note = PortableRestoreFailureNote()

    override fun failureDetail(): String? = note.detail

    val memoryReport: MemoryCategoryPlanner.Report? get() = precomputed?.memoryReport
    val modelRulesReport: MemoryCategoryPlanner.Report? get() = precomputed?.modelRulesReport

    override fun validate(): Boolean {
        note.reset()
        val plan = precomputed ?: return note.fail("no_prepared_plan")
        MemorySharedRestoreRowFormat.invalidReason(plan.current)?.let {
            return note.fail("current_rows_invalid: $it")
        }
        MemorySharedRestoreRowFormat.invalidReason(plan.desired)?.let {
            return note.fail("planned_rows_invalid: $it")
        }
        if (plan.affectedTables.isEmpty() ||
            !plan.affectedTables.all { it in MemorySharedRestoreRowFormat.tableNames }
        ) return note.fail("affected_tables_invalid")
        return true
    }

    override fun stage(): Boolean {
        note.reset()
        val plan = precomputed ?: return note.fail("no_prepared_plan")
        return try {
            if (stagingRoot.exists()) {
                if (!stagingRoot.isDirectory || !stagingRoot.listFiles().isNullOrEmpty()) {
                    return note.fail("staging_directory_not_empty")
                }
            } else if (!stagingRoot.mkdirs()) return note.fail("staging_directory_unavailable")

            val provisionedBefore = backend.isProvisioned()
            val exactCurrent = backend.snapshot() ?: return note.fail(
                "live_snapshot_unavailable: ${backend.lastFailure() ?: "no_reason_given"}"
            )
            if (backend.isProvisioned() != provisionedBefore) {
                return note.fail("database_provisioning_changed_during_snapshot")
            }
            if (exactCurrent != plan.current) {
                return note.fail(
                    "live_data_changed_since_planning: " +
                        PortableRestoreDiagnostics.tableDifference(plan.current.tables, exactCurrent.tables)
                )
            }
            if (!RestoreProvisioningState.write(stagingRoot, provisionedBefore)) {
                return note.fail("staged_write_failed: provisioning state")
            }
            if (!writeRows(CURRENT_JSON, exactCurrent) || !writeRows(DESIRED_JSON, plan.desired)) {
                return false
            }
            if (!writeAffected(plan.affectedTables)) return note.fail("staged_write_failed: $AFFECTED_JSON")
            if (!stagedCopyMatches(CURRENT_JSON, exactCurrent)) return false
            if (!stagedCopyMatches(DESIRED_JSON, plan.desired)) return false
            if (readAffected() != plan.affectedTables) {
                return note.fail("staged_copy_mismatch: $AFFECTED_JSON")
            }
            true
        } catch (e: Exception) {
            note.unexpected(e)
        }
    }

    override fun apply(): Boolean {
        note.reset()
        val desired = readRows(DESIRED_JSON) ?: return false
        val affected = readAffected() ?: return note.fail("staged_copy_unreadable: $AFFECTED_JSON")
        val wasProvisioned = RestoreProvisioningState.read(stagingRoot)
            ?: return note.fail("staged_copy_unreadable: provisioning state")
        if (!wasProvisioned && !backend.isProvisioned() && !requiresStore(desired, affected)) {
            return true
        }
        if (!backend.replace(desired, affected, requireExisting = false)) {
            return note.fail("database_write_failed: ${backend.lastFailure() ?: "no_reason_given"}")
        }
        return true
    }

    override fun rollback(): Boolean {
        note.reset()
        val current = readRows(CURRENT_JSON) ?: return false
        val affected = readAffected() ?: return note.fail("staged_copy_unreadable: $AFFECTED_JSON")
        val wasProvisioned = RestoreProvisioningState.read(stagingRoot)
            ?: return note.fail("staged_copy_unreadable: provisioning state")

        // A process may die after the outer journal marks this participant as
        // started but before the first database open. Recovery must not create
        // a new empty database merely to restore the absence of one.
        if (!backend.isProvisioned()) {
            if (wasProvisioned) return note.fail("database_missing_but_existed_before_restore")
            return backend.removeProvisionedStore() || note.fail("database_removal_failed")
        }
        if (!backend.replace(current, affected, requireExisting = true)) {
            return note.fail("database_write_failed: ${backend.lastFailure() ?: "no_reason_given"}")
        }
        return wasProvisioned || backend.removeProvisionedStore() || note.fail("database_removal_failed")
    }

    /** Reads a staged file back and requires it to equal what was written. */
    private fun stagedCopyMatches(name: String, expected: MemorySharedRestoreRows): Boolean {
        val staged = readRows(name) ?: return false
        if (staged == expected) return true
        return note.fail(
            "staged_copy_mismatch: $name: " +
                PortableRestoreDiagnostics.tableDifference(expected.tables, staged.tables)
        )
    }

    override fun cleanup() {
        stagingRoot.deleteRecursively()
    }

    private fun writeRows(name: String, rows: MemorySharedRestoreRows): Boolean {
        val json = try {
            MemorySharedRestoreRowFormat.toJson(rows)
        } catch (e: Exception) {
            val invalid = MemorySharedRestoreRowFormat.invalidReason(rows)
            return note.fail(
                "staged_encode_failed: $name: " + (invalid ?: PortableRestoreDiagnostics.unexpected(e))
            )
        }
        return AtomicFileWriter.writeAndVerify(File(stagingRoot, name), json) ||
            note.fail("staged_write_failed: $name")
    }

    /** Null when unreadable; the reason is recorded in [note]. */
    private fun readRows(name: String): MemorySharedRestoreRows? {
        val file = File(stagingRoot, name)
        if (!file.isFile) {
            note.fail("staged_copy_unreadable: $name: file is missing")
            return null
        }
        if (file.length() > MAX_JSON_BYTES) {
            note.fail("staged_copy_unreadable: $name: file is larger than the size limit")
            return null
        }
        return try {
            MemorySharedRestoreRowFormat.parse(file.readText(Charsets.UTF_8)) {
                note.fail("staged_copy_unreadable: $name: $it")
            }
        } catch (e: Exception) {
            note.fail("staged_copy_unreadable: $name: ${PortableRestoreDiagnostics.unexpected(e)}")
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

        private var failure: String? = null

        override fun lastFailure(): String? = failure

        override fun snapshot(): MemorySharedRestoreRows? {
            failure = null
            if (!isProvisioned()) return MemorySharedRestoreRowFormat.empty()
            if (DatabaseHealthState.isDegraded(app, BackupType.MEMORY)) {
                failure = "memory database is marked degraded"
                return null
            }
            return try {
                MemoryStore.getInstance(app).exportSharedRestoreRows()
            } catch (e: Exception) {
                failure = PortableRestoreDiagnostics.unexpected(e)
                null
            }
        }

        override fun replace(
            rows: MemorySharedRestoreRows,
            affectedTables: Set<String>,
            requireExisting: Boolean
        ): Boolean {
            failure = null
            if (requireExisting && !isProvisioned()) {
                failure = "memory database does not exist"
                return false
            }
            if (isProvisioned() && DatabaseHealthState.isDegraded(app, BackupType.MEMORY)) {
                failure = "memory database is marked degraded"
                return false
            }
            return try {
                MemoryStore.getInstance(app).replaceSharedRestoreRows(rows, affectedTables) {
                    failure = it
                }
            } catch (e: Exception) {
                failure = PortableRestoreDiagnostics.unexpected(e)
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
