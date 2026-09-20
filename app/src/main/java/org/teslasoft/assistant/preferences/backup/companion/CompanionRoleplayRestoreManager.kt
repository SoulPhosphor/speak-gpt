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
import org.teslasoft.assistant.preferences.lorebook.LoreBookStore
import org.teslasoft.assistant.preferences.memory.MemoryLog
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.profileimages.ProfileImageStore
import java.io.File
import java.util.UUID

/**
 * The restore half of the Companion & Roleplay Backup
 * (companion-roleplay-backup-plan.md §6): the all-or-nothing staged apply.
 *
 * Order and protection (§6.3), plus the crash journal:
 *  1. Images first — additive; hash-named files never overwrite different
 *     content. What this restore added is tracked for rollback.
 *  2. The journal is written durably: token, the captured settings snapshot,
 *     the backup's settings, the added images. From this point an
 *     interruption — including process death or power loss — is settled by
 *     [resumeIfPending] at the next app start.
 *  3. One database transaction: replace the §2.4 record sets, apply the §6.4
 *     resolution rules, write the token into meta, and (still inside the
 *     transaction window) write the staged app settings. The token becomes
 *     durable exactly when the transaction commits, so it is the pivot:
 *     recovery rolls FORWARD (re-applies the backup's settings) only when
 *     the token is found in the store, and rolls BACK otherwise.
 *  4. Any runtime failure: the transaction rolls back automatically, the
 *     captured settings are written back, added image files are removed
 *     (best-effort — a leftover hash-named file is harmless), and a store
 *     file this restore itself created is deleted again.
 *
 * Memories, lorebooks and lore entries are never written, deleted or
 * modified by this restore; lorebook ids are resolved purely to decide which
 * companion links survive (§6.4).
 */
object CompanionRoleplayRestoreManager {

    enum class FailReason {
        /** The companion memory database is unavailable (confirmed damage,
         *  pending repair) — nothing was or will be touched. */
        MEMORY_UNAVAILABLE,

        /** Lorebook links must be resolved but the lorebook database is
         *  unavailable — removing them blind would strip links whose books
         *  actually exist, so the restore refuses instead. */
        LOREBOOK_UNAVAILABLE,

        /** An image file could not be written into the profile image store. */
        IMAGES_WRITE_FAILED,

        /** The crash-safety journal could not be written; without it an
         *  interruption could not be settled, so nothing was started. */
        JOURNAL_WRITE_FAILED,

        /** The database replace failed and was rolled back. */
        DATABASE_WRITE_FAILED,

        /** The staged settings write failed; everything was rolled back. */
        SETTINGS_WRITE_FAILED
    }

    sealed class RestoreResult {
        data class Success(val removedLinks: List<RemovedLorebookLink>) : RestoreResult()
        data class Failed(val reason: FailReason) : RestoreResult()
    }

    private const val LOG_TAG = "CompanionRestore"

    /**
     * §6.2: whether any companion or roleplay data covered by this backup
     * already exists on the device (drives the replace-confirmation dialog).
     */
    fun hasExistingData(context: Context): Boolean {
        val appContext = context.applicationContext
        if (PersonaPreferences.getPersonaPreferences(appContext).getPersonasList().isNotEmpty()) {
            return true
        }
        if (ActivationPromptPreferences.getActivationPromptPreferences(appContext)
                .getActivationPromptsList().isNotEmpty()
        ) {
            return true
        }
        if (SystemPromptsPreferences.getSystemPromptsPreferences(appContext)
                .getSystemPrompts().isNotEmpty()
        ) {
            return true
        }
        if (MemoryStore.isProvisioned(appContext) &&
            !DatabaseHealthState.isDegraded(appContext, BackupType.MEMORY)
        ) {
            try {
                if (MemoryStore.getInstance(appContext).hasAnyRoleplayStructure()) return true
            } catch (_: Exception) { /* treated as no confirmable data */ }
        }
        return false
    }

    /**
     * Checked before the §6.2 confirmation so the user is never asked to
     * confirm a replace that is already known to be impossible. Returns null
     * when the restore can proceed. Touches nothing.
     */
    fun preflightFailure(context: Context, manifest: CompanionBackupManifest): FailReason? {
        val appContext = context.applicationContext
        val dbInvolved = MemoryStore.isProvisioned(appContext) || manifest.hasRoleplayRecords()
        if (dbInvolved && DatabaseHealthState.isDegraded(appContext, BackupType.MEMORY)) {
            return FailReason.MEMORY_UNAVAILABLE
        }
        if (manifestReferencesLorebooks(manifest) &&
            LoreBookStore.isProvisioned(appContext) &&
            DatabaseHealthState.isDegraded(appContext, BackupType.LOREBOOK)
        ) {
            return FailReason.LOREBOOK_UNAVAILABLE
        }
        return null
    }

