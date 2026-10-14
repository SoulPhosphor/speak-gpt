package org.teslasoft.assistant.preferences.backup.readable

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.ActivationPromptPreferences
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.SystemPromptsPreferences
import org.teslasoft.assistant.preferences.backup.BackupType
import org.teslasoft.assistant.preferences.backup.DatabaseHealthState
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupCodec
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupExporter
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupFormat
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupManifest
import org.teslasoft.assistant.preferences.backup.portable.ProfileImagePortableBackup
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.profileimages.ProfileImageFileNaming
import org.teslasoft.assistant.preferences.profileimages.ProfileImageStore

/** Reads only selected identity collections and carries only their assigned pictures. */
internal object ReadableIdentityBackup {
    fun collect(
        context: Context,
        staging: File,
        categories: Set<ReadableBackupCategory>,
        sources: MutableList<Pair<String, File>>
    ) {
        fun json(name: String, value: String) {
            val file = File(staging, name)
            file.writeText(value, Charsets.UTF_8)
            sources.add(name to file)
        }
        if (ReadableBackupCategory.ACTIVATION_PROMPTS in categories) {
            val entries = ActivationPromptPreferences.getActivationPromptPreferences(context)
                .getActivationPromptsList().sortedBy { it.id }
            json("activation_prompts.json", JSONArray().apply {
                entries.forEach { put(JSONObject().put("id", it.id).put("label", it.label).put("prompt", it.prompt)) }
            }.toString(2))
        }
        if (ReadableBackupCategory.SYSTEM_PROMPTS in categories) {
            val prefs = SystemPromptsPreferences.getSystemPromptsPreferences(context)
            json("system_prompts.json", JSONObject().put("selected_id", prefs.getSelectedId())
                .put("entries", JSONArray().apply {
                    prefs.getSystemPrompts().forEach {
                        put(JSONObject().put("id", it.id).put("title", it.title).put("body", it.body))
                    }
                }).toString(2))
        }
        val tableCategories = listOf(ReadableBackupCategory.COMPANIONS, ReadableBackupCategory.GLAMOURS,
            ReadableBackupCategory.ROLEPLAY).filter { it in categories }
        if (tableCategories.isEmpty()) return
        val tables = if (MemoryStore.isProvisioned(context)) {
            check(!DatabaseHealthState.isDegraded(context, BackupType.MEMORY))
            MemoryStore.getInstance(context).exportRoleplayTables()
        } else emptyMap()
        for (category in tableCategories) {
            val selectedTables = ReadableIdentitySelection.tables(tables, category)
            val data = JSONObject().put("tables", JSONObject(selectedTables))
            val hashes = LinkedHashSet<String>()
            if (category == ReadableBackupCategory.COMPANIONS) {
                val personas = PersonaPreferences.getPersonaPreferences(context).getPersonasList().sortedBy { it.id }
                val names = checkNotNull(CompanionBackupExporter.captureLorebookNames(context, personas))
                val profiles = CompanionBackupExporter.profileEntries(personas, names)
                val serialized = JSONObject(CompanionBackupCodec.toJson(CompanionBackupManifest(
                    formatVersion = CompanionBackupFormat.FORMAT_VERSION,
                    appVersion = "", exportedAt = "", companionProfiles = profiles,
                    activationPrompts = emptyList(), systemPrompts = emptyList(),
                    selectedSystemPromptId = "", roleplayTables = emptyMap(), images = emptyList()
                )))
                data.put("profiles", serialized.getJSONArray("companion_profiles"))
                profiles.map { it.avatarRef }.filter(String::isNotBlank).forEach(hashes::add)
            }
            for (table in listOf("user_personas", "roleplay_characters")) {
                selectedTables[table].orEmpty().forEach { row ->
                    (row["image_ref"] as? String)?.takeIf(String::isNotBlank)?.let(hashes::add)
                }
            }
            val directory = category.name.lowercase()
            json("$directory.json", data.toString(2))
            if (hashes.isNotEmpty()) {
                val imageStore = ProfileImageStore.getInstance(context.applicationContext)
                for (hash in hashes) {
                    val image = checkNotNull(imageStore.imageFile(hash))
                    check(ProfileImagePortableBackup.isValidAsset(image, hash))
                    sources.add("$directory/assets/${ProfileImageFileNaming.permanentFileName(hash)}" to image)
                }
            }
        }
    }
}
