/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.chatnavigation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.preferences.backup.portable.PortableRestoreProcessGate
import org.teslasoft.assistant.preferences.damaged.DamagedChatOperation
import org.teslasoft.assistant.ui.drawer.ChatListDetails
import org.teslasoft.assistant.ui.drawer.MostUsedChatModel
import org.teslasoft.assistant.usage.UsageLogStore

/**
 * What a chat row shows beyond its title, kept in that chat's encrypted
 * chat-list row so the drawer and Search draw rows without opening any chat's
 * own files. Values are derived only from the chat's stored history and
 * settings by one background worker, are refreshed when those change, and are
 * never carried in backups: a restored row is refilled from restored data.
 */
object ChatListIndex {
    const val VERSION_KEY = "index_version"
    const val HAS_BOOKMARKS_KEY = "index_has_bookmarks"
    const val MOST_USED_MODEL_KEY = "index_most_used_model"
    const val MODEL_KEY = "index_model"
    const val COMPANION_KEY = "index_companion"
    const val MEMORY_EXCLUDED_KEY = "index_memory_excluded"

    /** Raising this refills every row once. */
    const val VERSION = "1"

    private const val START_DATE_KEY = "created_at"

    /** Derived values: left out of backups and of chat comparisons. */
    val KEYS: Set<String> = setOf(
        VERSION_KEY, HAS_BOOKMARKS_KEY, MOST_USED_MODEL_KEY, MODEL_KEY, COMPANION_KEY, MEMORY_EXCLUDED_KEY
    )

    data class Entry(
        val hasBookmarks: Boolean,
        val mostUsedModel: String?,
        val model: String,
        val companionId: String,
        val memoryExcluded: Boolean
    ) {
        /** The model label: what the chat used most, else its selected model. */
        val displayModel: String get() = mostUsedModel ?: model
    }

    /** null until the row has been filled for the current [VERSION]. */
    fun entryOf(row: Map<String, String>): Entry? {
        if (row[VERSION_KEY] != VERSION) return null
        return Entry(
            hasBookmarks = row[HAS_BOOKMARKS_KEY] == "true",
            mostUsedModel = row[MOST_USED_MODEL_KEY]?.takeIf { it.isNotBlank() },
            model = row[MODEL_KEY].orEmpty(),
            companionId = row[COMPANION_KEY].orEmpty(),
            memoryExcluded = row[MEMORY_EXCLUDED_KEY] == "true"
        )
    }

    enum class Part { CONTENT, SETTINGS }

    data class ContentValues(val hasBookmarks: Boolean, val mostUsedModel: String?, val startedAt: Long?)
    data class SettingsValues(val model: String, val companionId: String, val memoryExcluded: Boolean)
    data class Update(val content: ContentValues? = null, val settings: SettingsValues? = null)

    /**
     * Applies [update] to [row] and reports whether anything changed. A row is
     * marked filled only when both parts are written together, and a known
     * creation date is never replaced.
     */
    fun apply(row: MutableMap<String, String>, update: Update): Boolean {
        val before = HashMap(row)
        update.content?.let { content ->
            row[HAS_BOOKMARKS_KEY] = content.hasBookmarks.toString()
            val mostUsed = content.mostUsedModel?.takeIf { it.isNotBlank() }
            if (mostUsed == null) row.remove(MOST_USED_MODEL_KEY) else row[MOST_USED_MODEL_KEY] = mostUsed
            val knownStart = row[START_DATE_KEY]?.toLongOrNull()?.let { it > 0 } == true
            val startedAt = content.startedAt
            if (!knownStart && startedAt != null && startedAt > 0) row[START_DATE_KEY] = startedAt.toString()
        }
        update.settings?.let { settings ->
            row[MODEL_KEY] = settings.model
            row[COMPANION_KEY] = settings.companionId
            row[MEMORY_EXCLUDED_KEY] = settings.memoryExcluded.toString()
        }
        if (update.content != null && update.settings != null) row[VERSION_KEY] = VERSION
        return row != before
    }

    /** The parts to read for a chat: everything while its row is unfilled. */
    fun partsToRead(row: Map<String, String>?, requested: Set<Part>): Set<Part> =
        if (row != null && row[VERSION_KEY] != VERSION) Part.entries.toSet() else requested

    /* ----------------------------- worker ----------------------------- */

    private const val URGENT_BATCH = 4
    private const val BACKGROUND_BATCH = 25
    private const val NOTIFY_INTERVAL_MS = 500L
    // A streaming reply saves every two seconds; reread once it has settled.
    private const val CONTENT_SETTLE_MS = 3000L

