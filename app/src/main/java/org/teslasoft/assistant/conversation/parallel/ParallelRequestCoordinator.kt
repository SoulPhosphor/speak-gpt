package org.teslasoft.assistant.conversation.parallel

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.teslasoft.assistant.conversation.responsegroups.*
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.usage.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One transient owner per actual request. No credentials or current activity state are stored here. */
internal data class ParallelRequest(
    val target: ResponseGroupTarget,
    val slotId: String,
    val versionId: String,
    val requestId: String,
    val assignment: ResponseModelAssignment,
    val prompt: List<ResponseSnapshotPart>,
    val attachmentBytes: Map<String, ByteArray>,
    val usage: ProviderUsageAttempt
)

/** The transport must return only after its request-scoped usage/reasoning observers have drained.
 * No retries, continuations or extra calls are implicit in this interface.
 */
internal fun interface ParallelTransport {
    suspend fun execute(request: ParallelRequest, output: ParallelOutput)
}

internal interface ParallelOutput {
    suspend fun text(delta: String)
    suspend fun reasoning(delta: String)
    /** Sanitized provider evidence, separate from the generated answer; never written to a new log. */
    suspend fun warning(value: String)
}

internal class ParallelTransportFailure(val category: String) : Exception() {
    init { require(category.matches(Regex("[a-z_]+"))) }
}

/** Hidden request engine. It has no Send/Stop UI policy, tools policy, setup or voice callbacks.
 * An owner supplies one frozen prepared group and an independently owned transport for each request.
 */
