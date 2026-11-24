package org.teslasoft.assistant.ui.conversationindex

import android.content.Context
import org.teslasoft.assistant.R
import org.teslasoft.assistant.conversation.index.IndexCheckResult
import org.teslasoft.assistant.conversation.index.IndexDeleteResult
import org.teslasoft.assistant.conversation.index.IndexFailureReason

/** Screen copy uses classified causes, never IndexCheckResult.detail or exception text. */
object IndexFailureMessages {
    fun databaseCheck(context: Context, result: IndexCheckResult): String = with(context) {
        val name = getString(R.string.health_db_conversation_index)
        when (result.status) {
            IndexCheckResult.Status.OK -> getString(R.string.health_check_index_ok, name)
            IndexCheckResult.Status.REBUILDING -> getString(R.string.health_check_index_rebuilding, name)
            IndexCheckResult.Status.REPAIR_FAILED -> getString(R.string.health_check_index_failed, name)
            IndexCheckResult.Status.UNCHECKED -> listOfNotNull(
                getString(R.string.health_check_index_unchecked, name), cause(this, result.reason),
                recovery(this, result.reason, deleting = false)
            ).joinToString(" ")
        }
    }

    fun deletion(context: Context, result: IndexDeleteResult): String = listOfNotNull(
        context.getString(R.string.conversation_index_delete_failed), cause(context, result.reason),
        context.getString(R.string.conversation_index_delete_failed_off), recovery(context, result.reason, deleting = true)
    ).joinToString(" ")

    private fun cause(context: Context, reason: IndexFailureReason): String? = when (reason) {
        IndexFailureReason.BUSY -> R.string.conversation_index_failure_busy
        IndexFailureReason.STORAGE_FULL -> R.string.conversation_index_failure_storage_full
        IndexFailureReason.STORAGE_IO -> R.string.conversation_index_failure_storage_io
        IndexFailureReason.KEY_UNAVAILABLE -> R.string.conversation_index_failure_key
        IndexFailureReason.ACCESS_DENIED -> R.string.conversation_index_failure_access
        IndexFailureReason.READ_ONLY -> R.string.conversation_index_failure_read_only
        IndexFailureReason.CANNOT_OPEN -> R.string.conversation_index_failure_open
        IndexFailureReason.UNKNOWN -> null
    }?.let(context::getString)

    private fun recovery(context: Context, reason: IndexFailureReason, deleting: Boolean): String {
        val retry = context.getString(
            if (deleting) R.string.conversation_index_delete_retry else R.string.conversation_index_check_retry
        )
        val action = when (reason) {
            IndexFailureReason.STORAGE_FULL -> R.string.conversation_index_failure_free_space
            IndexFailureReason.KEY_UNAVAILABLE -> R.string.conversation_index_failure_reopen
            else -> null
        }?.let(context::getString)
        return listOfNotNull(action, retry).joinToString(" ")
    }
}
