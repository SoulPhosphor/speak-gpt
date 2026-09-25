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

    private val note = PortableRestoreFailureNote()

    override fun failureDetail(): String? = note.detail

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
        note.reset()
        validationFailure = null
        precomputed?.let {
            prepared = it
            mergeReport = it.mergeReport
            return true
        }
        val source = incoming ?: return note.fail("no_backup_data")
        if (!source.isFile || source.length() > PortableRecoveryLimits.CHATS_JSON_BYTES) {
            return note.fail("backup_data_missing_or_too_large")
        }
        val json = try { source.readText(Charsets.UTF_8) } catch (e: Exception) { return note.unexpected(e) }
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
                note.fail("folder_decisions_required")
            }
            is PortableChatRestoreCoordinator.PrepareResult.Rejected -> {
                validationFailure = result.chatReason
                note.fail("backup_chats_rejected: ${result.chatReason?.name ?: "no_reason_given"}")
            }
        }
    }

    override fun stage(): Boolean {
        note.reset()
        val desired = prepared ?: return note.fail("backup_chats_not_validated")
        return try {
            if (stagingRoot.exists()) {
                if (!stagingRoot.isDirectory || !stagingRoot.listFiles().isNullOrEmpty()) {
                    return note.fail("staging_directory_not_empty")
                }
            } else if (!stagingRoot.mkdirs()) return note.fail("staging_directory_unavailable")
            val current = precomputedCurrent ?: run {
                val serialized = ChatLogicalSerializer.serializeV2(app)
                val currentJson = (serialized as? ChatLogicalSerializer.Result.Ok)?.json
                    ?: return note.fail(
                        "current_chats_unreadable: " +
                            ((serialized as? ChatLogicalSerializer.Result.Unavailable)?.category?.name
                                ?: serialized.javaClass.simpleName)
                    )
                when (val parsed = PortableChatRestorePlan.parse(currentJson)) {
                    is PortableChatRestorePlan.Result.Ok -> parsed.plan
                    is PortableChatRestorePlan.Result.Rejected ->
                        return note.fail("current_chats_rejected: ${parsed.reason.name}")
                }
            }
            val currentArchive = File(stagingRoot, CURRENT_ARCHIVE)
            val desiredArchive = File(stagingRoot, DESIRED_ARCHIVE)
            if (!ConvertedChatRecoveryArchive.write(app, current, currentArchive) || !currentArchive.isFile) {
                return note.fail("staged_write_failed: $CURRENT_ARCHIVE")
            }
            ConvertedChatRecoveryArchive.write(app, desired.plan, desiredArchive) &&
                desiredArchive.isFile || note.fail("staged_write_failed: $DESIRED_ARCHIVE")
        } catch (e: Exception) {
            note.unexpected(e)
        }
    }

    override fun apply(): Boolean {
        note.reset()
        return restore(File(stagingRoot, DESIRED_ARCHIVE))
    }

    override fun rollback(): Boolean {
        note.reset()
        return restore(File(stagingRoot, CURRENT_ARCHIVE))
    }

    override fun cleanup() {
        stagingRoot.deleteRecursively()
    }

    private fun restore(archive: File): Boolean {
        if (!archive.isFile) return note.fail("staged_copy_unreadable: ${archive.name}")
        return try {
            ChatRestoreManager.restoreFromArchive(app, archive).ok || note.fail("chat_write_failed")
        } catch (e: Exception) {
            note.unexpected(e)
        }
    }

    private companion object {
        const val CURRENT_ARCHIVE = "current.zip"
        const val DESIRED_ARCHIVE = "desired.zip"
    }
}
