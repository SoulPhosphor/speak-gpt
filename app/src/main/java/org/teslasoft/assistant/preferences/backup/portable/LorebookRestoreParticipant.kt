/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import java.io.File
import org.teslasoft.assistant.preferences.lorebook.LoreBookStore
import org.teslasoft.assistant.preferences.memory.DatabaseKeys

/** Lorebooks adapter for the selected-category all-or-nothing boundary. */
class LorebookRestoreParticipant internal constructor(
    private val mode: PortableRestoreMode,
    private val stagingRoot: File,
    private val backend: Backend,
    private val precomputed: PreparedPlan? = null
) : SelectedCategoryRestoreTransaction.Participant {

    data class PreparedPlan(
        val current: LorebookPortableData,
        val incoming: LorebookPortableData,
        val desired: LorebookPortableData,
        val report: LorebookCategoryPlanner.Report
    )

    interface Backend {
        fun incoming(): LorebookPortableData?
        fun snapshot(): LorebookPortableData?
        fun replace(data: LorebookPortableData): Boolean
        fun restoreOriginal(data: LorebookPortableData): Boolean = replace(data)
        fun wasProvisionedBeforeStage(): Boolean = true
        fun removeProvisionedStore(): Boolean = true
    }

    constructor(
        context: Context,
        artifacts: List<PortablePackage.ValidatedArtifact>,
        mode: PortableRestoreMode,
        stagingRoot: File,
        incomingIsEmpty: Boolean = false
    ) : this(
        mode,
        stagingRoot,
        AndroidBackend(context.applicationContext, artifacts, incomingIsEmpty),
        null
    )

    internal constructor(
        context: Context,
        plan: PreparedPlan,
        stagingRoot: File
    ) : this(
        PortableRestoreMode.MERGE,
        stagingRoot,
        AndroidBackend(context.applicationContext, emptyList(), false),
        plan
    ) {
        backup = plan.incoming
        report = plan.report
    }

    override val categoryKey: String = PortableRestoreCategory.LOREBOOKS.key

    private val note = PortableRestoreFailureNote()

    override fun failureDetail(): String? = note.detail

    var report: LorebookCategoryPlanner.Report? = null
        private set

    private var backup: LorebookPortableData? = null

    override fun validate(): Boolean {
        note.reset()
        precomputed?.let {
            backup = it.incoming
            report = it.report
            return true
        }
        backup = backend.incoming()
        return backup != null || note.fail("backup_lorebooks_unreadable")
    }

    override fun stage(): Boolean {
        note.reset()
        val incoming = backup ?: return note.fail("backup_lorebooks_not_validated")
        val current = precomputed?.current ?: backend.snapshot()
            ?: return note.fail("current_lorebooks_unreadable")
        val planned = precomputed?.let {
            LorebookCategoryPlanner.Result.Ready(it.desired, it.report)
        } ?: (LorebookCategoryPlanner.plan(current, incoming, mode) as?
            LorebookCategoryPlanner.Result.Ready ?: return note.fail("planning_failed"))
        report = planned.report
        return try {
            if (stagingRoot.exists()) {
                if (!stagingRoot.isDirectory || !stagingRoot.listFiles().isNullOrEmpty()) {
                    return note.fail("staging_directory_not_empty")
                }
            } else if (!stagingRoot.mkdirs()) return note.fail("staging_directory_unavailable")
            if (!RestoreProvisioningState.write(stagingRoot, backend.wasProvisionedBeforeStage())) {
                return note.fail("staged_write_failed: provisioning state")
            }
            write(CURRENT_JSON, current) && write(DESIRED_JSON, planned.data) &&
                read(CURRENT_JSON) != null && read(DESIRED_JSON) != null
        } catch (e: Exception) {
            note.unexpected(e)
        }
    }

    override fun apply(): Boolean {
        note.reset()
        val desired = read(DESIRED_JSON) ?: return false
        return backend.replace(desired) || note.fail("lorebook_database_write_failed")
    }

    override fun rollback(): Boolean {
        note.reset()
        val current = read(CURRENT_JSON) ?: return false
        val wasProvisioned = RestoreProvisioningState.read(stagingRoot)
            ?: return note.fail("staged_copy_unreadable: provisioning state")
        if (!backend.restoreOriginal(current)) return note.fail("lorebook_database_write_failed")
        return wasProvisioned || backend.removeProvisionedStore() || note.fail("database_removal_failed")
    }

    override fun cleanup() {
        stagingRoot.deleteRecursively()
    }

    private fun write(name: String, data: LorebookPortableData): Boolean = try {
        File(stagingRoot, name).writeText(LorebookPortableCodec.toJson(data), Charsets.UTF_8)
        true
    } catch (e: Exception) {
        note.fail("staged_write_failed: $name: ${PortableRestoreDiagnostics.unexpected(e)}")
    }

    /** Null when unreadable; the reason is recorded in [note]. */
    private fun read(name: String): LorebookPortableData? {
        val file = File(stagingRoot, name)
        if (!file.isFile || file.length() > MAX_JSON_BYTES) {
            note.fail("staged_copy_unreadable: $name: file is missing or larger than the size limit")
            return null
        }
        return try {
            LorebookPortableCodec.parse(file.readText(Charsets.UTF_8))
                ?: run { note.fail("staged_copy_unreadable: $name: lorebook codec rejected it"); null }
        } catch (e: Exception) {
            note.fail("staged_copy_unreadable: $name: ${PortableRestoreDiagnostics.unexpected(e)}")
            null
        }
    }

    private class AndroidBackend(
        context: Context,
        private val artifacts: List<PortablePackage.ValidatedArtifact>,
        private val incomingIsEmpty: Boolean
    ) : Backend {
        private val app = context.applicationContext
        private val initiallyProvisioned = LoreBookStore.isProvisioned(app)

        override fun incoming(): LorebookPortableData? {
            val matches = artifacts.filter {
                it.type == PortablePackage.TYPE_SQLCIPHER_DB && it.entryName == "lorebook.db"
            }
            if (matches.isEmpty() && incomingIsEmpty) {
                return LorebookPortableData(emptyList(), emptyList(), emptyList())
            }
            if (matches.size != 1) return null
            val artifact = matches.single()
            if (artifact.keySemantics != PortablePackage.KEY_SEMANTICS_PASSPHRASE) return null
            val key = decodeHex(artifact.databaseKeyHex ?: return null) ?: return null
            val store = try {
                LoreBookStore.openForTest(app, artifact.stagedFile.absolutePath, key)
            } catch (_: Exception) {
                key.fill(0)
                return null
            }
            return try {
                store.integrityCheck()?.let { return null }
                store.exportPortableData()
            } catch (_: Exception) {
                null
            } finally {
                store.close()
                key.fill(0)
            }
        }

        override fun snapshot(): LorebookPortableData? {
            if (!initiallyProvisioned) return LorebookPortableData(emptyList(), emptyList(), emptyList())
            return try { LoreBookStore.getInstance(app).exportPortableData() }
            catch (_: Exception) { null }
        }

        override fun wasProvisionedBeforeStage(): Boolean = initiallyProvisioned

        override fun replace(data: LorebookPortableData): Boolean = try {
            LoreBookStore.getInstance(app).replacePortableData(data)
        } catch (_: Exception) {
            false
        }

        override fun restoreOriginal(data: LorebookPortableData): Boolean {
            return replace(data)
        }

        override fun removeProvisionedStore(): Boolean {
            LoreBookStore.invalidateInstance()
            val database = app.getDatabasePath(LoreBookStore.DATABASE_NAME)
            val files = listOf(
                database,
                File(database.path + "-wal"),
                File(database.path + "-shm"),
                File(database.path + "-journal")
            )
            if (files.any { it.exists() && !it.delete() }) return false
            return DatabaseKeys.clearExisting(app, DatabaseKeys.KEY_LOREBOOK)
        }

        private fun decodeHex(value: String): ByteArray? {
            if (value.length != 64 || !value.matches(Regex("^[0-9a-fA-F]+$"))) return null
            return try {
                ByteArray(value.length / 2) { index ->
                    value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private companion object {
        const val CURRENT_JSON = "current.json"
        const val DESIRED_JSON = "desired.json"
        const val MAX_JSON_BYTES = 256L * 1024L * 1024L
    }
}
