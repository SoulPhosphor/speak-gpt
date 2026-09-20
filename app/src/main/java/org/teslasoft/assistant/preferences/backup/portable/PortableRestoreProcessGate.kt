/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import android.os.Process
import java.io.File
import java.util.concurrent.CountDownLatch
import org.json.JSONObject
import org.teslasoft.assistant.preferences.backup.DirectDatabaseRestoreCoordinator
import org.teslasoft.assistant.preferences.backup.DirectProfileImageRestoreRecovery
import org.teslasoft.assistant.util.AtomicFileWriter

/**
 * Durable guard against a pre-restore activity writing cached state after the
 * transaction starts. The marker belongs to one OS process. A newly launched
 * process clears the old marker only after outer-journal recovery has settled.
 */
object PortableRestoreProcessGate {
    enum class Phase { APPLYING, RESTART_PENDING }

    @Volatile private var startupRecovery: CountDownLatch? = null
    @Volatile private var startupRecoverySucceeded = true

    fun mark(context: Context, phase: Phase): Boolean = AtomicFileWriter.writeAndVerify(
        file(context),
        JSONObject()
            .put("version", VERSION)
            .put("owner_pid", Process.myPid())
            .put("phase", phase.name)
            .toString()
    )

    fun blocksCurrentProcess(context: Context): Boolean {
        if (UnifiedPortableRestore.journalRoot(context).exists() ||
            DirectDatabaseRestoreCoordinator.hasPending(context) ||
            DirectProfileImageRestoreRecovery.hasPending(context)
        ) return true
        val marker = file(context)
        val state = read(context) ?: return marker.exists()
        return state.first == Process.myPid()
    }

    fun beginStartupRecoveryIfNeeded(context: Context) {
        if (file(context).exists() || UnifiedPortableRestore.journalRoot(context).exists() ||
            DirectDatabaseRestoreCoordinator.hasPending(context) ||
            DirectProfileImageRestoreRecovery.hasPending(context)
        ) {
            startupRecoverySucceeded = false
            startupRecovery = CountDownLatch(1)
        }
    }

    fun finishStartupRecovery(succeeded: Boolean) {
        startupRecoverySucceeded = succeeded
        startupRecovery?.countDown()
    }

    /** Called only from an activity-owned worker, never from the main thread. */
    fun awaitStartupRecovery(): Boolean {
        startupRecovery?.await()
        return startupRecoverySucceeded
    }

    /** Call only after startup recovery has completed successfully. */
    fun clearRecoveredPreviousProcess(context: Context): Boolean {
        val marker = file(context)
        val state = read(context) ?: return marker.delete() || !marker.exists()
        if (state.first == Process.myPid()) return false
        return clear(context)
    }

    fun clear(context: Context): Boolean {
        val marker = file(context)
        return marker.delete() || !marker.exists()
    }

    private fun read(context: Context): Pair<Int, Phase>? {
        return try {
            val marker = file(context)
            if (!marker.isFile || marker.length() > MAX_BYTES) return null
            val root = JSONObject(marker.readText(Charsets.UTF_8))
            if (root.optInt("version", -1) != VERSION) return null
            root.getInt("owner_pid") to Phase.valueOf(root.getString("phase"))
        } catch (_: Exception) {
            null
        }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private const val VERSION = 1
    private const val FILE_NAME = "portable_restore_process_gate.json"
    private const val MAX_BYTES = 16L * 1024L
}
