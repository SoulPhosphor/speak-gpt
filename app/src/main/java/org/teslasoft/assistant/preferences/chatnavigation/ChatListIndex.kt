/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.chatnavigation

import android.content.ContentValues as SqlValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.preferences.backup.portable.PortableRestoreProcessGate
import org.teslasoft.assistant.preferences.damaged.DamagedChatOperation
import org.teslasoft.assistant.preferences.memory.DatabaseKeys
import org.teslasoft.assistant.ui.drawer.ChatListDetails
import org.teslasoft.assistant.ui.drawer.MostUsedChatModel
import org.teslasoft.assistant.usage.UsageLogStore

/**
 * What each chat row shows beyond its title, kept in its own small encrypted
 * database and in memory, so the chat list, Folders and Search draw rows
 * without opening any chat file. Every value is derived from the chat itself:
 * existing chats are prepared once, a restore prepares the chats it restored
 * before it finishes, and every change to a chat updates its entry.
 */
object ChatListIndex {

    data class Entry(
        val hasBookmarks: Boolean,
        val mostUsedModel: String?,
        val model: String,
        val companionId: String,
        val memoryExcluded: Boolean,
        /** Earliest message time; the chat list's own start date comes first. */
        val startedAt: Long?
    ) {
        /** The model label: what the chat used most, else its selected model. */
        val displayModel: String get() = mostUsedModel ?: model
    }

    enum class Part { CONTENT, SETTINGS }

    data class ContentValues(val hasBookmarks: Boolean, val mostUsedModel: String?, val startedAt: Long?)
    data class SettingsValues(val model: String, val companionId: String, val memoryExcluded: Boolean)
    data class Update(val content: ContentValues? = null, val settings: SettingsValues? = null)

    /** The entry after [update]; null when a chat with no entry gets only part of its details. */
    fun merge(existing: Entry?, update: Update): Entry? {
        val content = update.content
        val settings = update.settings
        var entry = existing ?: run {
            if (content == null || settings == null) return null
            Entry(false, null, settings.model, settings.companionId, settings.memoryExcluded, null)
        }
        if (content != null) entry = entry.copy(
            hasBookmarks = content.hasBookmarks,
            mostUsedModel = content.mostUsedModel?.takeIf { it.isNotBlank() },
            startedAt = content.startedAt?.takeIf { it > 0 }
        )
        if (settings != null) entry = entry.copy(
            model = settings.model,
            companionId = settings.companionId,
            memoryExcluded = settings.memoryExcluded
        )
        return entry
    }

    /** Where entries are stored. */
    interface Table {
        fun readAll(): Map<String, Entry>
        fun write(entries: Map<String, Entry>)
        fun delete(chatIds: Collection<String>)
    }

    /** Where entries are derived from. */
    interface Source {
        /** Every chat in the chat list, or null when the list cannot be read right now. */
        fun chatIds(): List<String>?
        /** null when the chat cannot be read right now (locked or damaged). */
        fun read(chatId: String, parts: Set<Part>): Update?
    }

    /** Changes saved to a chat whose entry has not been updated yet. Kept on
     *  disk, so a change is never lost to a crash or a failed update. */
    interface Pending {
        /** Records that [parts] of [chatId] changed; returns this record's mark. */
        fun mark(chatId: String, parts: Set<Part>): Long
        fun all(): Map<String, Pair<Set<Part>, Long>>
        /** Removes the record only if it is still the one marked [mark]. */
        fun clear(chatId: String, mark: Long)
    }

    /**
     * The entries and the rules for keeping them complete and current. One lock
     * covers every read-then-write, so a value read before a change can never
     * overwrite the value written after it.
     */
    class Core(private val table: Table, private val source: Source, private val pending: Pending) {
        private val entries = ConcurrentHashMap<String, Entry>()
        private val lock = Any()
        // Loading has its own lock so a lookup never waits behind a long preparation.
        private val loadLock = Any()
        @Volatile private var loaded = false
        // Chats that could not be read are not retried on every list refresh;
        // a change to the chat, a restore or the next app start retries them.
        private val unreadable = ConcurrentHashMap.newKeySet<String>()

        fun entryFor(chatId: String): Entry? {
            ensureLoaded()
            return entries[chatId]
        }

