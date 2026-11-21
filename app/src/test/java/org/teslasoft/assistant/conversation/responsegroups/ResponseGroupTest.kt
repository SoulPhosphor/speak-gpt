package org.teslasoft.assistant.conversation.responsegroups

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.preferences.MessageVisibility
import org.teslasoft.assistant.preferences.backup.readable.ReadableChatFormats
import org.teslasoft.assistant.preferences.chatsearch.SearchableMessageProjection
import org.teslasoft.assistant.usage.TokenUsageAccounting
import org.teslasoft.assistant.usage.UsageLogState
import org.teslasoft.assistant.util.summarizer.SummarySections
import java.util.UUID

class ResponseGroupTest {
    private val gson = Gson()
    private fun history(): List<HashMap<String, Any>> = gson.fromJson(
        javaClass.classLoader!!.getResource("response-groups/history.json")!!.readText(),
        object : TypeToken<List<HashMap<String, Any>>>() {}.type
    )
    private fun group(row: Map<String, Any>): ResponseGroup = (ResponseGroupCodec.read(row) as ResponseGroupRead.Valid).group
    private fun group(): ResponseGroup = group(history()[2])
    private fun row(): HashMap<String, Any> = history()[2]
    private fun target(g: ResponseGroup = group(), chatId: String = "chat") = ResponseGroupTarget(chatId, g.assistantMessageId, g.groupId)
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected invalid mutation to be refused") } catch (_: IllegalArgumentException) { }
    }
    private fun change(g: ResponseGroup, messages: List<Map<String, Any>> = history().take(3), discard: Boolean = false) =
        ResponseGroupHistory.replace("chat", messages, target(), row()[ResponseGroupCodec.KEY] as String, g, discard)

    @Test fun legacyAndMultipleGroupsRoundTripWithoutMigration() {
        val rows = history()
        val original = gson.toJson(rows[0])
        assertEquals(ResponseGroupRead.Absent, ResponseGroupCodec.read(rows[0]))
        ResponseGroupHistory.validateConversation(rows)
        for (row in rows.filter { it.containsKey(ResponseGroupCodec.KEY) }) {
            val g = group(row)
            assertEquals(4, g.slots.size)
            assertEquals(4, g.slots.map { it.assignment.endpointProfileId }.distinct().size)
            assertEquals(1, g.slots.map { it.assignment.label }.distinct().size)
            val restored = row + (ResponseGroupCodec.KEY to ResponseGroupCodec.encode(g))
            assertEquals(g, group(restored))
        }
        assertEquals(original, gson.toJson(rows[0]))
        assertEquals("[{\"message\":\"legacy canonical\"},{\"message\":\"legacy regeneration\"}]", rows[0]["variants"])
    }

    @Test fun browsePersistsSelectionsButLeavesEveryCanonicalConsumerUnchanged() {
        val original = row()
        val g = group(original)
        val browsed = ResponseGroupEdits.browse(g, g.slots[3].slotId, g.slots[3].versions[1].versionId)
        val result = change(browsed)
        assertFalse(result.canonicalChanged)
        assertEquals("answer-1-0-0", result.after["message"])
        assertEquals("answer-1-3-1", ResponseGroupEdits.display(result.after)["message"])
        assertEquals(SearchableMessageProjection.project(listOf(original)), SearchableMessageProjection.project(listOf(result.after)))
        assertEquals(browsed, group(result.after))
        assertEquals(g.slots[0].versions.map { it.fields },
            gson.fromJson(result.after["variants"].toString(), object : TypeToken<List<Map<String, String>>>() {}.type))
        assertEquals("true", result.after["bookmarked"])
        assertEquals(original[MessageIdentity.KEY], result.after[MessageIdentity.KEY])
        val names = ReadableChatFormats.SpeakerNames("Companion", "User", "Companion")
        val text = ReadableChatFormats.formatText("Synthetic", listOf(result.after), names, false)
        assertTrue(text.contains("answer-1-0-0"))
        assertFalse(text.contains("answer-1-3-1"))
    }

    @Test fun promotionChangesOnlySelectedCanonicalAndRequiresBranchPermission() {
        val g = group()
        val promoted = ResponseGroupEdits.promote(g, g.slots[2].slotId, g.slots[2].versions[1].versionId)
        rejected { change(promoted, history()) }
        val result = change(promoted, history(), discard = true)
        assertTrue(result.canonicalChanged)
        assertEquals(3, result.messages.size)
        assertEquals(2, result.removed.size)
        assertEquals("answer-1-2-1", result.after["message"])
        assertEquals("failed", result.after["state"])
        assertEquals(g.slots[1], group(result.after).slots[1])
        assertEquals(history()[0], result.messages[0])
        assertEquals("answer-2-0-0", result.removed.last()["message"])
        assertEquals(g.input, group(result.after).input)
    }

    @Test fun sourcesSeeOneCanonicalAnswerAndSummaryDetectsPromotionOnly() {
        val g = group()
        fun source(row: Map<String, Any>) = SummarySections.SourceMessage(g.assistantMessageId, true, row["message"].toString(), null)
        val section = SummarySections.Section("section", listOf(g.assistantMessageId),
            listOf(SummarySections.fingerprint(row()["message"].toString())), emptyList(), emptyList(), "frozen summary")
        val browse = change(ResponseGroupEdits.browse(g, g.slots[2].slotId, g.slots[2].versions[0].versionId))
        assertFalse(SummarySections.reconcile(listOf(section), listOf(source(browse.after))).changed)
        val promote = change(ResponseGroupEdits.promote(g, g.slots[2].slotId, g.slots[2].versions[0].versionId))
        assertTrue(SummarySections.reconcile(listOf(section), listOf(source(promote.after))).sections.single().needsUpdate)
        assertEquals(listOf("legacy canonical", "user turn 1", "answer-1-2-0"),
            SearchableMessageProjection.project(promote.messages).map { it.text })
        assertFalse(gson.toJson(source(promote.after)).contains("answer-1-1-0"))
        val transcript = """[{"role":"assistant","content":"answer-1-0-0","messageId":"${g.assistantMessageId}"}]"""
        assertEquals(JsonParser.parseString(transcript), JsonParser.parseString(ResponseGroupSourceProjection.transcript(transcript, browse.messages)))
        val reconciled = ResponseGroupSourceProjection.transcript(transcript, promote.messages)
        assertTrue(reconciled.contains("answer-1-2-0"))
        assertFalse(reconciled.contains("answer-1-0-0"))
        assertFalse(reconciled.contains("answer-1-1-0"))
    }

    @Test fun malformedUnsupportedMissingReferencesAndDuplicateIdsKeepRawAndMirror() {
        val original = row()
        val g = group()
        val invalid = listOf(
            "{", "null", "[]", "", "{unquoted:true}",
            gson.toJson(g).replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            gson.toJson(g).dropLast(1) + ",\"futureData\":true}", ResponseGroupCodec.encode(g).replace(g.primarySlotId, "bad-id"),
            gson.toJson(g.copy(slots = g.slots + g.slots[0])),
            gson.toJson(g.copy(input = g.input.copy(currentTurnPartIds = listOf(UUID.randomUUID().toString())))),
            gson.toJson(g.copy(slots = g.slots.map { it.copy(canonicalVersionId = "missing") })),
            gson.toJson(g).replace("\"state\":\"done\"", "\"state\":\"future-state\"")
        )
        for (raw in invalid) {
            val row = original + (ResponseGroupCodec.KEY to raw)
            assertTrue("Must reject $raw", ResponseGroupCodec.read(row) is ResponseGroupRead.Invalid)
            assertEquals(raw, row[ResponseGroupCodec.KEY])
            assertEquals(original["message"], row["message"])
        }
        val future = gson.toJson(g.copy(schemaVersion = 99))
        assertEquals(ResponseGroupRead.Unsupported(future, 99), ResponseGroupCodec.read(original + (ResponseGroupCodec.KEY to future)))
        assertTrue(ResponseGroupCodec.read(original + (ResponseGroupCodec.KEY to 3)) is ResponseGroupRead.Invalid)
        assertTrue(ResponseGroupCodec.read(original + (ResponseGroupCodec.KEY to null)) is ResponseGroupRead.Invalid)
    }

    @Test fun permanentIdsAndAttemptOwnershipSurviveInterruptionAndNewRetries() {
        val g = group()
        val recovered = ResponseGroupEdits.interrupted(g)
        assertEquals(ids(g), ids(recovered))
        assertEquals(recovered, ResponseGroupEdits.interrupted(recovered))
        assertEquals("interrupted", recovered.slots[3].versions[1].fields["state"])
        assertEquals("answer-1-3-1", recovered.slots[3].versions[1].fields["message"])
        ResponseGroupEdits.validateTransition(g, recovered)
        val slot = recovered.slots[3]
        val version = slot.versions[1]
        val retry = ResponseAttempt.pending(1700000000010).copy(state = "interrupted")
        val continuation = ResponseAttempt.pending(1700000000020)
        val next = recovered.copy(slots = recovered.slots.map {
            if (it != slot) it else it.copy(versions = listOf(it.versions[0], version.copy(fields = version.fields + ("state" to "streaming"), attempts = version.attempts + retry + continuation)))
        })
        ResponseGroupEdits.validateTransition(recovered, next)
        assertEquals(ids(g) + listOf(retry.requestId, continuation.requestId), ids(next))
        assertNotEquals(retry.requestId, continuation.requestId)
        val installed = ResponseGroupEdits.install(row(), next)
        assertEquals(ids(next), ids(group(installed)))
        val replaced = next.copy(slots = next.slots.map { s -> s.copy(versions = s.versions.map { v ->
            v.copy(attempts = v.attempts.map { it.copy(requestId = UUID.randomUUID().toString(), usageRecord = null) })
        }) })
        rejected { ResponseGroupEdits.validateTransition(next, replaced) }
        rejected { ResponseGroupEdits.validateTransition(next, next.copy(slots = next.slots.map { s -> s.copy(versions = s.versions.map { it.copy(attempts = it.attempts.dropLast(1)) }) })) }
    }

    @Test fun mismatchedCanonicalMirrorIsExplicitlyInvalidAndCannotOverwriteAValidRow() {
        val original = row()
        val mismatched = HashMap(original).apply { put("message", "unowned sibling text") }
        val result = ResponseGroupCodec.read(mismatched)
        assertTrue(result is ResponseGroupRead.Invalid)
        assertEquals("canonical_mirror", (result as ResponseGroupRead.Invalid).reason)
        assertEquals(original[ResponseGroupCodec.KEY], mismatched[ResponseGroupCodec.KEY])
        rejected { ResponseGroupHistory.validateSavedTransition(listOf(original), listOf(mismatched)) }
    }

    @Test fun immutableAssignmentAndSnapshotCannotBeReplacedByLaterSettings() {
        val g = group()
        rejected { ResponseGroupEdits.validateTransition(g, g.copy(slots = g.slots.map { it.copy(assignment = it.assignment.copy(modelId = "different")) })) }
        rejected { ResponseGroupEdits.validateTransition(g, g.copy(input = g.input.copy(parts = g.input.parts.map { if (it.text == null) it else it.copy(text = "edited later") }))) }
        rejected { ResponseGroupEdits.validateTransition(g, g.copy(slots = g.slots.reversed())) }
        val withSecret = g.copy(slots = g.slots.map { it.copy(assignment = it.assignment.copy(parameters = mapOf("api_key" to "do-not-store"))) })
        rejected { ResponseGroupCodec.encode(withSecret) }
    }

    @Test fun snapshotAttachmentIdentityIsVerifiedWithoutFallbackToCurrentInput() {
        val g = group()
        val bytes = javaClass.classLoader!!.getResourceAsStream("response-groups/document.pdf")!!.readBytes()
        val image = javaClass.classLoader!!.getResourceAsStream("response-groups/image.png")!!.readBytes()
        assertTrue(ResponseGroupEdits.unavailableAttachments(g) { if (it.store == "pdf") bytes else image }.isEmpty())
        assertEquals(2, ResponseGroupEdits.unavailableAttachments(g) { null }.size)
        assertEquals(2, ResponseGroupEdits.unavailableAttachments(g) { "changed".toByteArray() }.size)
    }

    @Test fun usageCountsAllEightAttemptsOnceAndDoesNotEstimateMirror() {
        val row = row()
        val records = TokenUsageAccounting.durableRecordsOf(row)
        assertEquals(8, records.size)
        assertEquals(8, records.map { it.requestId }.distinct().size)
        assertTrue(records.all { it.totalTokens == 5 && it.totalCost == null })
        assertNull(TokenUsageAccounting.legacyRecordOf(row, 0) { error("No mirror estimate") })
        val seeded = UsageLogState(false, emptyList()).seed(listOf(row), 1)
        assertEquals(8, seeded.entries.size)
        assertEquals(seeded, seeded.seed(listOf(row), 2))
        assertEquals(8, seeded.seed(emptyList(), 3).entries.size)
    }

    @Test fun unknownReceiptIsCountedAndLateEvidenceReconcilesByPermanentId() {
        val original = row()
        val g = group(original)
        val slot = g.slots[0]
        val version = slot.versions[0]
        val unknown = g.copy(slots = g.slots.map { s -> if (s != slot) s else s.copy(versions = s.versions.map { v ->
            if (v != version) v else v.copy(attempts = v.attempts.map { it.copy(usageRecord = null) })
        }) })
        val pending = ResponseGroupEdits.install(original - ResponseGroupCodec.KEY, unknown)
        val first = UsageLogState(false, emptyList()).seed(listOf(pending), 1)
        assertEquals(8, first.entries.size)
        val requestId = version.attempts.single().requestId
        assertNull(first.entries.single { it.record.requestId == requestId }.record.totalTokens)
        val enriched = first.seed(listOf(original), 2)
        assertEquals(8, enriched.entries.size)
        assertEquals(5, enriched.entries.single { it.record.requestId == requestId }.record.totalTokens)
        assertEquals(enriched, enriched.seed(listOf(original), 3))
        assertEquals(enriched, enriched.seed(listOf(pending), 3))
        val notYetSeeded = UsageLogState(false, enriched.entries)
        assertEquals(8, notYetSeeded.seed(listOf(original), 4).entries.size)
    }

    @Test fun readableJsonSanitizesNestedAttemptsVersionsAndSnapshotPool() {
        val names = ReadableChatFormats.SpeakerNames("Companion", "User", "Companion")
        val hidden = gson.toJson(ReadableChatFormats.jsonRecords(listOf(row()), names, false))
        assertFalse(hidden.contains("private-thinking"))
        assertFalse(hidden.contains("usageRecord"))
        assertFalse(hidden.contains("inputTokens"))
        assertTrue(hidden.contains("Frozen context"))
        val shown = gson.toJson(ReadableChatFormats.jsonRecords(listOf(row()), names, true))
        assertTrue(shown.contains("private-thinking"))
        assertFalse(shown.contains("usageRecord"))
    }

    @Test fun staleCallbackAndRestoredCopyMustMatchConversationAndOriginalEnvelope() {
        val g = group()
        rejected { ResponseGroupHistory.replace("other-chat", history(), target(), row()[ResponseGroupCodec.KEY] as String, g) }
        rejected { ResponseGroupHistory.replace("chat", history(), target(), "stale", g) }
        // Copy retains every ID but must be addressed as the copied conversation.
        val copy = ResponseGroupHistory.replace("copy", history(), target(chatId = "copy"), row()[ResponseGroupCodec.KEY] as String, g)
        assertEquals(ids(g), ids(group(copy.after)))
        rejected { ResponseGroupHistory.validateConversation(history() + history()) }
    }

    private fun ids(g: ResponseGroup): List<String> = g.slots.flatMap { s -> s.versions.flatMap { v -> v.attempts.map { it.requestId } } }
}
