package org.teslasoft.assistant.conversation.responsegroups

import androidx.annotation.Keep
import org.teslasoft.assistant.preferences.MessageCompletionState
import java.security.MessageDigest
import java.util.UUID

/** Durable values only. Credentials, jobs, clients and mutable source-row references never belong here. */
@Keep
data class ResponseGroup(
    val schemaVersion: Int = 1,
    val groupId: String,
    val userMessageId: String,
    val assistantMessageId: String,
    val slots: List<ResponseSlot>,
    val primarySlotId: String,
    val foregroundSlotId: String,
    val input: ResponseInputSnapshot
)

@Keep
data class ResponseSlot(
    val slotId: String,
    val assignment: ResponseModelAssignment,
    val promptPartIds: List<String>,
    val versions: List<ResponseVersion>,
    val canonicalVersionId: String,
    val foregroundVersionId: String
)

/** Requested configuration, frozen for this turn; serving evidence belongs to the version/attempt. */
@Keep
data class ResponseModelAssignment(
    val modelId: String,
    val label: String,
    val endpointProfileId: String,
    val endpoint: String,
    val protocol: String,
    val routing: Map<String, String>,
    val parameters: Map<String, String>,
    val modelRules: Map<String, String>,
    val capabilities: Map<String, String>
)

@Keep
data class ResponseVersion(
    val versionId: String,
    /** Same field contract as ordinary regenerated replies, never sibling models. */
    val fields: Map<String, String>,
    val attempts: List<ResponseAttempt>
)

@Keep
data class ResponseAttempt(
    /** Mint ONCE before durable commit/dispatch. Retries and tool continuations append a NEW attempt. */
    val requestId: String,
    val startedAtMs: Long,
    val state: String,
    val endedAtMs: Long? = null,
    val partialFields: Map<String, String> = emptyMap(),
    /** Existing frozen TurnUsageRecord codec, including this exact requestId. Never repriced on load. */
    val usageRecord: String? = null,
    val failure: String? = null,
    val warning: String? = null,
    /** Optional additive Stage 2 evidence. Old envelopes omit these fields unchanged. */
    val parentRequestId: String? = null,
    val withoutTools: Boolean? = null,
    val continuationMessages: List<String>? = null,
    val toolAssistantMessage: String? = null,
    val imageJobId: String? = null,
    val imageResult: String? = null
) {
    companion object {
        fun pending(startedAtMs: Long) = ResponseAttempt(
            UUID.randomUUID().toString(), startedAtMs, MessageCompletionState.STREAMING
        )
    }
}

@Keep
data class ResponseInputSnapshot(
    val contextMode: String,
    /** Shared immutable pool: identical text need only be stored once. */
    val parts: List<ResponseSnapshotPart>,
    val contextPartIds: List<String>,
    val currentTurnPartIds: List<String>
)

@Keep
data class ResponseSnapshotPart(
    val partId: String,
    val text: String? = null,
    val attachment: ResponseAttachment? = null
)

/** A content identity in an existing backed-up store, relative to the owning conversation. */
@Keep
data class ResponseAttachment(
    val store: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String
) {
    fun matches(bytes: ByteArray): Boolean = bytes.size.toLong() == sizeBytes && digest(bytes) == sha256

    companion object {
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