        private fun ensureLoaded() {
            if (loaded) return
            synchronized(loadLock) {
                if (loaded) return
                entries.putAll(table.readAll())
                loaded = true
            }
        }

        /** Prepares every chat that has no entry and drops entries of chats that
         *  no longer exist. Returns true when anything changed. */
        fun prepareAll(): Boolean = synchronized(lock) {
            ensureLoaded()
            prepareAllLocked()
        }.let { prepared -> applyPending() || prepared }

        private fun prepareAllLocked(): Boolean {
            ensureLoaded()
            val ids = source.chatIds() ?: return false
            val gone = entries.keys - ids.toSet()
            if (gone.isNotEmpty()) {
                table.delete(gone)
                gone.forEach { entries.remove(it) }
            }
            return prepareLocked(ids.filter { !entries.containsKey(it) }, retryUnreadable = true) || gone.isNotEmpty()
        }

        /** Prepares any of [chatIds] that have no entry yet. Returns true when any were prepared. */
        fun ensureReady(chatIds: Collection<String>): Boolean {
            ensureLoaded()
            if (chatIds.none { !entries.containsKey(it) && it !in unreadable }) return false
            return synchronized(lock) {
                prepareLocked(chatIds.filter { !entries.containsKey(it) && it !in unreadable }, retryUnreadable = false)
            }
        }

        /**
         * Rebuilds the entries of [chatIds] from their current data (a restore).
         * Their old entries are removed first, so an interruption leaves them
         * missing, and prepared again before any list shows them, never outdated.
         */
        fun replace(chatIds: Collection<String>): Boolean = synchronized(lock) {
            ensureLoaded()
            val known = chatIds.filter { entries.containsKey(it) }
            if (known.isNotEmpty()) {
                table.delete(known)
                known.forEach { entries.remove(it) }
            }
            chatIds.forEach { unreadable.remove(it) }
            prepareLocked(chatIds.toList(), retryUnreadable = true) || known.isNotEmpty()
        }

        /** Records a change to one chat; [applyPending] applies it. */
        fun markChanged(chatId: String, parts: Set<Part>) { pending.mark(chatId, parts) }

        /**
         * Applies every recorded change. A record is removed only once its entry
         * is up to date, so a change that fails or is interrupted is applied on
         * a later call or at the next start. Returns true when an entry changed.
         */
        fun applyPending(): Boolean {
            var changed = false
            for ((chatId, record) in pending.all()) {
                val (parts, mark) = record
                val outcome = synchronized(lock) { refreshLocked(chatId, parts) }
                if (outcome != null) {
                    pending.clear(chatId, mark)
                    changed = changed || outcome
                }
            }
            return changed
        }

        /** Applies a change to one chat. Returns true when its entry changed. */
        fun refresh(chatId: String, parts: Set<Part>): Boolean = synchronized(lock) { refreshLocked(chatId, parts) == true }

        /** null when the chat cannot be read right now. */
        private fun refreshLocked(chatId: String, parts: Set<Part>): Boolean? {
            ensureLoaded()
            val existing = entries[chatId]
            if (existing == null) {
                if (prepareLocked(listOf(chatId), retryUnreadable = true)) return true
                // A chat that no longer exists needs nothing; one that exists but
                // cannot be read keeps its record for a later try.
                return if (source.chatIds()?.contains(chatId) == false) false else null
            }
            val update = source.read(chatId, parts) ?: return null
            val next = merge(existing, update) ?: return null
            if (next == existing) return false
            table.write(mapOf(chatId to next))
            entries[chatId] = next
            return true
        }

        fun forget(chatId: String) = synchronized(lock) {
            ensureLoaded()
            unreadable.remove(chatId)
            pending.all()[chatId]?.let { pending.clear(chatId, it.second) }
            if (entries.remove(chatId) != null) table.delete(listOf(chatId))
        }

        private fun prepareLocked(chatIds: List<String>, retryUnreadable: Boolean): Boolean {
            var prepared = false
            val batch = LinkedHashMap<String, Entry>()
            for (chatId in chatIds) {
                if (!retryUnreadable && chatId in unreadable) continue
                val entry = source.read(chatId, Part.entries.toSet())?.let { merge(null, it) }
                if (entry == null) {
                    unreadable.add(chatId)
                    continue
                }
                unreadable.remove(chatId)
                batch[chatId] = entry
                if (batch.size >= WRITE_BATCH) prepared = flush(batch) || prepared
            }
            return flush(batch) || prepared
        }

