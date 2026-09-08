/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogSnapshot
import org.teslasoft.assistant.util.AtomicFileWriter
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** File/catalog replacement transaction with a durable private rollback
 * journal. A pending journal is always rolled back at startup unless it was
 * durably marked complete. */
object GeneratedImageRestoreTransaction {

    interface CatalogBackend {
        fun snapshot(): GeneratedImageCatalogSnapshot?
        fun replace(snapshot: GeneratedImageCatalogSnapshot): Boolean
    }

    enum class Failure {
        PENDING_RECOVERY,
        INVALID_CURRENT_CATALOG,
        INVALID_RESTORE_SET,
        JOURNAL_FAILED,
        APPLY_FAILED,
        ROLLBACK_FAILED
    }

    sealed class Result {
        data object Success : Result()
        data class Failed(val reason: Failure) : Result()
    }

    enum class InterruptionPoint { AFTER_FILES, AFTER_CATALOG }
    private class SimulatedInterruption : Error("simulated process interruption")

    fun replace(
        journalRoot: File,
        imagesDir: File,
        desired: GeneratedImageCatalogSnapshot,
        incomingAssets: Map<String, File>,
        catalog: CatalogBackend,
        interruptionPoint: InterruptionPoint? = null
    ): Result {
        if (journalRoot.exists()) return Result.Failed(Failure.PENDING_RECOVERY)
        val current = catalog.snapshot()
            ?: return Result.Failed(Failure.INVALID_CURRENT_CATALOG)
        if (GeneratedImagePortableCatalog.validate(current) != null) {
            return Result.Failed(Failure.INVALID_CURRENT_CATALOG)
        }
        if (GeneratedImagePortableCatalog.validate(desired) != null ||
            incomingAssets.keys != desired.active.map { it.assetFileName }.toSet()
        ) return Result.Failed(Failure.INVALID_RESTORE_SET)

        val incomingDir = File(journalRoot, INCOMING_DIR)
        val originalDir = File(journalRoot, ORIGINAL_DIR)
        if (!incomingDir.mkdirs() || !originalDir.mkdirs()) {
            journalRoot.deleteRecursively()
            return Result.Failed(Failure.JOURNAL_FAILED)
        }

        val originalFiles = LinkedHashSet<String>()
        try {
            for (name in current.active.map { it.assetFileName }.toSet()) {
                if (!GeneratedImagePortableCatalog.safeAssetName(name)) throw IllegalStateException()
                val source = File(imagesDir, name)
                if (source.isFile) {
                    if (!copyVerified(source, File(originalDir, name), replace = false)) {
                        throw IllegalStateException()
                    }
                    originalFiles.add(name)
                }
            }
            for ((name, source) in incomingAssets) {
                if (!GeneratedImagePortableCatalog.safeAssetName(name) || !source.isFile ||
                    !copyVerified(source, File(incomingDir, name), replace = false)
                ) throw IllegalStateException()
            }
            if (!writeState(journalRoot, Phase.PREPARED, current, desired, originalFiles)) {
                throw IllegalStateException()
            }
        } catch (_: Exception) {
            journalRoot.deleteRecursively()
            return Result.Failed(Failure.JOURNAL_FAILED)
        }

        return try {
            if (!imagesDir.exists() && !imagesDir.mkdirs()) throw IllegalStateException()
            if (!writeState(journalRoot, Phase.APPLYING, current, desired, originalFiles)) {
                throw IllegalStateException()
            }
            for (name in incomingAssets.keys) {
                if (!copyVerified(File(incomingDir, name), File(imagesDir, name), replace = true)) {
                    throw IllegalStateException()
                }
            }
            if (interruptionPoint == InterruptionPoint.AFTER_FILES) throw SimulatedInterruption()

            if (!catalog.replace(desired)) throw IllegalStateException()
            if (!writeState(journalRoot, Phase.CATALOG_REPLACED, current, desired, originalFiles)) {
                throw IllegalStateException()
            }
            if (interruptionPoint == InterruptionPoint.AFTER_CATALOG) throw SimulatedInterruption()

            val desiredNames = incomingAssets.keys
            for (retired in current.active.map { it.assetFileName }.toSet() - desiredNames) {
                val file = File(imagesDir, retired)
                if (file.exists() && !file.delete()) throw IllegalStateException()
            }
            if (!writeState(journalRoot, Phase.COMPLETE, current, desired, originalFiles)) {
                throw IllegalStateException()
            }
            journalRoot.deleteRecursively()
            Result.Success
        } catch (interruption: SimulatedInterruption) {
            throw interruption
        } catch (_: Exception) {
            if (rollback(journalRoot, imagesDir, catalog)) {
                Result.Failed(Failure.APPLY_FAILED)
            } else {
                Result.Failed(Failure.ROLLBACK_FAILED)
            }
        }
    }

