package org.teslasoft.assistant.preferences.backup.portable

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.util.Hash

/** The read side of the owner-only conversion: every rejection is a whole
 *  artifact, and identity is verified rather than re-derived. */
class ChatLogicalImportPlanTest {

    @Test
    fun aModernChatRebuildsWithItsUuidAndItsHistoryVerbatim() {
        val messages = """[{"message":"hello","isBot":false}]"""
        val json = artifact(
            chat(
                id = "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
                name = "Kitchen table",
                listExtras = mapOf("id" to "3f2504e0-4f89-11d3-9a0c-0305e82c3301"),
                messages = messages,
                settings = """[{"k":"model","t":"s","v":"gpt-4o"}]"""
            )
        )

        val plan = (ChatLogicalImportPlan.parse(json) as ChatLogicalImportPlan.Result.Ok).plan
        assertEquals(1, plan.chatCount)
        val chat = plan.chats.single()
        assertEquals("3f2504e0-4f89-11d3-9a0c-0305e82c3301", chat.chatId)
        assertEquals("Kitchen table", chat.listRow["name"])
        assertEquals("3f2504e0-4f89-11d3-9a0c-0305e82c3301", chat.listRow["id"])
        assertEquals(1, chat.messageCount)
        assertEquals(JSONArray(messages).toString(), chat.messagesJson)
    }

    @Test
    fun aLegacyRowWithNoExplicitIdKeepsItsTitleHashAndNeverGainsOne() {
        val json = artifact(
            chat(
                id = Hash.hash("Old conversation"),
                name = "Old conversation",
                listExtras = emptyMap()
            )
        )

        val plan = (ChatLogicalImportPlan.parse(json) as ChatLogicalImportPlan.Result.Ok).plan
        val chat = plan.chats.single()
        assertEquals(Hash.hash("Old conversation"), chat.chatId)
        assertFalse(chat.listRow.containsKey("id"))
    }

    /** The source app hashes `map["name"].toString()`, so an absent name
     *  hashes the literal string "null". The rebuilt row must omit the key
     *  rather than invent an empty title, or the identity check fails. */
    @Test
    fun aRowWithNoNameAtAllSurvivesWithTheSameIdentity() {
        val id = Hash.hash("null")
        val entry = JSONObject()
            .put("name", JSONObject.NULL)
            .put("chat_id", id)
            .put("messages", JSONArray())
            .put("settings", JSONArray())
        val json = artifact(entry)

        val plan = (ChatLogicalImportPlan.parse(json) as ChatLogicalImportPlan.Result.Ok).plan
        val chat = plan.chats.single()
        assertEquals(id, chat.chatId)
        assertFalse(chat.listRow.containsKey("name"))
    }

    @Test
    fun aRenamedRowThatNoLongerHashesToItsExportedIdIsRejected() {
        val json = artifact(
            chat(id = Hash.hash("Old title"), name = "Different title", listExtras = emptyMap())
        )
        val rejected = ChatLogicalImportPlan.parse(json) as ChatLogicalImportPlan.Result.Rejected
        assertEquals(ChatLogicalImportPlan.Reason.IDENTITY_MISMATCH, rejected.reason)
    }

    @Test
    fun anArtifactTheSourceMarkedIncompleteIsNeverMigrationTruth() {
        val root = JSONObject()
            .put("format", ChatLogicalImportPlan.SUPPORTED_FORMAT)
            .put("complete", false)
            .put("chats", JSONArray())
        val rejected = ChatLogicalImportPlan.parse(root.toString())
            as ChatLogicalImportPlan.Result.Rejected
        assertEquals(ChatLogicalImportPlan.Reason.INCOMPLETE_ARTIFACT, rejected.reason)
    }

    @Test
    fun anUnknownFormatIsRefusedRatherThanGuessed() {
        val root = JSONObject()
            .put("format", "chat-logical-v99")
            .put("complete", true)
            .put("chats", JSONArray())
        val rejected = ChatLogicalImportPlan.parse(root.toString())
            as ChatLogicalImportPlan.Result.Rejected
        assertEquals(ChatLogicalImportPlan.Reason.UNSUPPORTED_FORMAT, rejected.reason)

        val notOurs = ChatLogicalImportPlan.parse(JSONObject().put("x", 1).toString())
            as ChatLogicalImportPlan.Result.Rejected
        assertEquals(ChatLogicalImportPlan.Reason.NOT_A_CHATS_ARTIFACT, notOurs.reason)

        val garbage = ChatLogicalImportPlan.parse("not json at all")
            as ChatLogicalImportPlan.Result.Rejected
        assertEquals(ChatLogicalImportPlan.Reason.MALFORMED, garbage.reason)
    }

