/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************/

package org.teslasoft.assistant.preferences.generatedimages

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.annotation.VisibleForTesting
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import org.teslasoft.assistant.preferences.memory.DatabaseKeys

data class GeneratedImageCatalogWriteResult(
    val success: Boolean,
    val state: GeneratedImageCatalogStorageState
)

data class GeneratedImageCatalogLookup(
    val state: GeneratedImageCatalogStorageState,
    val record: GeneratedImageCatalogRecord? = null,
    val tombstoned: Boolean = false
)

data class GeneratedImageCatalogListResult(
    val state: GeneratedImageCatalogStorageState,
    val records: List<GeneratedImageCatalogRecord> = emptyList()
)

data class GeneratedImageCatalogBooleanResult(
    val state: GeneratedImageCatalogStorageState,
    val value: Boolean = false
)

data class GeneratedImageCatalogDeletionResult(
    val state: GeneratedImageCatalogStorageState,
    val removed: List<GeneratedImageCatalogRecord> = emptyList(),
    val lockedImageIds: Set<String> = emptySet(),
    val success: Boolean = false
)

enum class GeneratedImageAssetDeletionDisposition {
    DELETED_OR_ABSENT,
    RETAINED_ACTIVE_REFERENCE,
    FAILED
}

data class GeneratedImageAssetDeletionResult(
    val state: GeneratedImageCatalogStorageState,
    val disposition: GeneratedImageAssetDeletionDisposition
)

/**
 * Narrow SQLCipher-backed index for generated images. UUID [imageId] is the
 * immutable primary key. Display labels, origin-chat names and file metadata
 * are mutable attributes and can never re-key a row.
 */
