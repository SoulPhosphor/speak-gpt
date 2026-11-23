package org.teslasoft.assistant.conversation.parallel

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.conversation.responsegroups.*
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.usage.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ParallelRequestCoordinatorTest {
    private fun id() = UUID.randomUUID().toString()
    private val userId = id()
    private val assistantId = id()
    private val context = ParallelInputPart.Text("canonical earlier answer")
    private val current = ParallelInputPart.Text("current input with manual memories")
    private fun group(count: Int = 4, extra: List<ParallelInputPart> = emptyList()): ResponseGroup = ParallelInput.freeze(
        userId, assistantId, "full_history", listOf(context), listOf(current) + extra,
        (0 until count).map { index -> ParallelSlotInput(ResponseModelAssignment(
            "same-model", "Same label", "endpoint-$index", "https://endpoint-$index.invalid/v1/", "chat_completions",
            mapOf("only" to "provider-$index"), mapOf("temperature" to "$index"),
            mapOf("rules" to "rules-$index"), mapOf("text" to "reported")),
            listOf(ParallelInputPart.Text("rules-$index"), context, current) + extra) }, 0, 100L)

    private class Store(private val user: String, private val assistant: String) : ParallelGroupPersistence {
        @Volatile var rows: List<Map<String, Any>> = listOf(
            mapOf("message_id" to user, "isBot" to false, "message" to "user"),
            mapOf("message_id" to assistant, "isBot" to true, "message" to ""))
        val commits = Channel<ResponseGroup>(Channel.UNLIMITED)
        var writes = 0
        @Volatile var failSlot: String? = null
        @Volatile var failAll = false
        var durabilityGate: CompletableDeferred<Unit>? = null
        val durabilityEntered = CompletableDeferred<Unit>()
        override suspend fun awaitSaved(target: ResponseGroupTarget) {
            durabilityEntered.complete(Unit)
            durabilityGate?.await()
        }
        override fun read(target: ResponseGroupTarget): ResponseGroup =
            (ResponseGroupCodec.read(rows.single { it["message_id"] == target.assistantMessageId }) as ResponseGroupRead.Valid).group
        override fun update(target: ResponseGroupTarget, transform: (ResponseGroup?) -> ResponseGroup): ResponseGroup = synchronized(this) {
            val row = rows.single { it["message_id"] == assistant }
            val old = (ResponseGroupCodec.read(row) as? ResponseGroupRead.Valid)?.group
            val new = transform(old)
            if (failAll || (failSlot != null && old != null &&
                    old.slots.single { it.slotId == failSlot } != new.slots.single { it.slotId == failSlot })) {
                throw ParallelPersistenceException("fixture_write_failure")
            }
            rows = ResponseGroupHistory.replace(target.chatId, rows, target,
                row[ResponseGroupCodec.KEY] as String?, new).messages
            writes++
            commits.trySend(new)
            new
        }
    }

    private class Lane {
        val started = CompletableDeferred<ParallelRequest>()
        val seen = mutableListOf<ParallelRequest>()
        val commands = Channel<suspend (ParallelRequest, ParallelOutput) -> Boolean>(Channel.UNLIMITED)
        suspend fun execute(request: ParallelRequest, output: ParallelOutput) {
            seen += request
            started.complete(request)
            request.usage.beginObservation()
            try { for (command in commands) if (command(request, output)) return }
            finally { request.usage.finishObservation() }
        }
        suspend fun text(value: String) {
            val applied = CompletableDeferred<Unit>()
            commands.send { _, output -> output.text(value); applied.complete(Unit); false }
            applied.await()
        }
        suspend fun reasoning(value: String) {
            val applied = CompletableDeferred<Unit>()
            commands.send { _, output -> output.reasoning(value); applied.complete(Unit); false }
            applied.await()
        }
        suspend fun done(tokens: Int = 10) { commands.send { request, _ ->
            request.usage.noteTypedUsage(tokens, 2, tokens + 2); true
        } }
        suspend fun fail(category: String = "fixture_failure") { commands.send { _, _ -> throw ParallelTransportFailure(category) } }
    }

    @Test fun queuedReservationDoesNotDispatchUntilItsSaveIsDurable() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(group(2), this)
            val durable = CompletableDeferred<Unit>()
            f.store.durabilityGate = durable
            val dispatch = async { f.engine.dispatch("chat", f.g) {} }
            f.store.durabilityEntered.await()
            assertEquals(0, f.calls)
            assertFalse(dispatch.isCompleted)
            durable.complete(Unit)
            val run = dispatch.await()
            f.lanes.values.forEach { it.started.await() }
            f.completePricing()
            f.lanes.values.forEach { it.done() }
            run.await()
            assertEquals(2, f.calls)
        }
    }

    @Test fun stopDuringAContinuationSaveSettlesTheReservedAttemptWithoutDispatch() = runBlocking {
        withTimeout(10_000) {
            val g = group(2)
            val target = ResponseGroupTarget("chat", assistantId, g.groupId)
            val store = Store(userId, assistantId)
            val reserved = CompletableDeferred<Unit>()
            val durable = CompletableDeferred<Unit>()
            var barriers = 0
            val persistence = object : ParallelGroupPersistence by store {
                override suspend fun awaitSaved(target: ResponseGroupTarget) {
                    if (++barriers == 2) { reserved.complete(Unit); durable.await() }
                }
            }
            val calls = AtomicInteger()
            val engine = ParallelRequestCoordinator(this, persistence, { _, _ -> null }, { request ->
                ParallelTransport { _, _ ->
                    if (request.slotId == g.slots.first().slotId) {
                        calls.incrementAndGet()
                        throw ParallelTransportFailure("tools_not_supported")
                    } else awaitCancellation()
                }
            }, { TokenPricingCatalog(it.assignment.modelId) })
            val run = engine.dispatch("chat", g) {}
            reserved.await()
            engine.signalCancelAll()
            run.await()
            val attempts = store.read(target).slots.first().versions.single().attempts
            assertEquals(listOf(MessageCompletionState.FAILED, MessageCompletionState.STOPPED), attempts.map { it.state })
            assertEquals(1, calls.get())
            assertTrue(engine.unsavedRequestIds().isEmpty())
        }
    }

    @Test fun deletionDuringTheDurabilityWaitCannotSendTheAbandonedRequest() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(group(2), this)
            val durable = CompletableDeferred<Unit>()
            f.store.durabilityGate = durable
            val dispatch = async { f.engine.dispatch("chat", f.g) {} }
            f.store.durabilityEntered.await()
            f.engine.signalCancelAll()
            durable.complete(Unit)
            dispatch.await().await()
            assertEquals(0, f.calls)
            assertEquals(MessageCompletionState.STOPPED, f.version(0).fields["state"])
        }
    }

    @Test fun retiredFailedSavesRetryWithoutBlockingNewRequestsOrResending() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(group(2), this)
            val run = f.start()
            f.completePricing()
            f.lanes.values.forEach { it.started.await().usage.noteTypedUsage(27, 3, 30) }
            f.store.failAll = true
            f.engine.signalCancelAll()
            run.await()
            assertTrue(f.engine.unsavedRequestIds().isNotEmpty())
            assertTrue(f.engine.unsavedTargets(includeRetired = false).isEmpty())
            f.store.failAll = false
            while (f.engine.unsavedRequestIds().isNotEmpty()) delay(10)
            assertTrue(ResponseGroupEdits.usage(f.saved()).all { it.inputTokens == 27 })
            assertEquals(2, f.calls)
        }
    }

    private inner class Fixture(val g: ResponseGroup, scope: CoroutineScope) {
        val target = ResponseGroupTarget("chat", assistantId, g.groupId)
        val store = Store(userId, assistantId)
        val lanes = g.slots.associate { it.slotId to Lane() }
        val prices = g.slots.associate { it.slotId to CompletableDeferred<TokenPricingCatalog>() }
        @Volatile var elapsed = 0L
        private val callCount = AtomicInteger()
        val calls get() = callCount.get()
        var attachmentRead: (ResponseAttachment) -> ByteArray? = { null }
        val engine = ParallelRequestCoordinator(scope, store, { _, a -> attachmentRead(a) }, { request ->
            assertNotNull(store.read(target).slots.flatMap { it.versions }.flatMap { it.attempts }
                .singleOrNull { it.requestId == request.requestId })
            callCount.incrementAndGet()
            ParallelTransport { r, o -> lanes.getValue(r.slotId).execute(r, o) }
        }, { prices.getValue(it.slotId).await() }, { 200L }, { elapsed })
        fun saved() = store.read(target)
        fun version(index: Int) = saved().slots[index].versions.last()
        fun completePricing() { prices.forEach { (_, gate) -> gate.complete(TokenPricingCatalog("same-model")) } }
        suspend fun start(): ParallelRequestCoordinator.Run = engine.dispatch("chat", g) {}.also {
            lanes.values.forEach { lane -> lane.started.await() }
        }
    }

    @Test fun stopBetweenDurableReservationAndRegistrationCannotLaunchThatRequest() = runBlocking {
        withTimeout(10_000) {
            val g = group(2)
            val store = Store(g.userMessageId, g.assistantMessageId)
            val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
            val engine = ParallelRequestCoordinator(this, store, { _, _ -> null }, { request -> ParallelTransport { _, output ->
                seen += request.slotId
                output.text("retained sibling")
            } }, { TokenPricingCatalog(it.assignment.modelId) })
            val stopped = g.slots.first().versions.single().attempts.single().requestId
            engine.cancel(stopped)
            engine.dispatch("chat", g) {}.await()
            val saved = store.read(ResponseGroupTarget("chat", g.assistantMessageId, g.groupId))
            assertEquals(listOf(g.slots[1].slotId), seen)
            assertEquals("stopped", saved.slots[0].versions.single().fields["state"])
            assertEquals("done", saved.slots[1].versions.single().fields["state"])
            assertEquals(stopped, saved.slots[0].versions.single().attempts.single().requestId)
            assertTrue(engine.unsavedRequestIds().isEmpty())
        }
    }

    @Test fun fourEndpointsCompleteOutOfOrderWithoutTextReasoningOrPromptLeakage() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(group(), this)
            val run = f.start()
            val originalIds = run.requestIds.toSet()
            assertEquals(4, originalIds.size)
            f.lanes.values.forEachIndexed { index, lane ->
                val request = lane.started.await()
                assertEquals("https://endpoint-$index.invalid/v1/", request.assignment.endpoint)
                assertEquals("$index", request.assignment.parameters["temperature"])
                assertEquals(listOf("rules-$index", context.value, current.value), request.prompt.map { it.text })
            }
            f.completePricing()
            for (index in listOf(3, 1, 2, 0)) {
                val lane = f.lanes.getValue(f.g.slots[index].slotId)
                lane.text("answer-$index")
                lane.reasoning("reasoning-$index")
                lane.done(index + 10)
            }
            run.await()
            f.saved().slots.forEachIndexed { index, slot ->
                val version = slot.versions.single()
                assertEquals("answer-$index", version.fields["message"])
                assertEquals("reasoning-$index", version.fields["reasoningText"])
                assertEquals("done", version.fields["state"])
                assertEquals(run.requestIds[index], version.attempts.single().requestId)
                assertEquals(index + 10, TokenUsageAccounting.decodeRecords(version.attempts.single().usageRecord).single().inputTokens)
            }
            assertEquals("answer-0", f.store.rows.last()["message"])
            assertEquals(originalIds, ResponseGroupEdits.usage(f.saved()).map { it.requestId }.toSet())
            assertTrue(f.engine.unsavedRequestIds().isEmpty())
        }
    }

    @Test fun cancelOwnedJoinsATerminalCheckpointWithoutCancellingItsSibling() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(group(2), this)
            val run = f.start()
            val selected = f.g.slots.first()
            f.lanes.getValue(selected.slotId).done(23)
            while (f.store.commits.receive().slots.first().versions.single().fields["state"] != "done") { }
            // The transport has finished, but the owned job still waits for its pricing observer.
            f.engine.cancelOwned(f.target, selected.slotId)
            assertEquals("done", f.version(0).fields["state"])
            assertEquals("streaming", f.version(1).fields["state"])
            f.prices.getValue(f.g.slots[1].slotId).complete(TokenPricingCatalog("same-model"))
            f.lanes.getValue(f.g.slots[1].slotId).done()
            run.await()
            assertEquals(23, ResponseGroupEdits.usage(f.saved()).first().inputTokens)
            assertEquals(run.requestIds, ResponseGroupEdits.usage(f.saved()).map { it.requestId })
            assertTrue(f.engine.unsavedRequestIds().isEmpty())
            assertEquals(2, f.calls)
        }
    }

    @Test fun failureCancellationAndSlowSiblingHaveIndependentOutcomesAndCharges() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(group(), this)
            val run = f.start()
            f.completePricing()
            val lanes = f.lanes.values.toList()
            lanes[0].started.await().usage.noteTypedUsage(17, 3, 20)
            lanes[0].fail()
            lanes[1].text("stopped partial")
            lanes[1].started.await().usage.noteTypedUsage(19, 1, 20)
            f.engine.cancel(run.requestIds[1])
            lanes[2].text("fast")
            lanes[2].done()
            lanes[3].text("slow")
            lanes[3].done()
            run.await()
            assertEquals(listOf("failed", "stopped", "done", "done"), f.saved().slots.map { it.versions.single().fields["state"] })
            assertEquals("stopped partial", f.version(1).fields["message"])
            assertEquals(17, ResponseGroupEdits.usage(f.saved())[0].inputTokens)
            assertEquals(19, ResponseGroupEdits.usage(f.saved())[1].inputTokens)
            assertEquals(4, f.calls)
        }
    }

    @Test fun allFailuresAndMalformedTransportKeepEveryRequestIdentity() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(group(), this)
            val run = f.start()
            f.completePricing()
            f.lanes.values.forEach { it.fail("malformed_response") }
            run.await()
            assertTrue(f.saved().slots.all { it.versions.single().fields["state"] == "failed" })
            assertEquals(run.requestIds.toSet(), ResponseGroupEdits.usage(f.saved()).map { it.requestId }.toSet())
            assertTrue(TokenUsageAccounting.aggregate(ResponseGroupEdits.usage(f.saved())).hasUnknownCost)
        }
    }

    @Test fun failedInitialCommitNeverStartsTransportOrPricing() = runBlocking {
        val f = Fixture(group(), this)
        f.store.failAll = true
        try { f.engine.dispatch("chat", f.g) {}; fail() } catch (_: ParallelPersistenceException) { }
        assertEquals(0, f.calls)
        assertTrue(f.lanes.values.none { it.started.isCompleted })
    }

    @Test fun failedResultSaveDoesNotBlockSiblingAndFlushNeverResends() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(group(), this)
            val run = f.start()
            f.completePricing()
            f.store.failSlot = f.g.slots[2].slotId
            f.lanes.values.forEachIndexed { i, lane -> lane.text("result-$i"); lane.done() }
            run.await()
            assertEquals("streaming", f.version(2).fields["state"])
            assertEquals("done", f.version(3).fields["state"])
            assertEquals(setOf(run.requestIds[2]), f.engine.unsavedRequestIds())
            f.store.failSlot = null
            assertTrue(f.engine.flush().isEmpty())
            assertEquals("result-2", f.version(2).fields["message"])
            assertEquals("done", f.version(2).fields["state"])
            assertEquals(4, f.calls)
        }
    }

    @Test fun reopenInterruptsOnceAndLateCallbacksCannotResurrectAnAnswer() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(group(), this)
            val run = f.start()
            f.elapsed = 2_001
            f.lanes.values.first().started.await().usage.noteTypedUsage(4, 1, 5)
            f.lanes.values.first().text("checkpoint")
            val recovered = f.engine.reconcileInterrupted(f.target)
            assertTrue(recovered.slots.all { it.versions.single().fields["state"] == "interrupted" })
            assertEquals(recovered, f.engine.reconcileInterrupted(f.target))
            assertEquals(run.requestIds, ResponseGroupEdits.usage(recovered).map { it.requestId })
            f.completePricing()
            f.lanes.values.forEach { it.text("late text"); it.done(30) }
            run.await()
            assertEquals("checkpoint", f.version(0).fields["message"])
            assertTrue(f.saved().slots.all { it.versions.single().fields["state"] == "interrupted" })
            assertEquals(30, ResponseGroupEdits.usage(f.saved()).first().inputTokens)
            val retainedReceipt = f.version(0).attempts.single().partialFields[TokenUsageAccounting.KEY_USAGE_RECORDS]
            assertEquals(4, TokenUsageAccounting.decodeRecords(retainedReceipt).single().inputTokens)
            assertEquals(4, f.calls)
        }
    }

    @Test fun regenerationUsesOriginalInputAndNewIdWithoutTouchingSiblings() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(group(), this)
            val run = f.start()
            f.completePricing()
            f.lanes.values.forEach { it.text("original"); it.done() }
            run.await()
            val before = f.saved()
            val retry = f.engine.regenerate(f.target, before.slots[2].slotId)
            val lane = f.lanes.getValue(before.slots[2].slotId)
            lane.text("regenerated")
            lane.done(50)
            retry.await()
            val after = f.saved()
            assertEquals(1, retry.requestIds.size)
            assertFalse(retry.requestIds.single() in run.requestIds)
            assertEquals(before.input, after.input)
            assertEquals(before.slots[2].promptPartIds, after.slots[2].promptPartIds)
            assertEquals(2, after.slots[2].versions.size)
            assertEquals("regenerated", after.slots[2].versions.last().fields["message"])
            for (i in listOf(0, 1, 3)) assertEquals(before.slots[i], after.slots[i])
            assertEquals(f.lanes.getValue(before.slots[2].slotId).seen.first().prompt,
                f.lanes.getValue(before.slots[2].slotId).seen.last().prompt)
            assertEquals(5, f.calls)
            assertEquals(5, ResponseGroupEdits.usage(after).size)
        }
    }

    @Test fun missingOrModifiedAttachmentPreventsDispatchAndRegeneration() = runBlocking {
        val bytes = "invented image bytes".toByteArray()
        val sha = ResponseAttachment.digest(bytes)
        val attachment = ResponseAttachment("image", "$sha.png", "image/png", bytes.size.toLong(), sha)
        val f = Fixture(group(extra = listOf(ParallelInputPart.Attachment(attachment))), this)
        try { f.engine.dispatch("chat", f.g) {}; fail() } catch (_: IllegalArgumentException) { }
        assertEquals(0, f.store.writes)
        f.attachmentRead = { bytes }
        val run = f.start()
        f.completePricing()
        f.lanes.values.forEach { it.done() }
        run.await()
        f.attachmentRead = { "changed bytes".toByteArray() }
        val before = f.saved()
        try { f.engine.regenerate(f.target, before.slots[1].slotId); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(before, f.saved())
        assertEquals(4, f.calls)
    }

    @Test fun omittedAttachmentAndNoncanonicalBaseContextAreRejectedBeforeSaving() {
        val assignment = group().slots[0].assignment
        try {
            ParallelInput.freeze(userId, assistantId, "full_history", listOf(context), listOf(current),
                listOf(ParallelSlotInput(assignment, listOf(current)),
                    ParallelSlotInput(assignment.copy(endpointProfileId = "other"), listOf(context, current))), 0, 100)
            fail()
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun latePricingEnrichesExactlyOneReceiptAndReconciliationIsIdempotent() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(group(), this)
            val run = f.start()
            f.lanes.values.forEach { it.text("complete before price"); it.done(10) }
            while (f.store.commits.receive().slots.any { it.versions.single().fields["state"] != "done" }) { }
            assertEquals(4, ResponseGroupEdits.usage(f.saved()).size)
            assertTrue(TokenUsageAccounting.aggregate(ResponseGroupEdits.usage(f.saved())).hasUnknownCost)
            // Fictional rates test arithmetic only, never production catalog data.
            f.prices.values.forEachIndexed { i, gate -> gate.complete(TokenPricingCatalog("same-model",
                modelPricing = TokenPricingSnapshot((i + 1) * 0.001, 0.002, cachingOffered = false))) }
            run.await()
            val records = ResponseGroupEdits.usage(f.saved())
            assertEquals(4, records.size)
            assertEquals(0.116, records.sumOf { it.totalCost!! }, 0.0000001)
            val seeded = UsageLogState(false, emptyList()).seed(f.store.rows, 500)
            val again = seeded.seed(f.store.rows, 600)
            assertEquals(seeded, again)
            assertEquals(4, again.entries.size)
            val summary = again.summarize(f.store.rows, { error("No legacy estimates for groups") })
            val screenPayload = TokenUsageAccounting.decodeSummary(TokenUsageAccounting.encodeSummary(summary))
            assertEquals(40, screenPayload.totalInputTokens)
            assertEquals(8, screenPayload.totalOutputTokens)
            assertEquals(0.116, screenPayload.totalCost, 0.0000001)
            assertFalse(screenPayload.hasUnknownCost)
            assertEquals(4, screenPayload.groups.sumOf { it.recordCount })
            assertEquals(4, f.calls)
        }
    }

    @Test fun teardownKeepsPartialAndNeverMasqueradesAsUserStop() = runBlocking {
        withTimeout(10_000) {
            val owner = SupervisorJob(coroutineContext[Job])
            val f = Fixture(group(), CoroutineScope(coroutineContext + owner))
            val run = f.start()
            f.lanes.values.first().text("partial at destruction")
            owner.cancel()
            run.await()
            assertEquals("partial at destruction", f.version(0).fields["message"])
            assertTrue(f.saved().slots.all { it.versions.single().fields["state"] == "interrupted" })
            assertEquals(run.requestIds, ResponseGroupEdits.usage(f.saved()).map { it.requestId })
        }
    }

    @Test fun repeatDispatchCannotResendAlreadyDurableRequests() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(group(), this)
            val run = f.start()
            try { f.engine.dispatch("chat", f.g) {}; fail() } catch (_: IllegalArgumentException) { }
            assertEquals(4, f.calls)
            f.completePricing()
            f.lanes.values.forEach { it.done() }
            run.await()
        }
    }

    @Test fun callerMutationCannotChangeAnAlreadyFrozenAssignment() = runBlocking {
        withTimeout(10_000) {
            val settings = hashMapOf("temperature" to "0.4")
            val original = group()
            val mutable = original.copy(slots = original.slots.map { it.copy(assignment = it.assignment.copy(parameters = settings)) })
            val f = Fixture(mutable, this)
            val run = f.start()
            settings["temperature"] = "9.9"
            f.completePricing()
            f.lanes.values.forEach { lane ->
                assertEquals("0.4", lane.started.await().assignment.parameters["temperature"])
                lane.done()
            }
            run.await()
            assertTrue(f.saved().slots.all { it.assignment.parameters["temperature"] == "0.4" })
        }
    }

    @Test fun fourStreamsShareOnePeriodicHistoryCheckpoint() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(group(), this)
            val run = f.start()
            assertEquals(1, f.store.writes)
            f.elapsed = 2_001
            f.lanes.values.forEachIndexed { i, lane -> lane.text("partial-$i") }
            assertEquals(2, f.store.writes)
            f.completePricing()
            f.lanes.values.forEach { it.done() }
            run.await()
            assertEquals(listOf("partial-0", "partial-1", "partial-2", "partial-3"),
                f.saved().slots.map { it.versions.single().fields["message"] })
        }
    }

}
