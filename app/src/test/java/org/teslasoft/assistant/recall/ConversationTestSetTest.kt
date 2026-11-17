package org.teslasoft.assistant.recall

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Checks that the invented conversation-recall test set (G.16 Step 6) is well formed and that
 * every sample really has the structure its situation names. No search runs here: the index
 * and retrieval scoring arrive in later Steps. An exchange is a user message and every message
 * after it up to the next user message; `expect` and `must_not` name the opening user messages.
 */
class ConversationTestSetTest {

    private val minimums = mapOf(
        "exact_detail_far_back" to 6,
        "follow_up_without_subject" to 4,
        "time_reference" to 4,
        "before_summary" to 5,
        "nothing_relevant" to 6,
        "long_typed_detail" to 4,
        "long_dictated_detail" to 4,
        "hidden_best_match" to 4,
        "edited_old_words" to 3,
        "failed_or_unfinished_reply" to 3,
        "single_subject_chat" to 4,
        "roleplay_and_companion" to 4,
        "very_long_exchange" to 3,
        "more_than_limit" to 3,
        "decision_changed" to 4,
    )

    private val minChats = 8
    private val minLongChats = 3
    private val longChatMessages = 60
    private val farBackMessages = 40
    private val longMessageWords = 150
    private val maxDetailWords = 15
    private val veryLongExchangeWords = 1500
    private val exchangeLimit = 3

    private val uuidPattern =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val scopeTypes = setOf("companion", "project", "roleplay_world", "none")

    private fun load(): JSONObject {
        val stream = javaClass.classLoader!!
            .getResourceAsStream("recall_test_set/conversations.json")
        requireNotNull(stream) { "recall_test_set/conversations.json is missing" }
        return JSONObject(stream.bufferedReader().use { it.readText() })
    }

    // ------------------------------------------------------------------ model

    private class Message(
        val id: String,
        val author: String,
        val text: String,
        val time: Instant?,
        val visibility: String,
        val status: String,
        val input: String?,
        val editedFrom: String?,
    )

