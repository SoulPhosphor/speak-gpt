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
    private val backend: Backend
) : SelectedCategoryRestoreTransaction.Participant {

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
        AndroidBackend(context.applicationContext, artifacts, incomingIsEmpty)
    )

    override val categoryKey: String = PortableRestoreCategory.LOREBOOKS.key

    var report: LorebookCategoryPlanner.Report? = null
        private set

    private var backup: LorebookPortableData? = null

    override fun validate(): Boolean {
        backup = backend.incoming()
        return backup != null
    }

    override fun stage(): Boolean {
        val incoming = backup ?: return false
        val current = backend.snapshot() ?: return false
        val planned = LorebookCategoryPlanner.plan(current, incoming, mode) as?
            LorebookCategoryPlanner.Result.Ready ?: return false
        report = planned.report
        return try {
            if (stagingRoot.exists()) {
                if (!stagingRoot.isDirectory || !stagingRoot.listFiles().isNullOrEmpty()) return false
            } else if (!stagingRoot.mkdirs()) return false
            if (!RestoreProvisioningState.write(stagingRoot, backend.wasProvisionedBeforeStage())) return false
            write(CURRENT_JSON, current) && write(DESIRED_JSON, planned.data) &&
                read(CURRENT_JSON) != null && read(DESIRED_JSON) != null
        } catch (_: Exception) {
            false
        }
    }

    override fun apply(): Boolean = read(DESIRED_JSON)?.let(backend::replace) == true

    override fun rollback(): Boolean {
        val current = read(CURRENT_JSON) ?: return false
        val wasProvisioned = RestoreProvisioningState.read(stagingRoot) ?: return false
        if (!backend.restoreOriginal(current)) return false
        return wasProvisioned || backend.removeProvisionedStore()
    }

    override fun cleanup() {
        stagingRoot.deleteRecursively()
    }

    private fun write(name: String, data: LorebookPortableData): Boolean = try {
        File(stagingRoot, name).writeText(LorebookPortableCodec.toJson(data), Charsets.UTF_8)
        true
    } catch (_: Exception) {
        false
    }

    private fun read(name: String): LorebookPortableData? {
        val file = File(stagingRoot, name)
        if (!file.isFile || file.length() > MAX_JSON_BYTES) return null
        return try { LorebookPortableCodec.parse(file.readText(Charsets.UTF_8)) }
        catch (_: Exception) { null }
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
