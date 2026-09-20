/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogRecord
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogSnapshot
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogTombstone
import java.util.UUID

/** Versioned, device-independent representation of generated-image catalog
 * state. Image bytes remain separate package artifacts so every entry can be
 * bounded, hashed, and MIME-sniffed before a restore touches live data. */
object GeneratedImagePortableCatalog {

    const val FORMAT = "generated-images-logical-v1"
    const val MAX_RECORDS = 10_000
    const val MAX_META_ENTRIES = 256
    const val MAX_JSON_CHARS = 8 * 1024 * 1024

    enum class InvalidReason {
        MALFORMED,
        UNSUPPORTED_VERSION,
        TOO_LARGE,
        INVALID_IDENTITY,
        INVALID_FILE,
        DUPLICATE_IDENTITY,
        INVALID_METADATA
    }

    sealed class ParseResult {
        data class Ok(val snapshot: GeneratedImageCatalogSnapshot) : ParseResult()
        data class Invalid(val reason: InvalidReason) : ParseResult()
    }

    fun toJson(snapshot: GeneratedImageCatalogSnapshot): String {
        require(validate(snapshot) == null) { "invalid generated-image catalog" }
        return toJsonUnchecked(snapshot)
    }

    @JvmStatic
    fun toJsonUnchecked(snapshot: GeneratedImageCatalogSnapshot): String {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("active", JSONArray().apply {
                snapshot.active.forEach { put(recordJson(it)) }
            })
            .put("tombstones", JSONArray().apply {
                snapshot.tombstones.forEach { tombstone ->
                    put(JSONObject()
                        .put("image_id", tombstone.imageId)
                        .apply {
                            tombstone.assetFileName?.let { put("asset_file_name", it) }
                        }
                        .put("deleted_at", tombstone.deletedAt)
                        .put("reason", tombstone.reason))
                }
            })
            .put("meta", JSONArray().apply {
                snapshot.meta.toSortedMap().forEach { (key, value) ->
                    put(JSONObject().put("key", key).put("value", value))
                }
            })
            .put("backfill_chats", JSONArray().apply {
                snapshot.backfillChats.toSortedMap().forEach { (chatId, scannedAt) ->
                    put(JSONObject().put("chat_id", chatId).put("scanned_at", scannedAt))
                }
            })
        return root.toString()
    }

    fun parse(json: String): ParseResult {
        if (json.length > MAX_JSON_CHARS) return ParseResult.Invalid(InvalidReason.TOO_LARGE)
        return try {
            val root = JSONObject(json)
            if (root.optString("format") != FORMAT) {
                return ParseResult.Invalid(InvalidReason.UNSUPPORTED_VERSION)
            }
            val activeJson = root.optJSONArray("active")
                ?: return ParseResult.Invalid(InvalidReason.MALFORMED)
            val tombstoneJson = root.optJSONArray("tombstones")
                ?: return ParseResult.Invalid(InvalidReason.MALFORMED)
            val metaJson = root.optJSONArray("meta")
                ?: return ParseResult.Invalid(InvalidReason.MALFORMED)
            val backfillJson = root.optJSONArray("backfill_chats")
                ?: return ParseResult.Invalid(InvalidReason.MALFORMED)
            if (activeJson.length() > MAX_RECORDS || tombstoneJson.length() > MAX_RECORDS ||
                metaJson.length() > MAX_META_ENTRIES || backfillJson.length() > MAX_RECORDS
            ) return ParseResult.Invalid(InvalidReason.TOO_LARGE)

            val active = ArrayList<GeneratedImageCatalogRecord>(activeJson.length())
            repeat(activeJson.length()) { active.add(parseRecord(activeJson.getJSONObject(it))) }
            val tombstones = ArrayList<GeneratedImageCatalogTombstone>(tombstoneJson.length())
            repeat(tombstoneJson.length()) {
                val item = tombstoneJson.getJSONObject(it)
                tombstones.add(
                    GeneratedImageCatalogTombstone(
                        imageId = requiredString(item, "image_id"),
                        assetFileName = nullableString(item, "asset_file_name"),
                        deletedAt = requiredLong(item, "deleted_at"),
                        reason = requiredString(item, "reason")
                    )
                )
            }
            val meta = LinkedHashMap<String, String>()
            repeat(metaJson.length()) {
                val item = metaJson.getJSONObject(it)
                val key = requiredString(item, "key")
                if (meta.put(key, requiredString(item, "value")) != null) {
                    return ParseResult.Invalid(InvalidReason.INVALID_METADATA)
                }
            }
            val backfill = LinkedHashMap<String, Long>()
            repeat(backfillJson.length()) {
                val item = backfillJson.getJSONObject(it)
                val id = requiredString(item, "chat_id")
                if (backfill.put(id, requiredLong(item, "scanned_at")) != null) {
                    return ParseResult.Invalid(InvalidReason.INVALID_METADATA)
                }
            }
            val snapshot = GeneratedImageCatalogSnapshot(active, tombstones, meta, backfill)
            validate(snapshot)?.let { ParseResult.Invalid(it) } ?: ParseResult.Ok(snapshot)
        } catch (_: Exception) {
            ParseResult.Invalid(InvalidReason.MALFORMED)
        }
    }

