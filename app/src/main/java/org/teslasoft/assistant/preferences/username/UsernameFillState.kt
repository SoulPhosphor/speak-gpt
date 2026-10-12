/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.username

import android.content.Context
import androidx.core.content.edit

/**
 * Durable state of Apply to Previous Chats with No Name: whether a retry is
 * pending for the next app start, and the notices waiting for the next
 * screen. A background run cannot show a pop-up, so each problem is queued
 * here and shown once per unresolved incident: a notice already shown is not
 * queued again until a later run finds that problem resolved. Damaged chats
 * are tracked by id, so only newly damaged chats produce a new notice.
 * Holds chat ids only — titles stay in the encrypted Damaged Chat Files log.
 */
object UsernameFillState {

    private const val FILE = "username_fill_state"
    private const val KEY_RETRY = "retry_pending"
    private const val KEY_QUEUED = "queued_notices"
    private const val KEY_SHOWN = "shown_notices"
    private const val KEY_QUEUED_DAMAGED = "queued_damaged"
    private const val KEY_SHOWN_DAMAGED = "shown_damaged"

    enum class Notice { RESTORE, STORAGE, LOCKED, OTHER, DAMAGED }

    data class Pending(val notices: Set<Notice>, val damagedChatIds: Set<String>)

    private val lock = Any()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun retryPending(context: Context): Boolean = try {
        prefs(context).getBoolean(KEY_RETRY, false)
    } catch (_: Exception) {
        false
    }

    /** Keeps the fill pending for the next app start without queuing a notice. */
    internal fun deferToNextStart(context: Context) {
        try {
            synchronized(lock) { prefs(context).edit(commit = true) { putBoolean(KEY_RETRY, true) } }
        } catch (_: Exception) { }
    }

    internal fun finish(context: Context, outcome: DefaultUsernameFill.RunOutcome, complete: Boolean) {
        try {
            synchronized(lock) {
                val p = prefs(context)
                val queued = names(p.getStringSet(KEY_QUEUED, null))
                val shown = names(p.getStringSet(KEY_SHOWN, null))
                val update = NoticeRules.afterRun(
                    queued, shown,
                    p.getStringSet(KEY_QUEUED_DAMAGED, null).orEmpty(),
                    p.getStringSet(KEY_SHOWN_DAMAGED, null).orEmpty(),
                    outcome
                )
                p.edit(commit = true) {
                    putBoolean(KEY_RETRY, !complete)
                    putStringSet(KEY_QUEUED, update.queued.mapTo(HashSet()) { it.name })
                    putStringSet(KEY_SHOWN, update.shown.mapTo(HashSet()) { it.name })
                    putStringSet(KEY_QUEUED_DAMAGED, HashSet(update.queuedDamaged))
                    putStringSet(KEY_SHOWN_DAMAGED, HashSet(update.shownDamaged))
                }
            }
        } catch (_: Exception) { }
    }

    /** The queued notices, without consuming them. */
    fun peekPending(context: Context): Pending? = try {
        synchronized(lock) {
            val p = prefs(context)
            val queued = names(p.getStringSet(KEY_QUEUED, null))
            if (queued.isEmpty()) null
            else Pending(queued, p.getStringSet(KEY_QUEUED_DAMAGED, null).orEmpty().toSet())
        }
    } catch (_: Exception) {
        null
    }

    /** Marks [pending] as shown once its pop-up is on screen. */
    fun markShown(context: Context, pending: Pending) {
        try {
            synchronized(lock) {
                val p = prefs(context)
                val queued = names(p.getStringSet(KEY_QUEUED, null)) - pending.notices
                val queuedDamaged = p.getStringSet(KEY_QUEUED_DAMAGED, null).orEmpty() - pending.damagedChatIds
                p.edit(commit = true) {
                    putStringSet(KEY_QUEUED, (if (queuedDamaged.isEmpty()) queued - Notice.DAMAGED else queued)
                        .mapTo(HashSet()) { it.name })
                    putStringSet(KEY_SHOWN, (names(p.getStringSet(KEY_SHOWN, null)) + pending.notices - Notice.DAMAGED)
                        .mapTo(HashSet()) { it.name })
                    putStringSet(KEY_QUEUED_DAMAGED, HashSet(queuedDamaged))
                    putStringSet(KEY_SHOWN_DAMAGED, HashSet(p.getStringSet(KEY_SHOWN_DAMAGED, null).orEmpty() + pending.damagedChatIds))
                }
            }
        } catch (_: Exception) { }
    }

    private fun names(raw: Set<String>?): Set<Notice> =
        raw.orEmpty().mapNotNull { name -> Notice.entries.firstOrNull { it.name == name } }.toSet()

    /** Pure queueing rules; unit-tested. */
    internal object NoticeRules {
        data class Update(
            val queued: Set<Notice>,
            val shown: Set<Notice>,
            val queuedDamaged: Set<String>,
            val shownDamaged: Set<String>
        )

        fun afterRun(
            queued: Set<Notice>,
            shown: Set<Notice>,
            queuedDamaged: Set<String>,
            shownDamaged: Set<String>,
            outcome: DefaultUsernameFill.RunOutcome
        ): Update {
            val nowQueued = queued.toMutableSet()
            val nowShown = shown.toMutableSet()
            fun apply(notice: Notice, happened: Boolean, resolvedWhenAbsent: Boolean) {
                if (happened) {
                    if (notice !in nowShown) nowQueued.add(notice)
                } else if (resolvedWhenAbsent) {
                    // Resolved: forget it, so a later recurrence is announced.
                    nowQueued.remove(notice)
                    nowShown.remove(notice)
                }
            }
            // A run stopped by a restore says nothing about the other problems.
            val ranFully = !outcome.restoreInProgress
            apply(Notice.RESTORE, outcome.restoreInProgress, resolvedWhenAbsent = true)
            apply(Notice.STORAGE, outcome.insufficientStorage, resolvedWhenAbsent = ranFully)
            apply(Notice.LOCKED, outcome.locked, resolvedWhenAbsent = ranFully)
            apply(Notice.OTHER, outcome.otherFailure, resolvedWhenAbsent = ranFully)

            val newDamaged = outcome.damagedChatIds - shownDamaged
            val nowQueuedDamaged = queuedDamaged + newDamaged
            if (nowQueuedDamaged.isNotEmpty()) nowQueued.add(Notice.DAMAGED) else nowQueued.remove(Notice.DAMAGED)
            nowShown.remove(Notice.DAMAGED)
            return Update(nowQueued, nowShown, nowQueuedDamaged, shownDamaged)
        }
    }
}
