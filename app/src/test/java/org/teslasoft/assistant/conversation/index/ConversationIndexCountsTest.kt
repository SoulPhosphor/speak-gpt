package org.teslasoft.assistant.conversation.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.memory.librarian.InputTooLongException

/**
 * Step 11 (G.16a, G28; Owner 2026-11-24): the settings status counts only
 * chats whose every eligible message and vector is done, never a chat with a
 * waiting or failed vector, a pending refresh or a change not yet compared;
 * chats not opened since permanent IDs are counted separately; chats with
 * nothing to index are in neither count. The index is the in-memory one that
 * keeps the encrypted store's contract; the SQL itself is covered by
 * `ConversationIndexSettingsInstrumentedTest`.
 */
class ConversationIndexCountsTest {

    private val index = MemoryIndex()
    private val chats = MemoryChats()
    private var tokenLimit = 400

    private val reader = SearchTextReader(
        vectorModel = MODEL, vectorDimensions = DIMENSIONS, hardLimit = 400,
        prefixFor = { "title: $it | text: " }, countModelInput = ::words
    )

    private val vectors = object : PassageVectorReader {
        override val vectorModel = MODEL
        override val vectorDimensions = DIMENSIONS
        override val tokenLimit: Int get() = this@ConversationIndexCountsTest.tokenLimit
        override fun countTokens(modelInput: String) = words(modelInput)
        override fun read(modelInput: String): FloatArray {
            if (countTokens(modelInput) > tokenLimit) throw InputTooLongException(countTokens(modelInput), tokenLimit)
            return FloatArray(DIMENSIONS) { it.toFloat() }
        }
    }

    private val updater = ConversationIndexUpdater(index, reader, 40, 5)
    private val settingsKey get() = updater.settingsKey

    private fun user(id: String, text: String) = IndexSourceMessage(id, false, text, false, null, 100L, null)
    private fun reply(id: String, text: String) =
        IndexSourceMessage(id, true, text, false, MessageCompletionState.DONE, 200L, null)

    private fun index(chatId: String, messages: List<IndexSourceMessage>) {
        chats.put(chatId, messages)
        val version = chats.version(chatId)
        val outcome = updater.update(chatId, "$version|$settingsKey", messages, { "${chats.version(chatId)}|$settingsKey" })
        assertTrue("$outcome", outcome is ConversationIndexUpdater.Outcome.Updated)
    }

    private fun makeVectors() {
        val pause = object : EmbeddingPauseState { override var paused = false }
        PassageEmbeddingWorker(index, chats, reader, vectors, pause).runPass()
    }

    private fun counts(live: Collection<String> = chats.versions.keys, key: String? = settingsKey) =
        ConversationIndexCounting.count(live, index.chatSummaries(MODEL, DIMENSIONS), { chats.version(it) }, key)

    @Test fun aChatCountsOnlyOnceEveryVectorIsMade() {
        index("chat", listOf(user("u1", "I moved to Leeds."), reply("a1", "Welcome to Leeds.")))
        assertEquals(ConversationIndexCounts(0, 0), counts())
        makeVectors()
        assertEquals(ConversationIndexCounts(1, 0), counts())
    }

    @Test fun aFailedVectorIsNotIndexedEvenWhenNothingWaits() {
        index("chat", listOf(user("u1", "short"), reply("a1", (1..120).joinToString(" ") { "wide$it" })))
        tokenLimit = 20
        makeVectors()
        val progress = index.chatProgress("chat")
        assertEquals(0, progress.waiting)
        assertTrue(progress.failed > 0)
        assertFalse(progress.fullyIndexed)
        assertEquals(ConversationIndexCounts(0, 0), counts())
    }

    @Test fun aChatUpdatedAfterAVectorFailedIsStillNotFullyIndexed() {
        index("chat", listOf(user("u1", "short"), reply("a1", (1..120).joinToString(" ") { "wide$it" })))
        tokenLimit = 20
        makeVectors()
        // The finishing step of a later update recounts with the failed row in place.
        index("chat", listOf(user("u1", "short"), reply("a1", (1..120).joinToString(" ") { "wide$it" }), user("u2", "more")))
        assertFalse(index.indexState("chat")!!.fullyIndexed)
        tokenLimit = 400
        makeVectors()
        assertFalse(index.chatProgress("chat").fullyIndexed)
        assertEquals(0, counts().indexed)
    }

