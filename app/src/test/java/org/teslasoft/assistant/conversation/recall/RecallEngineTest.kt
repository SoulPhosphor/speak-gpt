/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.recall

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.conversation.index.IndexFingerprint
import org.teslasoft.assistant.conversation.index.IndexSourceMessage
import org.teslasoft.assistant.conversation.index.Speaker
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.memory.librarian.KeywordText
import org.teslasoft.assistant.preferences.memory.librarian.VectorMath

/**
 * Retrieval stages 1 to 8 (memory development plan G.6) and the G36 contract,
 * against a small in-memory index. Each message is one passage; its vector is
 * chosen by the test, so every gate is exercised with exact numbers.
 */
class RecallEngineTest {

    private val model = "model-4"
    private val dims = 7

    /** A unit vector whose similarity with the direction [axis] is exactly [sim]; the rest lies on [other]. */
    private fun at(sim: Double, other: Int = 1, axis: Int = 0): FloatArray {
        val v = FloatArray(dims)
        v[axis] = sim.toFloat()
        v[other] = kotlin.math.sqrt(1 - sim * sim).toFloat()
        return v
    }

    private fun axis(i: Int) = FloatArray(dims).also { it[i] = 1f }

    /** The message's direction. Filler passages lie on axes 1 to 3. */
    private val Q = axis(0)
    /** The API helper's query direction. */
    private val H = axis(4)
    /** A message unrelated to everything in the chat. */
    private val U = axis(5)
    /** The dark-theme subject. */
    private val T = axis(6)

    private inner class Chat(val id: String = "chat") {
        val messages = ArrayList<IndexSourceMessage>()
        val vectors = HashMap<String, FloatArray?>()
        /** Messages as the index saw them, when different from [messages] (an index that lags behind). */
        val indexedText = HashMap<String, String>()
        var otherModel = HashSet<String>()
        var readable = true
        /** Runs when the engine asks whether the chat changed (just before handing results on). */
        var onRecheck: (() -> Unit)? = null

        fun user(id: String, text: String, time: Long? = null, hidden: Boolean = false) =
            add(IndexSourceMessage(id, false, text, hidden, null, time, null))

        fun ai(id: String, text: String, state: String = MessageCompletionState.DONE, hidden: Boolean = false) =
            add(IndexSourceMessage(id, true, text, hidden, state, null, null))

        private fun add(m: IndexSourceMessage): Chat { messages.add(m); return this }

        fun vector(id: String, v: FloatArray?): Chat { vectors[id] = v; return this }

        val index = object : RecallIndex {
            private fun rows(): List<Pair<RecallPassage, IndexSourceMessage>> {
                var exchange: String? = null
                return messages.mapIndexedNotNull { i, m ->
                    if (!m.isBot) exchange = m.messageId
                    val text = indexedText[m.messageId] ?: m.modelText
                    val eligible = !m.hiddenFromAi && (!m.isBot || MessageCompletionState.isComplete(m.completionState))
                    if (!eligible && m.messageId !in indexedText) return@mapIndexedNotNull null
                    RecallPassage(i + 1L, m.messageId, 0, 0, text.length, exchange, Speaker.of(m.isBot),
                        if (m.isBot) "Companion" else "Human", m.timeMillis, i, IndexFingerprint.of(text),
                        if (m.messageId in otherModel) "other" else model, dims) to m.copy(modelText = text)
                }
            }
            override fun passages(chatId: String) = if (chatId == id) rows().map { it.first } else emptyList()
            override fun rowsWithTerm(chatId: String, term: String) =
                rows().filter { (_, m) -> term in KeywordText.terms(m.modelText).toSet() }.map { it.first.rowId }.toSet()
            override fun vectors(chatId: String, vectorModel: String, vectorDimensions: Int, afterRowId: Long, limit: Int) =
                rows().filter { (p, _) -> p.rowId > afterRowId && p.vectorModel == vectorModel && vectors[p.messageId] != null }
                    .take(limit).map { (p, _) -> RecallIndex.RowVector(p.rowId, VectorMath.normalize(vectors.getValue(p.messageId)!!)) }
        }

        /** Changes whenever any message changes, as the chat's stored version does. */
        private fun version() = messages.hashCode().toString()

        val source = object : RecallMessageSource {
            override fun read(chatId: String) = if (readable) ChatSnapshot(ArrayList(messages), version()) else null
            override fun changedSince(chatId: String, snapshot: ChatSnapshot): Boolean {
                onRecheck?.let { onRecheck = null; it() }
                return !readable || version() != snapshot.version
            }
        }
    }

