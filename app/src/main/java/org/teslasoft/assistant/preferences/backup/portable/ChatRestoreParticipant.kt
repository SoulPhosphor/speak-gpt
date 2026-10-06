/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import java.io.File
import org.teslasoft.assistant.preferences.backup.ChatRestoreManager
import org.teslasoft.assistant.preferences.includes.ChatInclude
import org.teslasoft.assistant.preferences.includes.PdfAttachmentStore

/** Chats adapter for the selected-category outer transaction. */
class ChatRestoreParticipant internal constructor(
    context: Context,
    private val incoming: File?,
    private val mode: PortableRestoreMode,
    private val folderResolutions: Map<String, ChatMergePlanner.FolderResolution>,
    private val stagingRoot: File,
    private val precomputed: PortableChatRestoreCoordinator.Prepared? = null,
    private val precomputedCurrent: PortableChatRestorePlan.Plan? = null,
    private val incomingPdfAssets: Map<String, File> = emptyMap()
) : SelectedCategoryRestoreTransaction.Participant {
    private val app = context.applicationContext
    private var prepared: PortableChatRestoreCoordinator.Prepared? = null

    var mergeReport: ChatMergePlanner.Report? = null
        private set
    var folderCollisions: List<ChatMergePlanner.FolderCollision> = emptyList()
        private set
    var chatCollisions: List<ChatMergePlanner.ChatCollision> = emptyList()
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
    ) : this(context, incoming, mode, folderResolutions, stagingRoot, null, null, emptyMap())

    internal constructor(
        context: Context,
        prepared: PortableChatRestoreCoordinator.Prepared,
        current: PortableChatRestorePlan.Plan,
        stagingRoot: File,
        incomingPdfAssets: Map<String, File> = emptyMap()
    ) : this(
        context,
        null,
        prepared.mode,
        emptyMap(),
        stagingRoot,
        prepared,
        current,
        incomingPdfAssets
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
            is PortableChatRestoreCoordinator.PrepareResult.NeedsChatDecisions -> {
                chatCollisions = result.collisions
                note.fail("chat_decisions_required")
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
            if (!stagePdfTree(current, File(stagingRoot, CURRENT_PDFS), emptyMap())) {
                return note.fail("staged_write_failed: $CURRENT_PDFS")
            }
            val available = incomingPdfAssets + localPdfAssets(current)
            if (!stagePdfTree(desired.plan, File(stagingRoot, DESIRED_PDFS), available)) {
                return note.fail("staged_write_failed: $DESIRED_PDFS")
            }
            ConvertedChatRecoveryArchive.write(app, desired.plan, desiredArchive) && desiredArchive.isFile ||
                note.fail("staged_write_failed: $DESIRED_ARCHIVE")
        } catch (e: Exception) {
            note.unexpected(e)
        }
    }

    override fun apply(): Boolean {
        note.reset()
        return restore(File(stagingRoot, DESIRED_ARCHIVE), File(stagingRoot, DESIRED_PDFS))
    }

    override fun rollback(): Boolean {
        note.reset()
        return restore(File(stagingRoot, CURRENT_ARCHIVE), File(stagingRoot, CURRENT_PDFS))
    }

    override fun cleanup() {
        stagingRoot.deleteRecursively()
    }

    private fun restore(archive: File, pdfRoot: File): Boolean {
        if (!archive.isFile) return note.fail("staged_copy_unreadable: ${archive.name}")
        return try {
            val result = ChatRestoreManager.restoreFromArchive(app, archive)
            if (result.ok && PdfAttachmentStore.replaceAllFromStaging(app, pdfRoot)) true
            else note.fail(
                "chat_write_failed: " + (result.detail ?: "PDF attachment restore failed")
            )
        } catch (e: Exception) {
            note.unexpected(e)
        }
    }

    private fun localPdfAssets(plan: PortableChatRestorePlan.Plan): Map<String, File> {
        val out = LinkedHashMap<String, File>()
        plan.chats.forEach { chat ->
            includes(chat).filter(ChatInclude::hasLivePdfBytes).forEach { include ->
                val hash = include.pdfFileHash ?: return@forEach
                PdfAttachmentStore.pdfFile(app, chat.chatId, include)?.takeIf(File::isFile)?.let {
                    out.putIfAbsent(hash, it)
                }
            }
        }
        return out
    }

    private fun stagePdfTree(
        plan: PortableChatRestorePlan.Plan,
        root: File,
        supplied: Map<String, File>
    ): Boolean = try {
        val local = localPdfAssets(plan)
        val entries = plan.chats.flatMap { chat ->
            includes(chat).filter(ChatInclude::hasLivePdfBytes).map { include ->
                chat.chatId to (include.pdfFileHash ?: error("missing PDF hash"))
            }
        }
        stagePdfFiles(root, entries) { hash -> supplied[hash] ?: local[hash] }
    } catch (_: Exception) { false }

    private fun includes(chat: ChatLogicalImportPlan.ChatPlan): List<ChatInclude> =
        PdfAttachmentPortableBackup.includes(chat)

    internal companion object {
        private const val CURRENT_ARCHIVE = "current.zip"
        private const val DESIRED_ARCHIVE = "desired.zip"
        private const val CURRENT_PDFS = "current_pdfs"
        private const val DESIRED_PDFS = "desired_pdfs"

        private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9_-]"), "_")

        /**
         * Copies each chat's content-addressed PDF into [root] once. A chat may
         * hold several live includes of the same PDF content; they share one
         * staged file, matching the live store.
         */
        internal fun stagePdfFiles(
            root: File,
            entries: List<kotlin.Pair<String, String>>,
            sourceFor: (String) -> File?
        ): Boolean {
            root.mkdirs()
            entries.map { (chatId, hash) -> sanitize(chatId) to hash }.distinct().forEach { (chatDir, hash) ->
                val source = sourceFor(hash) ?: error("missing PDF asset")
                val destination = File(root, chatDir).apply { mkdirs() }
                source.copyTo(File(destination, "$hash.pdf"), overwrite = false)
            }
            return true
        }
    }
}