    private val lock = Any()
    // Rows on screen and changes the user just made go first; the one-time
    // fill-in of everything else waits behind them.
    private val urgent = LinkedHashMap<String, MutableSet<Part>>()
    private val background = LinkedHashMap<String, MutableSet<Part>>()
    // Chats that could not be read (damaged or locked) are not retried by the
    // fill-in until the next app start; a change to the chat still retries.
    private val unreadable = HashSet<String>()
    private var running = false
    // Android keeps every chat file it has opened in memory until the app
    // closes. The fill-in of rows nobody is looking at stops for this run once
    // half the app's memory is in use, and continues at the next start.
    private var fillInPaused = false
    private var appContext: Context? = null
    private val executor = Executors.newSingleThreadExecutor {
        Thread(it, "chat-list-index").apply { priority = Thread.MIN_PRIORITY }
    }
    private val scheduler = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "chat-list-index-settle").apply { isDaemon = true }
    }
    private val settling = HashMap<String, ScheduledFuture<*>>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var lastNotifiedAt = 0L

    /** Called on the main thread after stored rows change. */
    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    /** The chat's history or recorded usage changed. Repeated changes are
     *  read once, after they stop arriving. */
    fun contentChanged(context: Context, chatId: String) {
        if (chatId.isBlank()) return
        val appContext = context.applicationContext
        synchronized(lock) {
            settling.remove(chatId)?.cancel(false)
            settling[chatId] = scheduler.schedule({
                synchronized(lock) { settling.remove(chatId) }
                request(appContext, chatId, setOf(Part.CONTENT), retry = true)
            }, CONTENT_SETTLE_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** A chat setting shown in the list (model, companion, memory exclusion) changed. */
    fun settingsChanged(context: Context, chatId: String) = request(context, chatId, setOf(Part.SETTINGS), retry = true)

    /** A row on screen has not been filled yet. */
    fun fillNow(context: Context, chatId: String) {
        request(context, chatId, Part.entries.toSet(), retry = false)
    }

    /** Queues every unfilled row behind anything on screen. */
    fun fillMissing(context: Context, chatIds: Collection<String>) {
        synchronized(lock) {
            for (chatId in chatIds) {
                if (fillInPaused) break
                if (chatId.isBlank() || chatId in unreadable || chatId in urgent || chatId in background) continue
                background[chatId] = Part.entries.toMutableSet()
            }
        }
        start(context)
    }

    private fun request(context: Context, chatId: String, parts: Set<Part>, retry: Boolean) {
        if (chatId.isBlank()) return
        synchronized(lock) {
            if (retry) unreadable.remove(chatId) else if (chatId in unreadable) return
            urgent[chatId] = (urgent[chatId] ?: background.remove(chatId) ?: mutableSetOf()).apply { addAll(parts) }
        }
        start(context)
    }

    private fun start(context: Context) {
        synchronized(lock) {
            if (appContext == null) appContext = context.applicationContext
            if (running || (urgent.isEmpty() && background.isEmpty())) return
            running = true
        }
        executor.execute(::drain)
    }

    private fun drain() {
        var unannounced = false
        while (true) {
            var inBackground = false
            val batch = synchronized(lock) {
                val queue = if (urgent.isNotEmpty()) urgent else background
                inBackground = queue === background
                val size = if (queue === urgent) URGENT_BATCH else BACKGROUND_BATCH
                val taken = LinkedHashMap<String, Set<Part>>()
                val entries = queue.entries.iterator()
                while (entries.hasNext() && taken.size < size) {
                    val entry = entries.next()
                    taken[entry.key] = entry.value
                    entries.remove()
                }
                if (taken.isEmpty()) running = false
                taken
            }
            if (batch.isEmpty()) break
            val context = appContext ?: continue
            if (runCatching { processBatch(context, batch, inBackground) }.getOrDefault(false)) unannounced = true
            if (unannounced) {
                val idle = synchronized(lock) { urgent.isEmpty() && background.isEmpty() }
                val now = SystemClock.elapsedRealtime()
                if (idle || now - lastNotifiedAt >= NOTIFY_INTERVAL_MS) {
                    lastNotifiedAt = now
                    unannounced = false
                    main.post { listeners.forEach { it() } }
                }
            }
        }
        if (unannounced) main.post { listeners.forEach { it() } }
    }

    /** Reads each chat once and saves every changed row in one list write. */
    private fun processBatch(context: Context, batch: Map<String, Set<Part>>, inBackground: Boolean): Boolean {
        // A restore replaces the whole chat set; its rows are refilled afterwards.
        if (PortableRestoreProcessGate.blocksCurrentProcess(context)) return false
        val storage = ChatPreferences.getChatPreferences()
        val list = storage.getChatListResult(context, includeFirstMessage = false)
        if (!ChatStorageHealth.isAuthoritative(list.state)) return false
        val rows = list.chats.associateBy { ChatPreferences.storedChatId(it) }
        val updates = LinkedHashMap<String, Update>()
        for ((chatId, requested) in batch) {
            if (inBackground && memoryIsTight()) {
                synchronized(lock) {
                    fillInPaused = true
                    background.clear()
                }
                break
            }
            val row = rows[chatId] ?: continue
            val update = read(context, chatId, partsToRead(row, requested))
            if (update == null) {
                synchronized(lock) { unreadable.add(chatId) }
            } else updates[chatId] = update
        }
        if (updates.isEmpty()) return false
        return storage.updateChatListRows(context) { current ->
            var changed = false
            for (row in current) {
                val update = updates[ChatPreferences.storedChatId(row)] ?: continue
                if (apply(row, update)) changed = true
            }
            changed
        }
    }

    private fun memoryIsTight(): Boolean {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory() > runtime.maxMemory() / 2
    }

    /** null when the chat's history or settings cannot be read right now. */
    private fun read(context: Context, chatId: String, parts: Set<Part>): Update? {
        val preferences = Preferences.getPreferences(context, chatId)
        if (SecurePrefs.isLockedName("settings.$chatId")) return null
        val settings = if (Part.SETTINGS in parts) SettingsValues(
            preferences.getModel(), preferences.getPersonaId(), preferences.isChatExcludedFromMemory()
        ) else null
        val content = if (Part.CONTENT in parts) {
            val history = ChatPreferences.getChatPreferences()
                .getChatByIdResult(context, chatId, DamagedChatOperation.CHAT_LIST)
            if (!ChatStorageHealth.isAuthoritative(history.state)) return null
            val details = ChatListDetails.fromMessages(history.messages)
            ContentValues(
                details.hasBookmarks,
                MostUsedChatModel.select(UsageLogStore.read(preferences), history.messages),
                details.startedAt
            )
        } else null
        return Update(content, settings)
    }
}
