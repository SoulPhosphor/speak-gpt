package org.teslasoft.assistant.conversation.index

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.preferences.MessageVisibility
import org.teslasoft.assistant.preferences.includes.ChatInclude
import org.teslasoft.assistant.preferences.includes.IncludeForm
import org.teslasoft.assistant.preferences.includes.IncludeKind
import org.teslasoft.assistant.preferences.includes.SummarizerSafeIncludeProjectionBuilder
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.scan.ScanMessageScope
import org.teslasoft.assistant.ui.adapters.chat.ChatAdapter
import org.teslasoft.assistant.ui.chat.ChatTurnMemoryScope
import org.teslasoft.assistant.ui.chat.ModelFacingMessageText

/**
 * The saved-chat reader's conversion (Step 10; G.5a rules 1, 7 and 9; G.7):
 * the text it indexes is the text the chat sends for the same messages, built
 * by the same functions the chat screen uses; scope comes from the message;
 * and the hookups are inert while indexing is off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SavedChatIndexReaderTest {

    private val context = RuntimeEnvironment.getApplication()

    @After fun off() {
        ConversationIndexSetting.overrideForTest = null
    }

    private fun row(id: String?, isBot: Boolean, text: String, vararg extra: Pair<String, Any>): HashMap<String, Any> =
        hashMapOf<String, Any>("isBot" to isBot, "message" to text, ChatAdapter.KEY_MESSAGE_TIME to "1700000000000").apply {
            if (id != null) put(MessageIdentity.KEY, id)
            putAll(extra)
        }

    private val include = ChatInclude(
        id = "include-1", fileName = "notes.txt", kind = IncludeKind.entries.first { !it.isImage() },
        form = IncludeForm.entries.first(), fullText = "The attached notes."
    )

    private fun chat(): List<HashMap<String, Any>> = listOf(
        row("u1", false, "I moved to Leeds."),
        row("a1", true, "Welcome to Leeds.", MessageCompletionState.KEY_STATE to MessageCompletionState.DONE),
        row("u2", false, "Please forget this one.", MessageVisibility.KEY to true),
        row(
            "a2", true, "The second version.",
            MessageCompletionState.KEY_STATE to MessageCompletionState.DONE,
            ChatAdapter.KEY_VARIANTS to ChatAdapter.variantsToJson(listOf(
                hashMapOf("message" to "The first version."),
                hashMapOf("message" to "The second version.")
            )),
            ChatAdapter.KEY_CANONICAL_VARIANT to 1
        ),
        row("card", true, "", ChatAdapter.KEY_IMAGE_CONFIRMATION to true),
        row("u3", false, "Read my notes.", "includes" to ChatInclude.listToJson(listOf(include)))
    )

    @Test fun theIndexedTextIsTheTextTheChatSendsForTheSameMessages() {
        val stored = chat()
        val read = IndexSourceMessages.fromStored(context, stored)

        // The image confirmation card is not a stored message for the AI.
        assertEquals(listOf("u1", "a1", "u2", "a2", "u3"), read.map { it.messageId })

        // Exactly the chat screen's Summarizer snapshot (the model-facing text Step 8 indexes).
        val snapshot = ModelFacingMessageText.summarizerEntries(context, stored).map { it.second }
        assertEquals(snapshot.map { it.id }, read.map { it.messageId })
        assertEquals(snapshot.map { it.text }, read.map { it.modelText })

        // And the words the chat request sends: the request is built from the same canonical messages.
        val canonical = ModelFacingMessageText.storedMessages(stored).map { ModelFacingMessageText.canonicalMessage(context, it) }
        val request = SummarizerSafeIncludeProjectionBuilder.build(canonical, foldedCount = 0).conversation
        val eligible = read.filter(IndexEligibility::isEligible)
        assertEquals(listOf("u1", "a1", "a2", "u3"), eligible.map { it.messageId })
        for (message in eligible) {
            val sent = request.single { it.isBot == message.isBot && message.modelText.startsWith(it.text) && it.text.isNotBlank() }
            if (sent.inlineIncludes.isEmpty()) {
                assertEquals(sent.text, message.modelText)
            } else {
                // An attachment is sent in full after the words; the index has its stable reference, never its contents.
                assertTrue(message.modelText.startsWith(sent.text + "\n\n"))
                assertFalse(message.modelText.contains(include.fullText))
            }
        }

        // The hidden message is sent as nothing and is not eligible.
        assertEquals("", read.single { it.messageId == "u2" }.modelText)
        assertTrue(read.single { it.messageId == "u2" }.hiddenFromAi)
        // The canonical version is the one read; browsing another version changes nothing here.
        assertEquals("The second version.", read.single { it.messageId == "a2" }.modelText)
    }

    @Test fun aMessageWithoutAPermanentIdIsNeverEligibleAndIsNotGivenOne() {
        val stored = chat() + row(null, false, "Saved before permanent IDs.")
        val read = IndexSourceMessages.fromStored(context, stored)
        assertEquals("", read.last().messageId)
        assertFalse(IndexEligibility.isEligible(read.last()))
        assertEquals(listOf("u1", "a1", "a2", "u3"), read.filter(IndexEligibility::isEligible).map { it.messageId })
        // Nothing was written to the stored messages.
        assertTrue(stored.last()[MessageIdentity.KEY] == null)
    }

    @Test fun aTurnWithoutTheSavedMemoryDatabaseIsIndexedWithItsRecordedScope() {
        assertFalse("the saved-memory database is not set up", MemoryStore.isProvisioned(context))
        val scope = ScanMessageScope(companionPersonaId = "companion-1", projectId = "project-1", userPersonaId = "glamour-1")
        val messages = listOf(
            row("u1", false, "Earlier message without a recorded scope."),
            row("u2", false, "Plan the garden."),
            row("a2", true, "Here is a plan.", MessageCompletionState.KEY_STATE to MessageCompletionState.DONE)
        )
        // What the chat screen does when a turn finishes, with no memory database.
        assertTrue(ChatTurnMemoryScope.stamp(messages, scope))

        val read = IndexSourceMessages.fromStored(context, messages)
        assertNull(read[0].scope)
        assertEquals(scope, read[1].scope)
        assertEquals(scope, read[2].scope)

        val index = MemoryIndex()
        val reader = SearchTextReader("test-model", 8, 400, { "title: $it | text: " }, { it.split(' ').size })
        val outcome = ConversationIndexUpdater(index, reader, 40, ConversationIndexSettings.OVERLAP_WORDS)
            .update("chat", "v1", read, { "v1" })
        assertTrue("$outcome", outcome is ConversationIndexUpdater.Outcome.Updated)
        val stamps = index.indexedMessages("chat")
        assertNull(stamps.getValue("u1").scope)
        assertEquals(scope, stamps.getValue("u2").scope)
        assertEquals(scope, stamps.getValue("a2").scope)
        assertFalse(MemoryStore.isProvisioned(context))
    }

    @Test fun theHookupsDoNothingWhileIndexingIsOff() {
        ConversationIndexSetting.overrideForTest = false
        val before = ConversationIndexChatChanges.count("off-chat")
        ConversationIndexHooks.chatSaved(context, "off-chat")
        ConversationIndexHooks.chatsDeleted(context, listOf("off-chat"))
        ConversationIndexHooks.chatRenamed(context, "off-chat", "off-chat-2")
        ConversationIndexHooks.chatSetReplaced(context)
        ConversationIndexHooks.embeddingModelChanged(context)
        ConversationIndexHooks.appStarted(context)
        assertEquals(before, ConversationIndexChatChanges.count("off-chat"))
        assertEquals(0L, ConversationIndexChatChanges.count("off-chat-2"))
    }

    @Test fun theHookupsReturnAtOnceAndAnIndexThatCannotOpenNeverReachesTheCaller() {
        ConversationIndexSetting.overrideForTest = true
        val started = System.nanoTime()
        ConversationIndexHooks.chatSaved(context, "on-chat")
        ConversationIndexHooks.chatsDeleted(context, listOf("gone-chat"))
        ConversationIndexHooks.chatRenamed(context, "old-chat", "new-chat")
        ConversationIndexHooks.chatSetReplaced(context)
        ConversationIndexHooks.embeddingModelChanged(context)
        ConversationIndexHooks.appStarted(context)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        // Only the change was recorded on the calling thread; the work runs on the index's thread.
        assertEquals(1L, ConversationIndexChatChanges.count("on-chat"))
        assertTrue("took $elapsedMillis ms", elapsedMillis < 1_000)
        assertNotNull(context)
    }
}
