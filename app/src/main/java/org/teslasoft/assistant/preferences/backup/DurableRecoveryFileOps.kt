/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.backup

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** File primitives used at the direct-database crash boundary. */
internal object DurableRecoveryFileOps {
    fun writeAtomic(target: File, bytes: ByteArray): Boolean {
        val parent = target.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val temporary = File(parent, target.name + ".writing")
        return try {
            writeAndSync(temporary, bytes)
            if (!atomicReplace(temporary, target)) return false
            target.readBytes().contentEquals(bytes)
        } catch (_: Exception) {
            runCatching { temporary.delete() }
            false
        }
    }

    fun copyAndSync(source: File, target: File): Boolean {
        val parent = target.parentFile ?: return false
        if (!source.isFile || (!parent.exists() && !parent.mkdirs())) return false
        return try {
            source.inputStream().buffered().use { input ->
                FileOutputStream(target).buffered().use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            syncFile(target) && syncDirectory(parent)
        } catch (_: Exception) {
            false
        }
    }

    fun atomicReplace(staged: File, target: File): Boolean {
        if (staged.parentFile?.canonicalFile != target.parentFile?.canonicalFile) return false
        return try {
            Files.move(
                staged.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            syncDirectory(target.parentFile ?: return false)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun deleteAndSync(file: File): Boolean {
        if (!file.exists()) return true
        val parent = file.parentFile ?: return false
        return try {
            if (!file.delete()) false else syncDirectory(parent)
        } catch (_: Exception) {
            false
        }
    }

    fun syncFile(file: File): Boolean = try {
        FileOutputStream(file, true).use {
            it.flush()
            it.fd.sync()
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun writeAndSync(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    private fun syncDirectory(directory: File): Boolean {
        var descriptor: java.io.FileDescriptor? = null
        return try {
            descriptor = Os.open(
                directory.path,
                OsConstants.O_RDONLY,
                0
            )
            Os.fsync(descriptor)
            true
        } catch (_: Exception) {
            false
        } finally {
            descriptor?.let { runCatching { Os.close(it) } }
        }
    }
}
