/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.damaged

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.conversation.NewConversationCoordinator
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.SecurePrefs

/**
 * Damaged Chat Files (owner ruling, Oct 12 2026): the central record of every
 * chat found damaged (its saved contents could not be decrypted or parsed),
 * whichever operation found it. A locked chat is never recorded here — a lock
 * is not damage.
 *
 * Each chat is recorded once, under the first operation that found it, with
 * the details that can still be read safely from the chat list: its title and
 * start date. The first username, first companion name and most-used model
 * live inside the damaged contents, so they are recorded only when known and
 * otherwise shown as Unavailable. Stored encrypted (chat titles are private).
 * A chat is removed when it is deleted.
 */
object DamagedChatLog {

    private const val FILE = "damaged_chat_log"
    private const val KEY = "detections"
    private const val UNREADABLE_PREFIX = "unreadable_detections_"

    data class DamagedChat(
        val chatId: String,
        val title: String?,
        val startedAt: Long?,
        val firstUsername: String?,
        val firstCompanionName: String?,
        val mostUsedModel: String?,
        /** Direct repair was tried and failed (Irreparable Chat Files). */
        val irreparable: Boolean = false
    )

    data class Detection(
        val operation: DamagedChatOperation,
        val detectedAt: Long,
        val chats: List<DamagedChat>
    )

    private val lock = Any()

    /** Records [chatId] as damaged unless it is already in the log. Chat list
     *  details are read here; call off the main thread where possible. */
    fun record(context: Context, chatId: String, operation: DamagedChatOperation): Boolean =
        recordAll(context, listOf(chatId), operation)

    /** Records every not-yet-logged chat in [chatIds] as one detection.
     *  Returns whether the log is durably saved with them in it. */
    fun recordAll(context: Context, chatIds: Collection<String>, operation: DamagedChatOperation): Boolean {
        if (chatIds.isEmpty()) return true
        return try {
            val alreadyKnown = chatIds(context)
            if (chatIds.all { it.isBlank() || it in alreadyKnown }) return unsaved == null
            // Chat list details are read BEFORE taking this log's lock, so a
            // caller holding the chat list lock can never deadlock with it.
            val rows = chatListRows(context)
            synchronized(lock) {
                val detections = read(context).toMutableList()
                val known = detections.flatMap { d -> d.chats.map { it.chatId } }.toSet()
                val fresh = chatIds.filter { it.isNotBlank() && it !in known }.distinct()
                if (fresh.isEmpty()) return unsaved == null
                val chats = fresh.map { id ->
                    val row = rows?.firstOrNull { ChatPreferences.storedChatId(it) == id }
                    DamagedChat(
                        chatId = id,
                        title = row?.get("name")?.takeIf { it.isNotBlank() },
                        startedAt = row?.get("created_at")?.toLongOrNull()?.takeIf { it > 0 },
                        firstUsername = null,
                        firstCompanionName = null,
                        mostUsedModel = null
                    )
                }
                detections.add(Detection(operation, System.currentTimeMillis(), chats))
                write(context, detections)
                unsaved == null
            }
        } catch (_: Exception) {
            // Best-effort record: never turn a damaged read into a crash.
            false
        }
    }

    /** The chat ids currently in the log. */
    fun chatIds(context: Context): Set<String> = try {
        synchronized(lock) { read(context).flatMap { d -> d.chats.map { it.chatId } }.toSet() }
    } catch (_: Exception) {
        emptySet()
    }

    /** Marks [chatIds] as irreparable: direct repair was tried and failed.
     *  Their original detection details are kept. */
    fun markIrreparable(context: Context, chatIds: Set<String>) {
        if (chatIds.isEmpty()) return
        try {
            synchronized(lock) {
                write(context, read(context).map { d ->
                    d.copy(chats = d.chats.map { if (it.chatId in chatIds) it.copy(irreparable = true) else it })
                })
            }
        } catch (_: Exception) { }
    }

    /** The operation that first found [chatId] damaged, when it is logged. */
    fun operationFor(context: Context, chatId: String): DamagedChatOperation? = try {
        synchronized(lock) { read(context).firstOrNull { d -> d.chats.any { it.chatId == chatId } }?.operation }
    } catch (_: Exception) {
        null
    }

    /** Removes [chatId] (deleted, or repaired and verified); drops emptied detections. */
    fun remove(context: Context, chatId: String) {
        try {
            synchronized(lock) {
                val detections = read(context)
                if (detections.none { d -> d.chats.any { it.chatId == chatId } }) return
                write(context, detections.mapNotNull { d ->
                    val kept = d.chats.filter { it.chatId != chatId }
                    if (kept.isEmpty()) null else d.copy(chats = kept)
                })
            }
        } catch (_: Exception) { }
    }