    fun validate(snapshot: GeneratedImageCatalogSnapshot): InvalidReason? {
        if (snapshot.active.size > MAX_RECORDS || snapshot.tombstones.size > MAX_RECORDS ||
            snapshot.meta.size > MAX_META_ENTRIES || snapshot.backfillChats.size > MAX_RECORDS
        ) return InvalidReason.TOO_LARGE

        val activeIds = HashSet<String>()
        for (record in snapshot.active) {
            if (!validImageId(record.imageId)) return InvalidReason.INVALID_IDENTITY
            if (!activeIds.add(record.imageId)) return InvalidReason.DUPLICATE_IDENTITY
            if (!HASH.matches(record.fileHash) || !safeAssetName(record.assetFileName)) {
                return InvalidReason.INVALID_FILE
            }
            if (record.mimeType != null && record.mimeType !in MIME_TYPES) return InvalidReason.INVALID_FILE
            if (!validDimension(record.width) || !validDimension(record.height) || record.createdAt < 0L) {
                return InvalidReason.INVALID_METADATA
            }
            if (!validOptional(record.originChatId, 256) ||
                !validOptional(record.originChatName, 4096) ||
                !validOptional(record.originMessageId, 256)
            ) return InvalidReason.INVALID_METADATA
        }

        val tombstoneIds = HashSet<String>()
        for (tombstone in snapshot.tombstones) {
            if (!validImageId(tombstone.imageId)) return InvalidReason.INVALID_IDENTITY
            if (!tombstoneIds.add(tombstone.imageId) || tombstone.imageId in activeIds) {
                return InvalidReason.DUPLICATE_IDENTITY
            }
            if (tombstone.assetFileName != null && !safeAssetName(tombstone.assetFileName)) {
                return InvalidReason.INVALID_FILE
            }
            if (tombstone.deletedAt < 0L || tombstone.reason.isBlank() || tombstone.reason.length > 128) {
                return InvalidReason.INVALID_METADATA
            }
        }
        if (snapshot.meta.any { (key, value) ->
                key.isBlank() || key.length > 128 || value.length > 4096
            }
        ) return InvalidReason.INVALID_METADATA
        if (snapshot.backfillChats.any { (chatId, scannedAt) ->
                chatId.isBlank() || chatId.length > 256 || scannedAt < 0L
            }
        ) return InvalidReason.INVALID_METADATA
        return null
    }

    fun safeAssetName(name: String): Boolean =
        name.isNotBlank() && name.length <= 160 &&
            !name.contains('/') && !name.contains('\\') && !name.contains("..") &&
            name.none { it.isISOControl() }

    private fun recordJson(record: GeneratedImageCatalogRecord): JSONObject = JSONObject()
        .put("image_id", record.imageId)
        .put("file_hash", record.fileHash)
        .put("asset_file_name", record.assetFileName)
        .apply {
            record.mimeType?.let { put("mime_type", it) }
            record.width?.let { put("width", it) }
            record.height?.let { put("height", it) }
        }
        .put("created_at", record.createdAt)
        .apply {
            record.originChatId?.let { put("origin_chat_id", it) }
            record.originChatName?.let { put("origin_chat_name", it) }
            record.originMessageId?.let { put("origin_message_id", it) }
        }
        .put("locked", record.locked)
        .put("source", record.source.storageValue)

    private fun parseRecord(item: JSONObject): GeneratedImageCatalogRecord {
        val source = when (requiredString(item, "source")) {
            GeneratedImageCatalogRecord.Source.GENERATED.storageValue ->
                GeneratedImageCatalogRecord.Source.GENERATED
            GeneratedImageCatalogRecord.Source.BACKFILL.storageValue ->
                GeneratedImageCatalogRecord.Source.BACKFILL
            else -> throw IllegalArgumentException("unknown source")
        }
        return GeneratedImageCatalogRecord(
            imageId = requiredString(item, "image_id"),
            fileHash = requiredString(item, "file_hash"),
            assetFileName = requiredString(item, "asset_file_name"),
            mimeType = nullableString(item, "mime_type"),
            width = nullableInt(item, "width"),
            height = nullableInt(item, "height"),
            createdAt = requiredLong(item, "created_at"),
            originChatId = nullableString(item, "origin_chat_id"),
            originChatName = nullableString(item, "origin_chat_name"),
            originMessageId = nullableString(item, "origin_message_id"),
            locked = item.optBoolean("locked", false),
            source = source
        )
    }

    private fun requiredString(item: JSONObject, key: String): String {
        if (!item.has(key) || item.isNull(key)) throw IllegalArgumentException("missing $key")
        return item.getString(key)
    }

    private fun nullableString(item: JSONObject, key: String): String? =
        if (!item.has(key) || item.isNull(key)) null else item.getString(key)

    private fun requiredLong(item: JSONObject, key: String): Long {
        if (!item.has(key) || item.isNull(key)) throw IllegalArgumentException("missing $key")
        return item.getLong(key)
    }

    private fun nullableInt(item: JSONObject, key: String): Int? =
        if (!item.has(key) || item.isNull(key)) null else item.getInt(key)

    private fun validDimension(value: Int?): Boolean = value == null || value in 1..100_000
    private fun validOptional(value: String?, cap: Int): Boolean = value == null || value.length <= cap

    private fun validImageId(value: String): Boolean =
        LEGACY_ID.matches(value) || try {
            UUID.fromString(value).toString().equals(value, ignoreCase = true)
        } catch (_: Exception) {
            false
        }

    private val HASH = Regex("^[0-9a-fA-F]{64}$")
    private val LEGACY_ID = Regex("^legacy-[0-9a-fA-F]{64}$")
    private val MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
}
