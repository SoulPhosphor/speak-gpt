/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.memory

import android.content.ContentValues
import android.database.Cursor
import java.util.Base64
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exact row snapshot for every portable table in companion_memory.db plus the
 * device-local rows that a portable identity or memory replacement can alter.
 * BLOB values are encoded explicitly so rollback staging remains lossless.
 */
data class MemorySharedRestoreRows(
    val tables: Map<String, List<Map<String, Any?>>>
)

data class MemorySharedRestoreBlob(val base64: String)

object MemorySharedRestoreRowFormat {
    data class Spec(val table: String, val keys: List<String>)

    /** Parents precede children. Deletion uses the reverse order. */
    val specs = listOf(
        Spec("app_state", listOf("id")),
        Spec("owner_profile", listOf("id")),
        Spec("companions", listOf("companion_id")),
        Spec("companion_name_history", listOf("companion_id", "name", "effective_from")),
        Spec("user_personas", listOf("persona_id")),
        Spec("roleplay_characters", listOf("roleplay_character_id")),
        Spec("worlds", listOf("world_id")),
        Spec("campaigns", listOf("campaign_id")),
        Spec("party_members", listOf("party_member_id")),
        Spec("campaign_party_members", listOf("campaign_id", "party_member_id")),
        Spec("card_entries", listOf("entry_id")),
        Spec("rp_tags", listOf("tag_id")),
        Spec("entities", listOf("entity_id")),
        Spec("projects", listOf("project_id")),
        Spec("memory_types", listOf("type_id")),
        Spec("memories", listOf("memory_id")),
        Spec("memory_companions", listOf("memory_id", "companion_id")),
        Spec("memory_entities", listOf("memory_id", "entity_id")),
        Spec("memory_worlds", listOf("memory_id", "world_id")),
        Spec("memory_campaigns", listOf("memory_id", "campaign_id")),
        Spec("memory_roleplay_characters", listOf("memory_id", "roleplay_character_id")),
        Spec("memory_projects", listOf("memory_id", "project_id")),
        Spec("memory_supersessions", listOf("new_memory_id", "old_memory_id")),
        Spec("memory_possible_match_hints", listOf("draft_memory_id", "existing_memory_id")),
        Spec("change_log", listOf("memory_id", "at", "actor", "action")),
        Spec("modes", listOf("mode_id")),
        Spec("directives", listOf("directive_id")),
        Spec("archivist_settings", listOf("id")),
        Spec("proposals", listOf("proposal_id")),
        Spec("transcripts", listOf("transcript_id")),
        Spec("analysis_chat_bookmarks", listOf("chat_id")),
        Spec("rp_tag_links", listOf("tag_id", "target_type", "target_id")),
        Spec("model_rules", listOf("rule_id")),
        Spec("model_rule_tags", listOf("tag_id")),
        Spec("model_rule_tag_links", listOf("rule_id", "tag_id")),
        Spec("generated_pending_drafts", listOf("memory_id")),
        Spec("embeddings", listOf("memory_id", "embedding_model")),
        Spec("deleted_ids", listOf("record_type", "record_id")),
        Spec("import_conflicts", listOf("conflict_id")),
        Spec("injection_cooldowns", listOf("chat_id", "source_type", "entry_id"))
    )

    val tableNames: Set<String> = specs.mapTo(LinkedHashSet(), Spec::table)

    val identityTables: Set<String> = linkedSetOf(
        "companions", "companion_name_history", "user_personas",
        "roleplay_characters", "worlds", "campaigns", "party_members",
        "campaign_party_members", "card_entries", "rp_tags", "rp_tag_links"
    )

    val memoryTables: Set<String> = MemoryPortableRowFormat.memorySpecs
        .mapTo(LinkedHashSet(), MemoryPortableRowFormat.Spec::table)

    val modelRuleTables: Set<String> = MemoryPortableRowFormat.modelRuleSpecs
        .mapTo(LinkedHashSet(), MemoryPortableRowFormat.Spec::table)

    val memoryAuxiliaryTables: Set<String> = linkedSetOf(
        "generated_pending_drafts", "embeddings", "import_conflicts", "injection_cooldowns"
    )

    fun empty(): MemorySharedRestoreRows = MemorySharedRestoreRows(
        specs.associateTo(LinkedHashMap()) { spec ->
            spec.table to if (spec.table == "app_state") {
                listOf(linkedMapOf<String, Any?>("id" to 1L))
            } else emptyList()
        }
    )

