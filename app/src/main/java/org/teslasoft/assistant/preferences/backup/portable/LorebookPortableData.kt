/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.dto.LoreBook
import org.teslasoft.assistant.preferences.dto.LoreBookEntry

data class LorebookPortableData(
    val books: List<LoreBook>,
    val entries: List<LoreBookEntry>,
    val deletedEntries: List<DeletedLorebookEntry>
)

data class DeletedLorebookEntry(
    val id: String,
    val deletedAt: Long,
    val createdAt: Long
)

object LorebookPortableCodec {
    fun toJson(data: LorebookPortableData): String = JSONObject()
        .put("version", 1)
        .put("books", JSONArray().apply {
            data.books.forEach { book -> put(JSONObject()
                .put("id", book.id).put("name", book.name)
                .put("description", book.description).put("tag", book.tag)
                .put("created_at", book.createdAt).put("updated_at", book.updatedAt)) }
        })
        .put("entries", JSONArray().apply {
            data.entries.forEach { entry -> put(JSONObject()
                .put("id", entry.id).put("lorebook_id", entry.lorebookId)
                .put("label", entry.label).put("content", entry.content)
                .put("source_text", entry.sourceText).put("triggers", JSONArray(entry.triggers))
                .put("enabled", entry.enabled).put("created_at", entry.createdAt)
                .put("updated_at", entry.updatedAt)) }
        })
        .put("deleted_entries", JSONArray().apply {
            data.deletedEntries.forEach { item -> put(JSONObject()
                .put("id", item.id).put("deleted_at", item.deletedAt)
                .put("created_at", item.createdAt)) }
        }).toString()

    fun parse(text: String): LorebookPortableData? {
        return try {
            val root = JSONObject(text)
            if (root.optInt("version", -1) != 1) return null
            val booksJson = root.getJSONArray("books")
            val books = ArrayList<LoreBook>(booksJson.length())
            repeat(booksJson.length()) { index ->
                val item = booksJson.getJSONObject(index)
                books.add(LoreBook(
                    item.getString("id"), item.getString("name"), item.getString("description"),
                    item.getString("tag"), item.getLong("created_at"), item.getLong("updated_at")
                ))
            }
            val entriesJson = root.getJSONArray("entries")
            val entries = ArrayList<LoreBookEntry>(entriesJson.length())
            repeat(entriesJson.length()) { index ->
                val item = entriesJson.getJSONObject(index)
                val triggersJson = item.getJSONArray("triggers")
                val triggers = ArrayList<String>(triggersJson.length())
                repeat(triggersJson.length()) { triggers.add(triggersJson.getString(it)) }
                entries.add(LoreBookEntry(
                    item.getString("id"), item.getString("lorebook_id"), item.getString("label"),
                    item.getString("content"), item.getString("source_text"), triggers,
                    item.getBoolean("enabled"), item.getLong("created_at"), item.getLong("updated_at")
                ))
            }
            val deletedJson = root.getJSONArray("deleted_entries")
            val deleted = ArrayList<DeletedLorebookEntry>(deletedJson.length())
            repeat(deletedJson.length()) { index ->
                val item = deletedJson.getJSONObject(index)
                deleted.add(DeletedLorebookEntry(
                    item.getString("id"), item.getLong("deleted_at"), item.getLong("created_at")
                ))
            }
            LorebookPortableData(books, entries, deleted).takeIf(LorebookCategoryPlanner::valid)
        } catch (_: Exception) {
            null
        }
    }
}

object LorebookCategoryPlanner {
    data class Conflict(val kind: String, val id: String, val displayName: String)
    data class Report(val conflicts: List<Conflict>, val removedDanglingEntries: Int)

    sealed class Result {
        data class Ready(val data: LorebookPortableData, val report: Report) : Result()
        data object Invalid : Result()
    }

    fun plan(
        current: LorebookPortableData,
        backup: LorebookPortableData,
        mode: PortableRestoreMode
    ): Result {
        if (!valid(current) || !valid(backup)) return Result.Invalid
        val conflicts = ArrayList<Conflict>()
        val books = choose(current.books, backup.books, mode, LoreBook::id, LoreBook::name, "Lorebook", conflicts)
        val entries = choose(current.entries, backup.entries, mode, LoreBookEntry::id, LoreBookEntry::label, "Lorebook Entry", conflicts)
        val deleted = choose(
            current.deletedEntries, backup.deletedEntries, mode,
            DeletedLorebookEntry::id, DeletedLorebookEntry::id, "Deleted Lorebook Entry", conflicts
        )
        val bookIds = books.mapTo(HashSet(), LoreBook::id)
        val keptEntries = entries.filter { it.lorebookId in bookIds }
        return Result.Ready(
            LorebookPortableData(books, keptEntries, deleted.filter { tombstone ->
                keptEntries.none { it.id == tombstone.id }
            }),
            Report(conflicts, entries.size - keptEntries.size)
        )
    }

    fun valid(data: LorebookPortableData): Boolean {
        fun idsValid(ids: List<String>) = ids.all(String::isNotBlank) && ids.distinct().size == ids.size
        return idsValid(data.books.map(LoreBook::id)) &&
            idsValid(data.entries.map(LoreBookEntry::id)) &&
            idsValid(data.deletedEntries.map(DeletedLorebookEntry::id)) &&
            data.books.all { it.createdAt >= 0L && it.updatedAt >= 0L } &&
            data.entries.all { entry ->
                entry.createdAt >= 0L && entry.updatedAt >= 0L &&
                    entry.triggers.all { it.isNotBlank() }
            }
    }

    private fun <T> choose(
        current: List<T>, backup: List<T>, mode: PortableRestoreMode,
        id: (T) -> String, name: (T) -> String, kind: String,
        conflicts: MutableList<Conflict>
    ): List<T> {
        if (mode == PortableRestoreMode.REPLACE) return backup
        val currentById = current.associateBy(id)
        val result = current.toMutableList()
        for (item in backup) {
            val existing = currentById[id(item)]
            if (existing == null) result.add(item)
            else if (existing != item) conflicts.add(Conflict(kind, id(item), name(existing)))
        }
        return result
    }
}
