/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.recall.eval

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.conversation.index.IndexSourceMessage
import org.teslasoft.assistant.conversation.index.SpeakerTitlePolicy
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.memory.scan.ScanMessageScope

/**
 * The conversation test chats (Step 6's frozen `conversations.json` and the
 * Step 14 additions), read for the recall evaluation. Nothing here changes a
 * fixture or an expected answer.
 */
class RecallFixtures(val chats: Map<String, Chat>, val questions: List<Question>, val followUps: List<FollowUp>, val identities: List<Identity>) {

    class Chat(val id: String, val messages: List<IndexSourceMessage>, val summarizedUpTo: String?, val frozen: Boolean) {
        /** Which messages the AI already reads in full: after the summary point, or none when the whole chat is sent. */
        fun keptInFull(): Set<String>? = summarizedUpTo?.let { last ->
            val i = messages.indexOfFirst { it.messageId == last }
            messages.drop(i + 1).map { it.messageId }.toSet()
        }
    }

    class Question(
        val id: String,
        val situation: String,
        val chat: String,
        val askedAtMillis: Long,
        val message: String,
        val expect: List<String>,
        val mustNot: List<String>,
        val frozen: Boolean,
        /** "frozen" (Step 6), "additions" (Step 14, used to choose values) or "holdout" (never used to choose values). */
        val set: String,
        val names: List<String>,
        val early: String?,
        val later: String?,
        val required: String?
    )

    class FollowUp(
        val id: String,
        val kind: String,
        val chat: String,
        val askedAtMillis: Long,
        val previousMessage: String,
        val message: String,
        val expect: List<String>,
        val mustNot: List<String>,
        val helperQuery: String,
        val set: String
    )

    class Identity(val id: String, val currentName: String)

    /** The permanent IDs each name in [names] resolves to by the identities' current names; unknown names are left out. */
    fun resolve(names: List<String>): List<Set<String>> = names.mapNotNull { name ->
        identities.filter { it.currentName.equals(name, ignoreCase = true) }.map { it.id }.toSet().takeIf { it.isNotEmpty() }
    }

    companion object {
        /** A: the owner's default titles. */
        val GENERIC: SpeakerTitlePolicy = SpeakerTitlePolicy.DEFAULT

        /** B: the default title followed by the name recorded on the message, when one was recorded. */
        val WITH_NAMES = SpeakerTitlePolicy { m ->
            val base = if (m.isBot) SpeakerTitlePolicy.COMPANION else SpeakerTitlePolicy.HUMAN
            m.recordedSpeakerName?.let { "$base: $it" } ?: base
        }

        /** The frozen Step 6 set, then each further set by name ("additions", "holdout"). */
        fun load(frozenJson: String, vararg others: Pair<String, String>): RecallFixtures {
            val roots = listOf("frozen" to JSONObject(frozenJson)) + others.map { it.first to JSONObject(it.second) }
            val chats = LinkedHashMap<String, Chat>()
            val questions = ArrayList<Question>()
            val followUps = ArrayList<FollowUp>()
            val identities = ArrayList<Identity>()
            for ((set, root) in roots) {
                root.getJSONArray("chats").objects().forEach { c -> chats[c.getString("id")] = chat(c, set == "frozen") }
                questions += root.getJSONArray("questions").objects().map { question(it, set) }
                root.optJSONArray("follow_ups")?.objects()?.forEach { f ->
                    followUps += FollowUp(f.getString("id"), f.getString("kind"), f.getString("chat"), millis(f.getString("asked_at")),
                        f.getString("previous_message"), f.getString("message"), f.getJSONArray("expect").strings(),
                        f.getJSONArray("must_not").strings(), f.getString("helper_query"), set)
                }
                root.optJSONArray("identities")?.objects()?.forEach { identities += Identity(it.getString("id"), it.getString("current_name")) }
            }
            return RecallFixtures(chats, questions, followUps, identities)
        }

        private fun chat(c: JSONObject, frozen: Boolean): Chat {
            val messages = c.getJSONArray("messages").objects().map { m ->
                val bot = m.getString("author") == "ai"
                val status = m.optString("status", "finished")
                val scope = m.optJSONObject("recorded_scope")?.let { s ->
                    ScanMessageScope(
                        companionId = s.optString("companionId").ifBlank { null },
                        worldId = s.optString("worldId").ifBlank { null },
                        roleplayCharacterId = s.optString("roleplayCharacterId").ifBlank { null },
                        userPersonaId = s.optString("userPersonaId").ifBlank { null }
                    )
                }
                IndexSourceMessage(
                    messageId = m.getString("id"),
                    isBot = bot,
                    modelText = m.getString("text"),
                    hiddenFromAi = m.optString("visibility") == "hidden",
                    completionState = if (!bot) null else when (status) {
                        "finished" -> MessageCompletionState.DONE
                        "failed" -> MessageCompletionState.FAILED
                        else -> MessageCompletionState.STREAMING
                    },
                    timeMillis = m.optString("time").takeIf { it.isNotBlank() }?.let(::millis),
                    scope = scope,
                    recordedSpeakerName = m.optString("speaker_name").ifBlank { null }
                )
            }
            return Chat(c.getString("id"), messages, c.optString("summarized_up_to").ifBlank { null }, frozen)
        }

        private fun question(q: JSONObject, set: String) = Question(
            id = q.getString("id"),
            situation = q.getString("situation"),
            chat = q.getString("chat"),
            askedAtMillis = millis(q.getString("asked_at")),
            message = q.getString("message"),
            expect = q.getJSONArray("expect").strings(),
            mustNot = q.getJSONArray("must_not").strings(),
            frozen = set == "frozen",
            set = set,
            names = q.optJSONArray("names")?.strings().orEmpty(),
            early = q.optString("early").ifBlank { null },
            later = q.optString("later").ifBlank { null },
            required = q.optString("required").ifBlank { null }
        )

        private fun millis(iso: String): Long = Instant.parse(iso).toEpochMilli()

        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

        private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
    }
}