internal class ParallelRequestCoordinator(
    private val scope: CoroutineScope,
    private val persistence: ParallelGroupPersistence,
    private val readAttachment: (ResponseGroupTarget, ResponseAttachment) -> ByteArray?,
    private val transport: (ParallelRequest) -> ParallelTransport,
    private val pricing: suspend (ParallelRequest) -> TokenPricingCatalog,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val worker: CoroutineDispatcher = Dispatchers.IO
) {
    private val requests = ConcurrentHashMap<String, Running>()

    internal class Run internal constructor(
        val requestIds: List<String>,
        private val jobs: List<Job>
    ) {
        suspend fun await() { jobs.joinAll() }
    }

    private class UserStop : CancellationException()
    private class Running(
        var request: ParallelRequest,
        val initialFields: Map<String, String>,
        val startedAtMs: Long,
        var lastCheckpoint: Long
    ) {
        val lock = Mutex()
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var warning: String? = null
        var catalog: TokenPricingCatalog? = null
        var state = MessageCompletionState.STREAMING
        var detail: String? = null
        var endedAtMs: Long? = null
        @Volatile var job: Job? = null
        val registered = CompletableDeferred<Job>()
        @Volatile var unsaved = false
    }

    /** The entire group (all IDs/assignments/input) commits before any pricing/client/network work.
     * Calling this twice is rejected; a loaded pending group is never permission to resend it.
     */
    suspend fun dispatch(chatId: String, input: ResponseGroup): Run = withContext(worker) {
        val group = detach(input)
        val target = ResponseGroupTarget(chatId, group.assistantMessageId, group.groupId)
        val prepared = prepare(target, group, group.slots)
        persistence.update(target) { old ->
            require(old == null) { "group_already_dispatched" }
            group
        }
        launch(prepared)
    }

    /** Appends only the selected slot's version, keeping the original prompt/settings and all receipts.
     * The guarded store refuses canonical branch changes with descendants. No network runs on refusal.
     */
    suspend fun regenerate(target: ResponseGroupTarget, slotId: String): Run = withContext(worker) {
        val before = persistence.read(target)
        val oldSlot = before.slots.single { it.slotId == slotId }
        require(oldSlot.versions.none { v -> v.attempts.any { it.state == MessageCompletionState.STREAMING } }) {
            "slot_still_running"
        }
        val versionId = UUID.randomUUID().toString()
        val fields = oldSlot.versions.single { it.versionId == oldSlot.canonicalVersionId }.fields
            .filterKeys { it in setOf("activeMemoryAttribution") } +
            mapOf("message" to "", "state" to MessageCompletionState.STREAMING)
        val newSlot = oldSlot.copy(versions = oldSlot.versions + ResponseVersion(versionId, fields,
            listOf(ResponseAttempt.pending(nowMs()))), canonicalVersionId = versionId, foregroundVersionId = versionId)
        val after = before.copy(slots = before.slots.map { if (it.slotId == slotId) newSlot else it })
        val prepared = prepare(target, after, listOf(newSlot))
        persistence.update(target) { current ->
            require(current == before) { "stale_regeneration" }
            after
        }
        launch(prepared)
    }

    /** An explicit per-request cancellation primitive; later UI decides which requests Stop targets. */
    suspend fun cancel(requestId: String) {
        val running = requests[requestId] ?: return
        val job = running.registered.await()
        job.cancel(UserStop())
        job.join()
    }

    /** Save retry only. Never executes a transport, reloads prices, or mints another request ID. */
    suspend fun flush(): Set<String> = withContext(worker) {
        requests.values.forEach { running -> running.lock.withLock {
            if (running.unsaved) save(running)
            if (!running.unsaved && running.job?.isCompleted == true) requests.remove(running.request.requestId, running)
        } }
        unsavedRequestIds()
    }

    fun unsavedRequestIds(): Set<String> = requests.values.filter { it.unsaved }.mapTo(HashSet()) { it.request.requestId }

    /** Explicit reopen reconciliation, with no launch/resume path. */
    suspend fun reconcileInterrupted(target: ResponseGroupTarget): ResponseGroup = withContext(worker) {
        persistence.update(target) { current -> ResponseGroupEdits.interrupted(requireNotNull(current)) }
    }

    private fun prepare(target: ResponseGroupTarget, group: ResponseGroup, slots: List<ResponseSlot>): List<Running> {
        ResponseGroupCodec.encode(group) // Validate before any side effect.
        val parts = group.input.parts.associateBy { it.partId }
        val requiredParts = slots.flatMap { it.promptPartIds }.toSet()
        val bytes = group.input.parts.filter { it.partId in requiredParts }.mapNotNull { part -> part.attachment?.let { attachment ->
            val content = readAttachment(target, attachment)?.copyOf()
            require(content != null && attachment.matches(content)) { "snapshot_attachment_unavailable" }
            part.partId to content
        } }.toMap()
        return slots.map { slot ->
            val version = slot.versions.single { it.versionId == slot.canonicalVersionId }
            val attempt = version.attempts.last()
            require(attempt.state == MessageCompletionState.STREAMING && attempt.partialFields.isEmpty()) {
                "new_attempt_required"
            }
            val assignment = slot.assignment.copy(routing = slot.assignment.routing.toMap(),
                parameters = slot.assignment.parameters.toMap(), modelRules = slot.assignment.modelRules.toMap(),
                capabilities = slot.assignment.capabilities.toMap())
            val request = ParallelRequest(target, slot.slotId, version.versionId, attempt.requestId,
                assignment, slot.promptPartIds.map { parts.getValue(it) },
                // Separate byte arrays stop even an ill-behaved encoder from changing a sibling's input.
                bytes.filterKeys { it in slot.promptPartIds }.mapValues { it.value.copyOf() },
                ProviderUsageAttempt(assignment.modelId, TokenUsageAccounting.PROVIDER_NOT_REPORTED,
                    assignment.endpoint, attempt.requestId))
            Running(request, version.fields.toMap(), attempt.startedAtMs, elapsedMs())
        }
    }

    private fun launch(prepared: List<Running>): Run {
        // One supervisor prevents either a transport failure or save failure from cancelling siblings.
        val parent = SupervisorJob(scope.coroutineContext[Job])
        val ownedScope = CoroutineScope(scope.coroutineContext + parent + worker)
        prepared.forEach { require(requests.putIfAbsent(it.request.requestId, it) == null) }
        val jobs = prepared.map { running ->
            // UNDISPATCHED enters the try/finally before callers can cancel a not-yet-scheduled job.
            ownedScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    yield()
                    coroutineScope {
                        val priceJob = launch {
                            val catalog = try { pricing(running.request) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { null }
                            running.lock.withLock {
                                running.catalog = catalog
                                if (running.state != MessageCompletionState.STREAMING) save(running)
                            }
                        }
                        try {
                            transport(running.request).execute(running.request, output(running))
                            running.lock.withLock { settle(running, MessageCompletionState.DONE) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            running.lock.withLock {
                                settle(running, MessageCompletionState.FAILED,
                                    (e as? ParallelTransportFailure)?.category ?: "transport_failure")
                            }
                        }
                        // A result is already durable while a slow pricing lookup finishes.
                        priceJob.join()
                    }
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { running.lock.withLock {
                        if (running.state == MessageCompletionState.STREAMING) {
                            settle(running, if (e is UserStop) MessageCompletionState.STOPPED else MessageCompletionState.INTERRUPTED,
                                if (e is UserStop) MessageCompletionState.DETAIL_USER_STOP else MessageCompletionState.DETAIL_SCREEN_CLOSED)
                        }
                    } }
                } finally {
                    withContext(NonCancellable) { running.lock.withLock {
                        if (running.state == MessageCompletionState.STREAMING) {
                            settle(running, MessageCompletionState.INTERRUPTED, MessageCompletionState.DETAIL_SCREEN_CLOSED)
                        }
                    } }
                }
            }.also { job ->
                running.job = job
                running.registered.complete(job)
                job.invokeOnCompletion {
                    if (!running.unsaved) requests.remove(running.request.requestId, running)
                    else running.request = running.request.copy(prompt = emptyList(), attachmentBytes = emptyMap())
                }
            }
        }
        parent.complete()
        return Run(prepared.map { it.request.requestId }, jobs)
    }

    private fun output(running: Running) = object : ParallelOutput {
        override suspend fun text(delta: String) = update { running.text.append(delta); Unit }
        override suspend fun reasoning(delta: String) = update { running.reasoning.append(delta); Unit }
        override suspend fun warning(value: String) = update { running.warning = value }
        private suspend fun update(change: () -> Unit) = running.lock.withLock {
            if (running.state != MessageCompletionState.STREAMING) return@withLock
            currentCoroutineContext().ensureActive()
            change()
            val elapsed = elapsedMs()
            if (elapsed - running.lastCheckpoint >= 2_000) {
                save(running)
                running.lastCheckpoint = elapsed
            }
        }
    }

    private suspend fun settle(running: Running, state: String, detail: String? = null) {
        running.state = state
        running.detail = detail
        running.endedAtMs = maxOf(nowMs(), running.startedAtMs)
        save(running)
    }

    private suspend fun save(running: Running) {
        val request = running.request
        try {
            val usage = if (running.state == MessageCompletionState.STREAMING) request.usage.checkpointSnapshot()
                else request.usage.snapshot()
            val record = TokenUsageAccounting.createRecord(usage.model, usage.provider, usage.apiEndpoint,
                usage.counts, if (usage.counts.hasAnyValue()) TokenCountSource.PROVIDER_REPORTED else TokenCountSource.ESTIMATED_CL100K,
                running.catalog?.pricingFor(usage.provider) ?: TokenPricingSnapshot(), usage.providerCost)
                .copy(requestId = request.requestId, requestedModel = request.assignment.modelId,
                    requestStartedAtMs = running.startedAtMs)
            val fields = running.initialFields + mapOf("message" to running.text.toString(), "state" to running.state,
                "responseModel" to usage.model, "responseProvider" to usage.provider,
                "messageTime" to (running.endedAtMs ?: running.startedAtMs).toString()) +
                (if (running.reasoning.isEmpty()) emptyMap() else mapOf("reasoningText" to running.reasoning.toString())) +
                (running.detail?.let { mapOf("stateDetail" to it) } ?: emptyMap()) +
                (running.warning?.let { mapOf("providerWarningText" to it) } ?: emptyMap())
            val encodedReceipt = TokenUsageAccounting.encodeRecords(listOf(record))
            val attempt = ResponseAttempt(request.requestId, running.startedAtMs, running.state, running.endedAtMs,
                fields + (TokenUsageAccounting.KEY_USAGE_RECORDS to encodedReceipt), encodedReceipt,
                running.detail.takeIf { running.state == MessageCompletionState.FAILED }, running.warning)
            persistence.update(request.target) { group ->
                val current = requireNotNull(group)
                val ownedSlot = current.slots.single { it.slotId == request.slotId }
                val ownedVersion = ownedSlot.versions.single { it.versionId == request.versionId }
                require(ownedVersion.attempts.single { it.requestId == request.requestId }.startedAtMs == running.startedAtMs)
                current.copy(slots = current.slots.map { slot ->
                    if (slot.slotId != request.slotId) slot else slot.copy(versions = slot.versions.map { version ->
                        if (version.versionId != request.versionId) version else {
                            val old = version.attempts.single { it.requestId == request.requestId }
                            // A reopened/recovered terminal request owns its checkpoint. A late callback
                            // may enrich accounting but cannot turn it into a success or overwrite text.
                            val saved = if (old.state == MessageCompletionState.STREAMING) attempt else old.copy(
                                usageRecord = mergeReceipt(old.usageRecord, attempt.usageRecord!!))
                            version.copy(fields = if (old.state == MessageCompletionState.STREAMING) fields else version.fields,
                                attempts = version.attempts.map { if (it.requestId == request.requestId) saved else it })
                        }
                    })
                })
            }
            running.unsaved = false
        } catch (e: CancellationException) {
            running.unsaved = true
            throw e
        } catch (_: Exception) {
            // Keep the exact transient evidence for an explicit save-only flush. Siblings still settle.
            running.unsaved = true
        }
    }

    private fun detach(group: ResponseGroup): ResponseGroup {
        val row = ResponseGroupEdits.install(mapOf("message_id" to group.assistantMessageId, "isBot" to true), group)
        return (ResponseGroupCodec.read(row) as ResponseGroupRead.Valid).group
    }

    private fun mergeReceipt(old: String?, new: String): String {
        if (old == null) return new
        val gson = com.google.gson.Gson()
        val previous = gson.toJsonTree(TokenUsageAccounting.decodeRecords(old).single()).asJsonObject
        val incoming = gson.toJsonTree(TokenUsageAccounting.decodeRecords(new).single()).asJsonObject
        // The old checkpoint receipt remains in immutable partialFields. A later reported receipt
        // supersedes partial counts without losing the evidence recovery actually preserved.
        previous.entrySet().forEach { (key, value) ->
            if (!incoming.has(key) || incoming[key].isJsonNull ||
                (key == "provider" && incoming[key].asString == TokenUsageAccounting.PROVIDER_NOT_REPORTED) ||
                (key == "source" && incoming[key].asString != TokenCountSource.PROVIDER_REPORTED.storedValue) ||
                (key == "costSource" && incoming[key].asString == CostSource.UNKNOWN.storedValue)) incoming.add(key, value)
        }
        return TokenUsageAccounting.encodeRecords(listOf(gson.fromJson(incoming, TurnUsageRecord::class.java)))
    }
}
