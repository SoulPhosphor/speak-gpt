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

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.preferences.backup.BackupType
import org.teslasoft.assistant.preferences.backup.DatabaseHealthState
import org.teslasoft.assistant.preferences.backup.RecoveryBackupManager
import org.teslasoft.assistant.preferences.backup.RecoveryBackupReceipt
import org.teslasoft.assistant.preferences.backup.RecoveryOperationGate
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupExporter
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupFormat
import org.teslasoft.assistant.preferences.chatstore.ChatSources
import org.teslasoft.assistant.preferences.chatstore.ChatStorageAuthority
import org.teslasoft.assistant.preferences.lorebook.LoreBookEncryption
import org.teslasoft.assistant.preferences.memory.DatabaseKeys
import org.teslasoft.assistant.preferences.memory.MemoryLog
import org.teslasoft.assistant.preferences.memory.MemoryStore
import java.io.File
import java.time.Instant

/**
 * Creates portable recovery packages (format v2) from the live stores —
 * the cleared implementation scope of the owner's July 22 2026 rulings.
 * Restoration into live data is NOT here (separate owner walkthrough).
 *
 * Artifact strategy (approved architecture):
 *  - Memory / lorebook DBs: the SQLCipher CIPHERTEXT snapshot (the same
 *    test-first VACUUM INTO path the v1 writer uses) plus the database key
 *    inside the package — the two most sensitive stores never exist as
 *    plaintext on disk during an encrypted backup. Keys are ordinary
 *    exportable bytes; only their STORAGE is Keystore-bound.
 *  - Avatar/Profile Images: row-level rebuilt plain SQLite catalog plus every
 *    valid content-addressed gallery JPEG, including unused images. Identity
 *    assignments live with their owning records and are not changed here.
 *  - Chats: logical serialization ([ChatLogicalSerializer]) — the raw
 *    enc.*.xml files can never be portable. LOCKED storage fails the run
 *    visibly.
 *  - Companions and roleplay: the existing validated logical archive, which
 *    also contains Activation Prompts, System Prompts, Glamours, Roleplay
 *    Characters, related roleplay records, and referenced profile images.
 *  - Model & Endpoint Settings: credential-free endpoint definitions,
 *    favorite-model parameters, provider preferences and routing choices.
 *
 * NOTE for the unencrypted tier: the SAME inner layout is used, so the
 * database keys are exposed in cleartext inside the file. That is within the
 * tier's owner-approved threat model ("anyone who obtains the file may be
 * able to access its contents") and was reported to the owner explicitly.
 * API keys are excluded on BOTH tiers (ruling 9).
 *
 * Every run: build in per-run staging -> envelope -> REOPEN the finished file
 * and fully verify it (inspect + decode + validate, using the same reader the
 * restore path uses) before reporting success — a package that cannot be
 * read back is a failure, not a backup. Staging is deleted in finally on
 * every path.
 *
 * ⚠️ RUNTIME NOT DEVICE-VERIFIED: compiles under CI; the on-device test gate
 * (owner ruling 3/11) decides when any of this counts as working.
 */
object PortableRecoveryWriter {

    sealed class Result {
        /** [includedTypes] = which of the four backup types have an artifact
         *  inside this package — the caller records per-type Backup Status
         *  from it, and ONLY after the destination is also verified. */
        data class Ok(
            val chatCount: Int,
            val artifactCount: Int,
            val includedTypes: Set<BackupType>,
            val generatedImageCount: Int = 0,
            val generatedImageBytes: Long = 0L,
            val profileImageCount: Int = 0,
            val profileImageBytes: Long = 0L,
            /** Which chat storage this package was captured from; the caller
             *  records it in [RecoveryBackupReceipt] once the package is saved. */
            val chatSource: RecoveryBackupReceipt.Source? = null
        ) : Result()

        /** [chatFailure] refines CHATS_UNAVAILABLE with WHICH part of chat
         *  storage failed, for the plain-words detail line. */
        data class Failed(
            val reason: Reason,
            val chatFailure: ChatLogicalSerializer.FailureCategory? = null,
            /** For NOT_ENOUGH_STORAGE: how much more free space is needed. */
            val shortfallBytes: Long? = null
        ) : Result()
    }

    enum class Reason {
        /** Chat storage LOCKED/non-authoritative — visible, typed (ruling 8). */
        CHATS_UNAVAILABLE,