        private fun flush(batch: LinkedHashMap<String, Entry>): Boolean {
            if (batch.isEmpty()) return false
            table.write(batch)
            entries.putAll(batch)
            batch.clear()
            return true
        }
    }

    private const val WRITE_BATCH = 50
    private const val MAINTENANCE_PREFS = "chat_list_maintenance"
    private const val KEY_START_DATES_FILLED = "start_dates_filled"

    /* ------------------------- the app's instance ------------------------- */

    @Volatile private var core: Core? = null
    @Volatile private var table: DatabaseTable? = null
    private val worker = Executors.newSingleThreadExecutor {
        Thread(it, "chat-list-details").apply { priority = Thread.MIN_PRIORITY }
    }
    private val drainLock = Any()
    private var draining = false
    private var changedAgain = false
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    private fun core(context: Context): Core = core ?: synchronized(this) {
        core ?: run {
            val created = DatabaseTable(context.applicationContext).also { table = it }
            Core(created, ChatSource(context.applicationContext), PendingStore(context.applicationContext))
                .also { core = it }
        }
    }

    /** Throws the whole database away; every chat is prepared again before a list shows it. */
    private fun discardAll(context: Context) = synchronized(this) {
        val current = table ?: DatabaseTable(context.applicationContext)
        runCatching { current.discard() }
        table = null
        core = null
    }

    /** Called on the main thread after entries change. */
    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    private fun notifyChanged() = main.post { listeners.forEach { it() } }

    /** Worker thread only. The entry of one chat, or null if it has none. */
    fun entryFor(context: Context, chatId: String): Entry? =
        runCatching { core(context).entryFor(chatId) }.getOrNull()

    /** Worker thread only. Makes sure every chat in [chatIds] has an entry
     *  before a list shows them. Returns true when any were prepared. */
    fun ensureReady(context: Context, chatIds: Collection<String>): Boolean =
        runCatching { core(context).ensureReady(chatIds) }.getOrDefault(false)

    /** Worker thread only. The one-time preparation of existing chats; also
     *  removes entries of deleted chats. Safe to run any number of times. */
    fun prepareAll(context: Context) {
        // A restore prepares the chats it restores; never read a set mid-restore.
        if (runCatching { PortableRestoreProcessGate.blocksCurrentProcess(context) }.getOrDefault(true)) return
        if (runCatching { core(context).prepareAll() }.getOrDefault(false)) notifyChanged()
        fillStartDates(context)
    }

    private fun maintenance(context: Context) =
        context.getSharedPreferences(MAINTENANCE_PREFS, Context.MODE_PRIVATE)

    /** True once every chat in the list has its start date saved, so opening a
     *  chat never needs to check. New chats save theirs when created. */
    fun startDatesFilled(context: Context): Boolean =
        maintenance(context).getBoolean(KEY_START_DATES_FILLED, false)

    /** Worker thread only. Saves the start date of every chat that has none,
     *  from its prepared details, once; afterwards it does nothing. */
    private fun fillStartDates(context: Context) {
        if (startDatesFilled(context)) return
        val filled = runCatching {
            ChatPreferences.getChatPreferences().fillMissingStartDates(context) { chatId ->
                val entry = core(context).entryFor(chatId) ?: return@fillMissingStartDates null
                entry.startedAt?.let { ChatPreferences.StartDate.Known(it) } ?: ChatPreferences.StartDate.None
            }
        }.getOrDefault(false)
        if (filled) maintenance(context).edit().putBoolean(KEY_START_DATES_FILLED, true).apply()
    }

    fun prepareAllInBackground(context: Context) {
        val app = context.applicationContext
        worker.execute { prepareAll(app) }
    }