    /**
     * The §6.3 staged apply. [archiveFile] is the already-validated backup
     * (the staging copy [CompanionBackupValidator.validate] accepted).
     */
    fun restore(
        context: Context,
        manifest: CompanionBackupManifest,
        archiveFile: File
    ): RestoreResult {
        val appContext = context.applicationContext

        preflightFailure(appContext, manifest)?.let { return RestoreResult.Failed(it) }

        val existingLorebookIds = loadLorebookIds(appContext)
            ?: return if (manifestReferencesLorebooks(manifest)) {
                RestoreResult.Failed(FailReason.LOREBOOK_UNAVAILABLE)
            } else {
                proceed(appContext, manifest, archiveFile, emptySet())
            }
        return proceed(appContext, manifest, archiveFile, existingLorebookIds)
    }

    private fun proceed(
        appContext: Context,
        manifest: CompanionBackupManifest,
        archiveFile: File,
        existingLorebookIds: Set<String>
    ): RestoreResult {
        val plan = CompanionRestorePlanner.plan(manifest, existingLorebookIds)
        val settingsOld = CompanionSettingsApplier.snapshot(appContext)

        // ---- 1. Images first (additive) ----
        val imageStore = ProfileImageStore.getInstance(appContext)
        val addedFiles = ArrayList<String>()
        val addedCatalog = ArrayList<String>()
        try {
            for (image in manifest.images) {
                val bytes = CompanionBackupValidator.readImageBytes(archiveFile, image)
                val imported = imageStore.importEncodedImage(bytes)
                    ?: throw IllegalStateException("image write failed for ${image.hash}")
                if (imported.fileWasNew) addedFiles.add(imported.hash)
                if (imported.catalogWasNew) addedCatalog.add(imported.hash)
            }
        } catch (e: Exception) {
            rollbackImages(imageStore, addedFiles, addedCatalog)
            logError(appContext, "image copy failed (${e.javaClass.simpleName})")
            return RestoreResult.Failed(FailReason.IMAGES_WRITE_FAILED)
        }

        val dbInvolved = MemoryStore.isProvisioned(appContext) || manifest.hasRoleplayRecords()
        val provisionedByRestore = dbInvolved && !MemoryStore.isProvisioned(appContext)
        val token = "crb-" + UUID.randomUUID()

        // ---- 2. The crash journal, durable before the transaction ----
        val journalWritten = CompanionRestoreJournal.write(
            appContext,
            CompanionRestoreJournal.Entry(
                token = token,
                dbInvolved = dbInvolved,
                provisionedByRestore = provisionedByRestore,
                addedImageFiles = addedFiles,
                addedCatalogRows = addedCatalog,
                settingsOld = settingsOld,
                settingsNew = plan.settingsNew
            )
        )
        if (!journalWritten) {
            rollbackImages(imageStore, addedFiles, addedCatalog)
            logError(appContext, "crash journal could not be written; restore not started")
            return RestoreResult.Failed(FailReason.JOURNAL_WRITE_FAILED)
        }

        // ---- 3. Database transaction + staged settings ----
        try {
            if (dbInvolved) {
                val store = MemoryStore.getInstance(appContext)
                store.replaceRoleplayTables(manifest.roleplayTables, token) {
                    CompanionSettingsApplier.apply(appContext, plan.settingsNew)
                }
                store.deleteMeta(MemoryStore.META_COMPANION_RESTORE_TOKEN)
            } else {
                CompanionSettingsApplier.apply(appContext, plan.settingsNew)
            }
        } catch (e: Exception) {
            // ---- 4. Rollback (§6.3 step 4): transaction already rolled back
            // automatically; write the captured settings back, remove what the
            // image step added, and delete a store file this restore created.
            try {
                CompanionSettingsApplier.apply(appContext, settingsOld)
            } catch (_: Exception) {
                // The journal still holds the snapshot; startup recovery
                // finishes the write-back if this attempt could not.
                logError(appContext, "settings write-back failed; startup recovery will finish it")
                return RestoreResult.Failed(classifyApplyFailure(e))
            }
            rollbackImages(imageStore, addedFiles, addedCatalog)
            if (provisionedByRestore && MemoryStore.isProvisioned(appContext)) {
                MemoryStore.invalidateInstance()
                appContext.deleteDatabase(MemoryStore.DATABASE_NAME)
            }
            CompanionRestoreJournal.clear(appContext)
            logError(appContext, "restore failed and was rolled back (${e.javaClass.simpleName})")
            return RestoreResult.Failed(classifyApplyFailure(e))
        }

        // ---- 5. Done: settle the journal, refresh cached stores ----
        CompanionRestoreJournal.clear(appContext)
        MemoryStore.invalidateInstance()
        return RestoreResult.Success(plan.removedLinks)
    }