        /** The Keystore-held key material could not be read — visible, never
         *  a silent stop, never an unencrypted fallback (ruling 10). */
        KEY_MATERIAL_UNAVAILABLE,

        /** A database snapshot failed to produce or verify. */
        SNAPSHOT_FAILED,

        /** The finished package failed its reopen-and-verify. */
        PACKAGE_VERIFY_FAILED,

        /** Nothing exists to back up (no store provisioned, no chats). */
        NOTHING_TO_BACK_UP,

        /** A database is disabled with CONFIRMED damage (Build Phase 3
         *  degraded flag). A recovery package must never capture a corrupt
         *  database as if it were a good copy — repair first (A1's
         *  "unavailable to use or save"). Visible, typed, never silent. */
        STORE_DEGRADED,

        /** A profile picture assigned to an included identity is missing or no
         *  longer matches its stored hash. Publishing an archive that omits an
         *  assigned picture would be a falsely-complete recovery backup
         *  (BR-07), so the write refuses and the previous automatic backup is
         *  kept. Visible, typed, never silent. */
        PROFILE_IMAGE_UNAVAILABLE,

        /** Live authoritative state changed during both bounded capture
         * attempts. No package is published from mixed generations. */
        SOURCE_CHANGED_DURING_CAPTURE,

        /** The device does not have the free space creating the package
         *  needs ([Result.Failed.shortfallBytes] says how much more). Checked
         *  before anything is written. */
        NOT_ENOUGH_STORAGE,

        /** The app ran out of memory while reading a conversation. Until
         *  chats move to the database each one is read whole. */
        OUT_OF_MEMORY
    }

    /**
     * Build a portable package into [out].
     *
     * @param recoverySecret the Recovery Secret for the encrypted tier, or
     *        null for the deliberate unencrypted tier. Callers obtain it from
     *        [RecoveryKeyStore] (automatic path) or setup flow (manual) and
     *        must surface [RecoveryKeyStore.SecretResult.Unavailable] as
     *        KEY_MATERIAL_UNAVAILABLE — never fall back to unencrypted.
     * @param passwordBlob the pre-computed static password blob, if a
     *        password is set (byte-copied; the KDF never runs here).
     */
    fun createPackage(
        context: Context,
        out: File,
        recoverySecret: ByteArray?,
        passwordBlob: PortablePackageFormat.PasswordBlob?,
        appVersion: String
    ): Result = RecoveryOperationGate.runExclusive {
        for (attempt in 1..PortableBackupCaptureStability.MAX_ATTEMPTS) {
            val before = PortableBackupMutationTokens.read(context)
                ?: return@runExclusive failed(context, Reason.SNAPSHOT_FAILED, "reading the change markers before capture")
            runCatching { if (out.exists()) out.delete() }
            val result = createPackageLocked(
                context, out, recoverySecret, passwordBlob, appVersion, before, attempt
            )
            if (result !is Result.Failed ||
                result.reason != Reason.SOURCE_CHANGED_DURING_CAPTURE ||
                attempt == PortableBackupCaptureStability.MAX_ATTEMPTS
            ) return@runExclusive result
        }
        failed(context, Reason.SOURCE_CHANGED_DURING_CAPTURE, "capture")
    }