    private fun request(
        text: String, mode: RecallMode = RecallMode.OFF, vector: FloatArray? = Q,
        sent: SentInFull = SentInFull.Messages(emptySet()), helper: String? = null, helperVector: FloatArray? = null,
        carried: List<String> = emptyList(), subjectChanged: Boolean? = null, current: String? = null,
        now: Long = 0, filter: List<Set<String>>? = null, pieceVectors: Map<String, FloatArray> = emptyMap()
    ) = RecallRequest("chat", text, current, now, ZoneOffset.UTC, model, dims, sent, vector, pieceVectors, mode, helper,
        helperVector, carried, subjectChanged, filter)

    private fun search(chat: Chat, req: RecallRequest, tuning: RecallTuning = RecallTuning(), reader: QueryVectorReader? = null,
                       budget: RecallBudget = RecallBudget.unlimited()): RecallResult =
        RecallEngine(chat.index, chat.source, reader, tuning).search(req, budget)

    private fun ids(r: RecallResult) = (r as RecallResult.Found).exchanges.map { it.exchangeId }

    /** A chat of [n] filler exchanges whose similarities to [Q] spread from 0.10 to 0.30. */
    private fun filler(chat: Chat, n: Int = 12): Chat {
        for (i in 0 until n) {
            chat.user("u$i", "filler question number $i about weather").vector("u$i", at(0.10 + 0.2 * i / n, 1 + i % 3))
            chat.ai("a$i", "filler answer $i").vector("a$i", at(0.12 + 0.2 * i / n, 1 + (i + 1) % 3))
        }
        return chat
    }

    @Test fun nothingQualifiesIsAnEmptyResultNotTheClosestExchange() {
        val chat = filler(Chat())
        val r = search(chat, request("tell me about penguins"))
        assertEquals(emptyList<String>(), ids(r))
    }

    @Test fun aPassageThatStandsOutPassesByMeaningAndBringsItsWholeExchange() {
        val chat = filler(Chat()).user("x", "we chose cedar for the raised beds").vector("x", at(0.55))
            .ai("xa", "cedar resists rot").vector("xa", at(0.2))
        val r = search(chat, request("why that wood")) as RecallResult.Found
        assertEquals(listOf("x"), r.exchanges.map { it.exchangeId })
        assertEquals(listOf("x", "xa"), r.exchanges.single().messages.map { it.messageId })
        assertTrue(r.exchanges.single().best!!.passedMeaning)
    }

    @Test fun typicalSimilarityIsMeasuredOverEveryPassageSoAOneSubjectChatNeedsAClearlyCloserPassage() {
        // The same 0.52 passage passes in a chat about other things and fails in a chat that is close throughout.
        val varied = filler(Chat()).user("near", "garden note near").vector("near", at(0.52))
        assertEquals(listOf("near"), ids(search(varied, request("garden"))))
        val chat = Chat()
        for (i in 0 until 12) chat.user("u$i", "garden note $i").vector("u$i", at(0.45 + 0.01 * (i % 4), 1 + i % 3))
        chat.user("near", "garden note near").vector("near", at(0.52))
        chat.user("close", "garden note close").vector("close", at(0.57))
        assertEquals(listOf("close"), ids(search(chat, request("garden"))))
    }

    @Test fun aCertainMatchPassesEvenWhenNothingStandsOut() {
        val chat = Chat()
        for (i in 0 until 12) chat.user("u$i", "note $i").vector("u$i", at(0.58 + 0.005 * (i % 3), 1 + i % 3))
        chat.user("sure", "note sure").vector("sure", at(0.62))
        val r = search(chat, request("anything")) as RecallResult.Found
        assertEquals(listOf("sure"), r.exchanges.map { it.exchangeId })
        assertTrue(r.exchanges.single().best!!.passedCertain)
        assertFalse(r.exchanges.single().best!!.passedMeaning)
    }

