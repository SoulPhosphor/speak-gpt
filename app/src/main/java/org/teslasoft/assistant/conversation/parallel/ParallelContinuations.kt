package org.teslasoft.assistant.conversation.parallel

import org.teslasoft.assistant.conversation.responsegroups.*
import org.teslasoft.assistant.preferences.MessageCompletionState
import java.util.UUID

/** Every continuation/rejection retry has a new durable identity before tools or network can run. */
internal class ParallelContinuations(private val store: ParallelGroupPersistence) {
    fun begin(request: ParallelRequest, nowMs: Long, withoutTools: Boolean): Pair<ResponseVersion, ResponseAttempt> {
        var reserved: ResponseAttempt? = null
        var pendingVersion: ResponseVersion? = null
        store.update(request.target) { current ->
            val group = requireNotNull(current)
            group.copy(slots = group.slots.map { slot ->
                if (slot.slotId != request.slotId) slot else slot.copy(versions = slot.versions.map { version ->
                    if (version.versionId != request.versionId) version else {
                        val old = version.attempts.last()
                        require(old.requestId == request.requestId && old.state ==
                            if (withoutTools) MessageCompletionState.FAILED else MessageCompletionState.DONE) { "stale_continuation" }
                        require(old.parentRequestId == null) { "bounded_continuation" }
                        if (!withoutTools) require(old.toolAssistantMessage != null)
                        reserved = ResponseAttempt.pending(nowMs).copy(parentRequestId = old.requestId, withoutTools = withoutTools)
                        val prefix = if (withoutTools) version.fields + mapOf("message" to "", "reasoningText" to "") else version.fields
                        pendingVersion = version.copy(fields = prefix.filterKeys {
                            it in setOf("message", "reasoningText", "activeMemoryAttribution", "reasoningLevel",
                                org.teslasoft.assistant.preferences.memory.scan.ScanMessageScope.KEY)
                        } + ("state" to MessageCompletionState.STREAMING), attempts = version.attempts + reserved!!)
                        pendingVersion!!
                    }
                })
            })
        }
        return checkNotNull(pendingVersion) { "request_owner_missing" } to checkNotNull(reserved)
    }

    fun freezeMessages(request: ParallelRequest, messages: List<String>) = edit(request) { old ->
        require(old.state == MessageCompletionState.STREAMING && old.continuationMessages == null)
        old.copy(continuationMessages = messages.toList())
    }

    fun reserveImage(request: ParallelRequest): String {
        var result = ""
        edit(request) { old ->
            require(old.state == MessageCompletionState.STREAMING && old.imageJobId == null) { "image_already_reserved" }
            result = UUID.randomUUID().toString()
            old.copy(imageJobId = result)
        }
        return result
    }

    fun recordImage(request: ParallelRequest, imageId: String, result: String) = edit(request) { old ->
        require(old.imageJobId == imageId && (old.imageResult == null || old.imageResult == result))
        old.copy(imageResult = result)
    }

    private fun edit(request: ParallelRequest, change: (ResponseAttempt) -> ResponseAttempt): ResponseGroup =
        store.update(request.target) { value ->
            val group = requireNotNull(value)
            val slot = group.slots.single { it.slotId == request.slotId }
            val version = slot.versions.single { it.versionId == request.versionId }
            val old = version.attempts.single { it.requestId == request.requestId }
            require(old.startedAtMs > 0)
            group.copy(slots = group.slots.map { s -> if (s != slot) s else s.copy(versions = s.versions.map { v ->
                if (v != version) v else v.copy(attempts = v.attempts.map { if (it.requestId == request.requestId) change(it) else it })
            }) })
        }
}