    private fun createPackageLocked(
        context: Context,
        out: File,
        recoverySecret: ByteArray?,
        passwordBlob: PortablePackageFormat.PasswordBlob?,
        appVersion: String,
        beforeTokens: Map<String, String>,
        captureAttempt: Int
    ): Result {
        val staging = PortableStaging.newRunDir(context)
        try {
            // Degraded gate (Build Phase 3): a store with confirmed damage is
            // "unavailable to use or save" — snapshotting it here would seal a
            // corrupt copy inside a package the user trusts. Refused loudly.
            if (DatabaseHealthState.anyDegraded(context)) {
                return failed(context, Reason.STORE_DEGRADED, "database health check", "a database is marked damaged")
            }
            val space = PortableBackupSpaceEstimate.check(context, staging)
            if (space.shortfallBytes > 0L) {
                return failed(
                    context, Reason.NOT_ENOUGH_STORAGE, "free space check",
                    "needs ${space.requiredBytes} bytes, ${space.availableBytes} available",
                    shortfallBytes = space.shortfallBytes
                )
            }
            val createdAt = Instant.now().toString()
            val artifacts = ArrayList<PortablePackage.Artifact>()
            val includedTypes = LinkedHashSet<BackupType>()
            var generatedImageCount = 0
            var generatedImageBytes = 0L
            var profileImageCount = 0
            var profileImageBytes = 0L

            // ---- memory DB (ciphertext + key) ----
            if (MemoryStore.isProvisioned(context)) {
                val staged = File(staging, "memory.snapshot")
                val key = DatabaseKeys.getOrCreate(context, DatabaseKeys.KEY_MEMORY, databaseExists = true)
                    ?: return failed(context, Reason.KEY_MATERIAL_UNAVAILABLE, "memory database", "its key is unavailable")
                if (!RecoveryBackupManager.snapshotCipher(context, MemoryStore.DATABASE_NAME, staged) { key }) {
                    return failed(context, Reason.SNAPSHOT_FAILED, "memory database", "the snapshot could not be taken")
                }
                RecoveryBackupManager.integrityCheckCipher(staged, key)
                artifacts.add(
                    PortablePackage.Artifact(
                        entryName = "memory.db", type = "sqlcipher-db", file = staged,
                        databaseKeyHex = key.toHex(),
                        keySemantics = PortablePackage.KEY_SEMANTICS_PASSPHRASE,
                        schemaVersion = null
                    )
                )
                includedTypes.add(BackupType.MEMORY)
            }

            // ---- lorebook DB (ciphertext + key; may legitimately be plaintext
            //      if the one-time encryption migration has not succeeded) ----
            if (context.getDatabasePath("lorebook.db").exists()) {
                val staged = File(staging, "lorebook.snapshot")
                val key = try {
                    LoreBookEncryption.obtainPassword(context, "lorebook.db")
                } catch (e: Exception) {
                    return failed(context, Reason.KEY_MATERIAL_UNAVAILABLE, "lorebook database", "its key is unavailable (${e.javaClass.simpleName})")
                }
                if (!RecoveryBackupManager.snapshotCipher(context, "lorebook.db", staged, allowEmptyKey = true) { key }) {
                    return failed(context, Reason.SNAPSHOT_FAILED, "lorebook database", "the snapshot could not be taken")
                }
                if (key.isNotEmpty()) RecoveryBackupManager.integrityCheckCipher(staged, key)
                else RecoveryBackupManager.integrityCheckPlain(staged)
                artifacts.add(
                    PortablePackage.Artifact(
                        entryName = "lorebook.db", type = "sqlcipher-db", file = staged,
                        databaseKeyHex = if (key.isEmpty()) null else key.toHex(),
                        keySemantics = if (key.isEmpty()) PortablePackage.KEY_SEMANTICS_PLAINTEXT
                        else PortablePackage.KEY_SEMANTICS_PASSPHRASE,
                        schemaVersion = null
                    )
                )
                includedTypes.add(BackupType.LOREBOOK)
            }

            // ---- Avatar/Profile Images (plain SQLite catalog + complete
            //      gallery bytes, including images not assigned anywhere) ----
            run {
                val staged = File(staging, "user_images.snapshot")
                if (RecoveryBackupManager.snapshotUserImageCatalog(context, staged)) {
                    RecoveryBackupManager.integrityCheckPlain(staged)
                    val profileImages = ProfileImagePortableBackup.buildArtifacts(context, staged)
                    if (profileImages is ProfileImagePortableBackup.Result.Failed) {
                        return failed(context, Reason.SNAPSHOT_FAILED, "profile images", profileImages.toString())
                    }
                    profileImages as ProfileImagePortableBackup.Result.Ok
                    artifacts.add(
                        PortablePackage.Artifact(
                            entryName = "user_images.db", type = "sqlite-db", file = staged,
                            databaseKeyHex = null, keySemantics = null, schemaVersion = null
                        )
                    )
                    artifacts.addAll(profileImages.artifacts)
                    profileImageCount = profileImages.inventory.imageCount
                    profileImageBytes = profileImages.inventory.imageBytes
                    includedTypes.add(BackupType.USER_IMAGE)
                }
            }

            // ---- generated images (portable logical catalog + every active
            //      gallery byte, including Gallery-only images) ----
            when (val generated = GeneratedImagePortableBackup.buildArtifacts(context, staging)) {
                is GeneratedImagePortableBackup.Result.NothingToBackUp -> Unit
                is GeneratedImagePortableBackup.Result.Failed ->
                    return failed(context, Reason.SNAPSHOT_FAILED, "generated images", generated.toString())
                is GeneratedImagePortableBackup.Result.Ok -> {
                    artifacts.addAll(generated.artifacts)
                    generatedImageCount = generated.inventory.imageCount
                    generatedImageBytes = generated.inventory.imageBytes
                }
            }

            // ---- companions, prompts and roleplay (logical archive) ----
            run {
                val staged = File(staging, "companion_roleplay.zip")
                when (CompanionBackupExporter.buildBackupZip(
                    context, staged, validateAssignedImages = true
                )) {
                    CompanionBackupExporter.BuildResult.MemoryUnavailable ->
                        return failed(context, Reason.SNAPSHOT_FAILED, "companions archive", "the memory database is unavailable")
                    CompanionBackupExporter.BuildResult.LorebookUnavailable ->
                        return failed(context, Reason.SNAPSHOT_FAILED, "companions archive", "the lorebook database is unavailable")
                    CompanionBackupExporter.BuildResult.ProfileImageUnavailable ->
                        return failed(context, Reason.PROFILE_IMAGE_UNAVAILABLE, "companions archive", "an assigned profile image is unavailable")
                    is CompanionBackupExporter.BuildResult.Ok -> artifacts.add(
                        PortablePackage.Artifact(
                            entryName = "companion_roleplay.zip",
                            type = PortablePackage.TYPE_COMPANION_ROLEPLAY_ARCHIVE,
                            file = staged,
                            databaseKeyHex = null,
                            keySemantics = null,
                            schemaVersion = CompanionBackupFormat.FORMAT_VERSION
                        )
                    )
                }
            }

            // ---- Model & Endpoint Settings (never credentials) ----
            run {
                val staged = File(staging, "model_endpoint_settings.json")
                val endpointResult = ModelEndpointPortableBackup.write(context, staged)
                if (endpointResult is ModelEndpointPortableBackup.Result.Failed) {
                    return failed(context, Reason.SNAPSHOT_FAILED, "model and endpoint settings", endpointResult.toString())
                }
                artifacts.add(
                    PortablePackage.Artifact(
                        entryName = "model_endpoint_settings.json",
                        type = PortablePackage.TYPE_MODEL_ENDPOINT_SETTINGS,
                        file = staged,
                        databaseKeyHex = null,
                        keySemantics = null,
                        schemaVersion = ModelEndpointPortableCodec.SCHEMA_VERSION
                    )
                )
            }

            // ---- Settings and Preferences (audited, credential-free) ----
            run {
                val staged = File(staging, AppSettingsPortableCodec.ENTRY_NAME)
                val settings = AppSettingsPortableStore.write(context, staged).getOrElse {
                    return failed(context, Reason.SNAPSHOT_FAILED, "app settings", it.javaClass.simpleName)
                }
                artifacts.add(
                    PortablePackage.Artifact(
                        entryName = AppSettingsPortableCodec.ENTRY_NAME,
                        type = PortablePackage.TYPE_APP_SETTINGS,
                        file = staged,
                        databaseKeyHex = null,
                        keySemantics = null,
                        schemaVersion = AppSettingsPortableCodec.SCHEMA_VERSION
                    )
                )
            }

            // ---- chats (written one message at a time from the authoritative
            //      storage; LOCKED or unreadable fails visibly) ----
            val chatStorage = ChatSources.authoritative(context)
                ?: return failed(
                    context, Reason.CHATS_UNAVAILABLE, "chats",
                    "chat storage authority is ${ChatStorageAuthority.current(context)}",
                    chatFailure = ChatLogicalSerializer.FailureCategory.LIST
                )
            val stagedChats = File(staging, "chats.json")
            val written = try {
                stagedChats.outputStream().buffered().writer(Charsets.UTF_8).use { out ->
                    ChatsArtifactWriter.write(context, chatStorage.source, out)
                }
            } catch (_: OutOfMemoryError) {
                runCatching { stagedChats.delete() }
                return failed(context, Reason.OUT_OF_MEMORY, "chats", "ran out of memory while writing the chats")
            }
            when (written) {
                is ChatsArtifactWriter.Result.Unavailable ->
                    return failed(
                        context, Reason.CHATS_UNAVAILABLE, "chats", "chat storage part ${written.category} could not be read",
                        chatFailure = written.category
                    )
                is ChatsArtifactWriter.Result.Ok -> {
                    artifacts.add(
                        PortablePackage.Artifact(
                            entryName = "chats.json", type = "chats-json", file = stagedChats,
                            databaseKeyHex = null, keySemantics = null, schemaVersion = 3
                        )
                    )
                    val summary = when (val scanned = stagedChats.bufferedReader(Charsets.UTF_8).use { ChatsArtifactScanner.scan(it) }) {
                        is ChatsArtifactScanner.Result.Ok -> scanned.summary
                        is ChatsArtifactScanner.Result.Rejected -> return failed(
                            context, Reason.PACKAGE_VERIFY_FAILED, "checking the written chats (${written.chatCount} chats)",
                            "${scanned.reason}: ${scanned.detail}"
                        )
                        is ChatsArtifactScanner.Result.Unreadable -> return failed(
                            context, Reason.PACKAGE_VERIFY_FAILED, "checking the written chats (${written.chatCount} chats)",
                            scanned.detail
                        )
                    }
                    when (val pdfs = PdfAttachmentPortableBackup.buildArtifacts(context, summary.pdfRefs)) {
                        is PdfAttachmentPortableBackup.Result.Ok -> artifacts.addAll(pdfs.artifacts)
                        is PdfAttachmentPortableBackup.Result.Failed ->
                            return failed(context, Reason.SNAPSHOT_FAILED, "PDF attachments", pdfs.detail)
                    }
                    includedTypes.add(BackupType.CHATS)
                    val afterTokens = PortableBackupMutationTokens.read(context)
                    when (PortableBackupCaptureStability.decide(beforeTokens, afterTokens, captureAttempt)) {
                        PortableBackupCaptureStability.Decision.STABLE -> Unit
                        PortableBackupCaptureStability.Decision.RETRY ->
                            // Retried by createPackage; only the last attempt is logged.
                            return Result.Failed(Reason.SOURCE_CHANGED_DURING_CAPTURE)
                        PortableBackupCaptureStability.Decision.REFUSE -> {
                            val changed = afterTokens.orEmpty().keys.union(beforeTokens.keys)
                                .filter { beforeTokens[it] != afterTokens?.get(it) }
                            return failed(
                                context, Reason.SOURCE_CHANGED_DURING_CAPTURE, "capture attempt $captureAttempt",
                                "changed while capturing: ${changed.joinToString()}"
                            )
                        }
                        PortableBackupCaptureStability.Decision.UNAVAILABLE ->
                            return failed(context, Reason.SNAPSHOT_FAILED, "reading the change markers after capture")
                    }
                    val validatedArtifacts = artifacts.map { artifact ->
                        PortablePackage.ValidatedArtifact(
                            artifact.entryName,
                            artifact.type,
                            artifact.file,
                            artifact.databaseKeyHex,
                            artifact.keySemantics,
                            artifact.schemaVersion
                        )
                    }
                    val represented = PortableRestoreInventory.from(validatedArtifacts).available
                    val semantic = PortableRecoverySemanticValidator.validate(
                        context,
                        validatedArtifacts,
                        declaredCategories = PortableRestoreCategory.entries.toSet(),
                        explicitlyEmptyCategories = PortableRestoreCategory.entries.toSet() - represented
                    )
                    when (semantic) {
                        is PortableRecoverySemanticValidator.Result.Valid -> Unit
                        PortableRecoverySemanticValidator.Result.TooLarge ->
                            return failed(context, Reason.SNAPSHOT_FAILED, "checking the backup contents", "an artifact is over its size limit")
                        is PortableRecoverySemanticValidator.Result.Invalid ->
                            return failed(context, Reason.PACKAGE_VERIFY_FAILED, "checking the backup contents", semantic.detail)
                    }
                    val declarations = PortableRecoverySemanticValidator.declarations(
                        validatedArtifacts, semantic.recordCounts
                    )
                    // ---- assemble + envelope + reopen-and-verify ----
                    val innerZip = File(staging, "inner.zip")
                    PortablePackage.buildInnerZip(
                        artifacts,
                        createdAt,
                        innerZip,
                        categoryDeclarations = declarations
                    )
                    // Producer metadata: identity lives in the header, never in
                    // the filename (owner filename architecture). The display
                    // name is captured AT CREATION TIME; a later app rename
                    // changes nothing about this package.
                    PortablePackage.envelope(
                        innerZip, out, createdAt, appVersion, recoverySecret, passwordBlob,
                        producerAppId = context.packageName,
                        producerDisplayName = try {
                            context.applicationInfo.loadLabel(context.packageManager).toString()
                        } catch (_: Exception) { "" }
                    )

                    verifyFinishedPackage(context, out, recoverySecret)?.let { problem ->
                        runCatching { out.delete() }
                        return failed(context, Reason.PACKAGE_VERIFY_FAILED, "reopening the finished backup", problem)
                    }
                    MemoryLog.log(context, "PortableRecovery", "info",
                        "Portable recovery package created and verified (${artifacts.size} artifact(s), ${written.chatCount} chat(s), " +
                            (if (recoverySecret != null) "encrypted" else "unencrypted") + ").")
                    return Result.Ok(
                        chatCount = written.chatCount,
                        artifactCount = artifacts.size,
                        includedTypes = includedTypes,
                        generatedImageCount = generatedImageCount,
                        generatedImageBytes = generatedImageBytes,
                        profileImageCount = profileImageCount,
                        profileImageBytes = profileImageBytes,
                        chatSource = chatStorage.receiptSource
                    )
                }
            }
        } catch (e: Exception) {
            runCatching { out.delete() }
            MemoryLog.log(context, "PortableRecovery", "error",
                "Portable recovery package creation failed (${e.javaClass.simpleName}).")
            return failed(context, Reason.SNAPSHOT_FAILED, "unexpected error", e.javaClass.name)
        } finally {
            PortableStaging.delete(staging)
        }
    }