    @Test fun oneCommonSharedWordDoesNotPassButARareNameDoes() {
        val chat = filler(Chat())
        for (i in 0 until 6) chat.user("two$i", "two seedlings in cell $i").vector("two$i", at(0.2, 2))
        chat.user("pepper", "Pepper the cat sleeps on the windowsill").vector("pepper", at(0.2, 3))
        assertEquals(emptyList<String>(), ids(search(chat, request("why those two"))))
        assertEquals(listOf("pepper"), ids(search(chat, request("pepper"))))
        // With the rarity rule off (the approved starting values), the common word passed.
        assertEquals(6, ids(search(chat, request("why those two"), RecallTuning.APPROVED_STARTING)).size)
    }

    @Test fun anAccidentalWordInALongerMessageDoesNotReachTheKeywordShare() {
        val chat = filler(Chat()).user("k", "the lighthouse keeper").vector("k", at(0.1))
        val r = search(chat, request("I walked along the river and saw a heron, a kingfisher, an old mill and the lighthouse"))
        assertEquals(emptyList<String>(), ids(r))
    }

    @Test fun aPassageWaitingForItsVectorIsNotFoundByMeaningAndIsReportedAsWaiting() {
        val chat = filler(Chat()).user("w", "we chose cedar").vector("w", null)
        val r = search(chat, request("why that wood")) as RecallResult.Found
        assertTrue(r.exchanges.isEmpty())
        assertEquals(1, r.report.passagesWithoutVector)
    }

    @Test fun anotherModelsVectorsAreNeverCompared() {
        val chat = filler(Chat()).user("o", "we chose cedar").vector("o", at(0.9))
        chat.otherModel.add("o")
        assertEquals(emptyList<String>(), ids(search(chat, request("why that wood"))))
    }

    @Test fun withNoModelOnlyKeywordsRun() {
        val chat = filler(Chat()).user("c", "Cherokee Purple and Sungold tomatoes").vector("c", at(0.9))
        val r = search(chat, request("Cherokee Purple tomatoes").copy(vectorModel = null, messageVector = null)) as RecallResult.Found
        assertEquals(listOf("c"), r.exchanges.map { it.exchangeId })
        assertEquals(null, r.exchanges.single().best!!.similarity)
    }

    @Test fun anEditedMessageIsFoundOnlyByItsNewTextAndANotYetUpdatedIndexNeverServesTheOldText() {
        val chat = filler(Chat()).user("e", "Should the recipe list sort newest first?").vector("e", at(0.9))
        chat.indexedText["e"] = "Should we use Firebase for sign-in?"
        val r = search(chat, request("firebase sign-in")) as RecallResult.Found
        assertTrue(r.exchanges.isEmpty())
        assertEquals(1, r.report.outdatedPassages)
    }

    @Test fun hiddenDeletedAndUnfinishedMessagesAreNeverRecalledNorTheirExchanges() {
        val chat = filler(Chat())
            .user("h", "surprise party for Jonah", hidden = true).vector("h", at(0.9))
            .ai("ha", "a surprise party for Jonah sounds fun").vector("ha", at(0.9))
            .user("f", "turn a recipe into a PDF").vector("f", at(0.1))
            .ai("fa", "PDF export of the surprise party recipe", MessageCompletionState.FAILED).vector("fa", at(0.9))
        chat.indexedText["fa"] = "PDF export of the surprise party recipe"
        val r = search(chat, request("surprise party for Jonah")) as RecallResult.Found
        assertTrue(r.exchanges.isEmpty())
    }

