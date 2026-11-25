/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

import android.content.Context
import java.util.UUID
import org.json.JSONObject

/**
 * Reports Summarizer operations that never ended (Owner, 2026-11-25): the app
 * stopped while a compaction, summary or chat outline ran (Android ending it,
 * a crash or a force stop; the cause is not known).
 *
 * - A small "running" record is written when an operation starts and removed
 *   whenever it ends: success, failure, cancellation or a superseded result,
 *   none of which is ever reported. A summary also counts each section it
 *   starts. Nothing is written per batch or by polling.
 * - The result's own save writes this run's saved mark in the same commit
 *   ([org.teslasoft.assistant.preferences.Preferences.getSummarizerRunSaved]),
 *   so a run that saved its result but stopped before its clean-up is told
 *   apart from unfinished work and is not reported.
 * - At the next start each record left by an earlier process is reported once
 *   and removed in the same step.
 */
object SummarizerRunRecords {

    /** Where the records live; plain storage, so the JVM tests use a map. */
    interface Store {
        fun all(): Map<String, String>
        fun put(key: String, value: String)
        fun remove(key: String)
    }

    /** What was still unfinished, for the report; [key] is its record, removed by [reported]. */
    data class Interrupted(val chatId: String, val kind: SummarizerController.OperationKind, val key: String = "$chatId|${kind.name}")

    /** This process; records written by it are never reported by it. */
    val session: String = UUID.randomUUID().toString()

    private const val STORE_NAME = "summarizer_run_records"

    @Volatile internal var storeForTest: Store? = null

    private fun store(context: Context): Store = storeForTest ?: AppStore(context.applicationContext)

    private fun key(chatId: String, kind: SummarizerController.OperationKind) = "$chatId|${kind.name}"

    /** An operation started; returns the token its result's save must carry. */
    fun started(context: Context, chatId: String, kind: SummarizerController.OperationKind): String? {
        if (chatId.isBlank()) return null
        val token = UUID.randomUUID().toString()
        return try {
            store(context).put(key(chatId, kind), record(token, session, 0))
            token
        } catch (_: Exception) {
            null
        }
    }

    /** A summary starts a section: one write per section request. */
    fun sectionStarted(context: Context, chatId: String, token: String?) {
        token ?: return
        try {
            val store = store(context)
            val k = key(chatId, SummarizerController.OperationKind.SUMMARIZING)
            val current = store.all()[k]?.let(::parse) ?: return
            if (current.token != token) return
            store.put(k, record(token, current.session, current.started + 1))
        } catch (_: Exception) { }
    }

    /** The operation ended in any way: its record goes. */
    fun ended(context: Context, chatId: String, kind: SummarizerController.OperationKind, token: String?) {
        token ?: return
        try {
            val store = store(context)
            val k = key(chatId, kind)
            if (store.all()[k]?.let(::parse)?.token == token) store.remove(k)
        } catch (_: Exception) { }
    }

    /** A rename moved the chat to a new ID: its records follow. */
    fun chatRenamed(context: Context, oldChatId: String, newChatId: String) {
        if (oldChatId.isBlank() || newChatId.isBlank() || oldChatId == newChatId) return
        try {
            val store = store(context)
            store.all().forEach { (k, v) ->
                if (k.substringBeforeLast('|') == oldChatId) {
                    store.put("$newChatId|${k.substringAfterLast('|')}", v)
                    store.remove(k)
                }
            }
        } catch (_: Exception) { }
    }

    /** The saved mark a section save writes: this run's token and how many sections it has saved. */
    fun summarySaved(token: String, savedSections: Int) = "$token:$savedSections"

    /**
     * Records left by an earlier process. One whose run saved its result is
     * removed at once; an unfinished one is returned and stays until the
     * caller has written its report and calls [reported], so it is reported
     * once and never lost. [savedMark] reads a chat's saved mark for a kind,
     * or null when the chat's settings cannot be read now (locked or
     * unreadable): that record is left alone for a later start. [liveChats]
     * is the chat list, or null when it cannot be read now, in which case
     * nothing is decided.
     */
    fun takeInterrupted(
        context: Context,
        savedMark: (chatId: String, kind: SummarizerController.OperationKind) -> String?,
        liveChats: () -> Set<String>?
    ): List<Interrupted> {
        val store = try { store(context) } catch (_: Exception) { return emptyList() }
        val records = try { store.all() } catch (_: Exception) { return emptyList() }
        val earlier = records.mapNotNull { (k, v) ->
            val parsed = parse(v) ?: return@mapNotNull k to null
            if (parsed.session == session) null else k to parsed
        }
        if (earlier.isEmpty()) return emptyList()
        val live = liveChats() ?: return emptyList()
        val out = ArrayList<Interrupted>()
        for ((k, record) in earlier) {
            val chatId = k.substringBeforeLast('|')
            val kind = runCatching { SummarizerController.OperationKind.valueOf(k.substringAfterLast('|')) }.getOrNull()
            if (record == null || kind == null) {
                // Not a record this version can read: nothing to report.
                runCatching { store.remove(k) }
                continue
            }
            val mark = if (chatId in live) {
                (try { savedMark(chatId, kind) } catch (_: Exception) { null }) ?: continue
            } else ""
            if (!unfinished(kind, record, mark)) {
                runCatching { store.remove(k) }
                continue
            }
            out += Interrupted(chatId, kind, k)
        }
        return out
    }

    /** The interruption's report was written: its record goes, so it is never reported again. */
    fun reported(context: Context, interrupted: Interrupted) {
        try { store(context).remove(interrupted.key) } catch (_: Exception) { }
    }

    /** Whether the run left unfinished work, from its record and the mark its last save wrote. */
    internal fun unfinished(kind: SummarizerController.OperationKind, record: Record, mark: String): Boolean =
        when (kind) {
            // A summary keeps each section as it is saved; only a section it started and never saved is lost.
            SummarizerController.OperationKind.SUMMARIZING -> {
                val saved = if (mark.substringBefore(':') == record.token) mark.substringAfter(':').toIntOrNull() ?: 0 else 0
                record.started > saved
            }
            // A compaction or outline saves only at the end: a mark with this run's token means it was saved.
            else -> mark != record.token
        }

    internal data class Record(val token: String, val session: String, val started: Int)

    private fun record(token: String, session: String, started: Int) =
        JSONObject().put("token", token).put("session", session).put("started", started).toString()

    internal fun parse(value: String): Record? = try {
        val json = JSONObject(value)
        Record(json.getString("token"), json.getString("session"), json.optInt("started", 0))
    } catch (_: Exception) {
        null
    }

    private class AppStore(context: Context) : Store {
        private val prefs = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
        override fun all(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
        override fun put(key: String, value: String) { prefs.edit().putString(key, value).commit() }
        override fun remove(key: String) { prefs.edit().remove(key).commit() }
    }
}
