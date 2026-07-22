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

package org.teslasoft.assistant.preferences.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.teslasoft.assistant.preferences.backup.portable.PackageCrypto
import org.teslasoft.assistant.preferences.backup.portable.PortablePackageFormat
import org.teslasoft.assistant.preferences.backup.portable.PortableRecoveryWriter
import org.teslasoft.assistant.preferences.backup.portable.RecoveryKeyStore
import org.teslasoft.assistant.preferences.memory.MemoryLog
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * The portable AUTOMATIC backup runner (owner directive, July 22 2026):
 * when the app opens, if an automatic backup is due, it creates the SAME
 * verified portable Recovery Backup package the manual flow creates —
 * always PROTECTED (an automatic run has no user present to choose a tier,
 * so it never produces an unencrypted package and requires the Recovery
 * Code setup to be CONFIRMED before it will run at all) — writes it into
 * the user's chosen automatic folder, reopens and hash-verifies the
 * destination, and records the file in the durable ownership index.
 *
 * Safety fences (owner, July 22 2026 — all deliberate):
 *  - NO deletion of user files. Retention only IDENTIFIES which owned,
 *    verified files exceed the keep-5 window ([AutomaticBackupIndex
 *    .retentionCandidates]); actual pruning stays disabled until owner
 *    review. The one removal this runner ever attempts is discarding its
 *    OWN just-created destination file when that copy fails verification —
 *    the same rollback the manual flow performs, so a corrupt package can
 *    never sit in the folder looking like a good backup.
 *  - Nothing is adopted by filename: the index gains entries only for files
 *    this runner created in this run.
 *  - The gate ([AutomaticBackupPolicy]) is evaluated from cheap state FIRST;
 *    the multi-store package build can never start for a disallowed run.
 *  - The heavy build is DELAYED after the gate passes ([START_DELAY_MILLIS])
 *    and the gate re-checked: this runs on the startup housekeeping thread,
 *    and the chat serialization inside the package build parses every
 *    history under CHAT_LIST_LOCK — starting that immediately at app open
 *    would contend with the main thread's own chat-list load on the
 *    encrypted-prefs monitor, the exact contention class behind the July 15
 *    2026 ANR. Backups are at most daily; a short wait is invisible.
 *  - Results are recorded truthfully: success only after BOTH the staged
 *    package and the destination copy verified; a failure stamps the
 *    attempt + category and preserves the last-good stamp.
 */
object AutomaticBackupRunner {

    /** Wait between the first gate pass and the heavy build (see class doc). */
    const val START_DELAY_MILLIS: Long = 30_000L

    /** Once per process: MainApplication calls [runIfDue] on every start;
     *  a second evaluation in the same process is pure waste. */
    @Volatile private var ranThisProcess = false

    fun runIfDue(context: Context) {
        if (ranThisProcess) return
        ranThisProcess = true
        val appContext = context.applicationContext

        if (evaluateGate(appContext) != AutomaticBackupPolicy.Gate.RUN) return

        // Let the app finish its own startup reads before the heavy build
        // (July 15 ANR class — see the class doc), then re-check: the user
        // may have flipped the switch or revoked the folder meanwhile.
        try { Thread.sleep(START_DELAY_MILLIS) } catch (_: InterruptedException) { return }
        if (evaluateGate(appContext) != AutomaticBackupPolicy.Gate.RUN) return

        runNow(appContext)
    }

    /** The cheap gate: prefs reads + the persisted-permission list + the
     *  Keystore setup state. No store is opened and no history is parsed. */
    private fun evaluateGate(context: Context): AutomaticBackupPolicy.Gate {
        val folderUri = RecoveryBackupState.getAutoFolderUri(context)
        val gate = AutomaticBackupPolicy.evaluate(
            enabled = RecoveryBackupState.isEnabled(context),
            hasFolder = folderUri != null,
            folderAccessible = folderUri != null && folderAccessible(context, folderUri),
            setupConfirmed = try {
                RecoveryKeyStore.getSetupState(context) == RecoveryKeyStore.SetupState.CONFIRMED
            } catch (_: Exception) { false },
            lastSuccessMillis = RecoveryBackupState.getAutoLastSuccess(context),
            lastAttemptMillis = RecoveryBackupState.getAutoLastAttempt(context),
            nowMillis = System.currentTimeMillis(),
            frequency = RecoveryBackupState.getAutoFrequency(context)
        )
        // The two states the user must ACT on are logged once per run so a
        // silently-never-running backup is diagnosable from the Memory log.
        if (gate == AutomaticBackupPolicy.Gate.FOLDER_INACCESSIBLE) {
            MemoryLog.log(context, "AutomaticBackup", "warning",
                "Automatic backup skipped: the chosen folder is no longer accessible. Choose a new location on the Backup & Restore screen.")
        }
        if (gate == AutomaticBackupPolicy.Gate.SETUP_NOT_CONFIRMED) {
            MemoryLog.log(context, "AutomaticBackup", "warning",
                "Automatic backup skipped: the Recovery Code setup is not confirmed. Automatic backups are always protected and need it.")
        }
        return gate
    }