    @Test fun aMessageEditedOrDeletedWhileTheSearchRunsIsLeftOutBeforeResultsAreHandedOn() {
        val chat = filler(Chat()).user("x", "we chose cedar").vector("x", at(0.9)).ai("xa", "cedar it is").vector("xa", at(0.2))
        chat.onRecheck = {
            val i = chat.messages.indexOfFirst { it.messageId == "xa" }
            chat.messages[i] = chat.messages[i].copy(modelText = "steel it is")
        }
        val edited = search(chat, request("which wood")) as RecallResult.Found
        assertTrue(edited.exchanges.isEmpty())
        assertTrue(edited.report.excluded.any { it.exchangeId == "x" && it.reason == ExclusionReason.NO_LONGER_ELIGIBLE })

        val deleted = filler(Chat()).user("x", "we chose cedar").vector("x", at(0.9))
        deleted.onRecheck = { deleted.messages.removeAll { it.messageId == "x" } }
        assertEquals(emptyList<String>(), ids(search(deleted, request("which wood"))))

        val hidden = filler(Chat()).user("x", "we chose cedar").vector("x", at(0.9))
        hidden.onRecheck = { hidden.messages[hidden.messages.indexOfFirst { it.messageId == "x" }] = hidden.messages.last().copy(hiddenFromAi = true) }
        assertEquals(emptyList<String>(), ids(search(hidden, request("which wood"))))
    }

    @Test fun aChatThatCannotBeReadNowIsSkippedNotSearchedFromTheIndexAlone() {
        val chat = filler(Chat()).user("x", "cedar").vector("x", at(0.9))
        chat.readable = false
        val r = search(chat, request("cedar"))
        assertEquals(SkipReason.CHAT_UNAVAILABLE, (r as RecallResult.Skipped).reason)
    }

    @Test fun anIndexThatCannotBeReadMidSearchHandsOnNothingInsteadOfFailingTheSend() {
        val chat = filler(Chat()).user("x", "cedar").vector("x", at(0.9))
        val closing = object : RecallIndex by chat.index {
            override fun vectors(chatId: String, vectorModel: String, vectorDimensions: Int, afterRowId: Long, limit: Int): List<RecallIndex.RowVector> =
                throw IllegalStateException("attempt to re-open an already-closed object")
        }
        val r = RecallEngine(closing, chat.source, null).search(request("cedar"), RecallBudget.unlimited())
        assertEquals(SkipReason.INDEX_UNAVAILABLE, (r as RecallResult.Skipped).reason)
    }

    @Test fun theCurrentMessageAndWhatTheAiAlreadyReadsAreNeverRecalled() {
        val chat = filler(Chat()).user("old", "we chose cedar").vector("old", at(0.9))
            .user("recent", "cedar again").vector("recent", at(0.9))
            .user("now", "why cedar").vector("now", at(0.9))
        assertEquals(listOf("old", "recent"), ids(search(chat, request("why cedar", current = "now"))).sorted())
        assertEquals(listOf("old"), ids(search(chat, request("why cedar", current = "now", sent = SentInFull.Messages(setOf("recent"))))))
        assertEquals(listOf("old"), ids(search(chat, request("why cedar", sent = SentInFull.LastExchanges(2)))))
    }

    @Test fun manyMatchingPassagesOfOneExchangeCountOnceAndALaterShortCorrectionIsKept() {
        val chat = filler(Chat())
        chat.user("long", "tell me about quartz").vector("long", at(0.30))
        for (i in 0 until 8) chat.ai("q$i", "quartz countertop detail $i").vector("q$i", at(0.70 - 0.01 * i, 1 + i % 3))
        chat.user("fix", "forget quartz, butcher block instead").vector("fix", at(0.62))
        val tuning = RecallTuning(exchangesPerMethod = 2)
        val r = search(chat, request("which countertop"), tuning) as RecallResult.Found
        assertEquals(setOf("long", "fix"), r.exchanges.map { it.exchangeId }.toSet())
        val long = r.exchanges.first { it.exchangeId == "long" }
        assertEquals(8, long.matchingPassages)
        assertTrue(long.supporting.size <= tuning.supportingPassages)
        // The extra-passage addition is capped, so the long exchange cannot outrun everything by length alone.
        assertTrue(long.score <= long.best!!.score + tuning.extraPassageBonus * tuning.maxExtraPassages + 1e-9)
    }

