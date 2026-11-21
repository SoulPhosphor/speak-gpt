package org.teslasoft.assistant.conversation.responsegroups

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.*
import org.teslasoft.assistant.preferences.backup.portable.*
import org.teslasoft.assistant.preferences.chatstore.ChatStore
import org.teslasoft.assistant.preferences.chatstore.ChatStorageAuthority
import org.teslasoft.assistant.preferences.damaged.DamagedChatOperation
import org.teslasoft.assistant.preferences.includes.ImageImporter
import org.teslasoft.assistant.preferences.includes.PdfAttachmentStore
import java.io.File
import java.util.UUID

/** Real encrypted preferences, SQLCipher, attachment files and the journaled chat restore participant. */
@RunWith(AndroidJUnit4::class)
class ResponseGroupStorageInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = ChatPreferences.getChatPreferences()
    private val gson = Gson()
    private lateinit var chatId: String
    private val ids = ArrayList<String>()
    private val tempFiles = ArrayList<File>()
    private fun asset(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open("response-groups/$name").use { it.readBytes() }
    private val rowsType = object : TypeToken<List<HashMap<String, Any>>>() {}.type
    /** The fixture as saving stores it: every message carries its permanent
     *  number (MessageNumbers), so later saves keep the rows unchanged. */
    private fun rows(): List<HashMap<String, Any>> {
        val fixture: List<HashMap<String, Any>> = gson.fromJson(asset("history.json").toString(Charsets.UTF_8), rowsType)
        MessageNumbers.ensure(fixture, 0)
        return gson.fromJson(gson.toJson(fixture), rowsType)
    }
    private fun read(id: String = chatId) = prefs.getChatByIdResult(context, id, DamagedChatOperation.OPENING_A_CHAT).messages
    private fun group(row: Map<String, Any>) = (ResponseGroupCodec.read(row) as ResponseGroupRead.Valid).group
    private fun file(name: String): File = File(context.cacheDir, "response-groups-${UUID.randomUUID()}-$name").also(tempFiles::add)

    @Before fun setup() {
        PortableRestoreProcessGate.clear(context)
        ChatStorageAuthority.clearForTest(context)
        chatId = UUID.randomUUID().toString().also(ids::add)
        assertTrue(SecurePrefs.get(context, "chat_list").edit().putString("data",
            JSONArray().put(JSONObject().put("id", chatId).put("name", "Synthetic groups")).toString()).commit())
        assertEquals(ChatStorageHealth.WriteOutcome.OK, prefs.saveChatHistory(context, chatId, rows(), synchronous = true))
        assertTrue(SecurePrefs.get(context, "settings.$chatId").edit().putBoolean("experimental_mode", true).commit())
        group(rows()[2]).input.parts.mapNotNull { it.attachment }.forEach { ref ->
            val dir = if (ref.store == "pdf") PdfAttachmentStore.chatPdfsDir(context, chatId) else ImageImporter.chatImagesDir(context, chatId)
            File(dir, ref.fileName).writeBytes(asset(if (ref.store == "pdf") "document.pdf" else "image.png"))
        }
    }

    @After fun cleanup() {
        PortableRestoreProcessGate.clear(context)
        ids.forEach { id ->
            SecurePrefs.get(context, "chat_$id").edit().clear().commit()
            SecurePrefs.get(context, "settings.$id").edit().clear().commit()
            PdfAttachmentStore.chatPdfsDir(context, id).deleteRecursively()
            ImageImporter.chatImagesDir(context, id).deleteRecursively()
        }
        SecurePrefs.get(context, "chat_list").edit().clear().commit()
        tempFiles.forEach { it.deleteRecursively() }
    }

    @Test fun liveAndDatabaseRoundTripRenameTruncateDeletePreserveUnrelatedVersionsAndIds() {
        val original = rows()
        assertEquals(original, read())
        assertTrue(prefs.editChat(context, "Renamed groups", "Synthetic groups", chatId))
        assertEquals(original, read())
        assertTrue(SecurePrefs.get(context, "settings.$chatId").getBoolean("experimental_mode", false))
        val dbName = "response-group-${UUID.randomUUID()}.db"
        val store = ChatStore.open(context, "fixture-key".toByteArray(), ChatStore.CloseOnCorruption, dbName)
        try {
            store.putChat(ChatStore.StoredChat(chatId, 0, JSONObject().put("id", chatId).put("name", "Synthetic groups").toString(), "{}", true, 0))
            original.forEach { row -> store.appendMessage(chatId, ChatStore.MessageRow(MessageIdentity.idOf(row), row["isBot"] == true, gson.toJson(row))) }
            val renamed = UUID.randomUUID().toString()
            assertTrue(store.renameChatId(chatId, renamed, JSONObject().put("id", renamed).put("name", "Renamed groups").toString()))
            val loaded = store.readPage(renamed, 0, 10)
            assertEquals(original.map { gson.toJson(it) }, loaded.map { it.payloadJson })
            assertEquals(2, store.deleteAfter(renamed, loaded[2].seq))
            assertEquals(3, store.messageCount(renamed))
            assertTrue(store.deleteMessage(renamed, loaded[2].seq))
            assertEquals(original[0]["variants"], gson.fromJson(store.messageAt(renamed, 0)!!.payloadJson, Map::class.java)["variants"])
            assertTrue(store.deleteChat(renamed))
            assertEquals(0, store.messageCount(renamed))
            assertEquals(original, read())
        } finally {
            store.close()
            listOf(dbName, "$dbName-wal", "$dbName-shm").forEach { context.getDatabasePath(it).delete() }
        }
    }

    @Test fun groupOnlySavesAdvanceMergeEvidenceAndFailedOrStaleWritesPreserveReadableData() {
        val original = read()
        val old = group(original[2])
        val firstTime = SecurePrefs.get(context, "chat_$chatId").getLong(ChatPreferences.CHAT_CONTENT_MODIFIED_AT_KEY, 0)
        assertTrue(firstTime > 0)
        // Older timestamp is fixed test input; no clock sleeps needed.
        SecurePrefs.get(context, "chat_$chatId").edit().putLong(ChatPreferences.CHAT_CONTENT_MODIFIED_AT_KEY, 1).commit()
        val target = ResponseGroupTarget(chatId, old.assistantMessageId, old.groupId)
        val browsed = ResponseGroupEdits.browse(old, old.slots[3].slotId, old.slots[3].versions[1].versionId)
        val saved = ResponseGroupStore.replace(context, chatId, target, original[2]["responseGroup"] as String, browsed) { fail("Browse must not reconcile canonical sources") }
        assertTrue(saved is ResponseGroupStore.Result.Saved)
        assertTrue(SecurePrefs.get(context, "chat_$chatId").getLong(ChatPreferences.CHAT_CONTENT_MODIFIED_AT_KEY, 0) > 1)
        val previous = read()
        val illegal = previous.map { HashMap(it) }
        illegal[2].remove("responseGroup")
        assertEquals(ChatStorageHealth.WriteOutcome.FAILED,
            prefs.saveChatHistory(context, chatId, illegal, synchronous = true))
        assertEquals(previous, read())
        assertEquals(ResponseGroupStore.Result.Rejected, ResponseGroupStore.replace(context, chatId, target,
            original[2]["responseGroup"] as String, old) { fail("Stale save") })
        assertTrue(PortableRestoreProcessGate.mark(context, PortableRestoreProcessGate.Phase.APPLYING))
        val failed = ResponseGroupStore.replace(context, chatId, target, previous[2]["responseGroup"] as String, old) { fail("Failed save") }
        assertTrue(failed is ResponseGroupStore.Result.WriteFailed)
        assertEquals(previous, read())
        PortableRestoreProcessGate.clear(context)
        // Earlier saves queued search-index refreshes that read this chat; a
        // successful read clears a recorded read failure. Let them finish
        // first so the simulated failure stays in place for the save below.
        org.teslasoft.assistant.preferences.chatsearch.ChatSearchIndexManager.get(context).awaitIdleForTest()
        ChatStorageHealth.recordReadFailure(context, "chat_$chatId", IllegalStateException("synthetic failure"))
        assertEquals(ChatStorageHealth.WriteOutcome.BLOCKED_CORRUPT,
            prefs.saveChatHistory(context, chatId, original, synchronous = true, responseGroupChanged = true))
        ChatStorageHealth.clearReadFailure(context, "chat_$chatId")
        assertEquals(previous, read())
    }

    @Test fun recoveryBackupAndRestoreAsCopyKeepAllSnapshotsAttachmentsSelectionsAndRequestIds() {
        verifyBackupAndRestore(rows())
    }

    @Test fun toolContinuationIdentityAndImageEvidenceSurviveBackupAndRestoreAsCopy() {
        val original = read()
        val old = group(original[2])
        val slot = old.slots[1]
        val version = slot.versions.last()
        val parent = version.attempts.last()
        val toolMessage = """{"role":"assistant","content":"fixture tool text","tool_calls":[{"id":"fixture-call","type":"function","function":{"name":"create_image","arguments":"{}"}}],"reasoning_details":[{"type":"reasoning.encrypted","data":"fixture-signature"}]}"""
        val fields = mapOf("message" to "fixture continuation", "state" to "done")
        val requestId = UUID.randomUUID().toString()
        val imageId = UUID.randomUUID().toString()
        val receipt = org.teslasoft.assistant.usage.TokenUsageAccounting.encodeRecords(listOf(
            org.teslasoft.assistant.usage.TurnUsageRecord(model = slot.assignment.modelId, provider = "fixture-provider",
                requestId = requestId, inputTokens = 17, outputTokens = 3, totalTokens = 20, totalCost = 0.02)))
        val continuation = ResponseAttempt(requestId, parent.startedAtMs + 100, "done", parent.startedAtMs + 101,
            fields, receipt, parentRequestId = parent.requestId, withoutTools = false,
            continuationMessages = listOf(toolMessage, """{"role":"tool","tool_call_id":"fixture-call","content":"fixture image result"}"""),
            imageJobId = imageId, imageResult = """{"imageId":"$imageId","status":"failed","failureCode":"PROVIDER_ERROR"}""")
        val after = old.copy(slots = old.slots.map { current -> if (current != slot) current else current.copy(
            versions = current.versions.map { v -> if (v != version) v else v.copy(fields = fields,
                attempts = v.attempts.dropLast(1) + parent.copy(toolAssistantMessage = toolMessage) + continuation) }) })
        val result = ResponseGroupStore.replace(context, chatId, ResponseGroupTarget(chatId, old.assistantMessageId, old.groupId),
            original[2]["responseGroup"] as String, after) { fail("Nonprimary continuation must not rewrite canonical sources") }
        assertTrue(result is ResponseGroupStore.Result.Saved)
        val expected = read()
        assertEquals(requestId, group(expected[2]).slots[1].versions.last().attempts.last().requestId)
        assertTrue(prefs.editChat(context, "Renamed tool fixture", "Synthetic groups", chatId))
        assertEquals(expected, read())
        verifyBackupAndRestore(expected)
    }

    private fun verifyBackupAndRestore(expected: List<HashMap<String, Any>>) {
        org.teslasoft.assistant.preferences.memory.MemoryStore.getInstance(context).readableDatabase
            .rawQuery("SELECT 1", null).use { it.moveToFirst() }
        org.teslasoft.assistant.preferences.profileimages.ProfileImageDb.getInstance(context).readableDatabase
            .rawQuery("SELECT 1", null).use { it.moveToFirst() }
        org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStore.ensureAvailableForRegistration(context)
        ModelEndpointStateGenerationStore.get(context).read()
        val packageFile = file("recovery.sgbak")
        val written = PortableRecoveryWriter.createPackage(context, packageFile, null, null, "response-group-test")
        assertTrue("package: $written", written is PortableRecoveryWriter.Result.Ok)
        val decodeRoot = file("decoded").apply { mkdirs() }
        val decoded = PortablePackage.decodeWithSecret(packageFile, ByteArray(0), decodeRoot) as PortablePackage.DecodeResult.Ok
        val verified = PortablePackage.validateAndExtract(decoded.innerZip, File(decodeRoot, "artifacts").apply { mkdirs() }) as PortablePackage.ValidateResult.Ok
        assertTrue(PortableRecoverySemanticValidator.validate(context, verified.artifacts, verified.declaredCategories,
            verified.explicitlyEmptyCategories, verified.categoryRecordCounts) is PortableRecoverySemanticValidator.Result.Valid)
        val recoveryRows = (PortableChatRestorePlan.parse(verified.artifacts.single { it.type == PortablePackage.TYPE_CHATS_JSON }.stagedFile.readText()) as PortableChatRestorePlan.Result.Ok).plan
        assertEquals(gson.toJson(expected), gson.toJson(gson.fromJson<List<HashMap<String, Any>>>(recoveryRows.chats.single().messagesJson,
            object : TypeToken<List<HashMap<String, Any>>>() {}.type)))
        val serialized = ChatLogicalSerializer.serializeV2(context) as ChatLogicalSerializer.Result.Ok
        val parsed = (PortableChatRestorePlan.parse(serialized.json) as PortableChatRestorePlan.Result.Ok).plan
        assertEquals(expected, read())
        val stagedChats = file("chats.json").apply { writeText(serialized.json) }
        val scanned = (ChatsArtifactScanner.scan(stagedChats.reader()) as ChatsArtifactScanner.Result.Ok).summary
        assertEquals(1, scanned.snapshotImageRefs.size)
        assertEquals(1, scanned.pdfRefs.size)
        val pdfs = (PdfAttachmentPortableBackup.buildArtifacts(context, scanned.pdfRefs) as PdfAttachmentPortableBackup.Result.Ok).artifacts
        val images = ResponseGroupAttachmentBackup.imageArtifacts(context, scanned.snapshotImageRefs)
        assertEquals(1, pdfs.size)
        assertEquals(1, images.size)
        val logical = parsed.chats.single()
        val incoming = parsed.copy(chats = listOf(logical.copy(messagesJson = gson.toJson(expected.take(3)), messageCount = 3)))
        val merged = ChatMergePlanner.plan(parsed, incoming, chatResolutions = mapOf(chatId to ChatMergePlanner.ChatResolution.KeepBoth)) as ChatMergePlanner.Result.Ready
        val copy = merged.plan.chats.single { it.chatId != chatId }
        ids.add(copy.chatId)
        assertEquals(group(expected[2]), group(gson.fromJson<List<HashMap<String, Any>>>(copy.messagesJson, object : TypeToken<List<HashMap<String, Any>>>() {}.type).let { it[2] }))
        val prepared = PortableChatRestoreCoordinator.Prepared(merged.plan, PortableRestoreMode.MERGE, merged.report)
        val participant = ChatRestoreParticipant(context, prepared, parsed, file("restore"),
            pdfs.associate { it.file.nameWithoutExtension to it.file }, images.associate { it.file.name to it.file })
        assertTrue(participant.validate())
        assertTrue(participant.stage())
        assertTrue(participant.apply())
        assertEquals(expected.take(3), read(copy.chatId))
        assertEquals(ResponseGroupHistory.fingerprint(read(copy.chatId)),
            SecurePrefs.get(context, "chat_${copy.chatId}").getString("response_group_fingerprint", null))
        assertTrue(SecurePrefs.get(context, "settings.${copy.chatId}").getBoolean("experimental_mode", false))
        for (ref in group(read(copy.chatId)[2]).input.parts.mapNotNull { it.attachment }) {
            val dir = if (ref.store == "pdf") PdfAttachmentStore.chatPdfsDir(context, copy.chatId) else ImageImporter.chatImagesDir(context, copy.chatId)
            assertTrue(ref.matches(File(dir, ref.fileName).readBytes()))
        }
        assertTrue(participant.rollback())
        assertEquals(expected, read())
        participant.cleanup()
    }

    @Test fun invalidGroupEnvelopeSurvivesEncryptedSaveBackupAndRecoveryWithoutChangingMirror() {
        val damaged = rows().map { HashMap(it) }
        damaged[2]["responseGroup"] = "{unreadable raw envelope"
        assertTrue(SecurePrefs.get(context, "chat_$chatId").edit().putString("chat", gson.toJson(damaged)).commit())
        val loaded = read()
        assertTrue(ResponseGroupCodec.read(loaded[2]) is ResponseGroupRead.Invalid)
        assertFalse(ResponseGroupRecovery.reconcile(loaded[2]))
        assertEquals(damaged[2], loaded[2])
        val exported = ChatLogicalSerializer.serializeV2(context) as ChatLogicalSerializer.Result.Ok
        val plan = (PortableChatRestorePlan.parse(exported.json) as PortableChatRestorePlan.Result.Ok).plan
        val restored: List<HashMap<String, Any>> = gson.fromJson(plan.chats.single().messagesJson, object : TypeToken<List<HashMap<String, Any>>>() {}.type)
        assertEquals(damaged, restored)
        // A complete recovery package must fail explicitly when attachment ownership cannot be read.
        assertTrue(ChatsArtifactScanner.scan(exported.json.reader()) is ChatsArtifactScanner.Result.Unreadable)
        assertNull(ResponseGroupAttachments.retainedHashes(loaded, "pdf"))
    }
}