    private fun folderAccessible(context: Context, uriStr: String): Boolean = try {
        val uri = Uri.parse(uriStr)
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    } catch (_: Exception) {
        false
    }

    /** Build → write → verify → record. Never throws. */
    private fun runNow(context: Context) {
        val now = System.currentTimeMillis()
        val treeUriStr = RecoveryBackupState.getAutoFolderUri(context) ?: return
        val treeUri = Uri.parse(treeUriStr)
        val staged = File(context.cacheDir, "auto_recovery_stage_${System.nanoTime()}.tmp")
        var secret: ByteArray? = null
        try {
            secret = when (val s = RecoveryKeyStore.getSecret(context)) {
                is RecoveryKeyStore.SecretResult.Ok -> s.secret
                else -> {
                    recordFailure(context, now, BackupFailureCategory.SOURCE,
                        "the Recovery Code material could not be read")
                    return
                }
            }
            val passwordBlob: PortablePackageFormat.PasswordBlob? =
                when (val b = RecoveryKeyStore.getPasswordBlob(context)) {
                    is RecoveryKeyStore.PasswordBlobResult.NotSet -> null
                    is RecoveryKeyStore.PasswordBlobResult.Ok -> b.blob.toFormatBlob()
                    is RecoveryKeyStore.PasswordBlobResult.Unavailable -> {
                        // A password WAS configured but its blob is unreadable:
                        // fail visibly rather than silently dropping the
                        // password restore route (standing owner correction).
                        recordFailure(context, now, BackupFailureCategory.SOURCE,
                            "the stored password material could not be read")
                        return
                    }
                }

            val appVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            } catch (_: Exception) { "" }

            when (val result = PortableRecoveryWriter.createPackage(context, staged, secret, passwordBlob, appVersion)) {
                is PortableRecoveryWriter.Result.Failed -> {
                    val category = when (result.reason) {
                        PortableRecoveryWriter.Reason.PACKAGE_VERIFY_FAILED -> BackupFailureCategory.VERIFY
                        else -> BackupFailureCategory.SOURCE
                    }
                    recordFailure(context, now, category, "the package could not be built (${result.reason.name})")
                }
                is PortableRecoveryWriter.Result.Ok -> {
                    writeVerifyRecord(context, treeUri, treeUriStr, staged, now, result.includedTypes)
                }
            }
        } catch (e: Exception) {
            recordFailure(
                context, now,
                if (BackupFailureClassifier.isPermissionRelated(e)) BackupFailureCategory.DESTINATION_PERMISSION
                else BackupFailureCategory.DESTINATION_WRITE,
                "unexpected error (${e.javaClass.simpleName})"
            )
        } finally {
            secret?.let { PackageCrypto.wipe(it) }
            runCatching { if (staged.exists()) staged.delete() }
        }
    }

    private fun writeVerifyRecord(
        context: Context,
        treeUri: Uri,
        treeUriStr: String,
        staged: File,
        now: Long,
        includedTypes: Set<BackupType>
    ) {
        val resolver = context.contentResolver
        val brand = BackupBrand.resolve(context)

        // Collision-free -2/-3 naming against the folder's CURRENT contents
        // (list once; never touches, adopts, or interprets the other files).
        val existingNames = HashSet<String>()
        try {
            val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            resolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) c.getString(0)?.let { existingNames.add(it) }
            }
        } catch (_: Exception) { /* name query is best-effort; the provider dedups if needed */ }
        var seq = 1
        var name = RecoveryFileNaming.automaticRecoveryPackage(brand, protected = true, epochMillis = now, seq = seq)
        while (name in existingNames && seq < 100) {
            seq++
            name = RecoveryFileNaming.automaticRecoveryPackage(brand, protected = true, epochMillis = now, seq = seq)
        }

        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val child = try {
            DocumentsContract.createDocument(resolver, parentDocUri, "application/zip", name)
        } catch (e: Exception) {
            recordFailure(
                context, now,
                if (BackupFailureClassifier.isPermissionRelated(e)) BackupFailureCategory.DESTINATION_PERMISSION
                else BackupFailureCategory.DESTINATION_WRITE,
                "the destination file could not be created"
            )
            return
        }
        if (child == null) {
            recordFailure(context, now, BackupFailureCategory.DESTINATION_WRITE,
                "the destination file could not be created")
            return
        }

        try {
            resolver.openOutputStream(child)?.use { out ->
                staged.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("could not open destination")

            val expected = sha256Hex(staged)
            val actual = resolver.openInputStream(child)?.use { sha256Hex(it) }
                ?: throw IllegalStateException("could not reopen destination")

            if (actual != expected) {
                // Roll back OUR OWN just-written, unverified file (the manual
                // flow's exact rollback). If the provider refuses, keep an
                // unverified index record so the file stays tracked — it never
                // counts toward retention.
                val discarded = try { DocumentsContract.deleteDocument(resolver, child) } catch (_: Exception) { false }
                if (!discarded) {
                    AutomaticBackupIndex.record(context, AutomaticBackupIndex.Entry(
                        uri = child.toString(), treeUri = treeUriStr, fileName = name,
                        sha256 = actual, createdAtMillis = now,
                        sizeBytes = staged.length(), verified = false
                    ))
                }
                recordFailure(context, now, BackupFailureCategory.VERIFY,
                    "the saved copy did not match the built package")
                return
            }

            // BOTH verifications passed (the writer verified the staged
            // package internally; the destination re-read matched). Record
            // ownership FIRST (durable index), then the schedule + status.
            AutomaticBackupIndex.record(context, AutomaticBackupIndex.Entry(
                uri = child.toString(), treeUri = treeUriStr, fileName = name,
                sha256 = expected, createdAtMillis = now,
                sizeBytes = staged.length(), verified = true
            ))
            RecoveryBackupState.recordAutoSuccess(context, now)
            for (type in BackupType.displayOrder) {
                if (type in includedTypes) RecoveryBackupState.recordSuccess(context, type, now)
                else RecoveryBackupState.recordNothingToBackUp(context, type, now)
            }

            // Retention: IDENTIFY (never delete) what exceeds keep-5 in the
            // current folder, for the UI note and the future owner-reviewed
            // pruning step.
            val candidates = AutomaticBackupIndex.retentionCandidates(
                AutomaticBackupIndex.load(context), treeUriStr
            )
            RecoveryBackupState.setAutoPrunePending(context, candidates.size)
            MemoryLog.log(context, "AutomaticBackup", "info",
                "Automatic backup created and verified ($name)." +
                    if (candidates.isNotEmpty())
                        " ${candidates.size} older automatic backup(s) now exceed the keep-${BackupRotationPlanner.KEEP} window; automatic cleanup is disabled pending owner review — nothing was deleted."
                    else ""
            )
        } catch (e: Exception) {
            // The half-written file is OUR OWN creation from this run: roll it
            // back like the manual flow so it cannot masquerade as a backup.
            try { DocumentsContract.deleteDocument(resolver, child) } catch (_: Exception) { }
            recordFailure(
                context, now,
                if (BackupFailureClassifier.isPermissionRelated(e)) BackupFailureCategory.DESTINATION_PERMISSION
                else BackupFailureCategory.DESTINATION_WRITE,
                "the package could not be saved to the folder"
            )
        }
    }

    /** Stamp the failed attempt (last-good preserved) on the automatic clock
     *  AND the per-type status rows, and log one sanitized line. */
    private fun recordFailure(context: Context, now: Long, category: BackupFailureCategory, detail: String) {
        RecoveryBackupState.recordAutoFailure(context, now, category)
        for (type in BackupType.displayOrder) {
            RecoveryBackupState.recordFailure(context, type, now, category)
        }
        MemoryLog.log(context, "AutomaticBackup", "error",
            "Automatic backup failed (${category.name}): $detail. Existing backups are untouched; the next attempt follows the schedule.")
    }

    private fun sha256Hex(file: File): String = file.inputStream().use { sha256Hex(it) }

    private fun sha256Hex(stream: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(8192)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