    fun read(db: SQLiteDatabase): MemorySharedRestoreRows {
        val out = LinkedHashMap<String, List<Map<String, Any?>>>()
        for (spec in specs) {
            val rows = ArrayList<Map<String, Any?>>()
            db.query(
                spec.table, null, null, null, null, null,
                spec.keys.joinToString(", ")
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val row = LinkedHashMap<String, Any?>()
                    repeat(cursor.columnCount) { index ->
                        row[cursor.getColumnName(index)] = when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                            Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                            Cursor.FIELD_TYPE_BLOB -> MemorySharedRestoreBlob(
                                Base64.getEncoder().encodeToString(cursor.getBlob(index))
                            )
                            else -> throw IllegalStateException("unsupported shared restore column")
                        }
                    }
                    rows.add(row)
                }
            }
            out[spec.table] = rows
        }
        return MemorySharedRestoreRows(out)
    }

    fun valid(data: MemorySharedRestoreRows): Boolean {
        if (data.tables.keys != tableNames) return false
        for (spec in specs) {
            val seen = HashSet<String>()
            for (row in data.tables[spec.table].orEmpty()) {
                val identity = identity(spec, row) ?: return false
                if (!seen.add(identity)) return false
                if (row.values.any { value ->
                        value !is String && value !is Long && value !is Int &&
                            value !is Double && value !is Boolean &&
                            value !is MemorySharedRestoreBlob && value != null
                    }
                ) return false
            }
        }
        return true
    }

    fun toJson(data: MemorySharedRestoreRows): String {
        require(valid(data))
        return JSONObject()
            .put("version", 1)
            .put("tables", JSONObject().apply {
                specs.forEach { spec ->
                    put(spec.table, JSONArray().apply {
                        data.tables.getValue(spec.table).forEach { row ->
                            put(JSONObject().apply {
                                row.forEach { (key, value) ->
                                    put(key, when (value) {
                                        null -> JSONObject.NULL
                                        is MemorySharedRestoreBlob -> JSONObject()
                                            .put("value_type", "blob")
                                            .put("base64", value.base64)
                                        else -> value
                                    })
                                }
                            })
                        }
                    })
                }
            })
            .toString()
    }

    fun parse(text: String): MemorySharedRestoreRows? {
        return try {
            val root = JSONObject(text)
            if (root.optInt("version", -1) != 1) return null
            val tablesJson = root.getJSONObject("tables")
            if (tablesJson.keys().asSequence().toSet() != tableNames) return null
            val tables = LinkedHashMap<String, List<Map<String, Any?>>>()
            for (spec in specs) {
                val array = tablesJson.getJSONArray(spec.table)
                val rows = ArrayList<Map<String, Any?>>(array.length())
                repeat(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    val row = LinkedHashMap<String, Any?>()
                    item.keys().forEach { key ->
                        row[key] = when (val value = item.get(key)) {
                            JSONObject.NULL -> null
                            is Int -> value.toLong()
                            is String, is Long, is Double, is Boolean -> value
                            is JSONObject -> {
                                if (value.optString("value_type") != "blob" ||
                                    !value.has("base64") || value.length() != 2
                                ) return null
                                val encoded = value.getString("base64")
                                try {
                                    Base64.getDecoder().decode(encoded)
                                } catch (_: IllegalArgumentException) {
                                    return null
                                }
                                MemorySharedRestoreBlob(encoded)
                            }
                            else -> return null
                        }
                    }
                    rows.add(row)
                }
                tables[spec.table] = rows
            }
            MemorySharedRestoreRows(tables).takeIf(::valid)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Replaces all affected rows under one SQLCipher transaction. Tombstones
     * are deliberately narrower: only memory/entity/project rows are replaced;
     * tombstones owned by every other record type remain untouched.
     */
    fun replace(
        db: SQLiteDatabase,
        data: MemorySharedRestoreRows,
        affectedTables: Set<String>
    ): Boolean {
        if (!valid(data) || affectedTables.isEmpty() || affectedTables.any { it !in tableNames }) {
            return false
        }
        db.beginTransaction()
        val committed = try {
            db.execSQL("PRAGMA defer_foreign_keys = ON")
            for (spec in specs.asReversed()) {
                if (spec.table !in affectedTables || spec.table == "deleted_ids") continue
                db.delete(spec.table, null, null)
            }
            if ("deleted_ids" in affectedTables) {
                db.delete(
                    "deleted_ids",
                    "record_type IN ('memory','entity','project')",
                    null
                )
            }
            for (spec in specs) {
                if (spec.table !in affectedTables) continue
                val rows = if (spec.table == "deleted_ids") {
                    data.tables.getValue(spec.table).filter {
                        (it["record_type"] as? String) in MEMORY_TOMBSTONE_TYPES
                    }
                } else data.tables.getValue(spec.table)
                val liveColumns = columns(db, spec.table)
                for (row in rows) insert(db, spec.table, liveColumns, row)
            }
            db.rawQuery("PRAGMA foreign_key_check", emptyArray<String>()).use {
                if (it.moveToFirst()) throw IllegalStateException("foreign key check failed")
            }
            db.setTransactionSuccessful()
            true
        } catch (_: Exception) {
            false
        } finally {
            db.endTransaction()
        }
        return committed && db.isDatabaseIntegrityOk
    }

    private fun identity(spec: Spec, row: Map<String, Any?>): String? {
        val parts = spec.keys.map { row[it]?.toString().orEmpty() }
        if (parts.any(String::isBlank)) return null
        return parts.joinToString("\u0000")
    }

    private fun insert(
        db: SQLiteDatabase,
        table: String,
        liveColumns: Set<String>,
        row: Map<String, Any?>
    ) {
        val values = ContentValues()
        for ((column, value) in row) {
            if (column !in liveColumns) continue
            when (value) {
                null -> values.putNull(column)
                is Long -> values.put(column, value)
                is Int -> values.put(column, value.toLong())
                is Double -> values.put(column, value)
                is Boolean -> values.put(column, if (value) 1L else 0L)
                is MemorySharedRestoreBlob -> values.put(
                    column, Base64.getDecoder().decode(value.base64)
                )
                else -> values.put(column, value.toString())
            }
        }
        if (values.size() == 0) throw IllegalStateException("row has no usable columns")
        db.insertOrThrow(table, null, values)
    }

    private fun columns(db: SQLiteDatabase, table: String): Set<String> {
        val out = HashSet<String>()
        db.rawQuery("PRAGMA table_info($table)", emptyArray<String>()).use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) out.add(cursor.getString(name))
        }
        return out
    }

    private val MEMORY_TOMBSTONE_TYPES = setOf("memory", "entity", "project")
}
