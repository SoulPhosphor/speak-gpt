/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup.companion

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Builds and parses the `backup.json` manifest
 * (companion-roleplay-backup-plan.md §3). Pure org.json — no Android APIs —
 * so round-trip and every rejection cause are unit-testable on the JVM.
 *
 * Parsing is also the structural half of the §6.1 validation ladder:
 * wrong `format` marker -> [ParseResult.WrongFile]; unsupported
 * `format_version` -> [ParseResult.NewerFormat]; anything unreadable or
 * structurally unsound -> [ParseResult.Damaged]. The ZIP-level checks
 * (readable archive, manifest present, images present and content-true)
 * live in [CompanionBackupValidator].
 */
object CompanionBackupCodec {

    private const val KEY_FORMAT = "format"
    private const val KEY_FORMAT_VERSION = "format_version"
    private const val KEY_APP_VERSION = "app_version"
    private const val KEY_EXPORTED_AT = "exported_at"
    private const val KEY_COMPANION_PROFILES = "companion_profiles"
    private const val KEY_ACTIVATION_PROMPTS = "activation_prompts"
    private const val KEY_SYSTEM_PROMPTS = "system_prompts"
    private const val KEY_SP_ENTRIES = "entries"
    private const val KEY_SP_SELECTED = "selected_id"
    private const val KEY_ROLEPLAY = "roleplay"
    private const val KEY_IMAGES = "images"

    private val IMAGE_HASH_REGEX = Regex("^[0-9a-f]{64}$")

    sealed class ParseResult {
        data class Ok(val manifest: CompanionBackupManifest) : ParseResult()

        /** The file is not a companion backup (missing/other format marker). */
        object WrongFile : ParseResult()

        /** Written by a newer app version (`format_version` above ours). */
        object NewerFormat : ParseResult()

        /** Claims to be a companion backup but cannot be read safely. */
        object Damaged : ParseResult()
    }

    /* ------------------------------- build ------------------------------- */

    fun toJson(manifest: CompanionBackupManifest): String {
        val root = JSONObject()
        root.put(KEY_FORMAT, CompanionBackupFormat.FORMAT_MARKER)
        root.put(KEY_FORMAT_VERSION, manifest.formatVersion)
        root.put(KEY_APP_VERSION, manifest.appVersion)
        root.put(KEY_EXPORTED_AT, manifest.exportedAt)

        val profiles = JSONArray()
        for (p in manifest.companionProfiles) {
            val o = JSONObject()
            o.put("id", p.id)
            o.put("label", p.label)
            o.put("prompt", p.prompt)
            o.put("activation_prompt_id", p.activationPromptId)
            o.put("core_lorebook_id", p.coreLoreBookId)
            o.put("core_lorebook_name", p.coreLoreBookName ?: JSONObject.NULL)
            o.put("additional_lorebook_ids", JSONArray(p.additionalLoreBookIds))
            o.put("additional_lorebook_names", JSONObject(p.additionalLoreBookNames as Map<*, *>))
            o.put("autoload_last_lorebooks", p.autoLoadLastLoreBooks)
            o.put("last_used_lorebook_ids", JSONArray(p.lastUsedLoreBookIds))
            o.put("avatar_ref", p.avatarRef)
            o.put("chat_name_font_id", p.chatNameFontId)
            o.put("chat_name_size_sp", p.chatNameSizeSp)
            profiles.put(o)
        }
        root.put(KEY_COMPANION_PROFILES, profiles)

        val activation = JSONArray()
        for (a in manifest.activationPrompts) {
            val o = JSONObject()
            o.put("id", a.id)
            o.put("label", a.label)
            o.put("prompt", a.prompt)
            activation.put(o)
        }
        root.put(KEY_ACTIVATION_PROMPTS, activation)

        val systemPrompts = JSONObject()
        val entries = JSONArray()
        for (s in manifest.systemPrompts) {
            val o = JSONObject()
            o.put("id", s.id)
            o.put("title", s.title)
            o.put("body", s.body)
            entries.put(o)
        }
        systemPrompts.put(KEY_SP_ENTRIES, entries)
        systemPrompts.put(KEY_SP_SELECTED, manifest.selectedSystemPromptId)
        root.put(KEY_SYSTEM_PROMPTS, systemPrompts)

        val roleplay = JSONObject()
        for (table in CompanionBackupFormat.ROLEPLAY_TABLES) {
            val rows = JSONArray()
            for (row in manifest.roleplayTables[table].orEmpty()) {
                val o = JSONObject()
                for ((column, value) in row) {
                    o.put(column, value ?: JSONObject.NULL)
                }
                rows.put(o)
            }
            roleplay.put(table, rows)
        }
        root.put(KEY_ROLEPLAY, roleplay)

        val images = JSONArray()
        for (img in manifest.images) {
            val o = JSONObject()
            o.put("hash", img.hash)
            o.put("file", img.file)
            images.put(o)
        }
        root.put(KEY_IMAGES, images)

        // Human-readable (§3): pretty-printed, stable section order.
        return root.toString(2)
    }

    /* ------------------------------- parse ------------------------------- */

    fun parse(jsonText: String): ParseResult {
        val root = try {
            JSONObject(jsonText)
        } catch (_: JSONException) {
            return ParseResult.Damaged
        }

        if (root.optString(KEY_FORMAT, "") != CompanionBackupFormat.FORMAT_MARKER) {
            return ParseResult.WrongFile
        }
        val version = root.opt(KEY_FORMAT_VERSION)
        if (version !is Int && version !is Long) return ParseResult.Damaged
        val versionInt = (version as Number).toInt()
        if (versionInt > CompanionBackupFormat.FORMAT_VERSION) return ParseResult.NewerFormat
        if (versionInt < 1) return ParseResult.Damaged

        return try {
            ParseResult.Ok(parseBody(root, versionInt))
        } catch (_: JSONException) {
            ParseResult.Damaged
        } catch (_: IllegalArgumentException) {
            ParseResult.Damaged
        }
    }