    @Test
    fun twoEntriesClaimingOneIdentityFailTheWholeArtifact() {
        val id = Hash.hash("Same")
        val json = artifact(
            chat(id = id, name = "Same", listExtras = emptyMap()),
            chat(id = id, name = "Same", listExtras = emptyMap())
        )
        val rejected = ChatLogicalImportPlan.parse(json) as ChatLogicalImportPlan.Result.Rejected
        assertEquals(ChatLogicalImportPlan.Reason.DUPLICATE_CHAT_ID, rejected.reason)
    }

    @Test
    fun aSettingTypeThisBuildCannotRestoreStopsTheConversion() {
        val json = artifact(
            chat(
                id = Hash.hash("Typed"),
                name = "Typed",
                listExtras = emptyMap(),
                settings = """[{"k":"whatever","t":"blob","v":"zz"}]"""
            )
        )
        val rejected = ChatLogicalImportPlan.parse(json) as ChatLogicalImportPlan.Result.Rejected
        assertEquals(ChatLogicalImportPlan.Reason.UNSUPPORTED_SETTING_TYPE, rejected.reason)
    }

    @Test
    fun everySupportedSettingTypeSurvivesTheRoundTrip() {
        val json = artifact(
            chat(
                id = Hash.hash("Types"),
                name = "Types",
                listExtras = emptyMap(),
                settings = """[
                    {"k":"a","t":"s","v":"text"},
                    {"k":"b","t":"b","v":true},
                    {"k":"c","t":"i","v":7},
                    {"k":"d","t":"l","v":90000000000},
                    {"k":"e","t":"f","v":1.5},
                    {"k":"f","t":"ss","v":["one","two"]}
                ]"""
            )
        )
        val plan = (ChatLogicalImportPlan.parse(json) as ChatLogicalImportPlan.Result.Ok).plan
        val byKey = plan.chats.single().settings.associateBy { it.key }
        assertEquals("text", byKey.getValue("a").value)
        assertEquals(true, byKey.getValue("b").value)
        assertEquals(7, byKey.getValue("c").value)
        assertEquals(90000000000L, byKey.getValue("d").value)
        assertEquals(1.5f, byKey.getValue("e").value)
        assertEquals(setOf("one", "two"), byKey.getValue("f").value)
        assertEquals(6, plan.settingCount)
    }

    /** The per-chat API key is excluded on the way out; nothing in the plan
     *  may reintroduce one on the way back in. */
    @Test
    fun theExportCarriesNoCredentialAndThePlanInventsNone() {
        val json = artifact(chat(id = Hash.hash("Clean"), name = "Clean", listExtras = emptyMap()))
        val plan = (ChatLogicalImportPlan.parse(json) as ChatLogicalImportPlan.Result.Ok).plan
        assertTrue(plan.chats.single().settings.none { it.key == "api_key" })
    }

    @Test
    fun countsAreReportedAcrossTheWholePlan() {
        val json = artifact(
            chat(
                id = Hash.hash("One"), name = "One", listExtras = emptyMap(),
                messages = """[{"message":"a"},{"message":"b"}]""",
                settings = """[{"k":"x","t":"s","v":"1"}]"""
            ),
            chat(
                id = Hash.hash("Two"), name = "Two", listExtras = emptyMap(),
                messages = """[{"message":"c"}]"""
            )
        )
        val plan = (ChatLogicalImportPlan.parse(json) as ChatLogicalImportPlan.Result.Ok).plan
        assertEquals(2, plan.chatCount)
        assertEquals(3, plan.messageCount)
        assertEquals(1, plan.settingCount)
    }

    private fun chat(
        id: String,
        name: String,
        listExtras: Map<String, String>,
        messages: String = "[]",
        settings: String = "[]"
    ): JSONObject {
        val obj = JSONObject()
            .put("name", name)
            .put("chat_id", id)
            .put("messages", JSONArray(messages))
            .put("settings", JSONArray(settings))
        for ((key, value) in listExtras) obj.put("list_$key", value)
        return obj
    }

    private fun artifact(vararg chats: JSONObject): String {
        val array = JSONArray()
        chats.forEach { array.put(it) }
        return JSONObject()
            .put("format", ChatLogicalImportPlan.SUPPORTED_FORMAT)
            .put("complete", true)
            .put("chats", array)
            .toString()
    }
}
