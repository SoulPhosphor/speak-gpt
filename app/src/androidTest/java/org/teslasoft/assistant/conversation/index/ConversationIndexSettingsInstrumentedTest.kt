package org.teslasoft.assistant.conversation.index

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.memory.DatabaseKeys
import org.teslasoft.assistant.preferences.memory.librarian.InputTooLongException
import org.teslasoft.assistant.ui.activities.MemoryControlsActivity
import org.teslasoft.assistant.ui.conversationindex.ConversationIndexSection

/**
 * Step 11 on a device (G.16a, G.13a, G20, G28; Owner 2026-11-24):
 *
 *  - the encrypted store never counts a chat with a failed vector as fully
 *    indexed, by either path that records it, and its summaries give the
 *    status counts, including chats not opened since permanent IDs;
 *  - real SQLCipher damage is recognised as damage and an access failure is
 *    not; recovery keeps the vectors it can read, rebuilds what it cannot,
 *    and the rebuilt index indexes again, reusing the kept vectors;
 *  - RAG Active Memory Settings: Index Conversations starts off, cannot turn
 *    on without a model and shows the approved explanation; turning it on is
 *    saved; Delete Conversation Index asks with the approved wording and
 *    turns the setting off.
 */
@RunWith(AndroidJUnit4::class)
class ConversationIndexSettingsInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "conversation_index_settings_test_${System.nanoTime()}.db"
    private val keyName = "conversation_index_settings_test_key_${System.nanoTime()}"
    private var store: ConversationIndexStore? = null
    private val chats = HashMap<String, MutableMap<String, IndexSourceMessage>>()
    private val versions = HashMap<String, Int>()
    private val source = IndexMessageSource { chat, message -> chats[chat]?.get(message) }
    private val pause = object : EmbeddingPauseState { override var paused = false }

    private val reader = SearchTextReader(
        vectorModel = MODEL, vectorDimensions = DIMENSIONS, hardLimit = 400,
        prefixFor = { "title: $it | text: " }, countModelInput = ::words
    )

    private inner class Vectors(override val tokenLimit: Int = 400) : PassageVectorReader {
        override val vectorModel = MODEL
        override val vectorDimensions = DIMENSIONS
        val inputs = ArrayList<String>()
        override fun countTokens(modelInput: String) = words(modelInput)
        override fun read(modelInput: String): FloatArray {
            if (countTokens(modelInput) > tokenLimit) throw InputTooLongException(countTokens(modelInput), tokenLimit)
            inputs.add(modelInput)
            return FloatArray(DIMENSIONS) { it + inputs.size.toFloat() }
        }
    }

    private fun open(ctx: Context = context): ConversationIndexStore {
        val exists = ctx.getDatabasePath(name).exists()
        val key = DatabaseKeys.getOrCreate(ctx, keyName, exists) ?: error("test key unavailable")
        return ConversationIndexStore.openForTest(ctx, name, key).also { store = it }
    }

    private fun closeStore() {
        try { store?.close() } catch (_: Exception) { }
        store = null
    }

    private fun recoveryFiles() = AppIndexRecoveryFiles(context, name, keyName, ::closeStore, { open(it) })

    /** The app's own index must not react to the damage these tests make in their own files. */
    private val appDamageListener = ConversationIndexDamage.listener

    @Before fun setUp() {
        ConversationIndexDamage.listener = null
        open()
    }

    @After fun tearDown() {
        closeStore()
        for (suffix in listOf("", "-wal", "-shm", "-journal", ".damaged", ".damaged-journal")) {
            val f = context.getDatabasePath(name + suffix)
            if (f.isDirectory) f.deleteRecursively() else f.delete()
        }
        DatabaseKeys.clearExisting(context, keyName)
        ConversationIndexDamage.take()
        ConversationIndexDamage.listener = appDamageListener
    }

    private fun user(id: String, text: String) = IndexSourceMessage(id, false, text, false, null, 100L, null)
    private fun reply(id: String, text: String) =
        IndexSourceMessage(id, true, text, false, MessageCompletionState.DONE, 200L, null)

    private fun save(chatId: String, messages: List<IndexSourceMessage>) {
        chats[chatId] = messages.associateBy { it.messageId }.toMutableMap()
        versions[chatId] = (versions[chatId] ?: 0) + 1
        val version = versions[chatId].toString()
        val outcome = ConversationIndexUpdater(store!!, reader, 40, 5).update(chatId, version, messages, { versions[chatId].toString() })
        assertTrue("$outcome", outcome is ConversationIndexUpdater.Outcome.Updated)
    }

    private fun pass(vectors: Vectors = Vectors()) = PassageEmbeddingWorker(store!!, source, reader, vectors, pause).runPass()

    private fun longReply() = reply("a1", (1..120).joinToString(" ") { "wide$it" })

    // --- Fully indexed and the counts -------------------------------------------

    @Test fun aFailedVectorNeverMakesTheChatFullyIndexed() {
        save("chat", listOf(user("u1", "short"), longReply()))
        // The vector write recounts the chat (recountChatsUsing).
        val outcome = pass(Vectors(tokenLimit = 20))
        assertTrue("$outcome", outcome is PassageEmbeddingWorker.Outcome.Finished)
        val progress = store!!.chatProgress("chat")
        assertTrue(progress.failed > 0)
        assertEquals(0, progress.waiting)
        assertFalse(progress.fullyIndexed)
        // The finishing step of a later update counts again with the failed row in place (finishUpdate).
        save("chat", listOf(user("u1", "short"), longReply(), user("u2", "more")))
        assertFalse(store!!.indexState("chat")!!.fullyIndexed)
        pass()
        assertFalse(store!!.chatProgress("chat").fullyIndexed)
        val summary = store!!.chatSummaries(MODEL, DIMENSIONS).getValue("chat")
        assertTrue(summary.madePassages < summary.passages)
        assertEquals(0, ConversationIndexCounting.count(listOf("chat"), mapOf("chat" to summary), { versions[it].toString() }, null).indexed)
    }

    @Test fun theSummariesGiveTheStatusCounts() {
        save("done", listOf(user("u1", "I moved to Leeds."), reply("a1", "Welcome to Leeds.")))
        save("old", listOf(user("", "Saved before IDs."), reply("", "Also before IDs.")))
        save("empty", emptyList())
        save("waiting", listOf(user("w1", "Still waiting.")))
        pass()
        save("waiting", listOf(user("w1", "Still waiting."), user("w2", "A new one.")))
        assertEquals(1, store!!.waitingPassages(MODEL, DIMENSIONS))
        val summaries = store!!.chatSummaries(MODEL, DIMENSIONS)
        assertEquals(2, summaries.getValue("old").unidentifiedMessages)
        assertEquals(0, summaries.getValue("old").passages)
        val counts = ConversationIndexCounting.count(listOf("done", "old", "empty", "waiting"), summaries, { versions[it].toString() }, null)
        assertEquals(ConversationIndexCounts(indexed = 1, needOpening = 1), counts)
        assertEquals(summaries.getValue("done").passages, store!!.chatSummaries("another", DIMENSIONS).getValue("done").otherModelPassages)
    }

    @Test fun aHealthyIndexPassesItsCheck() {
        save("chat", listOf(user("u1", "Hello.")))
        assertNull(store!!.integrityProblem())
    }

    // --- Damage, access failures and recovery -------------------------------------

    @Test fun aFileThatIsNotADatabaseIsDamage() {
        closeStore()
        context.getDatabasePath(name).writeBytes(ByteArray(8192) { (it * 31).toByte() })
        try {
            open().indexState("chat")
            fail("a damaged file opened")
        } catch (e: Exception) {
            assertTrue(ConversationIndexErrorLog.cause(e), IndexFailures.isDamage(e))
        }
    }

    @Test fun aFileThatCannotBeOpenedForNowIsNotDamage() {
        closeStore()
        val path = context.getDatabasePath(name)
        path.delete()
        assertTrue(path.mkdirs())
        try {
            open().indexState("chat")
            fail("a directory opened as a database")
        } catch (e: Exception) {
            assertFalse(ConversationIndexErrorLog.cause(e), IndexFailures.isDamage(e))
        }
    }

    @Test fun recoveryKeepsTheReadableVectorsAndTheRebuiltIndexReusesThem() {
        save("chat", listOf(user("u1", "I moved to Leeds."), reply("a1", "Welcome to Leeds.")))
        val first = Vectors()
        pass(first)
        val made = store!!.madeVectorCount()
        assertTrue(made > 0)

        val result = IndexRecovery(recoveryFiles()).recover()
        assertEquals(IndexRecovery.Result.Rebuilt(retained = made, notRead = null), result)
        assertFalse(File(context.getDatabasePath(name).path + ".damaged").exists())
        assertNull(store!!.integrityProblem())
        assertTrue(store!!.indexedChatIds().isEmpty())
        assertEquals(made, store!!.madeVectorCount())

        // Indexing again after the rebuild reads nothing the kept vectors already hold.
        save("chat", listOf(user("u1", "I moved to Leeds."), reply("a1", "Welcome to Leeds.")))
        val second = Vectors()
        pass(second)
        assertTrue(second.inputs.isEmpty())
        assertTrue(store!!.chatProgress("chat").fullyIndexed)
    }

    @Test fun anUnreadableIndexIsRebuiltEmptyAndIndexesAgain() {
        save("chat", listOf(user("u1", "I moved to Leeds.")))
        pass()
        closeStore()
        context.getDatabasePath(name).writeBytes(ByteArray(8192) { (it * 7).toByte() })

        val result = IndexRecovery(recoveryFiles()).recover() as IndexRecovery.Result.Rebuilt
        assertEquals(0, result.retained)
        assertNotNull(result.notRead)
        assertNull(store!!.integrityProblem())

        save("chat", listOf(user("u1", "I moved to Leeds.")))
        val vectors = Vectors()
        pass(vectors)
        assertEquals(1, vectors.inputs.size)
        assertTrue(store!!.chatProgress("chat").fullyIndexed)
    }

    @Test fun anIndexWhoseKeyIsGoneIsRebuiltWithANewKey() {
        save("chat", listOf(user("u1", "I moved to Leeds.")))
        pass()
        closeStore()
        assertTrue(DatabaseKeys.clearExisting(context, keyName))
        assertEquals(IndexRecoveryFiles.Key.ABSENT, recoveryFiles().keyState())

        val result = IndexRecovery(recoveryFiles()).recover() as IndexRecovery.Result.Rebuilt
        assertEquals(0, result.retained)
        assertTrue(result.notRead!!.contains("key is missing"))
        assertTrue(DatabaseKeys.readState(context, keyName) is DatabaseKeys.StoredKeyState.Present)
        save("chat", listOf(user("u1", "I moved to Leeds.")))
        pass()
        assertTrue(store!!.chatProgress("chat").fullyIndexed)
    }

    @Test fun deletingTheIndexAlsoRemovesADamagedFileAnUnfinishedRecoveryKeptAside() {
        val app = context.applicationContext
        val primary = app.getDatabasePath(ConversationIndexStore.DATABASE_NAME)
        val aside = File(primary.path + ".damaged")
        aside.parentFile?.mkdirs()
        aside.writeBytes(ByteArray(4096) { 1 })
        try {
            val done = java.util.concurrent.CountDownLatch(1)
            var gone = false
            ConversationIndexRuntime.deleteIndex(app) { gone = it; done.countDown() }
            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(gone)
            assertFalse(aside.exists())
            // Nothing is left that a later recovery could bring back.
            assertTrue(AppIndexRecoveryFiles(app).readKeptVectors().vectors.isEmpty())
        } finally {
            aside.delete()
        }
    }

    // --- RAG Active Memory Settings ---------------------------------------------------

    private fun withScreen(block: (ActivityScenario<MemoryControlsActivity>) -> Unit) {
        val before = ConversationIndexSetting.isOn(context)
        ConversationIndexSetting.set(context, false)
        ConversationIndexSection.lastDialogForTest = null
        val scenario = ActivityScenario.launch(MemoryControlsActivity::class.java)
        try {
            block(scenario)
        } finally {
            scenario.onActivity { ConversationIndexSection.lastDialogForTest?.dismiss() }
            scenario.close()
            ConversationIndexSection.modelInstalledForTest = null
            ConversationIndexSection.lastDialogForTest = null
            // Conditions the real runtime recorded while the setting was on (no model on this device).
            ConversationIndexPrefs.of(context).edit().clear().commit()
            ConversationIndexSetting.set(context, before)
        }
    }

    private fun AlertDialog.title() = findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text?.toString()
    private fun AlertDialog.message() = findViewById<TextView>(android.R.id.message)?.text?.toString()

    @Test fun indexConversationsCannotTurnOnWithoutAModelAndSaysWhy() = withScreen { scenario ->
        ConversationIndexSection.modelInstalledForTest = false
        scenario.onActivity { activity ->
            val toggle = activity.findViewById<MaterialSwitch>(R.id.switch_index_conversations)
            assertFalse(toggle.isChecked)
            toggle.performClick()
            assertFalse(toggle.isChecked)
            val dialog = ConversationIndexSection.lastDialogForTest!!
            assertTrue(dialog.isShowing)
            assertEquals(activity.getString(R.string.conversation_index_model_needed_title), dialog.title())
            assertEquals(activity.getString(R.string.conversation_index_model_needed_text), dialog.message())
            assertEquals(
                activity.getString(R.string.conversation_index_open_model_settings),
                dialog.findViewById<MaterialButton>(R.id.btn_dialog_primary_action)!!.text.toString()
            )
            assertEquals(
                activity.getString(R.string.btn_close),
                dialog.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action)!!.text.toString()
            )
        }
        assertFalse(ConversationIndexSetting.isOn(context))
    }

    @Test fun turningIndexConversationsOnIsSavedAndDeleteAsksThenTurnsItOff() = withScreen { scenario ->
        ConversationIndexSection.modelInstalledForTest = true
        scenario.onActivity { activity ->
            activity.findViewById<MaterialSwitch>(R.id.switch_index_conversations).performClick()
        }
        assertTrue(ConversationIndexSetting.isOn(context))
        scenario.onActivity { activity ->
            ConversationIndexSection.lastDialogForTest?.dismiss()
            activity.findViewById<MaterialButton>(R.id.btn_delete_conversation_index).performClick()
            val dialog = ConversationIndexSection.lastDialogForTest!!
            assertEquals(activity.getString(R.string.conversation_index_delete_title), dialog.title())
            assertEquals(activity.getString(R.string.conversation_index_delete_text), dialog.message())
            assertEquals(activity.getString(R.string.btn_cancel), dialog.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action)!!.text.toString())
            dialog.findViewById<MaterialButton>(R.id.btn_dialog_primary_action)!!.performClick()
        }
        assertFalse(ConversationIndexSetting.isOn(context))
        // The status is read again after the deletion, from the index itself.
        val deadline = System.currentTimeMillis() + 10_000
        var shown: String? = null
        while (System.currentTimeMillis() < deadline && shown == null) {
            scenario.onActivity { activity ->
                val status = activity.findViewById<TextView>(R.id.text_conversation_index_status)
                if (status.visibility == View.VISIBLE) shown = status.text.toString()
            }
            if (shown == null) Thread.sleep(100)
        }
        assertNotNull("the status line was never shown", shown)
        assertTrue(shown!!, shown!!.lines().first().endsWith("indexed"))
        scenario.onActivity { activity ->
            assertFalse(activity.findViewById<MaterialSwitch>(R.id.switch_index_conversations).isChecked)
        }
    }

    private companion object {
        const val MODEL = "settings-test-model"
        const val DIMENSIONS = 4
        fun words(text: String) = text.split(Regex("\\s+")).count { it.isNotEmpty() } + 2
    }
}
