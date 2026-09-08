/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.teslasoft.assistant.preferences.backup.portable.PackageCrypto
import org.teslasoft.assistant.preferences.backup.portable.PortablePackageFormat
import org.teslasoft.assistant.preferences.backup.portable.PortableRecoveryWriter
import org.teslasoft.assistant.preferences.backup.portable.RecoveryKeyStore
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Writes one complete portable Recovery package into the automatic SAF
 * destination. It never writes the retired per-database same-install files and
 * never rotates or deletes an older backup. */
object AutomaticPortableBackupWriter {

    sealed class Result {
        data class Success(
            val sizeBytes: Long,
            val includedTypes: Set<BackupType>
        ) : Result()

        data object NothingToBackUp : Result()

        data class Failed(
            val category: BackupFailureCategory,
            val insufficientStorage: Boolean = false
        ) : Result()
    }

    fun create(context: Context, treeUri: Uri, protected: Boolean): Result {
        val app = context.applicationContext
        val staged = File(app.cacheDir, "automatic_portable_${System.nanoTime()}.tmp")
        var destination: Uri? = null
        var secret: ByteArray? = null
        var stage = BackupStage.READ_SOURCE
        try {
            val passwordBlob: PortablePackageFormat.PasswordBlob?
            if (protected) {
                if (RecoveryKeyStore.getSetupState(app) != RecoveryKeyStore.SetupState.CONFIRMED) {
                    return Result.Failed(BackupFailureCategory.SOURCE)
                }
                secret = when (val stored = RecoveryKeyStore.getSecret(app)) {
                    is RecoveryKeyStore.SecretResult.Ok -> stored.secret
                    else -> return Result.Failed(BackupFailureCategory.SOURCE)
                }
                passwordBlob = when (val stored = RecoveryKeyStore.getPasswordBlob(app)) {
                    RecoveryKeyStore.PasswordBlobResult.NotSet -> null
                    is RecoveryKeyStore.PasswordBlobResult.Ok -> stored.blob.toFormatBlob()
                    RecoveryKeyStore.PasswordBlobResult.Unavailable ->
                        return Result.Failed(BackupFailureCategory.SOURCE)
                }
            } else {
                passwordBlob = null
            }

            val appVersion = try {
                app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
            } catch (_: Exception) {
                ""
            }
            val built = PortableRecoveryWriter.createPackage(
                app,
                staged,
                secret,
                passwordBlob,
                appVersion
            )
            if (built is PortableRecoveryWriter.Result.Failed) {
                return if (built.reason == PortableRecoveryWriter.Reason.NOTHING_TO_BACK_UP) {
                    Result.NothingToBackUp
                } else {
                    Result.Failed(
                        if (built.reason == PortableRecoveryWriter.Reason.PACKAGE_VERIFY_FAILED) {
                            BackupFailureCategory.VERIFY
                        } else {
                            BackupFailureCategory.SOURCE
                        }
                    )
                }
            }
            built as PortableRecoveryWriter.Result.Ok

            val expectedHash = sha256(staged.inputStream())
            val name = nextName(app, treeUri, protected, System.currentTimeMillis())
            stage = BackupStage.WRITE_DESTINATION
            val parentId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
            destination = DocumentsContract.createDocument(
                app.contentResolver,
                parentUri,
                "application/zip",
                name
            ) ?: throw IllegalStateException("destination unavailable")
            app.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                staged.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
            } ?: throw IllegalStateException("destination unavailable")

            stage = BackupStage.VERIFY_DESTINATION
            val verified = app.contentResolver.openInputStream(destination)?.use { sha256WithSize(it) }
                ?: throw IllegalStateException("destination unavailable")
            if (!MessageDigest.isEqual(expectedHash, verified.first)) {
                throw IllegalStateException("destination verification failed")
            }
            return Result.Success(verified.second, built.includedTypes)
        } catch (error: Exception) {
            discardDestination(app, destination)
            return Result.Failed(
                BackupFailureClassifier.classify(stage, error),
                BackupFailureClassifier.isInsufficientStorage(error)
            )
        } finally {
            secret?.let { PackageCrypto.wipe(it) }
            runCatching { if (staged.exists()) staged.delete() }
        }
    }

    private fun nextName(context: Context, treeUri: Uri, protected: Boolean, now: Long): String {
        val names = existingNames(context, treeUri)
        var sequence = 1
        while (true) {
            val candidate = RecoveryFileNaming.automaticRecoveryPackage(
                BackupBrand.resolve(context),
                protected,
                now,
                seq = sequence
            )
            if (candidate !in names) return candidate
            sequence++
        }
    }

    private fun existingNames(context: Context, treeUri: Uri): Set<String> {
        val parentId = DocumentsContract.getTreeDocumentId(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val names = LinkedHashSet<String>()
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) if (!cursor.isNull(0)) names.add(cursor.getString(0))
        }
        return names
    }

    private fun sha256(input: InputStream): ByteArray {
        return sha256WithSize(input).first
    }

    private fun sha256WithSize(input: InputStream): Pair<ByteArray, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        input.use {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                size += count
            }
        }
        return digest.digest() to size
    }

    private fun discardDestination(context: Context, uri: Uri?) {
        if (uri == null) return
        try {
            if (DocumentsContract.deleteDocument(context.contentResolver, uri)) return
        } catch (_: Exception) { }
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { }
        } catch (_: Exception) { }
    }
}