    /** Called by a restore, on its own thread, before it reports success:
     *  every restored chat's entry is rebuilt from the restored data. */
    fun prepareRestored(context: Context, restoredChatIds: Collection<String>) {
        // Restored chats may be missing their start dates; fill them again.
        maintenance(context).edit().putBoolean(KEY_START_DATES_FILLED, false).commit()
        val rebuilt = runCatching {
            val target = core(context)
            target.replace(restoredChatIds)
            target.prepareAll()
        }
        // If the restored chats' details could not be rebuilt, no outdated
        // details may survive: discard everything and prepare it again.
        if (rebuilt.isFailure) {
            discardAll(context)
            runCatching { core(context).prepareAll() }
        }
        notifyChanged()
        fillStartDates(context)
    }

    /** The chat's history or recorded usage changed. */
    fun contentChanged(context: Context, chatId: String) = request(context, chatId, setOf(Part.CONTENT))

    /** A chat setting shown in the list (model, companion, memory exclusion) changed. */
    fun settingsChanged(context: Context, chatId: String) = request(context, chatId, setOf(Part.SETTINGS))

    /** A new chat joined the list, possibly reusing a deleted chat's ID. */
    fun chatCreated(context: Context, chatId: String) {
        if (chatId.isBlank()) return
        val app = context.applicationContext
        worker.execute {
            val changed = runCatching { core(app).replace(listOf(chatId)) }.getOrDefault(false)
            if (changed) notifyChanged()
        }
    }

    /** A chat was deleted; its ID may be reused by a later chat. */
    fun chatDeleted(context: Context, chatId: String) {
        if (chatId.isBlank()) return
        val app = context.applicationContext
        worker.execute { runCatching { core(app).forget(chatId) } }
    }

    private fun request(context: Context, chatId: String, parts: Set<Part>) {
        if (chatId.isBlank()) return
        val app = context.applicationContext
        // Recorded on disk before anything else, so the change survives a crash.
        if (runCatching { core(app).markChanged(chatId, parts) }.isFailure) return
        synchronized(drainLock) {
            changedAgain = true
            if (draining) return
            draining = true
        }
        worker.execute { drain(app) }
    }

    private fun drain(context: Context) {
        while (true) {
            synchronized(drainLock) {
                if (!changedAgain) {
                    draining = false
                    return
                }
                changedAgain = false
            }
            val changed = runCatching { core(context).applyPending() }.getOrDefault(false)
            if (changed) notifyChanged()
        }
    }

    /** The records of changes not yet applied, kept in the app's private storage.
     *  Holds only chat IDs and which kind of detail changed. */
    private class PendingStore(context: Context) : Pending {
        private val preferences = context.getSharedPreferences("chat_list_details_pending", Context.MODE_PRIVATE)
        private var lastMark = 0L

        @Synchronized
        override fun mark(chatId: String, parts: Set<Part>): Long {
            val merged = parts + (decode(preferences.getString(chatId, null))?.first ?: emptySet())
            val mark = maxOf(System.nanoTime(), lastMark + 1).also { lastMark = it }
            preferences.edit().putString(chatId, merged.joinToString(",") { it.name } + ";" + mark).apply()
            return mark
        }

        @Synchronized
        override fun all(): Map<String, Pair<Set<Part>, Long>> =
            preferences.all.mapNotNull { (chatId, value) -> decode(value as? String)?.let { chatId to it } }.toMap()

        @Synchronized
        override fun clear(chatId: String, mark: Long) {
            if (decode(preferences.getString(chatId, null))?.second == mark) preferences.edit().remove(chatId).apply()
        }

        private fun decode(value: String?): Pair<Set<Part>, Long>? {
            val (parts, mark) = value?.split(";")?.takeIf { it.size == 2 } ?: return null
            return parts.split(",").mapNotNull { name -> Part.entries.firstOrNull { it.name == name } }.toSet() to
                (mark.toLongOrNull() ?: return null)
        }
    }

    /** Reads a chat's details from its own storage. */
    private class ChatSource(private val context: Context) : Source {
        override fun chatIds(): List<String>? {
            val list = ChatPreferences.getChatPreferences().getChatListResult(context, includeFirstMessage = false)
            if (!ChatStorageHealth.isAuthoritative(list.state)) return null
            return list.chats.map { ChatPreferences.storedChatId(it) }
        }

