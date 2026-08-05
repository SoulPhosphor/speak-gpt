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

import android.content.Context
import org.teslasoft.assistant.preferences.ActivationPromptPreferences
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.SystemPromptsPreferences
import org.teslasoft.assistant.preferences.backup.BackupType
import org.teslasoft.assistant.preferences.backup.DatabaseHealthState
import org.teslasoft.assistant.preferences.dto.PersonaObject
import org.teslasoft.assistant.preferences.lorebook.LoreBookStore
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.profileimages.ProfileImageStore
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Export half of the Companion & Roleplay Backup
 * (companion-roleplay-backup-plan.md §5): collects every §2 record set,
 * builds the `backup.json` manifest, and writes the ZIP — manifest plus the
 * referenced `profile_<hash>.jpg` files — into a caller-provided staging
 * file. The caller copies the staged archive to the user-chosen destination
 * and verifies it there.
 *
 * Lorebook CONTENT is never read; only the linked books' names are captured,
 * at this moment, purely so a later restore report can name a missing
 * lorebook. A referenced image whose file is missing on this device is
 * simply absent from the archive — the reference itself is still carried.
 */
object CompanionBackupExporter {

    sealed class BuildResult {
        data class Ok(val manifest: CompanionBackupManifest) : BuildResult()

        /** The companion memory database exists but is unavailable (confirmed
         *  damage, pending repair) — exporting would silently drop the user's
         *  roleplay structure, so the export refuses instead. */
        object MemoryUnavailable : BuildResult()

        /** Lorebook links exist but the lorebook database cannot be read, so
         *  their names cannot be captured. Names are load-bearing: the restore
         *  report names removed connections and NEVER shows an internal id
         *  (owner ruling, August 5 2026), so exporting nameless links — or
         *  silently dropping links whose books actually exist — is not
         *  allowed. The export refuses instead. */
        object LorebookUnavailable : BuildResult()
    }

    /**
     * Collects all §2 data and writes the complete ZIP to [staged]
     * (overwritten if present). Throws on I/O failure — the caller surfaces
     * the approved save-failure dialog.
     */
    fun buildBackupZip(context: Context, staged: File): BuildResult {
        val appContext = context.applicationContext

        val personas = PersonaPreferences.getPersonaPreferences(appContext)
            .getPersonasList().sortedBy { it.id }
        val activationPrompts = ActivationPromptPreferences
            .getActivationPromptPreferences(appContext)
            .getActivationPromptsList().sortedBy { it.id }
        val systemPromptsPrefs = SystemPromptsPreferences.getSystemPromptsPreferences(appContext)
        val systemPrompts = systemPromptsPrefs.getSystemPrompts()
        val selectedSystemPromptId = systemPromptsPrefs.getSelectedId()

        val roleplayTables: Map<String, List<Map<String, Any?>>> =
            if (MemoryStore.isProvisioned(appContext)) {
                if (DatabaseHealthState.isDegraded(appContext, BackupType.MEMORY)) {
                    return BuildResult.MemoryUnavailable
                }
                MemoryStore.getInstance(appContext).exportRoleplayTables()
            } else {
                // Memory store never opted into: the roleplay section exports
                // empty and the rest of the backup still works (§3).
                CompanionBackupFormat.ROLEPLAY_TABLES.associateWith { emptyList() }
            }

        val lorebookNames = captureLorebookNames(appContext, personas)
            ?: return BuildResult.LorebookUnavailable

        // Every lorebook link carried by the backup carries its name (the
        // restore report names removed connections — never an internal id).
        // A link whose book no longer exists on THIS device has no name and
        // nothing to reconnect, so it is not carried.
        val profiles = personas.map { p ->
            val coreKept = p.coreLoreBookId.isNotBlank() &&
                lorebookNames.containsKey(p.coreLoreBookId)
            val additionalKept = p.additionalLoreBookIdList()
                .filter { lorebookNames.containsKey(it) }
            CompanionProfileEntry(
                id = p.id,
                label = p.label,
                prompt = p.prompt,
                activationPromptId = p.activationPromptId,
                coreLoreBookId = if (coreKept) p.coreLoreBookId else "",
                coreLoreBookName = if (coreKept) lorebookNames[p.coreLoreBookId] else null,
                additionalLoreBookIds = additionalKept,
                additionalLoreBookNames = additionalKept.associateWith { lorebookNames.getValue(it) },
                autoLoadLastLoreBooks = p.autoLoadLastLoreBooks,
                lastUsedLoreBookIds = p.lastUsedLoreBookIdList(),
                avatarRef = p.avatarRef
            )
        }

        val imageStore = ProfileImageStore.getInstance(appContext)
        val referencedHashes = collectImageHashes(profiles, roleplayTables)
        val imageFiles = LinkedHashMap<String, File>()
        for (hash in referencedHashes) {
            val file = imageStore.imageFile(hash) ?: continue // missing: carried as reference only
            imageFiles[hash] = file
        }

        val manifest = CompanionBackupManifest(
            formatVersion = CompanionBackupFormat.FORMAT_VERSION,
            appVersion = appVersion(appContext),
            exportedAt = MemoryStore.nowIso(),
            companionProfiles = profiles,
            activationPrompts = activationPrompts.map {
                ActivationPromptEntry(it.id, it.label, it.prompt)
            },
            systemPrompts = systemPrompts.map { SystemPromptEntry(it.id, it.title, it.body) },
            selectedSystemPromptId = selectedSystemPromptId,
            roleplayTables = roleplayTables,
            images = imageFiles.keys.map {
                CompanionBackupImage(it, CompanionBackupFormat.imageEntryName(it))
            }
        )

        writeZip(staged, manifest, imageFiles)
        return BuildResult.Ok(manifest)
    }

