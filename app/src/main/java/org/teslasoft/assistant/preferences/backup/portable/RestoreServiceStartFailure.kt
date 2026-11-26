/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.backup.portable

/**
 * When the restore's foreground service cannot start (Owner, 2026-11-25): how
 * the failure is classified and the error-log wording. Kept free of Android
 * types so the choices are tested on the JVM. Only exception types and
 * messages are written, never backup or chat content.
 */
object RestoreServiceStartFailure {

    const val TAG = "RestoreService"

    /** Android refuses a foreground service with this exception (and its subclasses). */
    const val START_NOT_ALLOWED = "android.app.ForegroundServiceStartNotAllowedException"

    /** Whether Android itself refused the start, rather than an unexpected failure. */
    fun androidRefused(e: Throwable): Boolean {
        var type: Class<*>? = e.javaClass
        while (type != null) {
            if (type.name == START_NOT_ALLOWED) return true
            type = type.superclass
        }
        return false
    }

    /** The restore step the failure interrupted, as the log names it. */
    fun phaseText(phase: UnifiedPortableRestoreCoordinator.ProgressPhase?): String = when (phase) {
        UnifiedPortableRestoreCoordinator.ProgressPhase.DECODING -> "decoding"
        UnifiedPortableRestoreCoordinator.ProgressPhase.PREFLIGHT -> "checking"
        // Confirmed, but applying had not begun: nothing was written.
        UnifiedPortableRestoreCoordinator.ProgressPhase.APPLYING -> "before applying"
        UnifiedPortableRestoreCoordinator.ProgressPhase.RESTARTING -> "restarting"
        null -> "step not recorded"
    }

    private fun runningPhaseText(phase: UnifiedPortableRestoreCoordinator.ProgressPhase?): String = when (phase) {
        UnifiedPortableRestoreCoordinator.ProgressPhase.DECODING -> "decoding the backup"
        UnifiedPortableRestoreCoordinator.ProgressPhase.PREFLIGHT -> "checking the backup"
        UnifiedPortableRestoreCoordinator.ProgressPhase.APPLYING -> "applying changes"
        UnifiedPortableRestoreCoordinator.ProgressPhase.RESTARTING -> "restarting the app"
        null -> "running"
    }

    private fun cause(e: Throwable): String =
        "${e.javaClass.name}: ${e.message?.ifBlank { null } ?: "no message"}"

    /**
     * The restore was cancelled before it wrote anything. The failure message
     * has not been shown at this point; [shown] is written when it is.
     */
    fun notStarted(e: Throwable, phase: UnifiedPortableRestoreCoordinator.ProgressPhase?): String =
        "Restore Service: The restore service could not start: ${cause(e)}. " +
            "Operation: unified backup restore, ${phaseText(phase)}. " +
            "The restoration stopped before any backup data was applied. " +
            "The message has not been shown yet; it will appear when the restore screen opens. " +
            "Keep the restore screen open and try again."

    /** An additional service request failed while an existing restoration worker was running. */
    fun stillRunning(
        e: Throwable,
        phase: UnifiedPortableRestoreCoordinator.ProgressPhase?
    ): String =
        "Restore Service: " +
            (if (androidRefused(e)) "Android refused a further start of the restore service"
            else "A further start of the restore service failed") +
            " while the restoration was ${runningPhaseText(phase)}: ${cause(e)}. " +
            "The failed additional service request did not cancel the restoration. " +
            "The existing restoration continues from its current step."

    /** A service-start failure arrived after the coordinator no longer held a restoration. */
    fun noActiveRestore(e: Throwable): String =
        "Restore Service: The restore service could not start: ${cause(e)}. " +
            "No active restoration was found when this failure was handled. " +
            "This service failure does not establish the outcome of an earlier restoration."

    /** A completed or failed result must not be replaced by a later service-start failure. */
    fun terminalPreserved(e: Throwable): String =
        "Restore Service: The restore service could not start: ${cause(e)}. " +
            "The restoration had already ended, so its result and recovery information were preserved. " +
            "Open Backup & Restore to review the result or finish recovery."

    const val SHOWN = "Restore Service: The restore start failure message was shown on the restore screen."
}

object PortableRestoreScreenRule {
    fun startFailureMessage(androidRefused: Boolean): Int =
        if (androidRefused) org.teslasoft.assistant.R.string.portable_restore_start_refused
        else org.teslasoft.assistant.R.string.portable_restore_start_failed
}