    /** Throws JSONException/IllegalArgumentException on any unsound section. */
    private fun parseBody(root: JSONObject, versionInt: Int): CompanionBackupManifest {
        val profilesJson = root.getJSONArray(KEY_COMPANION_PROFILES)
        val profiles = ArrayList<CompanionProfileEntry>(profilesJson.length())
        for (i in 0 until profilesJson.length()) {
            val o = profilesJson.getJSONObject(i)
            val id = o.getString("id")
            require(id.isNotBlank()) { "blank companion profile id" }
            val namesJson = o.optJSONObject("additional_lorebook_names") ?: JSONObject()
            val names = HashMap<String, String>()
            for (key in namesJson.keys()) names[key] = namesJson.getString(key)
            // Every carried lorebook link must carry its name: the restore
            // report names removed connections and never shows an internal id
            // (owner ruling, August 5 2026). A link without a name cannot be
            // reported truthfully, so the file is not sound.
            val coreId = o.optString("core_lorebook_id", "")
            val coreName =
                if (o.isNull("core_lorebook_name")) null
                else o.optString("core_lorebook_name", "")
            require(coreId.isBlank() || coreName != null) { "core lorebook link without a name" }
            val additionalIds = stringList(o.getJSONArray("additional_lorebook_ids"))
            for (linkId in additionalIds) {
                require(names.containsKey(linkId)) { "additional lorebook link without a name" }
            }
            profiles.add(
                CompanionProfileEntry(
                    id = id,
                    label = o.getString("label"),
                    prompt = o.optString("prompt", ""),
                    activationPromptId = o.optString("activation_prompt_id", ""),
                    coreLoreBookId = coreId,
                    coreLoreBookName = coreName,
                    additionalLoreBookIds = additionalIds,
                    additionalLoreBookNames = names,
                    autoLoadLastLoreBooks = o.optBoolean("autoload_last_lorebooks", false),
                    lastUsedLoreBookIds = stringList(o.getJSONArray("last_used_lorebook_ids")),
                    avatarRef = o.optString("avatar_ref", ""),
                    chatNameFontId = o.optString("chat_name_font_id", ""),
                    chatNameSizeSp = o.optInt("chat_name_size_sp", 0)
                )
            )
        }

        val activationJson = root.getJSONArray(KEY_ACTIVATION_PROMPTS)
        val activation = ArrayList<ActivationPromptEntry>(activationJson.length())
        for (i in 0 until activationJson.length()) {
            val o = activationJson.getJSONObject(i)
            val id = o.getString("id")
            require(id.isNotBlank()) { "blank activation prompt id" }
            activation.add(
                ActivationPromptEntry(id, o.getString("label"), o.optString("prompt", ""))
            )
        }

        val systemJson = root.getJSONObject(KEY_SYSTEM_PROMPTS)
        val entriesJson = systemJson.getJSONArray(KEY_SP_ENTRIES)
        val systemPrompts = ArrayList<SystemPromptEntry>(entriesJson.length())
        for (i in 0 until entriesJson.length()) {
            val o = entriesJson.getJSONObject(i)
            val id = o.getString("id")
            require(id.isNotBlank()) { "blank system prompt id" }
            systemPrompts.add(
                SystemPromptEntry(id, o.optString("title", ""), o.optString("body", ""))
            )
        }
        val selected = systemJson.optString(KEY_SP_SELECTED, "")

        val roleplayJson = root.getJSONObject(KEY_ROLEPLAY)
        val roleplay = LinkedHashMap<String, List<Map<String, Any?>>>()
        for (table in CompanionBackupFormat.ROLEPLAY_TABLES) {
            val rowsJson = roleplayJson.optJSONArray(table) ?: JSONArray()
            val rows = ArrayList<Map<String, Any?>>(rowsJson.length())
            for (i in 0 until rowsJson.length()) {
                val o = rowsJson.getJSONObject(i)
                val row = LinkedHashMap<String, Any?>()
                for (key in o.keys()) {
                    row[key] = when (val value = o.get(key)) {
                        JSONObject.NULL -> null
                        is Boolean -> value
                        is Int -> value.toLong()
                        is Long -> value
                        is Double -> value
                        is String -> value
                        // Nested structures are never written by this format;
                        // finding one means the file is not sound.
                        else -> throw IllegalArgumentException(
                            "unsupported value type in $table.$key"
                        )
                    }
                }
                rows.add(row)
            }
            roleplay[table] = rows
        }

        val imagesJson = root.getJSONArray(KEY_IMAGES)
        val images = ArrayList<CompanionBackupImage>(imagesJson.length())
        for (i in 0 until imagesJson.length()) {
            val o = imagesJson.getJSONObject(i)
            val hash = o.getString("hash")
            val file = o.getString("file")
            require(IMAGE_HASH_REGEX.matches(hash)) { "invalid image hash" }
            require(file.startsWith(CompanionBackupFormat.IMAGES_DIR)) { "invalid image path" }
            images.add(CompanionBackupImage(hash, file))
        }

        return CompanionBackupManifest(
            formatVersion = versionInt,
            appVersion = root.optString(KEY_APP_VERSION, ""),
            exportedAt = root.optString(KEY_EXPORTED_AT, ""),
            companionProfiles = profiles,
            activationPrompts = activation,
            systemPrompts = systemPrompts,
            selectedSystemPromptId = selected,
            roleplayTables = roleplay,
            images = images
        )
    }

    private fun stringList(array: JSONArray): List<String> {
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) out.add(array.getString(i))
        return out
    }
}