    /**
     * Reads the linked lorebooks' names for the manifest. Returns null when
     * the lorebook database exists but cannot be read — the export must
     * refuse rather than carry nameless links or drop links whose books are
     * actually present. When no lorebook database exists at all, no books
     * exist: every link is already dead on this device and an empty map is
     * the truthful answer.
     */
    private fun captureLorebookNames(
        context: Context,
        personas: List<PersonaObject>
    ): Map<String, String>? {
        val referenced = LinkedHashSet<String>()
        for (p in personas) {
            if (p.coreLoreBookId.isNotBlank()) referenced.add(p.coreLoreBookId)
            referenced.addAll(p.additionalLoreBookIdList())
        }
        if (referenced.isEmpty()) return emptyMap()
        if (!LoreBookStore.isProvisioned(context)) return emptyMap()
        if (DatabaseHealthState.isDegraded(context, BackupType.LOREBOOK)) return null
        return try {
            val store = LoreBookStore.getInstance(context)
            val names = HashMap<String, String>()
            for (id in referenced) {
                store.getBook(id)?.name?.let { names[id] = it }
            }
            names
        } catch (_: Exception) {
            null
        }
    }

    /** Every Profile Images hash referenced by an included record (§2.5). */
    private fun collectImageHashes(
        profiles: List<CompanionProfileEntry>,
        roleplayTables: Map<String, List<Map<String, Any?>>>
    ): LinkedHashSet<String> {
        val hashes = LinkedHashSet<String>()
        for (p in profiles) {
            if (p.avatarRef.isNotBlank()) hashes.add(p.avatarRef)
        }
        for (table in listOf("user_personas", "roleplay_characters")) {
            for (row in roleplayTables[table].orEmpty()) {
                val ref = row["image_ref"] as? String ?: continue
                if (ref.isNotBlank()) hashes.add(ref)
            }
        }
        return hashes
    }

    private fun writeZip(
        staged: File,
        manifest: CompanionBackupManifest,
        imageFiles: Map<String, File>
    ) {
        ZipOutputStream(staged.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(CompanionBackupFormat.MANIFEST_ENTRY))
            zip.write(CompanionBackupCodec.toJson(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for ((hash, file) in imageFiles) {
                zip.putNextEntry(ZipEntry(CompanionBackupFormat.imageEntryName(hash)))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }
}