    @Test fun identicalExchangesCountOnceKeepingTheLaterButDifferentWordingIsKept() {
        val chat = filler(Chat())
            .user("r1", "Remind me to measure the window.").vector("r1", at(0.9)).ai("r1a", "Noted.").vector("r1a", at(0.1))
            .user("w", "I am waiting for the permit reply.").vector("w", at(0.9)).ai("wa", "Okay.").vector("wa", at(0.1))
            .user("r2", "Remind me  to measure the window. ").vector("r2", at(0.9)).ai("r2a", "Noted.").vector("r2a", at(0.1))
            .user("g", "The permit reply came: approved.").vector("g", at(0.9)).ai("ga", "Great.").vector("ga", at(0.1))
        val r = search(chat, request("window and permit")) as RecallResult.Found
        assertEquals(setOf("r2", "w", "g"), r.exchanges.map { it.exchangeId }.toSet())
        assertTrue(r.report.excluded.any { it.exchangeId == "r1" && it.reason == ExclusionReason.REPEAT })
    }

    @Test fun aLongMessageIsAlsoSearchedInPiecesAndAPieceNeedsItsOwnSimilarityAndASharedWord() {
        val chat = filler(Chat()).user("allergy", "Nora is allergic to walnuts").vector("allergy", H)
        val long = (1..120).joinToString(" ") { "word$it" } + " and remember Nora's walnuts"
        val pieces = org.teslasoft.assistant.preferences.memory.librarian.LongMessageSearch.cut(long, 80)
        assertTrue(pieces.size > 1)
        val vectors = pieces.associate { it.text to if ("walnuts" in it.text) at(0.5, 5, axis = 4) else U }
        val r = search(chat, request(long, vector = U, pieceVectors = vectors)) as RecallResult.Found
        assertEquals(listOf("allergy"), r.exchanges.map { it.exchangeId })
        assertEquals(QueryKind.PIECE, r.exchanges.single().best!!.query)
        // Without a shared word the same similarity is not enough for a piece.
        val noWord = filler(Chat()).user("allergy", "an unrelated sentence").vector("allergy", H)
        assertEquals(emptyList<String>(), ids(search(noWord, request(long, vector = U, pieceVectors = vectors))))
    }

    @Test fun apiAiAddsTheHelpersQueryAfterTheMessagesOwnResultsAndOffIgnoresIt() {
        val chat = filler(Chat())
            .user("own", "the dark theme colors").vector("own", at(0.9))
            .user("coil", "image loading with Coil").vector("coil", at(0.9, 5, axis = 4))
        val helper = H
        val api = search(chat, request("dark theme", RecallMode.API_AI, helper = "why Coil for images", helperVector = helper)) as RecallResult.Found
        assertEquals(listOf("own", "coil"), api.exchanges.map { it.exchangeId })
        assertEquals(listOf(RecallOrigin.OWN, RecallOrigin.HELPER), api.exchanges.map { it.origin })
        assertEquals(listOf("own"), ids(search(chat, request("dark theme", RecallMode.OFF, helper = "why Coil for images", helperVector = helper))))
        // A failed helper gives no query: the message's own results only, nothing kept from before.
        val failed = search(chat, request("dark theme", RecallMode.API_AI, helper = null, carried = listOf("coil"))) as RecallResult.Found
        assertEquals(listOf("own"), failed.exchanges.map { it.exchangeId })
        assertEquals(FollowUpDecision.NOT_CODE_BASED, failed.report.followUp)
    }

    private fun followUpChat(): Chat = filler(Chat())
        .user("coil", "Which image loading library should we use for recipe photos?").vector("coil", H)
        .ai("coila", "Coil, because it is small and works with coroutines.").vector("coila", H)
        .user("theme", "What colors should the dark theme use?").vector("theme", T)

