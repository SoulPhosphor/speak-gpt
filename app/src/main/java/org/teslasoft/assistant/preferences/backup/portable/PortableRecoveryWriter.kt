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
import org.teslasoft.assistant.preferences.backup.BackupType
import org.teslasoft.assistant.preferences.backup.DatabaseHealthState
import org.teslasoft.assistant.preferences.backup.RecoveryBackupManager
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
 *  - User image catalog: row-level rebuilt plain SQLite copy (catalog only;
 *    the JPEGs are not backed up — standing owner ruling).
 *  - Chats: logical serialization ([ChatLogicalSerializer]) — the raw
 *    enc.*.xml files can never be portable. LOCKED storage fails the run
 *    visibly.
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
            val includedTypes: Set<BackupType>
        ) : Result()

        /** [chatFailure] refines CHATS_UNAVAILABLE with WHICH part of chat
         *  storage failed, for the plain-words detail line. */
        data class Failed(
            val reason: Reason,
            val chatFailure: ChatLogicalSerializer.FailureCategory? = null
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
        STORE_DEGRADED
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
    ): Result {
        val staging = PortableStaging.newRunDir(context)
        try {
            // Degraded gate (Build Phase 3): a store with confirmed damage is
            // "unavailable to use or save" — snapshotting it here would seal a
            // corrupt copy inside a package the user trusts. Refused loudly.
            if (DatabaseHealthState.anyDegraded(context)) {
                return Result.Failed(Reason.STORE_DEGRADED)
            }
            val createdAt = Instant.now().toString()
            val artifacts = ArrayList<PortablePackage.Artifact>()
            val includedTypes = LinkedHashSet<BackupType>()

            // ---- memory DB (ciphertext + key) ----
            if (MemoryStore.isProvisioned(context)) {
                val staged = File(staging, "memory.snapshot")
                val key = DatabaseKeys.getOrCreate(context, DatabaseKeys.KEY_MEMORY, databaseExists = true)
                    ?: return Result.Failed(Reason.KEY_MATERIAL_UNAVAILABLE)
                if (!RecoveryBackupManager.snapshotCipher(context, MemoryStore.DATABASE_NAME, staged) { key }) {
                    return Result.Failed(Reason.SNAPSHOT_FAILED)
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
                } catch (_: Exception) {
                    return Result.Failed(Reason.KEY_MATERIAL_UNAVAILABLE)
                }
                if (!RecoveryBackupManager.snapshotCipher(context, "lorebook.db", staged, allowEmptyKey = true) { key }) {
                    return Result.Failed(Reason.SNAPSHOT_FAILED)
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

            // ---- user image catalog (plain SQLite; catalog only) ----
            run {
                val staged = File(staging, "user_images.snapshot")
                if (RecoveryBackupManager.snapshotUserImageCatalog(context, staged)) {
                    RecoveryBackupManager.integrityCheckPlain(staged)
                    artifacts.add(
                        PortablePackage.Artifact(
                            entryName = "user_images.db", type = "sqlite-db", file = staged,
                            databaseKeyHex = null, keySemantics = null, schemaVersion = null
                        )
                    )
                    includedTypes.add(BackupType.USER_IMAGE)
                }
            }

            // ---- chats (logical serialization; LOCKED fails visibly) ----
            when (val chats = ChatLogicalSerializer.serialize(context)) {
                is ChatLogicalSerializer.Result.Unavailable ->
                    return Result.Failed(Reason.CHATS_UNAVAILABLE, chatFailure = chats.category)
                is ChatLogicalSerializer.Result.Ok -> {
                    val staged = File(staging, "chats.json")
                    staged.writeText(chats.json, Charsets.UTF_8)
                    artifacts.add(
                        PortablePackage.Artifact(
                            entryName = "chats.json", type = "chats-json", file = staged,
                            databaseKeyHex = null, keySemantics = null, schemaVersion = null
                        )
                    )
                    includedTypes.add(BackupType.CHATS)
                    if (artifacts.size == 1 && chats.chatCount == 0) {
                        // No databases exist and no chats exist: nothing real
                        // to package — neutral, not a failure (owner ruling).
                        return Result.Failed(Reason.NOTHING_TO_BACK_UP)
                    }

                    // ---- assemble + envelope + reopen-and-verify ----
                    val innerZip = File(staging, "inner.zip")
                    PortablePackage.buildInnerZip(artifacts, createdAt, innerZip)
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

                    if (!verifyFinishedPackage(context, out, recoverySecret)) {
                        runCatching { out.delete() }
                        return Result.Failed(Reason.PACKAGE_VERIFY_FAILED)
                    }
                    MemoryLog.log(context, "PortableRecovery", "info",
                        "Portable recovery package created and verified (${artifacts.size} artifact(s), ${chats.chatCount} chat(s), " +
                            (if (recoverySecret != null) "encrypted" else "unencrypted") + ").")
                    return Result.Ok(
                        chatCount = chats.chatCount,
                        artifactCount = artifacts.size,
                        includedTypes = includedTypes
                    )
                }
            }
        } catch (e: Exception) {
            runCatching { out.delete() }
            MemoryLog.log(context, "PortableRecovery", "error",
                "Portable recovery package creation failed (${e.javaClass.simpleName}).")
            return Result.Failed(Reason.SNAPSHOT_FAILED)
        } finally {
            PortableStaging.delete(staging)
        }
    }

    /** Reopen the finished file and verify it end-to-end with the SAME reader
     *  the restore path uses. A package we cannot read back is not a backup. */
    private fun verifyFinishedPackage(context: Context, packageFile: File, recoverySecret: ByteArray?): Boolean {
        if (!PackageCrypto.withinSizeCap(packageFile)) return false
        val verifyStaging = PortableStaging.newRunDir(context)
        try {
            val decoded = if (recoverySecret != null) {
                PortablePackage.decodeWithSecret(packageFile, recoverySecret, verifyStaging)
            } else {
                PortablePackage.decodeWithSecret(packageFile, ByteArray(0), verifyStaging)
            }
            val inner = (decoded as? PortablePackage.DecodeResult.Ok)?.innerZip ?: return false
            return PortablePackage.validateAndExtract(inner, verifyStaging) is PortablePackage.ValidateResult.Ok
        } catch (_: Exception) {
            return false
        } finally {
            PortableStaging.delete(verifyStaging)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