    /** Startup recovery. A complete transaction only needs journal cleanup;
     * every other durable phase is restored to the exact pre-restore set. */
    fun recover(journalRoot: File, imagesDir: File, catalog: CatalogBackend): Boolean {
        if (!journalRoot.exists()) return true
        val stateFile = File(journalRoot, STATE_FILE)
        if (!stateFile.exists()) {
            // Journal preparation failed before the durable state record; live
            // files are not touched until after that record is written.
            return journalRoot.deleteRecursively() || !journalRoot.exists()
        }
        val state = readState(journalRoot)
        // An existing but unreadable journal is not deletion authority and
        // must never be guessed away; leave it for explicit recovery.
        if (state == null) return false
        if (state.phase == Phase.COMPLETE) {
            return journalRoot.deleteRecursively() || !journalRoot.exists()
        }
        return rollback(journalRoot, imagesDir, catalog)
    }

    private fun rollback(journalRoot: File, imagesDir: File, catalog: CatalogBackend): Boolean {
        val state = readState(journalRoot) ?: return false
        val originalDir = File(journalRoot, ORIGINAL_DIR)
        val allNames = state.original.active.map { it.assetFileName }.toSet() +
            state.desired.active.map { it.assetFileName }.toSet()
        var filesOk = true
        for (name in allNames) {
            if (!GeneratedImagePortableCatalog.safeAssetName(name)) return false
            val target = File(imagesDir, name)
            val temp = File(imagesDir, "$name.restoretmp")
            runCatching { if (temp.exists()) temp.delete() }
            if (name in state.originalFiles) {
                if (!copyVerified(File(originalDir, name), target, replace = true)) filesOk = false
            } else if (target.exists() && !target.delete()) {
                filesOk = false
            }
        }
        val catalogOk = filesOk && catalog.replace(state.original)
        if (!catalogOk) return false
        return journalRoot.deleteRecursively() || !journalRoot.exists()
    }

    private enum class Phase { PREPARED, APPLYING, CATALOG_REPLACED, COMPLETE }

    private data class State(
        val phase: Phase,
        val original: GeneratedImageCatalogSnapshot,
        val desired: GeneratedImageCatalogSnapshot,
        val originalFiles: Set<String>
    )

    private fun writeState(
        root: File,
        phase: Phase,
        original: GeneratedImageCatalogSnapshot,
        desired: GeneratedImageCatalogSnapshot,
        originalFiles: Set<String>
    ): Boolean {
        val json = JSONObject()
            .put("version", 1)
            .put("phase", phase.name)
            .put("original", JSONObject(GeneratedImagePortableCatalog.toJson(original)))
            .put("desired", JSONObject(GeneratedImagePortableCatalog.toJson(desired)))
            .put("original_files", JSONArray().apply { originalFiles.sorted().forEach { put(it) } })
            .toString()
        return AtomicFileWriter.writeAndVerify(File(root, STATE_FILE), json)
    }

    private fun readState(root: File): State? {
        return try {
            val stateFile = File(root, STATE_FILE)
            if (!stateFile.isFile || stateFile.length() > GeneratedImagePortableCatalog.MAX_JSON_CHARS * 2L) return null
            val rootJson = JSONObject(stateFile.readText(Charsets.UTF_8))
            if (rootJson.optInt("version", -1) != 1) return null
            val original = GeneratedImagePortableCatalog.parse(rootJson.getJSONObject("original").toString())
            val desired = GeneratedImagePortableCatalog.parse(rootJson.getJSONObject("desired").toString())
            if (original !is GeneratedImagePortableCatalog.ParseResult.Ok ||
                desired !is GeneratedImagePortableCatalog.ParseResult.Ok
            ) return null
            val filesJson = rootJson.getJSONArray("original_files")
            val files = LinkedHashSet<String>()
            repeat(filesJson.length()) {
                val name = filesJson.getString(it)
                if (!GeneratedImagePortableCatalog.safeAssetName(name) || !files.add(name)) return null
            }
            State(
                Phase.valueOf(rootJson.getString("phase")),
                original.snapshot,
                desired.snapshot,
                files
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun copyVerified(source: File, target: File, replace: Boolean): Boolean {
        if (!source.isFile) return false
        val parent = target.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        if (!replace && target.exists()) return false
        val temp = File(parent, target.name + ".restoretmp")
        return try {
            if (temp.exists() && !temp.delete()) return false
            val sourceDigest = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        sourceDigest.update(buffer, 0, count)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (!MessageDigest.isEqual(sourceDigest.digest(), sha256(temp))) {
                temp.delete()
                return false
            }
            if (target.exists() && (!replace || !target.delete())) {
                temp.delete()
                return false
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                return false
            }
            true
        } catch (_: Exception) {
            runCatching { if (temp.exists()) temp.delete() }
            false
        }
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private const val STATE_FILE = "state.json"
    private const val INCOMING_DIR = "incoming"
    private const val ORIGINAL_DIR = "original"
}