    private class Chat(
        val id: String,
        val scopeType: String,
        val messages: List<Message>,
        val summarizedUpTo: String?,
        val singleSubjectTerms: List<String>,
    ) {
        fun indexOf(id: String) = messages.indexOfFirst { it.id == id }

        /** The exchange opened by the user message [openerId]. */
        fun exchange(openerId: String): List<Message> {
            val start = indexOf(openerId)
            if (start < 0) return emptyList()
            var end = start + 1
            while (end < messages.size && messages[end].author != "user") end++
            return messages.subList(start, end)
        }

        fun openerOf(messageId: String): String? {
            var i = indexOf(messageId)
            while (i >= 0 && messages[i].author != "user") i--
            return if (i >= 0) messages[i].id else null
        }
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private fun JSONObject.stringList(name: String): List<String> =
        optJSONArray(name)?.strings() ?: emptyList()

    private fun parseTime(value: String?): Instant? = try {
        value?.let { Instant.parse(it) }
    } catch (_: DateTimeParseException) {
        null
    }

    private fun words(text: String): Int = text.split(Regex("\\s+")).count { it.isNotEmpty() }

    /** Whole-word, case-insensitive match that also accepts a plain -s, -es, -ed or -ing ending. */
    private fun contains(text: String, term: String): Boolean =
        Regex(
            "(?<![\\p{L}\\p{N}])" + Regex.escape(term) + "(?:s|es|ed|ing)?(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text)

    private fun currentText(exchange: List<Message>) = exchange.joinToString("\n") { it.text }

    // ------------------------------------------------------------------ validation

    /** Every problem found in [root]; an empty list means the set is well formed. */
    private fun problems(root: JSONObject): List<String> {
        val out = mutableListOf<String>()
        if (root.optInt("exchange_limit", -1) != exchangeLimit) {
            out += "exchange_limit must be $exchangeLimit"
        }

        // Chats and messages.
        val chats = linkedMapOf<String, Chat>()
        val messageOwner = hashMapOf<String, String>()
        val chatArray = root.getJSONArray("chats")
        for (c in 0 until chatArray.length()) {
            val obj = chatArray.getJSONObject(c)
            val chatId = obj.optString("id")
            if (chatId.isBlank()) out += "chat #$c has no id"
            if (chats.containsKey(chatId)) out += "duplicate chat id $chatId"

            val scope = obj.optJSONObject("scope")
            val scopeType = scope?.optString("type").orEmpty()
            if (scopeType !in scopeTypes) out += "$chatId has unknown scope '$scopeType'"
            if (scope != null && scopeType != "none" &&
                (scope.optString("id").isBlank() || scope.optString("name").isBlank())
            ) out += "$chatId scope $scopeType needs an id and a name"
            if (scopeType == "none" && scope?.has("id") == true) out += "$chatId has scope none with an id"

            val messages = mutableListOf<Message>()
            val array = obj.getJSONArray("messages")
            for (m in 0 until array.length()) {
                val msg = array.getJSONObject(m)
                val message = Message(
                    id = msg.optString("id"),
                    author = msg.optString("author"),
                    text = msg.optString("text"),
                    time = parseTime(msg.optStringOrNull("time")),
                    visibility = msg.optString("visibility"),
                    status = msg.optString("status"),
                    input = msg.optStringOrNull("input"),
                    editedFrom = msg.optStringOrNull("edited_from"),
                )
                val where = "$chatId message #$m (${message.id})"
                if (!uuidPattern.matches(message.id)) out += "$where id is not a permanent-style UUID"
                messageOwner.put(message.id, chatId)?.let { out += "$where id is not unique" }
                if (message.author !in setOf("user", "ai")) out += "$where author '${message.author}'"
                if (message.text.isBlank()) out += "$where has no text"
                if (message.time == null) out += "$where has no valid ISO-8601 time"
                if (message.visibility !in setOf("visible", "hidden")) out += "$where visibility"
                if (message.status !in setOf("finished", "failed", "unfinished")) out += "$where status"
                if (message.author == "user") {
                    if (message.status != "finished") out += "$where user messages are finished"
                    if (message.input !in setOf("typed", "dictated")) out += "$where input"
                } else {
                    if (message.input != null) out += "$where only user messages have an input"
                    if (message.editedFrom != null) out += "$where only user messages are edited here"
                }
                if (message.editedFrom != null && message.editedFrom == message.text) {
                    out += "$where edited_from equals the current text"
                }
                val previous = messages.lastOrNull()?.time
                if (previous != null && message.time != null && message.time < previous) {
                    out += "$where is earlier than the message before it"
                }
                messages += message
            }
            if (messages.firstOrNull()?.author != "user") out += "$chatId must open with a user message"

            val summarized = obj.optStringOrNull("summarized_up_to")
            if (summarized != null) {
                val index = messages.indexOfFirst { it.id == summarized }
                if (index < 0) out += "$chatId summarized_up_to is not one of its messages"
                if (index == messages.lastIndex) out += "$chatId summarized_up_to leaves nothing after it"
            }
            chats[chatId] = Chat(chatId, scopeType, messages, summarized, obj.stringList("single_subject_terms"))
        }
        if (chats.size < minChats) out += "only ${chats.size} chats; at least $minChats are required"
        val longChats = chats.values.count { it.messages.size > longChatMessages }
        if (longChats < minLongChats) {
            out += "only $longChats chats exceed $longChatMessages messages; $minLongChats are required"
        }

        // Questions.
        val questionIds = hashSetOf<String>()
        val counts = hashMapOf<String, Int>()
        val questions = root.getJSONArray("questions")
        for (n in 0 until questions.length()) {
            val question = questions.getJSONObject(n)
            val qid = question.optString("id")
            if (qid.isBlank() || !questionIds.add(qid)) out += "question #$n id '$qid' is blank or repeated"
            if (messageOwner.containsKey(qid) || chats.containsKey(qid)) out += "$qid reuses another id"
            val situation = question.optString("situation")
            if (situation !in minimums) {
                out += "$qid has unknown situation '$situation'"
                continue
            }
            val chat = chats[question.optString("chat")]
            if (chat == null) {
                out += "$qid asks in unknown chat '${question.optString("chat")}'"
                continue
            }
            val asked = parseTime(question.optStringOrNull("asked_at"))
            val last = chat.messages.mapNotNull { it.time }.maxOrNull()
            if (asked == null) out += "$qid has no valid asked_at"
            else if (last != null && asked <= last) out += "$qid is asked before the chat's last message"
            val text = question.optString("message")
            if (text.isBlank()) out += "$qid has no message"

            val expect = question.optJSONArray("expect")?.strings()
            val mustNot = question.optJSONArray("must_not")?.strings()
            if (expect == null || mustNot == null) {
                out += "$qid needs expect and must_not arrays"
                continue
            }
            if (expect.toSet().size != expect.size) out += "$qid repeats an expected id"
            if (mustNot.toSet().size != mustNot.size) out += "$qid repeats a must_not id"
            if (expect.any { it in mustNot }) out += "$qid lists an id in both expect and must_not"
            for (id in expect + mustNot) {
                val owner = messageOwner[id]
                val message = owner?.let { chats.getValue(it).messages.first { m -> m.id == id } }
                if (message == null) out += "$qid references unknown message $id"
                else if (message.author != "user") out += "$qid references $id, which does not open an exchange"
            }
            for (id in expect) {
                if (messageOwner[id] != chat.id) {
                    out += "$qid expects $id from another chat"
                    continue
                }
                val exchange = chat.exchange(id)
                if (exchange.first().visibility != "visible") out += "$qid expects hidden message $id"
                if (exchange.none { it.author == "ai" && it.visibility == "visible" && it.status == "finished" }) {
                    out += "$qid expects $id, whose exchange has no visible finished reply"
                }
            }
            val before = out.size
            checkSituation(situation, qid, question, text, chat, chats, expect, mustNot, out)
            if (out.size == before) counts.merge(situation, 1, Int::plus)
        }

        for ((situation, minimum) in minimums) {
            val have = counts[situation] ?: 0
            if (have < minimum) out += "$situation has $have valid questions; at least $minimum are required"
        }
        return out
    }

    private fun checkSituation(
        situation: String,
        qid: String,
        question: JSONObject,
        text: String,
        chat: Chat,
        chats: Map<String, Chat>,
        expect: List<String>,
        mustNot: List<String>,
        out: MutableList<String>,
    ) {
        fun requireExpected() {
            if (expect.isEmpty()) out += "$qid ($situation) must expect at least one exchange"
        }

        fun detailIn(openerId: String, requireLong: Boolean, input: String?) {
            val detail = question.optString("detail")
            if (detail.isBlank()) {
                out += "$qid needs a detail"
                return
            }
            val exchange = chat.exchange(openerId)
            if (!requireLong) {
                if (!contains(currentText(exchange), detail)) out += "$qid detail is not in exchange $openerId"
                return
            }
            val opener = exchange.first()
            if (words(detail) > maxDetailWords) out += "$qid detail is longer than $maxDetailWords words"
            if (!contains(opener.text, detail)) out += "$qid detail is not in long message $openerId"
            if (words(opener.text) < longMessageWords) {
                out += "$qid message $openerId has ${words(opener.text)} words; at least $longMessageWords"
            }
            if (opener.input != input) out += "$qid message $openerId is not $input"
            if (input == "dictated" && opener.text.contains('.')) {
                out += "$qid dictated message $openerId contains a period"
            }
        }

        fun matchTerms(): List<String> {
            val terms = question.stringList("match_terms")
            if (terms.isEmpty()) out += "$qid needs match_terms"
            for (term in terms) if (!contains(text, term)) out += "$qid message lacks match term '$term'"
            return terms
        }

        fun ownMessage(field: String): Message? {
            val id = question.optString(field)
            val message = chat.messages.firstOrNull { it.id == id }
            if (message == null) out += "$qid $field '$id' is not in chat ${chat.id}"
            return message
        }

        when (situation) {
            "exact_detail_far_back" -> {
                requireExpected()
                if (chat.messages.size <= longChatMessages) out += "$qid chat has $longChatMessages or fewer messages"
                for (id in expect) {
                    val after = chat.messages.size - 1 - chat.indexOf(id)
                    if (after < farBackMessages) out += "$qid $id has only $after messages after it"
                    detailIn(id, requireLong = false, input = null)
                }
            }

            "follow_up_without_subject" -> {
                requireExpected()
                val turns = question.optJSONArray("preceding_turns")
                val terms = question.stringList("subject_terms")
                if (turns == null || turns.length() == 0) {
                    out += "$qid needs preceding_turns"
                    return
                }
                val first = turns.getJSONObject(0)
                if (first.optString("author") != "user") out += "$qid preceding_turns must start with the user"
                if (terms.isEmpty()) out += "$qid needs subject_terms"
                if (terms.none { contains(first.optString("text"), it) }) {
                    out += "$qid preceding user turn does not name the subject"
                }
                for (term in terms) if (contains(text, term)) out += "$qid follow-up names its subject '$term'"
                for (id in expect) {
                    if (terms.none { contains(currentText(chat.exchange(id)), it) }) {
                        out += "$qid expected $id is not about the subject"
                    }
                }
            }

            "time_reference" -> {
                requireExpected()
                val window = question.optJSONObject("time_window")
                val from = parseTime(window?.optStringOrNull("from"))
                val to = parseTime(window?.optStringOrNull("to"))
                val asked = parseTime(question.optStringOrNull("asked_at"))
                if (from == null || to == null || from >= to) {
                    out += "$qid needs a valid time_window"
                    return
                }
                if (asked != null && to >= asked) out += "$qid time_window does not end before asked_at"
                if (mustNot.isEmpty()) out += "$qid needs an exchange outside the window in must_not"
                for (id in expect) {
                    val t = chat.messages[chat.indexOf(id)].time
                    if (t == null || t < from || t > to) out += "$qid expected $id is outside the window"
                }
                for (id in mustNot.filter { chat.indexOf(it) >= 0 }) {
                    val t = chat.messages[chat.indexOf(id)].time
                    if (t != null && t >= from && t <= to) out += "$qid must_not $id is inside the window"
                }
            }

            "before_summary" -> {
                requireExpected()
                val boundary = chat.summarizedUpTo?.let { chat.indexOf(it) } ?: -1
                if (boundary < 0) {
                    out += "$qid chat has no summarized_up_to"
                    return
                }
                for (id in expect) {
                    val end = chat.indexOf(id) + chat.exchange(id).size - 1
                    if (end > boundary) out += "$qid expected $id is not wholly before the summary point"
                    detailIn(id, requireLong = false, input = null)
                }
            }

            "nothing_relevant" ->
                if (expect.isNotEmpty()) out += "$qid expects something although nothing is relevant"

            "long_typed_detail" -> {
                requireExpected()
                for (id in expect) detailIn(id, requireLong = true, input = "typed")
            }

            "long_dictated_detail" -> {
                requireExpected()
                for (id in expect) detailIn(id, requireLong = true, input = "dictated")
            }

            "hidden_best_match" -> {
                val terms = matchTerms()
                val hidden = ownMessage("hidden_message") ?: return
                if (hidden.visibility != "hidden") out += "$qid hidden_message is visible"
                val opener = chat.openerOf(hidden.id)
                val exchange = opener?.let { chat.exchange(it) }.orEmpty()
                if (exchange.any { it.visibility != "hidden" }) out += "$qid hidden exchange has visible parts"
                if (opener !in mustNot) out += "$qid hidden exchange is not in must_not"
                for (term in terms) if (!contains(hidden.text, term)) out += "$qid hidden text lacks '$term'"
            }

            "edited_old_words" -> {
                val terms = matchTerms()
                val edited = ownMessage("edited_message") ?: return
                val old = edited.editedFrom
                if (old == null) {
                    out += "$qid edited_message has no edited_from"
                    return
                }
                if (edited.id !in mustNot) out += "$qid edited exchange is not in must_not"
                for (term in terms) {
                    if (!contains(old, term)) out += "$qid old text lacks '$term'"
                    if (chat.messages.any { contains(it.text, term) }) {
                        out += "$qid old word '$term' is still in the chat's current text"
                    }
                }
            }

            "failed_or_unfinished_reply" -> {
                val terms = matchTerms()
                val reply = ownMessage("failed_message") ?: return
                if (reply.author != "ai" || reply.status !in setOf("failed", "unfinished")) {
                    out += "$qid failed_message is not a failed or unfinished reply"
                }
                val opener = chat.openerOf(reply.id)
                if (opener !in mustNot) out += "$qid failed exchange is not in must_not"
                for (term in terms) {
                    if (!contains(reply.text, term)) out += "$qid failed reply lacks '$term'"
                    val elsewhere = chat.messages.filter { it.id != reply.id && contains(it.text, term) }
                    if (elsewhere.isNotEmpty()) out += "$qid '$term' also appears in ${elsewhere.map { it.id }}"
                }
            }

            "single_subject_chat" -> {
                requireExpected()
                val terms = chat.singleSubjectTerms
                if (terms.isEmpty()) out += "$qid chat has no single_subject_terms"
                if (mustNot.isEmpty()) out += "$qid needs same-subject exchanges in must_not"
                for (m in chat.messages.filter { it.author == "user" }) {
                    if (terms.none { contains(m.text, it) }) out += "$qid chat message ${m.id} is off the subject"
                }
                for (id in mustNot) if (chat.indexOf(id) < 0) out += "$qid must_not $id is from another chat"
            }

            "roleplay_and_companion" -> {
                requireExpected()
                val other = chats[question.optString("other_chat")]
                val terms = question.stringList("shared_terms")
                if (other == null || other.id == chat.id) {
                    out += "$qid needs a different other_chat"
                    return
                }
                if (setOf(chat.scopeType, other.scopeType) != setOf("roleplay_world", "companion")) {
                    out += "$qid must pair a roleplay chat with a companion chat"
                }
                if (terms.isEmpty()) out += "$qid needs shared_terms"
                if (mustNot.isEmpty()) out += "$qid needs the other chat's exchange in must_not"
                for (id in mustNot) if (other.indexOf(id) < 0) out += "$qid must_not $id is not in other_chat"
                for (term in terms) {
                    if (!contains(text, term)) out += "$qid message lacks shared term '$term'"
                    for (id in expect) {
                        if (!contains(currentText(chat.exchange(id)), term)) out += "$qid $id lacks '$term'"
                    }
                    for (id in mustNot.filter { other.indexOf(it) >= 0 }) {
                        if (!contains(currentText(other.exchange(id)), term)) out += "$qid $id lacks '$term'"
                    }
                }
            }

            "very_long_exchange" -> {
                requireExpected()
                for (id in expect) {
                    val count = chat.exchange(id).sumOf { words(it.text) }
                    if (count <= veryLongExchangeWords) out += "$qid exchange $id has only $count words"
                }
            }

            "more_than_limit" -> {
                val terms = matchTerms()
                if (question.optInt("limit", -1) != exchangeLimit) out += "$qid limit must be $exchangeLimit"
                if (expect.size <= exchangeLimit) out += "$qid expects ${expect.size} exchanges; more than $exchangeLimit needed"
                for (id in expect) for (term in terms) {
                    if (!contains(currentText(chat.exchange(id)), term)) out += "$qid $id lacks '$term'"
                }
            }

            "decision_changed" -> {
                val terms = matchTerms()
                val early = ownMessage("early") ?: return
                val later = ownMessage("later") ?: return
                if (early.author != "user" || later.author != "user") out += "$qid early and later open exchanges"
                if (early.time == null || later.time == null || early.time >= later.time) {
                    out += "$qid early decision is not before the later change"
                }
                if (early.id !in expect || later.id !in expect) out += "$qid must expect both decisions"
                val matches = question.optString("matches")
                val (matched, unmatched) = when (matches) {
                    "early" -> early to later
                    "later" -> later to early
                    else -> {
                        out += "$qid matches must be early or later"
                        return
                    }
                }
                for (term in terms) {
                    if (!contains(currentText(chat.exchange(matched.id)), term)) {
                        out += "$qid '$term' is not in the $matches decision"
                    }
                    if (contains(currentText(chat.exchange(unmatched.id)), term)) {
                        out += "$qid '$term' also matches the other decision"
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun theTestSetIsWellFormedAndEverySampleHasItsSituation() {
        val found = problems(load())
        assertTrue(found.joinToString("\n", prefix = "Problems:\n"), found.isEmpty())
    }

    @Test
    fun everySituationMeetsItsMinimum() {
        val questions = load().getJSONArray("questions")
        val counts = (0 until questions.length())
            .groupingBy { questions.getJSONObject(it).getString("situation") }
            .eachCount()
        for ((situation, minimum) in minimums) {
            val have = counts[situation] ?: 0
            assertTrue("$situation has $have questions; at least $minimum", have >= minimum)
        }
        assertEquals("unknown situations", emptySet<String>(), counts.keys - minimums.keys)
    }

    // The checks below change one thing in a copy of the set and confirm the checker notices,
    // so a label alone can never satisfy a situation.

    private fun mutated(edit: (JSONObject) -> Unit): List<String> {
        val root = load()
        edit(root)
        return problems(root)
    }

    private fun JSONObject.chat(id: String): JSONObject {
        val chats = getJSONArray("chats")
        return (0 until chats.length()).map { chats.getJSONObject(it) }.first { it.getString("id") == id }
    }

    private fun JSONObject.question(id: String): JSONObject {
        val questions = getJSONArray("questions")
        return (0 until questions.length()).map { questions.getJSONObject(it) }.first { it.getString("id") == id }
    }

    private fun JSONObject.message(id: String): JSONObject {
        val chats = getJSONArray("chats")
        for (c in 0 until chats.length()) {
            val messages = chats.getJSONObject(c).getJSONArray("messages")
            for (m in 0 until messages.length()) {
                if (messages.getJSONObject(m).getString("id") == id) return messages.getJSONObject(m)
            }
        }
        error("no message $id")
    }

    private fun assertReported(problems: List<String>, fragment: String) =
        assertTrue("expected a problem containing '$fragment' in $problems", problems.any { fragment in it })

    @Test
    fun aDictatedMessageWithAPeriodIsReported() {
        val found = mutated { root ->
            val q = root.question("q-mira-dictated-library")
            val message = root.message(q.getJSONArray("expect").getString(0))
            message.put("text", message.getString("text") + ". The end")
        }
        assertReported(found, "contains a period")
    }

    @Test
    fun aHiddenSampleWhoseMessageIsVisibleIsReported() {
        val found = mutated { root ->
            val q = root.question("q-mira-hidden-party")
            root.message(q.getString("hidden_message")).put("visibility", "visible")
        }
        assertReported(found, "hidden_message is visible")
    }

    @Test
    fun anEditedSampleWhoseOldWordsRemainIsReported() {
        val found = mutated { root ->
            val q = root.question("q-app-edited-firebase")
            val message = root.message(q.getString("edited_message"))
            message.put("text", message.getString("text") + " Firebase too")
        }
        assertReported(found, "still in the chat's current text")
    }

    @Test
    fun aFailedSampleWithAFinishedReplyIsReported() {
        val found = mutated { root ->
            val q = root.question("q-club-failed-villanelle")
            root.message(q.getString("failed_message")).put("status", "finished")
        }
        assertReported(found, "is not a failed or unfinished reply")
    }

    @Test
    fun aSummarySampleAfterTheSummaryPointIsReported() {
        val found = mutated { root ->
            val chat = root.chat("chat-portugal-trip")
            val first = chat.getJSONArray("messages").getJSONObject(1).getString("id")
            chat.put("summarized_up_to", first)
        }
        assertReported(found, "is not wholly before the summary point")
    }

    @Test
    fun aNothingRelevantSampleWithAnExpectedAnswerIsReported() {
        val found = mutated { root ->
            val q = root.question("q-mira-nothing-boardgame")
            val any = root.question("q-mira-typed-vet").getJSONArray("expect").getString(0)
            q.put("expect", JSONArray().put(any))
        }
        assertReported(found, "expects something although nothing is relevant")
    }

    @Test
    fun aShortenedVeryLongExchangeIsReported() {
        val found = mutated { root ->
            val q = root.question("q-rowan-long-week7")
            val opener = q.getJSONArray("expect").getString(0)
            val chat = root.chat("chat-companion-rowan").getJSONArray("messages")
            val index = (0 until chat.length()).first { chat.getJSONObject(it).getString("id") == opener }
            chat.getJSONObject(index + 1).put("text", "A short plan.")
        }
        assertReported(found, "has only")
    }

    @Test
    fun aFollowUpThatNamesItsSubjectIsReported() {
        val found = mutated { root ->
            root.question("q-app-followup-coil").put("message", "Why did we go with Coil?")
        }
        assertReported(found, "follow-up names its subject")
    }

    @Test
    fun aMissingExpectedIdIsReported() {
        val found = mutated { root ->
            root.question("q-garden-exact-soil").put("expect", JSONArray().put("00000000-0000-0000-0000-000000000000"))
        }
        assertReported(found, "unknown message")
    }

    @Test
    fun aRepeatedMessageIdIsReported() {
        val found = mutated { root ->
            val messages = root.chat("chat-home-repairs").getJSONArray("messages")
            messages.getJSONObject(1).put("id", messages.getJSONObject(0).getString("id"))
        }
        assertReported(found, "id is not unique")
    }

    @Test
    fun aTimeSampleWithAnAnswerOutsideItsWindowIsReported() {
        val found = mutated { root ->
            val q = root.question("q-garden-time-march")
            q.getJSONObject("time_window").put("from", "2026-03-10T00:00:00Z")
        }
        assertReported(found, "outside the window")
    }
}
