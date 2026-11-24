/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The saved Index Conversations setting (memory development plan G.16a,
 * Step 11). It starts off and is the switch every hookup reads. Turning it
 * off stops indexing at the next safe point and keeps the index; only
 * Delete Conversation Index removes it.
 */
object ConversationIndexSetting {
    private const val KEY_ON = "index_conversations"

    @Volatile private var cached: Boolean? = null

    /** Tests only: replaces the saved value until set back to null. */
    @Volatile var overrideForTest: Boolean? = null

    fun isOn(context: Context): Boolean {
        overrideForTest?.let { return it }
        cached?.let { return it }
        return try {
            ConversationIndexPrefs.of(context).getBoolean(KEY_ON, false).also { cached = it }
        } catch (_: Exception) {
            false
        }
    }

    fun set(context: Context, on: Boolean) {
        cached = on
        try { ConversationIndexPrefs.of(context).edit().putBoolean(KEY_ON, on).apply() } catch (_: Exception) { }
        ConversationIndexConditions.changed()
    }
}

/** The index's own small preferences file: the setting and the ongoing conditions. Holds no content. */
internal object ConversationIndexPrefs {
    private const val FILE = "conversation_index"
    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

/** Why indexing is paused for its model (G20 2a, Owner 2026-11-24). */
enum class IndexModelProblem { REMOVED, CANNOT_LOAD }

/** The notice to show for an ongoing condition. */
enum class IndexNotice { MODEL_REMOVED, MODEL_CANNOT_LOAD, STOPPED }

/**
 * The ongoing conditions of conversation indexing (G20, Owner 2026-11-24),
 * saved so that closing a notice or the app never erases them:
 *
 *  - the model is unavailable: indexing is paused with the setting on, and
 *    resumes once the model works again;
 *  - recovery failed: the setting was turned off, and turning it on again
 *    tries recovery again;
 *  - a rebuild is running (in memory only).
 *
 * Each condition is cleared only when recovery is verified. A new
 * condition gets one notice; the same ongoing condition never gets another,
 * except after the user asks again by turning the setting on.
 */
object ConversationIndexConditions {
    private const val KEY_MODEL_PROBLEM = "model_problem"
    private const val KEY_MODEL_NOTICED = "model_problem_noticed"
    private const val KEY_STOPPED = "recovery_failed"
    private const val KEY_STOPPED_NOTICED = "recovery_failed_noticed"
    private const val KEY_RECOVERY_PENDING = "recovery_pending"

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** A rebuild is running or about to run. */
    @Volatile var rebuilding: Boolean = false
        internal set(value) {
            field = value
            changed()
        }

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    internal fun changed() {
        for (listener in listeners) try { listener() } catch (_: Exception) { }
    }

    fun modelProblem(context: Context): IndexModelProblem? = read {
        ConversationIndexPrefs.of(context).getString(KEY_MODEL_PROBLEM, null)
            ?.let { name -> IndexModelProblem.entries.firstOrNull { it.name == name } }
    }

    /** Returns true when this is a new condition (none, or a different cause, before). */
    internal fun setModelProblem(context: Context, problem: IndexModelProblem): Boolean {
        if (modelProblem(context) == problem) return false
        write(context) { putString(KEY_MODEL_PROBLEM, problem.name).putBoolean(KEY_MODEL_NOTICED, false) }
        return true
    }

    /** The model was verified to work again. Returns true when a condition was cleared. */
    internal fun clearModelProblem(context: Context): Boolean {
        if (modelProblem(context) == null) return false
        write(context) { remove(KEY_MODEL_PROBLEM).remove(KEY_MODEL_NOTICED) }
        return true
    }

    fun recoveryFailed(context: Context): Boolean = read {
        ConversationIndexPrefs.of(context).getBoolean(KEY_STOPPED, false)
    } ?: false

    /**
     * Recovery failed. With [notice], a notice is due again even for the same
     * condition, since it follows a new attempt; without it (the setting was
     * already off, so nothing was turned off) only the status shows it.
     */
    internal fun setRecoveryFailed(context: Context, notice: Boolean) {
        write(context) { putBoolean(KEY_STOPPED, true).putBoolean(KEY_STOPPED_NOTICED, !notice) }
    }

    /** Recovery was verified: the index opened, passed its check and is in use again. */
    internal fun clearRecoveryFailed(context: Context) {
        if (!recoveryFailed(context)) return
        write(context) { remove(KEY_STOPPED).remove(KEY_STOPPED_NOTICED) }
    }

    /** A recovery that could not run yet (the key store was unavailable); tried again at the next start. */
    fun recoveryPending(context: Context): Boolean = read {
        ConversationIndexPrefs.of(context).getBoolean(KEY_RECOVERY_PENDING, false)
    } ?: false

    internal fun setRecoveryPending(context: Context, pending: Boolean) {
        write(context) { if (pending) putBoolean(KEY_RECOVERY_PENDING, true) else remove(KEY_RECOVERY_PENDING) }
    }

    /** The notice still due, if any: recovery failure first, then a model problem while the setting is on. */
    fun pendingNotice(context: Context): IndexNotice? {
        val prefs = read { ConversationIndexPrefs.of(context) } ?: return null
        return noticeFor(
            settingOn = ConversationIndexSetting.isOn(context),
            recoveryFailed = prefs.getBoolean(KEY_STOPPED, false),
            recoveryFailedNoticed = prefs.getBoolean(KEY_STOPPED_NOTICED, false),
            modelProblem = modelProblem(context),
            modelNoticed = prefs.getBoolean(KEY_MODEL_NOTICED, false)
        )
    }

    /** The notice was shown; the same ongoing condition gets no other. */
    fun markNoticed(context: Context, notice: IndexNotice) {
        write(context, notify = false) {
            when (notice) {
                IndexNotice.STOPPED -> putBoolean(KEY_STOPPED_NOTICED, true)
                IndexNotice.MODEL_REMOVED, IndexNotice.MODEL_CANNOT_LOAD -> putBoolean(KEY_MODEL_NOTICED, true)
            }
        }
    }

    /** The decision behind [pendingNotice]. */
    fun noticeFor(
        settingOn: Boolean,
        recoveryFailed: Boolean,
        recoveryFailedNoticed: Boolean,
        modelProblem: IndexModelProblem?,
        modelNoticed: Boolean
    ): IndexNotice? = when {
        recoveryFailed -> if (recoveryFailedNoticed) null else IndexNotice.STOPPED
        settingOn && modelProblem != null && !modelNoticed -> when (modelProblem) {
            IndexModelProblem.REMOVED -> IndexNotice.MODEL_REMOVED
            IndexModelProblem.CANNOT_LOAD -> IndexNotice.MODEL_CANNOT_LOAD
        }
        else -> null
    }

    private inline fun <T> read(block: () -> T): T? = try { block() } catch (_: Exception) { null }

    private inline fun write(context: Context, notify: Boolean = true, block: SharedPreferences.Editor.() -> SharedPreferences.Editor) {
        try { ConversationIndexPrefs.of(context).edit().block().commit() } catch (_: Exception) { }
        if (notify) changed()
    }
}
