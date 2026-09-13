/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.backup

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.teslasoft.assistant.preferences.backup.portable.PortablePackage
import org.teslasoft.assistant.preferences.backup.portable.PortableRestoreMode
import org.teslasoft.assistant.preferences.backup.portable.ProfileImageRestoreParticipant
import org.teslasoft.assistant.preferences.backup.portable.SelectedCategoryRestoreTransaction

/** Durable owner for the direct portable Profile Image participant. */
internal object DirectProfileImageRestoreRecovery {
    private val active = AtomicBoolean(false)
    private val recoveryThread = ThreadLocal<Boolean>()

    fun hasPending(context: Context): Boolean = journal(context).exists()

    fun blocksStore(context: Context): Boolean =
        recoveryThread.get() != true && (active.get() || journal(context).exists())

    fun execute(
        context: Context,
        artifacts: List<PortablePackage.ValidatedArtifact>
    ): DatabaseRepairManager.Outcome = RecoveryOperationGate.runExclusive {
        val app = context.applicationContext
        active.set(true)
        recoveryThread.set(true)
        try {
            if (!recoverPendingLocked(app)) {
                return@runExclusive DatabaseRepairManager.Outcome(
                    false, null, "pending profile image restore recovery could not be completed"
                )
            }
            val root = root(app)
            if (root.exists() && !root.deleteRecursively()) {
                return@runExclusive DatabaseRepairManager.Outcome(
                    false, null, "profile image restore staging unavailable"
                )
            }
            if (!root.mkdirs()) {
                return@runExclusive DatabaseRepairManager.Outcome(
                    false, null, "profile image restore staging unavailable"
                )
            }
            val participant = ProfileImageRestoreParticipant(
                app,
                artifacts,
                PortableRestoreMode.REPLACE,
                emptySet(),
                staging(app)
            )
            when (val result = SelectedCategoryRestoreTransaction.execute(
                journal(app), listOf(participant)
            )) {
                SelectedCategoryRestoreTransaction.Result.Success -> {
                    root.deleteRecursively()
                    DatabaseRepairManager.Outcome(true, null, null)
                }
                is SelectedCategoryRestoreTransaction.Result.Failed ->
                    DatabaseRepairManager.Outcome(
                        false, null, "profile image restore failed: ${result.reason}"
                    )
            }
        } finally {
            recoveryThread.remove()
            active.set(false)
        }
    }

    fun recoverPending(context: Context): Boolean = RecoveryOperationGate.runExclusive {
        val app = context.applicationContext
        active.set(true)
        recoveryThread.set(true)
        try {
            recoverPendingLocked(app)
        } finally {
            recoveryThread.remove()
            active.set(false)
        }
    }

    private fun recoverPendingLocked(context: Context): Boolean {
        val root = root(context)
        val journal = journal(context)
        if (!journal.exists()) return !root.exists() || root.deleteRecursively()
        val participant = ProfileImageRestoreParticipant(
            context,
            emptyList(),
            PortableRestoreMode.REPLACE,
            emptySet(),
            staging(context)
        )
        val recovered = SelectedCategoryRestoreTransaction.recover(
            journal,
            mapOf(participant.categoryKey to participant)
        )
        if (recovered) root.deleteRecursively()
        return recovered
    }

    private fun root(context: Context) = File(context.filesDir, ROOT_DIR)
    private fun journal(context: Context) = File(root(context), "journal")
    private fun staging(context: Context) = File(root(context), "staging")

    private const val ROOT_DIR = "direct_profile_image_restore"
}
