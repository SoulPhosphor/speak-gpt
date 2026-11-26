/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.backup.portable

/**
 * When the restore's foreground service cannot start (Owner, 2026-11-25): how
 * the failure is classified and the Error Log wording. Kept free of Android
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

    private fun cause(e: Throwable): String =
        "${e.javaClass.name}: ${e.message?.ifBlank { null } ?: "no message"}"

    /**
     * The restore was cancelled before it wrote anything. The failure message
     * has not been shown at this point; [shown] is written when it is.
     */
    fun notStarted(e: Throwable, phase: UnifiedPortableRestoreCoordinator.ProgressPhase?): String =
        "Restore Service: The restore service could not start: ${cause(e)}. " +
            "Operation: unified backup restore, ${phaseText(phase)}. " +
            "The restore did not run and nothing was changed. " +
            "The failure message has not been shown yet; it appears when the restore screen is open. " +
            "The restore can be tried again."

    /** A restore already applying could not have its service started again: it was not cancelled. */
    fun stillApplying(e: Throwable): String =
        "Restore Service: " +
            (if (androidRefused(e)) "Android refused a further start of the restore service"
            else "A further start of the restore service failed") +
            " while the restore was applying: ${cause(e)}. " +
            "The restore was not cancelled. It may continue without the foreground-service protection " +
            "and may be interrupted by Android. An incomplete restore is finished or rolled back by the " +
            "existing recovery at the next app launch."

    const val SHOWN = "Restore Service: The restore start failure message was shown on the restore screen."
}

/**
 * What the restore screen does when a decision, unlock or begin is refused
 * by the coordinator (Owner, 2026-11-25), as plain functions for the JVM tests.
 */
object PortableRestoreScreenRule {

    enum class Refusal {
        /** An active or finished restore is shown from its actual state; no failure is reported. */
        REDRAW,
        /** Another restore holds the coordinator: "Restoration Unavailable". */
        UNAVAILABLE,
        /** No restore is active: "Restoration Failed", "Nothing was changed.", state changed. */
        STATE_CHANGED,
        /** The backup itself could not be prepared: the existing validation message. */
        VALIDATION
    }

    /**
     * [startingNew]: the refusal came from beginning a new restore with a
     * prepared package (rather than a decision or unlock for one already
     * going). [active]: the coordinator holds a session.
     */
    fun refusal(startingNew: Boolean, active: Boolean, state: UnifiedPortableRestoreCoordinator.State): Refusal = when {
        startingNew && active -> Refusal.UNAVAILABLE
        startingNew -> Refusal.VALIDATION
        active || state is UnifiedPortableRestoreCoordinator.State.Terminal ||
            state is UnifiedPortableRestoreCoordinator.State.StartFailed -> Refusal.REDRAW
        else -> Refusal.STATE_CHANGED
    }

    /** The second line under "Nothing was changed." for a start failure. */
    fun startFailureMessage(androidRefused: Boolean): Int =
        if (androidRefused) org.teslasoft.assistant.R.string.portable_restore_start_refused
        else org.teslasoft.assistant.R.string.portable_restore_start_failed
}
