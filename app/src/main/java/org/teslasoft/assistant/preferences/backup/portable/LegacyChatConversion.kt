/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import java.io.File
import org.teslasoft.assistant.preferences.chatdeletion.ChatDeletionCoordinator
import org.teslasoft.assistant.preferences.chatdeletion.ChatDeletionDecision
import org.teslasoft.assistant.preferences.chatdeletion.ChatDeletionPreflightResult
import org.teslasoft.assistant.preferences.chatdeletion.ChatDeletionTarget
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationRepository
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationResult

/**
 * The owner-only conversion (Phase 8.6): take a portable Recovery Package
 * exported by the working pre-release and seed the disposable destination.
 *
 * Engine only — no wording, no UI, no logging. It returns a typed outcome and
 * a structural report; how any of that is presented is a separate, approved
 * decision.
 *
 * The input is treated as strictly read-only. The package is opened for
 * reading, everything is extracted into a fresh staging directory, and the
 * staging directory is the only source-side artifact ever deleted. The caller
 * is expected to pass a disposable copy anyway, but nothing here depends on
 * that: this code cannot modify or remove the file it is given. The destination
 * chat set is deliberately disposable and is cleared only after the incoming
 * package and logical chat artifact have passed validation.
 *
 * Only the chats artifact is handled. `memory.db`, `lorebook.db` and
 * `user_images.db` in the same package are already restorable through
 * [org.teslasoft.assistant.preferences.backup.DatabaseRestoreManager], and
 * Companions, personas and roleplay data travel in the separate Companion &
 * Roleplay Backup, which has its own working import. This fills the one hole:
 * chats had no read side at all.
 */
object LegacyChatConversion {

    /** The chats entry [ChatLogicalSerializer] writes into a portable package. */
    const val CHATS_ENTRY = "chats.json"

    sealed class Outcome {
        data class Ok(val report: ChatLogicalImporter.Report) : Outcome()

        /** This temporary path intentionally accepts unencrypted packages only. */
        object EncryptedPackageUnsupported : Outcome()

        /** The package itself could not be opened, authenticated or validated. */
        data class PackageUnusable(
            val error: PortablePackageFormat.RestoreError
        ) : Outcome()

        /** A valid package that carries no chats — a Companion-only or
         *  databases-only export, not a failure of this converter. */
        object NoChatsInPackage : Outcome()

        /** The chats artifact was read but cannot be trusted; nothing written. */
        data class ChatsRejected(
            val reason: ChatLogicalImportPlan.Reason,
            val detail: String
        ) : Outcome()

        /** The destination is not an empty installation. */
        data class DestinationRefused(
            val reason: ChatLogicalImporter.RefusalReason
        ) : Outcome()

        /** Existing disposable chats or folder metadata could not be cleared. */
        object DestinationClearFailed : Outcome()

        /** A write did not commit. The destination should be reset and the
         *  conversion re-run from a fresh copy of the original export. */
        data class WriteFailed(
            val stage: ChatLogicalImporter.FailureStage,
            val chatId: String?
        ) : Outcome()

        /** The staged artifact could not be read off disk. */
        object StagingUnreadable : Outcome()
    }

    /**
     * @param packageFile a disposable copy of the exported package. Read only.
     */
    fun convert(context: Context, packageFile: File): Outcome {
        val header = PortablePackageFormat.readHeader(packageFile)
        if (header !is PortablePackageFormat.HeaderResult.Ok) {
            return Outcome.PackageUnusable(
                (header as PortablePackageFormat.HeaderResult.Invalid).error
            )
        }

        if (header.header.protection == PortablePackageFormat.PROTECTION_ENCRYPTED) {
            return Outcome.EncryptedPackageUnsupported
        }

        val staging = PortableStaging.newRunDir(context)
        try {
            val decoded = PortablePackage.decodeWithSecret(packageFile, ByteArray(0), staging)
            if (decoded !is PortablePackage.DecodeResult.Ok) {
                return Outcome.PackageUnusable(
                    (decoded as PortablePackage.DecodeResult.Failed).error
                )
            }
            val validated = PortablePackage.validateAndExtract(decoded.innerZip, staging)
            if (validated !is PortablePackage.ValidateResult.Ok) {
                return Outcome.PackageUnusable(
                    (validated as PortablePackage.ValidateResult.Failed).error
                )
            }

            val chats = validated.artifacts.firstOrNull { it.entryName == CHATS_ENTRY }
                ?: return Outcome.NoChatsInPackage

            val json = try {
                chats.stagedFile.readText()
            } catch (_: Exception) {
                return Outcome.StagingUnreadable
            }

            val plan = when (val parsed = ChatLogicalImportPlan.parse(json)) {
                is ChatLogicalImportPlan.Result.Rejected ->
                    return Outcome.ChatsRejected(parsed.reason, parsed.detail)
                is ChatLogicalImportPlan.Result.Ok -> parsed.plan
            }

            if (!clearDisposableDestination(context.applicationContext)) {
                return Outcome.DestinationClearFailed
            }

            return when (
                val seeded = ChatLogicalImporter.seedEmptyInstallation(context, plan)
            ) {
                is ChatLogicalImporter.Outcome.Ok -> Outcome.Ok(seeded.report)
                is ChatLogicalImporter.Outcome.Refused ->
                    Outcome.DestinationRefused(seeded.reason)
                is ChatLogicalImporter.Outcome.Failed ->
                    Outcome.WriteFailed(seeded.stage, seeded.chatId)
            }
        } finally {
            PortableStaging.delete(staging)
        }
    }

    private fun clearDisposableDestination(context: Context): Boolean {
        val navigation = ChatNavigationRepository.get(context)
        val snapshot = navigation.snapshot()
        if (snapshot !is ChatNavigationResult.Success || snapshot.value.foldersUnavailable) {
            return false
        }
        val chatIds = snapshot.value.allChats.mapTo(LinkedHashSet()) { it.id }
        if (chatIds.isNotEmpty()) {
            val deletion = ChatDeletionCoordinator.get(context)
            val preflight = deletion.preflight(ChatDeletionTarget.Chats(chatIds))
            if (preflight !is ChatDeletionPreflightResult.Ready) return false
            val result = deletion.execute(preflight.value, ChatDeletionDecision.DELETE_CHAT_ONLY)
            if (!result.metadataCommitted || !result.cleanupComplete) return false
        }
        return navigation.clearFoldersWhenChatSetIsEmpty() is ChatNavigationResult.Success
    }
}