    @Test fun codeBasedCarriesTheDeliveredExchangeForARealFollowUp() {
        val r = search(followUpChat(), request("Why did we go with that one over the others?", RecallMode.CODE_BASED, vector = U,
            carried = listOf("coil"))) as RecallResult.Found
        assertEquals(listOf("coil"), r.exchanges.map { it.exchangeId })
        assertEquals(RecallOrigin.CARRIED, r.exchanges.single().origin)
        assertEquals(FollowUpDecision.CHECKED, r.report.followUp)
        // Words of its own that the carried exchange holds keep it a follow-up.
        val named = search(followUpChat(), request("Is Coil fine for the recipe photos?", RecallMode.CODE_BASED, vector = U,
            carried = listOf("coil"))) as RecallResult.Found
        assertEquals(listOf("coil"), named.exchanges.map { it.exchangeId })
    }

    @Test fun codeBasedNeverCarriesForAnUnrelatedRequestEvenWithoutAnySavedMemorySubject() {
        for (message in listOf("Tell me a joke about penguins.", "Can you recommend a good podcast for my commute?",
            "How do I sync my calendar with my car?")) {
            val r = search(followUpChat(), request(message, RecallMode.CODE_BASED, vector = U, carried = listOf("coil"),
                subjectChanged = null)) as RecallResult.Found
            assertEquals(message, emptyList<String>(), r.exchanges.map { it.exchangeId })
            assertEquals(false, r.report.carriedChecks.single().carried)
        }
    }

    @Test fun codeBasedCarriesNothingWhenTheSubjectChangedOrTheMessageFoundItsOwnOrIsLong() {
        val changed = search(followUpChat(), request("and then?", RecallMode.CODE_BASED, vector = U,
            carried = listOf("coil"), subjectChanged = true)) as RecallResult.Found
        assertEquals(FollowUpDecision.SUBJECT_CHANGED, changed.report.followUp)
        assertTrue(changed.exchanges.isEmpty())

        // New confidently relevant results take priority over the carried exchange.
        val own = search(followUpChat(), request("dark theme colors", RecallMode.CODE_BASED, vector = T, carried = listOf("coil"))) as RecallResult.Found
        assertEquals(listOf("theme"), own.exchanges.map { it.exchangeId })
        assertEquals(FollowUpDecision.OWN_RESULTS_FOUND, own.report.followUp)

        val long = search(followUpChat(), request((1..30).joinToString(" ") { "and" }, RecallMode.CODE_BASED,
            vector = U, carried = listOf("coil"))) as RecallResult.Found
        assertEquals(FollowUpDecision.TOO_LONG, long.report.followUp)
        assertTrue(long.exchanges.isEmpty())

        val off = search(followUpChat(), request("and then?", RecallMode.OFF, vector = U, carried = listOf("coil"))) as RecallResult.Found
        assertTrue(off.exchanges.isEmpty())
    }

    @Test fun aCarriedExchangeIsCheckedAgainstTheChatAsStoredNow() {
        val hidden = followUpChat()
        hidden.messages[hidden.messages.indexOfFirst { it.messageId == "coila" }] =
            hidden.messages.first { it.messageId == "coila" }.copy(hiddenFromAi = true)
        val r = search(hidden, request("and then?", RecallMode.CODE_BASED, vector = U, carried = listOf("coil"))) as RecallResult.Found
        assertTrue(r.exchanges.isEmpty())

        val gone = followUpChat()
        gone.messages.removeAll { it.messageId == "coil" || it.messageId == "coila" }
        assertTrue((search(gone, request("and then?", RecallMode.CODE_BASED, vector = U, carried = listOf("coil"))) as RecallResult.Found).exchanges.isEmpty())

        val inFull = search(followUpChat(), request("and then?", RecallMode.CODE_BASED, vector = U, carried = listOf("coil"),
            sent = SentInFull.Messages(setOf("coila")))) as RecallResult.Found
        assertTrue(inFull.exchanges.isEmpty())

        val edited = followUpChat()
        edited.onRecheck = {
            val i = edited.messages.indexOfFirst { it.messageId == "coila" }
            edited.messages[i] = edited.messages[i].copy(modelText = "Glide instead.")
        }
        assertTrue((search(edited, request("and then?", RecallMode.CODE_BASED, vector = U, carried = listOf("coil"))) as RecallResult.Found).exchanges.isEmpty())
    }

