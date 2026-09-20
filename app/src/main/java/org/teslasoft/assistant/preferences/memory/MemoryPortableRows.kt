/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.memory

import android.content.ContentValues
import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

enum class MemoryPortableGroup { MEMORIES, MODEL_RULES }

data class MemoryPortableRows(val tables: Map<String, List<Map<String, Any?>>>)

object MemoryPortableRowFormat {
    data class Spec(val table: String, val keys: List<String>, val where: String? = null)

    val memorySpecs = listOf(
        Spec("owner_profile", listOf("id")),
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
        Spec("rp_tag_links", listOf("tag_id", "target_type", "target_id"), "target_type = 'memory'"),
        Spec("deleted_ids", listOf("record_type", "record_id"), "record_type IN ('memory','entity','project')")
    )

    val modelRuleSpecs = listOf(
        Spec("model_rules", listOf("rule_id")),
        Spec("model_rule_tags", listOf("tag_id")),
        Spec("model_rule_tag_links", listOf("rule_id", "tag_id"))
    )

    fun specs(group: MemoryPortableGroup): List<Spec> = when (group) {
        MemoryPortableGroup.MEMORIES -> memorySpecs
        MemoryPortableGroup.MODEL_RULES -> modelRuleSpecs
    }

    fun read(db: SQLiteDatabase, group: MemoryPortableGroup): MemoryPortableRows {
        val out = LinkedHashMap<String, List<Map<String, Any?>>>()
        for (spec in specs(group)) {
            val rows = ArrayList<Map<String, Any?>>()
            db.query(spec.table, null, spec.where, null, null, null, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val row = LinkedHashMap<String, Any?>()
                    repeat(cursor.columnCount) { index ->
                        val column = cursor.getColumnName(index)
                        if (spec.table == "change_log" && column == "id") return@repeat
                        row[column] = when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                            Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                            else -> throw IllegalStateException("unsupported portable column")
                        }
                    }
                    rows.add(row)
                }
            }
            out[spec.table] = rows
        }
        return MemoryPortableRows(out)
    }

    fun replace(db: SQLiteDatabase, group: MemoryPortableGroup, data: MemoryPortableRows): Boolean {
        if (!valid(group, data)) return false
        val specs = specs(group)
        db.beginTransaction()
        return try {
            db.execSQL("PRAGMA defer_foreign_keys = ON")
            when (group) {
                MemoryPortableGroup.MEMORIES -> {
                    db.delete("rp_tag_links", "target_type = 'memory'", null)
                    db.delete("injection_cooldowns", "source_type = 'memory'", null)
                    db.delete("import_conflicts", null, null)
                    for (spec in specs.asReversed()) {
                        if (spec.where == null) db.delete(spec.table, null, null)
                    }
                    db.delete(
                        "deleted_ids",
                        "record_type IN ('memory','entity','project')",
                        null
                    )
                }
                MemoryPortableGroup.MODEL_RULES -> specs.asReversed().forEach {
                    db.delete(it.table, null, null)
                }
            }
            for (spec in specs) {
                val liveColumns = columns(db, spec.table)
                for (row in data.tables[spec.table].orEmpty()) {
                    val values = ContentValues()
                    for ((column, value) in row) {
                        if (column !in liveColumns) continue
                        when (value) {
                            null -> values.putNull(column)
                            is Long -> values.put(column, value)
                            is Int -> values.put(column, value.toLong())
                            is Double -> values.put(column, value)
                            is Boolean -> values.put(column, if (value) 1L else 0L)
                            else -> values.put(column, value.toString())
                        }
                    }
                    db.insertOrThrow(spec.table, null, values)
                }
            }
            db.setTransactionSuccessful()
            true
        } catch (_: Exception) {
            false
        } finally {
            db.endTransaction()
        }
    }

    fun valid(group: MemoryPortableGroup, data: MemoryPortableRows): Boolean {
        val specs = specs(group)
        if (data.tables.keys != specs.mapTo(LinkedHashSet(), Spec::table)) return false
        for (spec in specs) {
            val seen = HashSet<String>()
            for (row in data.tables[spec.table].orEmpty()) {
                val identity = identity(spec, row) ?: return false
                if (!seen.add(identity)) return false
            }
        }
        return true
    }

    fun identity(spec: Spec, row: Map<String, Any?>): String? {
        val parts = spec.keys.map { row[it]?.toString().orEmpty() }
        if (parts.any(String::isBlank)) return null
        return parts.joinToString("\u0000")
    }

    fun toJson(group: MemoryPortableGroup, data: MemoryPortableRows): String {
        require(valid(group, data))
        return JSONObject().put("version", 1).put("group", group.name).put("tables", JSONObject().apply {
            specs(group).forEach { spec -> put(spec.table, JSONArray().apply {
                data.tables.getValue(spec.table).forEach { row -> put(JSONObject().apply {
                    row.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
                }) }
            }) }
        }).toString()
    }

    fun parse(text: String, expected: MemoryPortableGroup): MemoryPortableRows? {
        return try {
            val root = JSONObject(text)
            if (root.optInt("version", -1) != 1 || root.optString("group") != expected.name) return null
            val tablesJson = root.getJSONObject("tables")
            val tables = LinkedHashMap<String, List<Map<String, Any?>>>()
            for (spec in specs(expected)) {
                val array = tablesJson.getJSONArray(spec.table)
                val rows = ArrayList<Map<String, Any?>>(array.length())
                repeat(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    val row = LinkedHashMap<String, Any?>()
                    item.keys().forEach { key -> row[key] = when (val value = item.get(key)) {
                        JSONObject.NULL -> null
                        is Int -> value.toLong()
                        is String, is Long, is Double, is Boolean -> value
                        else -> return null
                    } }
                    rows.add(row)
                }
                tables[spec.table] = rows
            }
            MemoryPortableRows(tables).takeIf { valid(expected, it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun columns(db: SQLiteDatabase, table: String): Set<String> {
        val out = HashSet<String>()
        db.rawQuery("PRAGMA table_info($table)", emptyArray<String>()).use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) out.add(cursor.getString(name))
        }
        return out
    }
}