    @Test fun chatsNotOpenedSincePermanentIdsAreCountedApartAndNeverAsIndexed() {
        index("old", listOf(user("", "Saved before IDs."), reply("", "Also before IDs.")))
        index("new", listOf(user("u1", "Saved with IDs.")))
        makeVectors()
        assertEquals(2, index.indexState("old")!!.unidentifiedMessages)
        assertEquals(ConversationIndexCounts(indexed = 1, needOpening = 1), counts())
    }

    @Test fun aChatWithNothingToIndexIsInNeitherCount() {
        index("empty", emptyList())
        index("hidden", listOf(IndexSourceMessage("u1", false, "Hidden from the AI.", true, null, 100L, null)))
        // A message without an ID that would not be indexed anyway is not waiting for opening.
        index("hiddenOld", listOf(IndexSourceMessage("", false, "Hidden and old.", true, null, 100L, null)))
        makeVectors()
        assertEquals(ConversationIndexCounts(0, 0), counts())
    }

    @Test fun aChatChangedSinceItWasIndexedIsNotCountedUntilComparedAgain() {
        index("chat", listOf(user("u1", "First.")))
        makeVectors()
        assertEquals(1, counts().indexed)
        chats.put("chat", listOf(user("u1", "First."), user("u2", "Second.")))
        assertEquals(0, counts().indexed)
    }

    @Test fun aChatBeingRefreshedOrDeletedIsNotCounted() {
        index("chat", listOf(user("u1", "First.")))
        index("gone", listOf(user("u1", "Gone soon.")))
        makeVectors()
        assertEquals(2, counts().indexed)
        index.markNeedsRefresh("chat")
        assertEquals(1, counts().indexed)
        assertEquals(0, counts(live = listOf("chat")).indexed)
    }

    @Test fun anotherModelOrCuttingSettingsAreNotCountedAsIndexed() {
        index("chat", listOf(user("u1", "First.")))
        makeVectors()
        assertEquals(0, counts(key = "cut-t99|o5|other").indexed)
        val other = ConversationIndexCounting.count(listOf("chat"), index.chatSummaries("other-model", DIMENSIONS), { chats.version(it) }, settingsKey)
        assertEquals(0, other.indexed)
    }

    @Test fun withNoModelInstalledAChatCountsByTheModelItWasIndexedWith() {
        index("chat", listOf(user("u1", "First.")))
        makeVectors()
        val noModel = ConversationIndexCounting.count(listOf("chat"), index.chatSummaries("", 0), { chats.version(it) }, null)
        assertEquals(ConversationIndexCounts(1, 0), noModel)
    }

    @Test fun theSettingsKeyWithoutATokenizerIsTheUpdatersKey() {
        val model = org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModels.ALL.first {
            it.documentPrefix.contains("title: none")
        }
        val real = SearchTextReader(
            vectorModel = model.embeddingTag, vectorDimensions = model.dimensions, hardLimit = model.storedTextMaxTokens,
            prefixFor = { title -> model.documentPrefix.replaceFirst("title: none", "title: $title") },
            countModelInput = ::words
        )
        assertEquals(real.key, SearchTextReader.keyFor(model))
        val target = ConversationIndexSettings.TARGET_TOKENS.coerceAtMost(model.storedTextMaxTokens)
        assertEquals(
            ConversationIndexUpdater(MemoryIndex(), real, target, ConversationIndexSettings.OVERLAP_WORDS).settingsKey,
            ConversationIndexUpdater.settingsKey(target, ConversationIndexSettings.OVERLAP_WORDS, SearchTextReader.keyFor(model))
        )
    }

    private companion object {
        const val MODEL = "count-model"
        const val DIMENSIONS = 4
        fun words(text: String) = text.split(Regex("\\s+")).count { it.isNotEmpty() } + 2
    }
}