    @Test fun aTimeReferenceFavoursPassagesInsideItButNeverMakesOnePass() {
        val day = 86_400_000L
        val chat = filler(Chat())
            .user("march", "planted peas", time = 70 * day).vector("march", at(0.55))
            .user("may", "planted beans", time = 130 * day).vector("may", at(0.56))
            .user("weak", "planted nothing much", time = 72 * day).vector("weak", at(0.2))
        val now = 140 * day // 1970-05-21
        val r = search(chat, request("what did I plant in March", now = now)) as RecallResult.Found
        assertEquals(listOf("march", "may"), r.exchanges.map { it.exchangeId })
        assertTrue(r.exchanges.first().inTimeRange)
        assertFalse(r.exchanges.any { it.exchangeId == "weak" })
    }

    @Test fun outOfTimeHandsOnNothingAndOptionalWorkDoesNotStartWithoutEnoughTimeLeft() {
        var now = 0L
        val clock = RecallClock { now }
        val chat = filler(Chat()).user("x", "cedar").vector("x", at(0.9))
        val slowReader = QueryVectorReader { _, _ -> now += 2_000_000_000L; Q }
        val r = search(chat, request("cedar", vector = null), reader = slowReader,
            budget = RecallBudget(1_500, null, 300, clock))
        assertEquals(SkipReason.OUT_OF_TIME, (r as RecallResult.Skipped).reason)

        now = 0L
        val shared = PreSendDeadline(1_000, clock, 0L)
        now = 800_000_000L // 200 ms of the shared allowance left: no optional work
        val long = (1..200).joinToString(" ") { "word$it" }
        val tight = search(chat, request(long, RecallMode.API_AI, helper = "cedar", helperVector = Q),
            budget = RecallBudget(1_500, shared, 300, clock)) as RecallResult.Found
        assertTrue(tight.report.queriesSkipped.any { it.first == QueryKind.PIECE && it.second == "no time" })
        assertTrue(tight.report.queriesSkipped.any { it.first == QueryKind.HELPER && it.second == "no time" })
        assertEquals(listOf(QueryKind.WHOLE to 0), tight.report.queriesRun)
    }

    @Test fun theIdentityFilterKeepsOnlyPassagesRecordedUnderOneIdOfEveryNamedGroup() {
        val chat = Chat()
        for (i in 0 until 24) chat.user("u$i", "note $i").vector("u$i", at(0.40 + 0.01 * (i % 4), 1 + i % 3))
        chat.user("x", "the sailing trip").vector("x", at(0.9)).user("y", "the pottery class").vector("y", at(0.9))
            .user("z", "a quiet note").vector("z", at(0.44))
        val index = chat.index
        val filtered = object : RecallIndex by index {
            override fun passages(chatId: String) = index.passages(chatId).map {
                it.copy(identityIds = when (it.messageId) {
                    "x", "z" -> setOf("mara", "juniper")
                    else -> setOf("ivy", "juniper")
                })
            }
        }
        val r = RecallEngine(filtered, chat.source, null).search(
            request("trip", filter = listOf(setOf("mara"), setOf("juniper"))), RecallBudget.unlimited()) as RecallResult.Found
        assertEquals(listOf("x"), r.exchanges.map { it.exchangeId })
        // Typical similarity is still measured over the whole chat: the filter leaves two passages,
        // but the 0.45 passage does not pass as it would under the small-chat rule.
        assertEquals(27, r.report.passagesWithVectors)
        assertTrue(r.report.excluded.any { it.exchangeId == "y" && it.reason == ExclusionReason.IDENTITY_FILTER })
    }

    // ---- Review findings (2026-11-26) ----

    @Test fun everyWordTheChatHoldsWeighsInALongMessageSoAFewSharedRareWordsAreNotEnough() {
        val chat = Chat()
        val common = (1..100).map { "common$it" }
        for (i in 0 until 30) chat.user("u$i", common.filterIndexed { j, _ -> (j + i) % 6 != 0 }.joinToString(" ")).vector("u$i", null)
        chat.user("t", "alpha bravo charlie delta").vector("t", null)
        val message = "alpha bravo charlie delta " + common.joinToString(" ")
        val r = search(chat, request(message, vector = null)) as RecallResult.Found
        assertFalse(r.exchanges.any { it.exchangeId == "t" })
    }