    /** Reopen the finished file and verify it end-to-end with the SAME reader
     *  the restore path uses. A package we cannot read back is not a backup. */
    /** Null when the finished file reopens and verifies; otherwise why not. */
    private fun verifyFinishedPackage(context: Context, packageFile: File, recoverySecret: ByteArray?): String? {
        if (!PackageCrypto.withinSizeCap(packageFile)) {
            return "the package is ${packageFile.length()} bytes, over the size cap"
        }
        val verifyStaging = PortableStaging.newRunDir(context)
        try {
            val decoded = if (recoverySecret != null) {
                PortablePackage.decodeWithSecret(packageFile, recoverySecret, verifyStaging)
            } else {
                PortablePackage.decodeWithSecret(packageFile, ByteArray(0), verifyStaging)
            }
            val inner = when (decoded) {
                is PortablePackage.DecodeResult.Ok -> decoded.innerZip
                is PortablePackage.DecodeResult.Failed -> return "decoding failed: ${decoded.error}"
            }
            val validated = when (val result = PortablePackage.validateAndExtract(inner, verifyStaging)) {
                is PortablePackage.ValidateResult.Ok -> result
                is PortablePackage.ValidateResult.Failed -> return "extracting failed: ${result.error}"
            }
            return when (val semantic = PortableRecoverySemanticValidator.validate(
                context,
                validated.artifacts,
                validated.declaredCategories,
                validated.explicitlyEmptyCategories,
                validated.categoryRecordCounts
            )) {
                is PortableRecoverySemanticValidator.Result.Valid -> null
                PortableRecoverySemanticValidator.Result.TooLarge -> "an artifact is over its size limit"
                is PortableRecoverySemanticValidator.Result.Invalid -> "content check failed: ${semantic.detail}"
            }
        } catch (e: Exception) {
            return "unexpected ${e.javaClass.name}"
        } finally {
            PortableStaging.delete(verifyStaging)
        }
    }

    /**
     * Records a failed Recovery Backup in the Error Log with its step and
     * specific cause (owner approved October 18 2026), then returns the
     * failure. Details name steps, counts, chat IDs and positions, never
     * message text.
     */
    private fun failed(
        context: Context,
        reason: Reason,
        step: String,
        detail: String? = null,
        chatFailure: ChatLogicalSerializer.FailureCategory? = null,
        shortfallBytes: Long? = null
    ): Result.Failed {
        runCatching {
            Logger.log(
                context, "crash", "RecoveryBackup", "error",
                "Recovery Backup failed: $reason during $step" + (detail?.let { ". $it" } ?: ".")
            )
        }
        return Result.Failed(reason, chatFailure = chatFailure, shortfallBytes = shortfallBytes)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