        override fun read(chatId: String, parts: Set<Part>): Update? {
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

    /** The encrypted database. It holds only derived values, so a database that
     *  cannot be opened is discarded and prepared again from the chats. */
    private class DatabaseTable(private val context: Context) : Table {
        private var helper: Helper? = null

        @Synchronized
        private fun database(): SQLiteDatabase {
            helper?.let { return it.writableDatabase }
            loadLibrary()
            val file = context.getDatabasePath(DATABASE_NAME)
            var key = DatabaseKeys.getOrCreate(context, DatabaseKeys.KEY_CHAT_LIST_DETAILS, file.exists())
            if (key == null) {
                discardFiles()
                key = DatabaseKeys.getOrCreate(context, DatabaseKeys.KEY_CHAT_LIST_DETAILS, false)
                    ?: throw IllegalStateException("Chat list details key unavailable")
            }
            val opened = Helper(context, key)
            return try {
                opened.writableDatabase.also { helper = opened }
            } catch (e: Exception) {
                runCatching { opened.close() }
                discardFiles()
                Helper(context, key).also { helper = it }.writableDatabase
            }
        }

        override fun readAll(): Map<String, Entry> = database().rawQuery(
            "SELECT chat_id, has_bookmarks, most_used_model, model, companion_id, memory_excluded, started_at FROM chat_details",
            null
        ).use { cursor ->
            val out = HashMap<String, Entry>(cursor.count)
            while (cursor.moveToNext()) {
                out[cursor.getString(0)] = Entry(
                    hasBookmarks = cursor.getInt(1) != 0,
                    mostUsedModel = if (cursor.isNull(2)) null else cursor.getString(2),
                    model = cursor.getString(3),
                    companionId = cursor.getString(4),
                    memoryExcluded = cursor.getInt(5) != 0,
                    startedAt = if (cursor.isNull(6)) null else cursor.getLong(6)
                )
            }
            out
        }

        override fun write(entries: Map<String, Entry>) {
            val db = database()
            db.beginTransaction()
            try {
                for ((chatId, entry) in entries) {
                    db.insertWithOnConflict("chat_details", null, SqlValues().apply {
                        put("chat_id", chatId)
                        put("has_bookmarks", if (entry.hasBookmarks) 1 else 0)
                        put("most_used_model", entry.mostUsedModel)
                        put("model", entry.model)
                        put("companion_id", entry.companionId)
                        put("memory_excluded", if (entry.memoryExcluded) 1 else 0)
                        put("started_at", entry.startedAt)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        override fun delete(chatIds: Collection<String>) {
            val db = database()
            db.beginTransaction()
            try {
                chatIds.forEach { db.delete("chat_details", "chat_id = ?", arrayOf(it)) }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        @Synchronized
        fun discard() = discardFiles()

        private fun discardFiles() {
            runCatching { helper?.close() }
            helper = null
            listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                context.getDatabasePath(DATABASE_NAME + suffix).delete()
            }
        }

        private class Helper(context: Context, password: ByteArray) : SQLiteOpenHelper(
            context, DATABASE_NAME, password, null, DATABASE_VERSION, 0, CloseOnCorruption, null, true
        ) {
            override fun onCreate(db: SQLiteDatabase) = createSchema(db)

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                // Derived values: a new schema is prepared again from the chats.
                db.execSQL("DROP TABLE IF EXISTS chat_details")
                createSchema(db)
            }

            private fun createSchema(db: SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS chat_details(" +
                        "chat_id TEXT PRIMARY KEY NOT NULL, has_bookmarks INTEGER NOT NULL, " +
                        "most_used_model TEXT, model TEXT NOT NULL, companion_id TEXT NOT NULL, " +
                        "memory_excluded INTEGER NOT NULL, started_at INTEGER)"
                )
            }
        }

        private object CloseOnCorruption : DatabaseErrorHandler {
            override fun onCorruption(dbObj: SQLiteDatabase, exception: android.database.sqlite.SQLiteException?) {
                try { dbObj.close() } catch (_: Exception) { }
            }
        }

        companion object {
            const val DATABASE_NAME = "chat_list_details.db"
            private const val DATABASE_VERSION = 1
            @Volatile private var libraryLoaded = false

            private fun loadLibrary() {
                if (libraryLoaded) return
                System.loadLibrary("sqlcipher")
                libraryLoaded = true
            }
        }
    }
}