    @Test fun aRepeatedPermanentIdIsCheckedAgainstTheCopyThatWasSearched() {
        // The same permanent ID stored twice: the index keeps the first eligible copy, as the recheck must.
        val chat = filler(Chat()).user("dup", "we chose cedar").vector("dup", at(0.9)).ai("dupa", "cedar it is").vector("dupa", at(0.2))
            .user("other", "something else").vector("other", at(0.1)).ai("dup", "a later copy with other words")
        // Another save lands during the search, so the chat is read again before handing on.
        chat.onRecheck = { chat.ai("late", "an unrelated reply saved meanwhile") }
        val r = search(chat, request("which wood")) as RecallResult.Found
        assertEquals(listOf("dup"), r.exchanges.map { it.exchangeId })
    }

    @Test fun anIdenticalEarlierExchangeIsKeptWhenTheLaterCopyFailsItsFinalCheck() {
        val chat = filler(Chat())
            .user("r1", "Remind me to measure the window.").vector("r1", at(0.9)).ai("r1a", "Noted.").vector("r1a", at(0.1))
            .user("r2", "Remind me to measure the window.").vector("r2", at(0.9)).ai("r2a", "Noted.").vector("r2a", at(0.1))
        chat.onRecheck = {
            val i = chat.messages.indexOfFirst { it.messageId == "r2a" }
            chat.messages[i] = chat.messages[i].copy(modelText = "Measured: 90 centimetres.")
        }
        val r = search(chat, request("window")) as RecallResult.Found
        assertEquals(listOf("r1"), r.exchanges.map { it.exchangeId })
    }

    @Test fun theLastExchangesTheAiReadsAreCountedWithoutTheCurrentMessageOrExchangesItCannotRead() {
        val chat = filler(Chat(), 6).user("old", "cedar beds").vector("old", at(0.9))
            .user("e1", "cedar again").vector("e1", at(0.9))
            .user("e2", "cedar once more").vector("e2", at(0.9))
            .user("e3", "cedar still").vector("e3", at(0.9))
            .user("gone", "cedar hidden", hidden = true)
            .user("now", "why cedar").vector("now", at(0.9))
        val r = search(chat, request("why cedar", current = "now", sent = SentInFull.LastExchanges(3)))
        assertEquals(listOf("old"), ids(r))
    }

    @Test fun aReaderThatFailsLeavesKeywordsAndASourceThatFailsSkipsRecallButNeitherFailsTheSend() {
        val chat = filler(Chat()).user("c", "Cherokee Purple tomatoes").vector("c", at(0.9))
        val failing = QueryVectorReader { _, _ -> throw IllegalStateException("model released") }
        val r = search(chat, request("Cherokee Purple tomatoes", vector = null), reader = failing) as RecallResult.Found
        assertEquals(listOf("c"), r.exchanges.map { it.exchangeId })
        val broken = object : RecallMessageSource by chat.source {
            override fun read(chatId: String): ChatSnapshot? = throw IllegalStateException("locked")
        }
        val s = RecallEngine(chat.index, broken, null).search(request("cedar"), RecallBudget.unlimited())
        assertEquals(SkipReason.CHAT_UNAVAILABLE, (s as RecallResult.Skipped).reason)
    }

    @Test fun aLongMessageLooksUpABoundedNumberOfWords() {
        val chat = filler(Chat())
        var lookups = 0
        val counting = object : RecallIndex by chat.index {
            override fun rowsWithTerm(chatId: String, term: String): Set<Long> { lookups++; return chat.index.rowsWithTerm(chatId, term) }
        }
        val message = (1..600).joinToString(" ") { "distinctword$it" }
        RecallEngine(counting, chat.source, null).search(request(message, vector = null), RecallBudget.unlimited())
        assertTrue("looked up $lookups words", lookups <= RecallTuning().maxTermLookups)
    }
}