    /**
     * Startup recovery (MainApplication): settles a restore interrupted by
     * process death or power loss. Roll FORWARD — re-apply the backup's
     * settings — only when the journal's token is present in the store's meta
     * table (the database transaction provably committed); roll BACK to the
     * captured snapshot otherwise. Either way the device ends fully restored
     * or fully untouched (§4). When the store cannot be opened (degraded,
     * pending repair) the journal is left in place and retried next start.
     */
    fun resumeIfPending(context: Context) {
        val appContext = context.applicationContext
        if (!CompanionRestoreJournal.hasToken(appContext)) return
        val entry = CompanionRestoreJournal.read(appContext)
        if (entry == null) {
            // Undecodable journal: written by this codec, so this is a defect,
            // not user data. Nothing can be settled from it — say so loudly
            // and clear it rather than blocking every future restore.
            MemoryLog.logAlways(
                appContext, LOG_TAG, "error",
                "an interrupted restore's journal could not be read; it was cleared"
            )
            CompanionRestoreJournal.clear(appContext)
            return
        }

        val rollForward: Boolean
        if (entry.dbInvolved) {
            if (!MemoryStore.isProvisioned(appContext)) {
                rollForward = false
            } else {
                if (DatabaseHealthState.isDegraded(appContext, BackupType.MEMORY)) {
                    MemoryLog.logAlways(
                        appContext, LOG_TAG, "warn",
                        "interrupted-restore recovery postponed: memory database pending repair"
                    )
                    return
                }
                val tokenInStore = try {
                    MemoryStore.getInstance(appContext)
                        .getMeta(MemoryStore.META_COMPANION_RESTORE_TOKEN)
                } catch (e: Exception) {
                    MemoryLog.logAlways(
                        appContext, LOG_TAG, "warn",
                        "interrupted-restore recovery postponed: store unreadable (${e.javaClass.simpleName})"
                    )
                    return
                }
                rollForward = CompanionRestorePlanner.shouldRollForward(
                    dbInvolved = true, tokenInStore = tokenInStore, journalToken = entry.token
                )
            }
        } else {
            rollForward = false
        }

        try {
            if (rollForward) {
                CompanionSettingsApplier.apply(appContext, entry.settingsNew)
                try {
                    MemoryStore.getInstance(appContext)
                        .deleteMeta(MemoryStore.META_COMPANION_RESTORE_TOKEN)
                } catch (_: Exception) { /* stale token is compared by value; harmless */ }
                CompanionRestoreJournal.clear(appContext)
                MemoryStore.invalidateInstance()
                MemoryLog.logAlways(
                    appContext, LOG_TAG, "warn",
                    "finished an interrupted companion/roleplay restore at startup (rolled forward)"
                )
            } else {
                CompanionSettingsApplier.apply(appContext, entry.settingsOld)
                rollbackImages(
                    ProfileImageStore.getInstance(appContext),
                    entry.addedImageFiles, entry.addedCatalogRows
                )
                if (entry.provisionedByRestore && MemoryStore.isProvisioned(appContext)) {
                    MemoryStore.invalidateInstance()
                    appContext.deleteDatabase(MemoryStore.DATABASE_NAME)
                }
                CompanionRestoreJournal.clear(appContext)
                MemoryLog.logAlways(
                    appContext, LOG_TAG, "warn",
                    "reverted an interrupted companion/roleplay restore at startup (rolled back)"
                )
            }
        } catch (e: Exception) {
            // Journal deliberately left in place: recovery retries next start.
            MemoryLog.logAlways(
                appContext, LOG_TAG, "error",
                "interrupted-restore recovery failed (${e.javaClass.simpleName}); will retry next start"
            )
        }
    }

    /* ------------------------------ helpers ------------------------------ */

    private fun manifestReferencesLorebooks(manifest: CompanionBackupManifest): Boolean =
        manifest.companionProfiles.any {
            it.coreLoreBookId.isNotBlank() || it.additionalLoreBookIds.isNotEmpty()
        }

    /** The lorebook ids on this device, or null when the store exists but
     *  cannot be read. No lorebook database at all = no lorebooks (empty). */
    private fun loadLorebookIds(context: Context): Set<String>? {
        if (!LoreBookStore.isProvisioned(context)) return emptySet()
        if (DatabaseHealthState.isDegraded(context, BackupType.LOREBOOK)) return null
        return try {
            LoreBookStore.getInstance(context).getAllBooks().map { it.id }.toSet()
        } catch (_: Exception) {
            null
        }
    }

    private fun rollbackImages(
        store: ProfileImageStore,
        addedFiles: List<String>,
        addedCatalog: List<String>
    ) {
        val catalogSet = addedCatalog.toSet()
        val fileSet = addedFiles.toSet()
        for (hash in fileSet + catalogSet) {
            try {
                store.removeRestoredImage(
                    hash,
                    removeFile = hash in fileSet,
                    removeCatalogRow = hash in catalogSet
                )
            } catch (_: Exception) { /* best-effort; orphans are harmless */ }
        }
    }

    private fun classifyApplyFailure(e: Exception): FailReason =
        if (e is CompanionSettingsWriteException) FailReason.SETTINGS_WRITE_FAILED
        else FailReason.DATABASE_WRITE_FAILED

    private fun logError(context: Context, message: String) {
        try {
            MemoryLog.logAlways(context, LOG_TAG, "error", message)
        } catch (_: Exception) { /* logging is best-effort */ }
    }
}