class GeneratedImageCatalogStore private constructor(
    context: Context,
    password: ByteArray,
    databaseName: String = DATABASE_NAME
) : SQLiteOpenHelper(
    context.applicationContext,
    databaseName,
    password,
    null,
    DATABASE_VERSION,
    0,
    CatalogCorruptionHandler(context),
    null,
    true
) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createCurrentSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            if (!hasColumn(db, TABLE_IMAGES, COL_ORIGIN_MESSAGE_ID)) {
                db.execSQL("ALTER TABLE $TABLE_IMAGES ADD COLUMN $COL_ORIGIN_MESSAGE_ID TEXT")
            }
            if (!hasColumn(db, TABLE_IMAGES, COL_SOURCE)) {
                db.execSQL(
                    "ALTER TABLE $TABLE_IMAGES ADD COLUMN $COL_SOURCE TEXT NOT NULL DEFAULT 'backfill'"
                )
            }
            createSupportingTables(db)
            createIndexes(db)
        }
    }

    private fun createCurrentSchema(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE_IMAGES (" +
                "$COL_IMAGE_ID TEXT PRIMARY KEY, " +
                "$COL_FILE_HASH TEXT NOT NULL, " +
                "$COL_ASSET_FILE_NAME TEXT NOT NULL, " +
                "$COL_MIME_TYPE TEXT, " +
                "$COL_WIDTH INTEGER, " +
                "$COL_HEIGHT INTEGER, " +
                "$COL_CREATED_AT INTEGER NOT NULL, " +
                "$COL_ORIGIN_CHAT_ID TEXT, " +
                "$COL_ORIGIN_CHAT_NAME TEXT, " +
                "$COL_ORIGIN_MESSAGE_ID TEXT, " +
                "$COL_LOCKED INTEGER NOT NULL DEFAULT 0 CHECK ($COL_LOCKED IN (0,1)), " +
                "$COL_SOURCE TEXT NOT NULL DEFAULT 'backfill')"
        )
        createSupportingTables(db)
        createIndexes(db)
    }

    private fun createSupportingTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_TOMBSTONES (" +
                "$COL_IMAGE_ID TEXT PRIMARY KEY, " +
                "$COL_ASSET_FILE_NAME TEXT, " +
                "deleted_at INTEGER NOT NULL, reason TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_META (" +
                "key TEXT PRIMARY KEY, value TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_BACKFILL_CHATS (" +
                "chat_id TEXT PRIMARY KEY, scanned_at INTEGER NOT NULL)"
        )
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_generated_images_hash ON $TABLE_IMAGES($COL_FILE_HASH)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_generated_images_created ON $TABLE_IMAGES($COL_CREATED_AT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_generated_images_origin ON $TABLE_IMAGES($COL_ORIGIN_CHAT_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_generated_images_asset ON $TABLE_IMAGES($COL_ASSET_FILE_NAME)")
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS generated_image_id_immutable " +
                "BEFORE UPDATE OF $COL_IMAGE_ID ON $TABLE_IMAGES " +
                "WHEN OLD.$COL_IMAGE_ID != NEW.$COL_IMAGE_ID " +
                "BEGIN SELECT RAISE(ABORT, 'generated image UUID is immutable'); END"
        )
    }

    /** Idempotent registration for newly generated assets. Existing UUID rows
     * are retained unchanged; retrying cannot transfer ownership or identity. */
    fun register(record: GeneratedImageCatalogRecord): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val inserted = db.insertWithOnConflict(
                TABLE_IMAGES,
                null,
                values(record),
                SQLiteDatabase.CONFLICT_IGNORE
            )
            val existing = if (inserted == -1L) findActiveTx(db, record.imageId) else record
            val matches = existing?.let {
                it.imageId == record.imageId &&
                    it.fileHash == record.fileHash &&
                    it.assetFileName == record.assetFileName
            } == true
            if (matches) {
                db.delete(TABLE_TOMBSTONES, "$COL_IMAGE_ID = ?", arrayOf(record.imageId))
                db.setTransactionSuccessful()
            }
            matches
        } finally {
            db.endTransaction()
        }
    }

    /** Backfill merges duplicate references conservatively. If the same UUID
     * appears in different chats, ownership/name become unknown rather than
     * being reassigned to whichever copy was scanned last. */
    fun upsertBackfill(record: GeneratedImageCatalogRecord): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val current = findActiveTx(db, record.imageId)
            when {
                current == null -> {
                    db.insertOrThrow(TABLE_IMAGES, null, values(record.copy(source = GeneratedImageCatalogRecord.Source.BACKFILL)))
                }
                current.source == GeneratedImageCatalogRecord.Source.GENERATED -> Unit
                current.originChatId != record.originChatId -> {
                    val clearOrigin = ContentValues().apply {
                        putNull(COL_ORIGIN_CHAT_ID)
                        putNull(COL_ORIGIN_CHAT_NAME)
                        putNull(COL_ORIGIN_MESSAGE_ID)
                    }
                    db.update(TABLE_IMAGES, clearOrigin, "$COL_IMAGE_ID = ?", arrayOf(record.imageId))
                }
                else -> Unit
            }
            db.delete(TABLE_TOMBSTONES, "$COL_IMAGE_ID = ?", arrayOf(record.imageId))
            db.setTransactionSuccessful()
            true
        } finally {
            db.endTransaction()
        }
    }

    fun lookup(imageId: String): GeneratedImageCatalogLookup {
        val record = findActiveTx(readableDatabase, imageId)
        if (record != null) {
            return GeneratedImageCatalogLookup(GeneratedImageCatalogStorageState.AVAILABLE, record)
        }
        val tombstoned = readableDatabase.rawQuery(
            "SELECT 1 FROM $TABLE_TOMBSTONES WHERE $COL_IMAGE_ID = ? LIMIT 1",
            arrayOf(imageId)
        ).use { it.moveToFirst() }
        return GeneratedImageCatalogLookup(
            GeneratedImageCatalogStorageState.AVAILABLE,
            tombstoned = tombstoned
        )
    }

    fun allActive(): List<GeneratedImageCatalogRecord> = readableDatabase.rawQuery(
        "SELECT * FROM $TABLE_IMAGES ORDER BY $COL_CREATED_AT ASC, $COL_IMAGE_ID ASC",
        emptyArray()
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(record(cursor))
        }
    }

    fun hasActiveFileHash(fileHash: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM $TABLE_IMAGES WHERE $COL_FILE_HASH = ? LIMIT 1",
        arrayOf(fileHash)
    ).use { it.moveToFirst() }

    fun activeAssetReferenceCount(assetFileName: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM $TABLE_IMAGES WHERE $COL_ASSET_FILE_NAME = ?",
        arrayOf(assetFileName)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun ownedByChats(chatIds: Set<String>): List<GeneratedImageCatalogRecord> {
        if (chatIds.isEmpty()) return emptyList()
        return allActive().filter { it.originChatId in chatIds }
    }

    /**
     * Explicit, user-confirmed deletion primitive. Every candidate is looked
     * up again inside this transaction; UUID, origin ownership, and Lock are
     * authoritative here rather than in the UI snapshot.
     */
    fun tombstoneUnlockedOwned(
        chatIds: Set<String>,
        candidateImageIds: Set<String>
    ): Pair<List<GeneratedImageCatalogRecord>, Set<String>> {
        if (chatIds.isEmpty() || candidateImageIds.isEmpty()) return emptyList<GeneratedImageCatalogRecord>() to emptySet()
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val removed = ArrayList<GeneratedImageCatalogRecord>()
            val locked = LinkedHashSet<String>()
            for (imageId in candidateImageIds) {
                val current = findActiveTx(db, imageId) ?: continue
                if (current.originChatId !in chatIds) continue
                if (current.locked) {
                    locked.add(current.imageId)
                    continue
                }
                val tombstone = ContentValues().apply {
                    put(COL_IMAGE_ID, current.imageId)
                    put(COL_ASSET_FILE_NAME, current.assetFileName)
                    put("deleted_at", System.currentTimeMillis())
                    put("reason", "chat_delete_all")
                }
                val tombstoneId = db.insertWithOnConflict(
                    TABLE_TOMBSTONES,
                    null,
                    tombstone,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                check(tombstoneId != -1L) { "Unable to persist generated-image tombstone" }
                check(
                    db.delete(TABLE_IMAGES, "$COL_IMAGE_ID = ?", arrayOf(current.imageId)) == 1
                ) { "Unable to remove active generated-image identity" }
                removed.add(current)
            }
            db.setTransactionSuccessful()
            removed to locked
        } finally {
            db.endTransaction()
        }
    }

    /** Re-check every active catalog reference while the catalog write lock is
     * held, then remove the physical file only when no active identity uses it. */
    fun deleteAssetIfUnreferenced(assetFileName: String, deleteFile: () -> Boolean): GeneratedImageAssetDeletionDisposition {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val references = db.rawQuery(
                "SELECT COUNT(*) FROM $TABLE_IMAGES WHERE $COL_ASSET_FILE_NAME = ?",
                arrayOf(assetFileName)
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            val disposition = if (references > 0) {
                GeneratedImageAssetDeletionDisposition.RETAINED_ACTIVE_REFERENCE
            } else if (deleteFile()) {
                GeneratedImageAssetDeletionDisposition.DELETED_OR_ABSENT
            } else {
                GeneratedImageAssetDeletionDisposition.FAILED
            }
            if (disposition != GeneratedImageAssetDeletionDisposition.FAILED) {
                db.setTransactionSuccessful()
            }
            disposition
        } finally {
            db.endTransaction()
        }
    }

    fun renameOriginChat(chatId: String, newName: String): Boolean {
        val values = ContentValues().apply { put(COL_ORIGIN_CHAT_NAME, newName) }
        writableDatabase.update(
            TABLE_IMAGES,
            values,
            "$COL_ORIGIN_CHAT_ID = ?",
            arrayOf(chatId)
        )
        return true
    }

    fun synchronizeOriginNames(namesById: Map<String, String>) {
        if (namesById.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((id, name) in namesById) {
                val values = ContentValues().apply { put(COL_ORIGIN_CHAT_NAME, name) }
                db.update(TABLE_IMAGES, values, "$COL_ORIGIN_CHAT_ID = ?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun tombstoneMissing(imageId: String, assetFileName: String?): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val current = findActiveTx(db, imageId)
            if (current != null && current.assetFileName == assetFileName) {
                db.delete(TABLE_IMAGES, "$COL_IMAGE_ID = ?", arrayOf(imageId))
                val tombstone = ContentValues().apply {
                    put(COL_IMAGE_ID, imageId)
                    if (assetFileName == null) putNull(COL_ASSET_FILE_NAME) else put(COL_ASSET_FILE_NAME, assetFileName)
                    put("deleted_at", System.currentTimeMillis())
                    put("reason", "missing")
                }
                db.insertWithOnConflict(TABLE_TOMBSTONES, null, tombstone, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
            true
        } finally {
            db.endTransaction()
        }
    }

    fun isBackfillChatComplete(chatId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM $TABLE_BACKFILL_CHATS WHERE chat_id = ? LIMIT 1",
        arrayOf(chatId)
    ).use { it.moveToFirst() }

    fun markBackfillChatComplete(chatId: String) {
        writableDatabase.insertWithOnConflict(
            TABLE_BACKFILL_CHATS,
            null,
            ContentValues().apply {
                put("chat_id", chatId)
                put("scanned_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getMeta(key: String): String? = readableDatabase.rawQuery(
        "SELECT value FROM $TABLE_META WHERE key = ?",
        arrayOf(key)
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun setMeta(key: String, value: String) {
        writableDatabase.insertWithOnConflict(
            TABLE_META,
            null,
            ContentValues().apply { put("key", key); put("value", value) },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun integrityCheck(): String? = readableDatabase.rawQuery("PRAGMA integrity_check", null).use {
        if (!it.moveToFirst()) "integrity_check returned no result"
        else it.getString(0).takeIf { result -> result != "ok" }
    }

    private fun findActiveTx(db: SQLiteDatabase, imageId: String): GeneratedImageCatalogRecord? =
        db.rawQuery(
            "SELECT * FROM $TABLE_IMAGES WHERE $COL_IMAGE_ID = ? LIMIT 1",
            arrayOf(imageId)
        ).use { if (it.moveToFirst()) record(it) else null }

    private fun values(record: GeneratedImageCatalogRecord) = ContentValues().apply {
        put(COL_IMAGE_ID, record.imageId)
        put(COL_FILE_HASH, record.fileHash)
        put(COL_ASSET_FILE_NAME, record.assetFileName)
        if (record.mimeType == null) putNull(COL_MIME_TYPE) else put(COL_MIME_TYPE, record.mimeType)
        if (record.width == null) putNull(COL_WIDTH) else put(COL_WIDTH, record.width)
        if (record.height == null) putNull(COL_HEIGHT) else put(COL_HEIGHT, record.height)
        put(COL_CREATED_AT, record.createdAt)
        if (record.originChatId == null) putNull(COL_ORIGIN_CHAT_ID) else put(COL_ORIGIN_CHAT_ID, record.originChatId)
        if (record.originChatName == null) putNull(COL_ORIGIN_CHAT_NAME) else put(COL_ORIGIN_CHAT_NAME, record.originChatName)
        if (record.originMessageId == null) putNull(COL_ORIGIN_MESSAGE_ID) else put(COL_ORIGIN_MESSAGE_ID, record.originMessageId)
        put(COL_LOCKED, if (record.locked) 1 else 0)
        put(COL_SOURCE, record.source.storageValue)
    }

    private fun record(cursor: Cursor) = GeneratedImageCatalogRecord(
        imageId = cursor.getString(cursor.getColumnIndexOrThrow(COL_IMAGE_ID)),
        fileHash = cursor.getString(cursor.getColumnIndexOrThrow(COL_FILE_HASH)),
        assetFileName = cursor.getString(cursor.getColumnIndexOrThrow(COL_ASSET_FILE_NAME)),
        mimeType = cursor.stringOrNull(COL_MIME_TYPE),
        width = cursor.intOrNull(COL_WIDTH),
        height = cursor.intOrNull(COL_HEIGHT),
        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT)),
        originChatId = cursor.stringOrNull(COL_ORIGIN_CHAT_ID),
        originChatName = cursor.stringOrNull(COL_ORIGIN_CHAT_NAME),
        originMessageId = cursor.stringOrNull(COL_ORIGIN_MESSAGE_ID),
        locked = cursor.getInt(cursor.getColumnIndexOrThrow(COL_LOCKED)) != 0,
        source = GeneratedImageCatalogRecord.Source.fromStorage(cursor.stringOrNull(COL_SOURCE))
    )

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun Cursor.intOrNull(column: String): Int? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getInt(index)
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) if (cursor.getString(nameIndex) == column) return@use true
            false
        }

    companion object {
        const val DATABASE_NAME = "generated_images.db"
        private const val DATABASE_VERSION = 2
        const val META_BACKFILL_VERSION = "legacy_backfill_version"
        const val BACKFILL_VERSION = "1"

        private const val TABLE_IMAGES = "generated_images"
        private const val TABLE_TOMBSTONES = "generated_image_tombstones"
        private const val TABLE_META = "meta"
        private const val TABLE_BACKFILL_CHATS = "backfill_chats"
        private const val COL_IMAGE_ID = "image_id"
        private const val COL_FILE_HASH = "file_hash"
        private const val COL_ASSET_FILE_NAME = "asset_file_name"
        private const val COL_MIME_TYPE = "mime_type"
        private const val COL_WIDTH = "width"
        private const val COL_HEIGHT = "height"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_ORIGIN_CHAT_ID = "origin_chat_id"
        private const val COL_ORIGIN_CHAT_NAME = "origin_chat_name"
        private const val COL_ORIGIN_MESSAGE_ID = "origin_message_id"
        private const val COL_LOCKED = "locked"
        private const val COL_SOURCE = "source"

        @Volatile private var instance: GeneratedImageCatalogStore? = null
        @Volatile private var libraryLoaded = false

        private fun access(context: Context): Pair<GeneratedImageCatalogStore?, GeneratedImageCatalogStorageState> {
            val app = context.applicationContext
            if (GeneratedImageCatalogHealth.isCorrupt(app)) return null to GeneratedImageCatalogStorageState.CORRUPT
            return try {
                val store = instance ?: synchronized(this) {
                    instance ?: run {
                        loadLibrary()
                        val exists = app.getDatabasePath(DATABASE_NAME).exists()
                        val key = DatabaseKeys.getOrCreate(app, DatabaseKeys.KEY_GENERATED_IMAGES, exists)
                            ?: return null to GeneratedImageCatalogStorageState.LOCKED
                        GeneratedImageCatalogStore(app, key).also { instance = it }
                    }
                }
                store.readableDatabase.rawQuery("SELECT 1", null).use { it.moveToFirst() }
                store to GeneratedImageCatalogStorageState.AVAILABLE
            } catch (_: Exception) {
                null to if (GeneratedImageCatalogHealth.isCorrupt(app)) {
                    GeneratedImageCatalogStorageState.CORRUPT
                } else {
                    GeneratedImageCatalogStorageState.UNAVAILABLE
                }
            }
        }

        fun register(context: Context, record: GeneratedImageCatalogRecord): GeneratedImageCatalogWriteResult =
            write(context) { it.register(record) }

        fun upsertBackfill(context: Context, record: GeneratedImageCatalogRecord): GeneratedImageCatalogWriteResult =
            write(context) { it.upsertBackfill(record) }

        fun lookup(context: Context, imageId: String): GeneratedImageCatalogLookup {
            val (store, state) = access(context)
            if (store == null) return GeneratedImageCatalogLookup(state)
            return try { store.lookup(imageId) } catch (_: Exception) { GeneratedImageCatalogLookup(failureState(context)) }
        }

        fun listActive(context: Context): GeneratedImageCatalogListResult {
            val (store, state) = access(context)
            if (store == null) return GeneratedImageCatalogListResult(state)
            return try {
                GeneratedImageCatalogListResult(GeneratedImageCatalogStorageState.AVAILABLE, store.allActive())
            } catch (_: Exception) {
                GeneratedImageCatalogListResult(failureState(context))
            }
        }

        fun listOwnedByChats(context: Context, chatIds: Set<String>): GeneratedImageCatalogListResult {
            val (store, state) = access(context)
            if (store == null) return GeneratedImageCatalogListResult(state)
            return try {
                GeneratedImageCatalogListResult(
                    GeneratedImageCatalogStorageState.AVAILABLE,
                    store.ownedByChats(chatIds)
                )
            } catch (_: Exception) {
                GeneratedImageCatalogListResult(failureState(context))
            }
        }

        fun tombstoneUnlockedOwned(
            context: Context,
            chatIds: Set<String>,
            candidateImageIds: Set<String>
        ): GeneratedImageCatalogDeletionResult {
            val (store, state) = access(context)
            if (store == null) return GeneratedImageCatalogDeletionResult(state = state)
            return try {
                val (removed, locked) = store.tombstoneUnlockedOwned(chatIds, candidateImageIds)
                GeneratedImageCatalogDeletionResult(
                    state = GeneratedImageCatalogStorageState.AVAILABLE,
                    removed = removed,
                    lockedImageIds = locked,
                    success = true
                )
            } catch (_: Exception) {
                GeneratedImageCatalogDeletionResult(state = failureState(context))
            }
        }

        fun deleteAssetIfUnreferenced(
            context: Context,
            assetFileName: String
        ): GeneratedImageAssetDeletionResult {
            val safeName = assetFileName.takeIf {
                it.isNotBlank() && !it.contains('/') && !it.contains('\\')
            } ?: return GeneratedImageAssetDeletionResult(
                GeneratedImageCatalogStorageState.AVAILABLE,
                GeneratedImageAssetDeletionDisposition.RETAINED_ACTIVE_REFERENCE
            )
            val imagesDir = context.applicationContext.getExternalFilesDir("images")
                ?: return GeneratedImageAssetDeletionResult(
                    GeneratedImageCatalogStorageState.UNAVAILABLE,
                    GeneratedImageAssetDeletionDisposition.FAILED
                )
            val (store, state) = access(context)
            if (store == null) return GeneratedImageAssetDeletionResult(
                state,
                GeneratedImageAssetDeletionDisposition.FAILED
            )
            return try {
                val file = java.io.File(imagesDir, safeName)
                GeneratedImageAssetDeletionResult(
                    GeneratedImageCatalogStorageState.AVAILABLE,
                    store.deleteAssetIfUnreferenced(safeName) {
                        !file.exists() || (file.delete() && !file.exists())
                    }
                )
            } catch (_: Exception) {
                GeneratedImageAssetDeletionResult(
                    failureState(context),
                    GeneratedImageAssetDeletionDisposition.FAILED
                )
            }
        }

        fun hasActiveFileHash(context: Context, hash: String): GeneratedImageCatalogBooleanResult {
            val (store, state) = access(context)
            if (store == null) return GeneratedImageCatalogBooleanResult(state)
            return try {
                GeneratedImageCatalogBooleanResult(GeneratedImageCatalogStorageState.AVAILABLE, store.hasActiveFileHash(hash))
            } catch (_: Exception) {
                GeneratedImageCatalogBooleanResult(failureState(context))
            }
        }

        fun renameOriginChat(context: Context, chatId: String, newName: String): GeneratedImageCatalogWriteResult =
            write(context) { it.renameOriginChat(chatId, newName) }

        fun synchronizeOriginNames(context: Context, names: Map<String, String>): GeneratedImageCatalogWriteResult =
            write(context) { it.synchronizeOriginNames(names); true }

        fun tombstoneMissing(context: Context, imageId: String, assetFileName: String?): GeneratedImageCatalogWriteResult =
            write(context) { it.tombstoneMissing(imageId, assetFileName) }

        fun isBackfillChatComplete(context: Context, chatId: String): GeneratedImageCatalogBooleanResult {
            val (store, state) = access(context)
            if (store == null) return GeneratedImageCatalogBooleanResult(state)
            return try {
                GeneratedImageCatalogBooleanResult(GeneratedImageCatalogStorageState.AVAILABLE, store.isBackfillChatComplete(chatId))
            } catch (_: Exception) {
                GeneratedImageCatalogBooleanResult(failureState(context))
            }
        }

        fun markBackfillChatComplete(context: Context, chatId: String): GeneratedImageCatalogWriteResult =
            write(context) { it.markBackfillChatComplete(chatId); true }

        fun getMeta(context: Context, key: String): Pair<GeneratedImageCatalogStorageState, String?> {
            val (store, state) = access(context)
            if (store == null) return state to null
            return try { GeneratedImageCatalogStorageState.AVAILABLE to store.getMeta(key) }
            catch (_: Exception) { failureState(context) to null }
        }

        fun setMeta(context: Context, key: String, value: String): GeneratedImageCatalogWriteResult =
            write(context) { it.setMeta(key, value); true }

        private fun write(context: Context, block: (GeneratedImageCatalogStore) -> Boolean): GeneratedImageCatalogWriteResult {
            val (store, state) = access(context)
            if (store == null) return GeneratedImageCatalogWriteResult(false, state)
            return try {
                GeneratedImageCatalogWriteResult(block(store), GeneratedImageCatalogStorageState.AVAILABLE)
            } catch (_: Exception) {
                GeneratedImageCatalogWriteResult(false, failureState(context))
            }
        }

        private fun failureState(context: Context) =
            if (GeneratedImageCatalogHealth.isCorrupt(context)) GeneratedImageCatalogStorageState.CORRUPT
            else GeneratedImageCatalogStorageState.UNAVAILABLE

        fun invalidateInstance() {
            synchronized(this) {
                try { instance?.close() } catch (_: Exception) { }
                instance = null
            }
        }

        private fun loadLibrary() {
            if (!libraryLoaded) synchronized(this) {
                if (!libraryLoaded) {
                    System.loadLibrary("sqlcipher")
                    libraryLoaded = true
                }
            }
        }

        @VisibleForTesting
        fun openForTest(context: Context, databaseName: String, password: ByteArray): GeneratedImageCatalogStore {
            loadLibrary()
            return GeneratedImageCatalogStore(context.applicationContext, password, databaseName)
        }
    }
}

private class CatalogCorruptionHandler(context: Context) : DatabaseErrorHandler {
    private val app = context.applicationContext

    override fun onCorruption(dbObj: SQLiteDatabase, exception: android.database.sqlite.SQLiteException?) {
        GeneratedImageCatalogHealth.markCorrupt(
            app,
            exception?.javaClass?.simpleName ?: "SQLiteDatabaseCorruptException"
        )
        try { if (dbObj.isOpen) dbObj.close() } catch (_: Exception) { }
    }
}