    /**
     * The detections to show, oldest first. Chats that no longer exist (not in
     * the chat list and not a new chat still waiting for its first save) are
     * left out; when the chat list cannot be read, everything recorded is
     * shown.
     */
    fun detections(context: Context): List<Detection> {
        val all = try { synchronized(lock) { read(context) } } catch (_: Exception) { emptyList() }
        val existing = chatListRows(context)?.map { ChatPreferences.storedChatId(it) }?.toSet()
            ?: return all
        return all.mapNotNull { d ->
            val kept = d.chats.filter { it.chatId in existing || isProvisional(context, it.chatId) }
            if (kept.isEmpty()) null else d.copy(chats = kept)
        }
    }

    /** A new chat that holds files but is not in the chat list yet (its first
     *  save has not been folded into the list). */
    internal fun isProvisional(context: Context, chatId: String): Boolean = try {
        SecurePrefs.encryptedFile(context, "settings.$chatId").exists() &&
            NewConversationCoordinator(context).isPending(chatId)
    } catch (_: Exception) {
        false
    }

    private fun chatListRows(context: Context): List<Map<String, String>>? {
        val result = ChatPreferences.getChatPreferences().getChatListResult(context, includeFirstMessage = false)
        return if (org.teslasoft.assistant.preferences.ChatStorageHealth.isAuthoritative(result.state)) {
            result.chats
        } else {
            null
        }
    }

    /** The log's latest contents when saving them failed; kept in this
     *  process and saved again on every later access until a save lands, so
     *  a failed save never drops a detection while the app is running. */
    private var unsaved: List<Detection>? = null

    private fun read(context: Context): List<Detection> {
        unsaved?.let { pending ->
            if (!commit(context, pending)) return pending
            unsaved = null
        }
        val prefs = SecurePrefs.get(context, FILE)
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        decodeOrNull(raw)?.let { return it }
        // The stored log cannot be read. Keep its exact contents under a
        // separate key before anything writes a new log over it; if that
        // copy cannot be saved, nothing is rewritten.
        val kept = prefs.edit().putString("$UNREADABLE_PREFIX${System.currentTimeMillis()}", raw)
            .remove(KEY).commit()
        check(kept) { "damaged chat log unreadable and could not be preserved" }
        return emptyList()
    }

    private fun write(context: Context, detections: List<Detection>) {
        unsaved = if (commit(context, detections)) null else detections
    }

    private fun commit(context: Context, detections: List<Detection>): Boolean = try {
        SecurePrefs.get(context, FILE).edit().putString(KEY, encode(detections)).commit()
    } catch (_: Exception) {
        false
    }

    internal fun encode(detections: List<Detection>): String = JSONArray().apply {
        detections.forEach { d ->
            put(JSONObject().apply {
                put("operation", d.operation.name)
                put("detected_at", d.detectedAt)
                put("chats", JSONArray().apply {
                    d.chats.forEach { c ->
                        put(JSONObject().apply {
                            put("chat_id", c.chatId)
                            c.title?.let { put("title", it) }
                            c.startedAt?.let { put("started_at", it) }
                            c.firstUsername?.let { put("first_username", it) }
                            c.firstCompanionName?.let { put("first_companion_name", it) }
                            c.mostUsedModel?.let { put("most_used_model", it) }
                            if (c.irreparable) put("irreparable", true)
                        })
                    }
                })
            })
        }
    }.toString()

    internal fun decode(raw: String): List<Detection> = decodeOrNull(raw) ?: emptyList()

    /** Null when [raw] is not a readable log. */
    internal fun decodeOrNull(raw: String): List<Detection>? {
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val d = array.getJSONObject(i)
                // An entry this version cannot read makes the whole log
                // unreadable, so its contents are kept rather than rewritten.
                val operation = DamagedChatOperation.entries.firstOrNull { it.name == d.optString("operation") }
                    ?: return null
                val chats = d.optJSONArray("chats") ?: JSONArray()
                Detection(
                    operation,
                    d.optLong("detected_at"),
                    (0 until chats.length()).map { j ->
                        val c = chats.getJSONObject(j)
                        DamagedChat(
                            chatId = c.getString("chat_id"),
                            title = c.optString("title").takeIf { c.has("title") },
                            startedAt = c.optLong("started_at").takeIf { c.has("started_at") },
                            firstUsername = c.optString("first_username").takeIf { c.has("first_username") },
                            firstCompanionName = c.optString("first_companion_name").takeIf { c.has("first_companion_name") },
                            mostUsedModel = c.optString("most_used_model").takeIf { c.has("most_used_model") },
                            irreparable = c.optBoolean("irreparable", false)
                        )
                    }
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
