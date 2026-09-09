/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import java.io.File
import org.teslasoft.assistant.preferences.backup.ChatRestoreManager

/** Chats adapter for the selected-category outer transaction. */
class ChatRestoreParticipant internal constructor(
    context: Context,
    private val incoming: File?,
    private val mode: PortableRestoreMode,
    private val folderResolutions: Map<String, ChatMergePlanner.FolderResolution>,
    private val stagingRoot: File,
    private val precomputed: PortableChatRestoreCoordinator.Prepared? = null,
    private val precomputedCurrent: PortableChatRestorePlan.Plan? = null
) : SelectedCategoryRestoreTransaction.Participant {
    private val app = context.applicationContext
    private var prepared: PortableChatRestoreCoordinator.Prepared? = null

    var mergeReport: ChatMergePlanner.Report? = null
        private set
    var folderCollisions: List<ChatMergePlanner.FolderCollision> = emptyList()
        private set
    var validationFailure: PortableChatRestorePlan.Reason? = null
        private set

    override val categoryKey: String = PortableRestoreCategory.CHATS.key

    constructor(
        context: Context,
        incoming: File,
        mode: PortableRestoreMode,
        folderResolutions: Map<String, ChatMergePlanner.FolderResolution>,
        stagingRoot: File
    ) : this(context, incoming, mode, folderResolutions, stagingRoot, null, null)

    internal constructor(
        context: Context,
        prepared: PortableChatRestoreCoordinator.Prepared,
        current: PortableChatRestorePlan.Plan,
        stagingRoot: File
    ) : this(
        context,
        null,
        prepared.mode,
        emptyMap(),
        stagingRoot,
        prepared,
        current
    ) {
        this.prepared = prepared
        mergeReport = prepared.mergeReport
    }

    override fun validate(): Boolean {
        validationFailure = null
        precomputed?.let {
            prepared = it
            mergeReport = it.mergeReport
            return true
        }
        val source = incoming ?: return false
        if (!source.isFile || source.length() > PortablePackage.MAX_ENTRY_BYTES) return false
        val json = try { source.readText(Charsets.UTF_8) } catch (_: Exception) { return false }
        return when (val result = PortableChatRestoreCoordinator.prepare(
            app, json, mode, folderResolutions
        )) {
            is PortableChatRestoreCoordinator.PrepareResult.Ready -> {
                prepared = result.prepared
                mergeReport = result.prepared.mergeReport
                true
            }
            is PortableChatRestoreCoordinator.PrepareResult.NeedsFolderDecisions -> {
                folderCollisions = result.collisions
                false
            }
            is PortableChatRestoreCoordinator.PrepareResult.Rejected -> {
                validationFailure = result.chatReason
                false
            }
        }
    }

    override fun stage(): Boolean {
        val desired = prepared ?: return false
        return try {
            if (stagingRoot.exists()) {
                if (!stagingRoot.isDirectory || !stagingRoot.listFiles().isNullOrEmpty()) return false
            } else if (!stagingRoot.mkdirs()) return false
            val current = precomputedCurrent ?: run {
                val currentJson = (ChatLogicalSerializer.serializeV2(app) as?
                    ChatLogicalSerializer.Result.Ok)?.json ?: return false
                (PortableChatRestorePlan.parse(currentJson) as?
                    PortableChatRestorePlan.Result.Ok)?.plan ?: return false
            }
            val currentArchive = File(stagingRoot, CURRENT_ARCHIVE)
            val desiredArchive = File(stagingRoot, DESIRED_ARCHIVE)
            ConvertedChatRecoveryArchive.write(app, current, currentArchive) &&
                ConvertedChatRecoveryArchive.write(app, desired.plan, desiredArchive) &&
                currentArchive.isFile && desiredArchive.isFile
        } catch (_: Exception) {
            false
        }
    }

    override fun apply(): Boolean = restore(File(stagingRoot, DESIRED_ARCHIVE))

    override fun rollback(): Boolean = restore(File(stagingRoot, CURRENT_ARCHIVE))

    override fun cleanup() {
        stagingRoot.deleteRecursively()
    }

    private fun restore(archive: File): Boolean {
        if (!archive.isFile) return false
        return try { ChatRestoreManager.restoreFromArchive(app, archive).ok }
        catch (_: Exception) { false }
    }

    private companion object {
        const val CURRENT_ARCHIVE = "current.zip"
        const val DESIRED_ARCHIVE = "desired.zip"
    }
}
