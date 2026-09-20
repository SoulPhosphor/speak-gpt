package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import java.io.File
import org.teslasoft.assistant.preferences.backup.ChatRestoreManager

/**
 * Journaled chat-category engine for the future unified restore transaction.
 * Preparation is read-only. Application always converts the complete desired
 * logical set into a locally encrypted archive and delegates the swap to the
 * Phase 9 replacement coordinator; no Activity writes chat storage directly.
 */
object PortableChatRestoreCoordinator {
    data class Prepared(
        val plan: PortableChatRestorePlan.Plan,
        val mode: PortableRestoreMode,
        val mergeReport: ChatMergePlanner.Report? = null
    )

    sealed class PrepareResult {
        data class Ready(val prepared: Prepared) : PrepareResult()
        data class NeedsFolderDecisions(
            val collisions: List<ChatMergePlanner.FolderCollision>
        ) : PrepareResult()
        data class Rejected(
            val detail: String,
            val chatReason: PortableChatRestorePlan.Reason? = null
        ) : PrepareResult()
    }

    sealed class RestoreResult {
        data object Success : RestoreResult()
        data class Failed(val detail: String) : RestoreResult()
    }

    fun prepare(
        context: Context,
        backupJson: String,
        mode: PortableRestoreMode,
        folderResolutions: Map<String, ChatMergePlanner.FolderResolution> = emptyMap()
    ): PrepareResult {
        val backup = when (val parsed = PortableChatRestorePlan.parse(backupJson)) {
            is PortableChatRestorePlan.Result.Ok -> parsed.plan
            is PortableChatRestorePlan.Result.Rejected -> {
                return PrepareResult.Rejected(parsed.detail, parsed.reason)
            }
        }
        if (mode == PortableRestoreMode.REPLACE) return PrepareResult.Ready(Prepared(backup, mode))

        val currentJson = when (val serialized = ChatLogicalSerializer.serializeV2(context)) {
            is ChatLogicalSerializer.Result.Ok -> serialized.json
            is ChatLogicalSerializer.Result.Unavailable -> {
                return PrepareResult.Rejected("current chat storage is unavailable")
            }
        }
        val current = when (val parsed = PortableChatRestorePlan.parse(currentJson)) {
            is PortableChatRestorePlan.Result.Ok -> parsed.plan
            is PortableChatRestorePlan.Result.Rejected -> {
                return PrepareResult.Rejected("current chat storage failed validation")
            }
        }
        return when (val merged = ChatMergePlanner.plan(current, backup, folderResolutions)) {
            is ChatMergePlanner.Result.NeedsFolderDecisions ->
                PrepareResult.NeedsFolderDecisions(merged.collisions)
            is ChatMergePlanner.Result.Rejected -> PrepareResult.Rejected(merged.detail)
            is ChatMergePlanner.Result.Ready ->
                PrepareResult.Ready(Prepared(merged.plan, mode, merged.report))
        }
    }

    /** Worker-thread only. The caller restarts the app after Success. */
    fun restore(context: Context, prepared: Prepared): RestoreResult {
        var staging: File? = null
        return try {
            staging = PortableStaging.newRunDir(context)
            val archive = File(staging, "chats-recovery.zip")
            if (!ConvertedChatRecoveryArchive.write(context, prepared.plan, archive)) {
                return RestoreResult.Failed("the staged chat set failed validation")
            }
            val result = ChatRestoreManager.restoreFromArchive(context, archive)
            if (result.ok) RestoreResult.Success
            else RestoreResult.Failed(result.detail ?: "chat restore failed")
        } catch (error: Exception) {
            RestoreResult.Failed(error.javaClass.simpleName)
        } finally {
            PortableStaging.delete(staging)
        }
    }
}
