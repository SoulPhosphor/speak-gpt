/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.teslasoft.assistant.util.Hash

/**
 * Reads a `chats.json` artifact produced by [ChatLogicalSerializer] and plans
 * the writes that would rebuild those chats in an empty destination. Pure — no
 * Android APIs, no I/O — so the whole parse/validate/plan contract is unit
 * tested on the JVM.
 *
 * This is the read side the app has never had. The restore manager refuses a
 * chats artifact outright, and [org.teslasoft.assistant.preferences.backup.ChatRestoreManager]
 * swaps raw Keystore-wrapped preference files, which cannot cross installations
 * at all. Neither is reused here, so nothing in this path can replace a live
 * chat set.
 *
 * Two rules shape everything below.
 *
 * **Identity is verified, never re-derived.** Each entry carries the stable
 * `chat_id` the source app used. The plan rebuilds the chat-list row from the
 * exported fields and then checks that the row hashes back to that same id
 * through the app's own compatibility rule. A row that came from a legacy chat
 * with no explicit `id` therefore stays keyed on its title hash and never
 * acquires one. A mismatch is a rejected conversion, not a renamed chat.
 *
 * **Nothing is skipped quietly.** Every structural problem is a typed
 * rejection of the whole artifact. There is no partial plan.
 */
object ChatLogicalImportPlan {

    /** The one artifact format this converter accepts. */
    const val SUPPORTED_FORMAT = ChatLogicalSerializer.FORMAT

    /** A per-chat settings value, still type-tagged as the export wrote it. */
    data class SettingEntry(val key: String, val type: String, val value: Any)

    /**
     * One chat's complete rebuild instructions.
     *
     * [listRow] is the exact map that belongs in `chat_list`; [messagesJson] is
     * the history verbatim as the export carried it, never re-encoded, so the
     * conversion cannot alter message content by rewriting it.
     */
    data class ChatPlan(
        val chatId: String,
        val listRow: Map<String, String>,
        val messagesJson: String,
        val messageCount: Int,
        val settings: List<SettingEntry>
    )

    data class Plan(val chats: List<ChatPlan>) {
        val chatCount: Int get() = chats.size
        val messageCount: Int get() = chats.sumOf { it.messageCount }
        val settingCount: Int get() = chats.sumOf { it.settings.size }
    }

    enum class Reason {
        /** The file is not a chat-logical artifact at all. */
        NOT_A_CHATS_ARTIFACT,

        /** A chats artifact, but a format version this build cannot read. */
        UNSUPPORTED_FORMAT,

        /** The source marked the artifact incomplete; it is not migration truth. */
        INCOMPLETE_ARTIFACT,

        /** Structurally broken: unparseable, or a required field is missing. */
        MALFORMED,

        /** A rebuilt row does not hash back to the id the export recorded. */
        IDENTITY_MISMATCH,

        /** Two entries claim the same stable id. */
        DUPLICATE_CHAT_ID,

        /** A settings entry carries a type tag this build cannot restore. */
        UNSUPPORTED_SETTING_TYPE
    }

    sealed class Result {
        data class Ok(val plan: Plan) : Result()

        /** [detail] names the failing entry by stable id or index only — never
         *  a title, a message, or a settings value. */
        data class Rejected(val reason: Reason, val detail: String) : Result()
    }

    fun parse(json: String): Result {
        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            return Result.Rejected(Reason.MALFORMED, "the artifact is not readable JSON")
        }

        val format = root.optString("format", "")
        if (format.isEmpty()) {
            return Result.Rejected(Reason.NOT_A_CHATS_ARTIFACT, "no format marker")
        }
        if (format != SUPPORTED_FORMAT) {
            return Result.Rejected(Reason.UNSUPPORTED_FORMAT, "format $format")
        }
        if (!root.has("complete")) {
            return Result.Rejected(Reason.MALFORMED, "no completeness marker")
        }
        if (!root.optBoolean("complete", false)) {
            return Result.Rejected(
                Reason.INCOMPLETE_ARTIFACT,
                "the source marked this artifact incomplete"
            )
        }

        val chats = root.optJSONArray("chats")
            ?: return Result.Rejected(Reason.MALFORMED, "no chats array")

        val plans = ArrayList<ChatPlan>(chats.length())
        val seen = HashSet<String>()
        for (index in 0 until chats.length()) {
            val entry = chats.optJSONObject(index)
                ?: return Result.Rejected(Reason.MALFORMED, "entry $index is not an object")
            val plan = when (val planned = planChat(entry, index)) {
                is Result.Rejected -> return planned
                is Result.Ok -> planned.plan.chats.single()
            }
            if (!seen.add(plan.chatId)) {
                return Result.Rejected(Reason.DUPLICATE_CHAT_ID, "id ${plan.chatId} appears twice")
            }
            plans.add(plan)
        }
        return Result.Ok(Plan(plans))
    }

    private fun planChat(entry: JSONObject, index: Int): Result {
        val declaredId = entry.optString("chat_id", "")
        if (declaredId.isEmpty()) {
            return Result.Rejected(Reason.MALFORMED, "entry $index has no chat id")
        }

        // The stored name travels as-is. JSON null means the source row had no
        // name at all, which the app hashes as the literal string "null" — so
        // the key is omitted here rather than set to an invented empty string.
        val listRow = LinkedHashMap<String, String>()
        if (!entry.isNull("name")) listRow["name"] = entry.optString("name")
        val keys = entry.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!key.startsWith("list_")) continue
            listRow[key.removePrefix("list_")] = entry.optString(key)
        }

        val rebuiltId = listRow["id"] ?: Hash.hash(listRow["name"].toString())
        if (rebuiltId != declaredId) {
            return Result.Rejected(
                Reason.IDENTITY_MISMATCH,
                "entry $index rebuilds as $rebuiltId but was exported as $declaredId"
            )
        }

        val messages = entry.optJSONArray("messages")
            ?: return Result.Rejected(Reason.MALFORMED, "chat $declaredId has no messages array")

        val settingsArray = entry.optJSONArray("settings")
            ?: return Result.Rejected(Reason.MALFORMED, "chat $declaredId has no settings array")
        val settings = ArrayList<SettingEntry>(settingsArray.length())
        for (position in 0 until settingsArray.length()) {
            val raw = settingsArray.optJSONObject(position)
                ?: return Result.Rejected(
                    Reason.MALFORMED, "chat $declaredId has a malformed settings entry"
                )
            val key = raw.optString("k", "")
            if (key.isEmpty()) {
                return Result.Rejected(
                    Reason.MALFORMED, "chat $declaredId has a settings entry with no key"
                )
            }
            val type = raw.optString("t", "")
            val value: Any = when (type) {
                "s" -> raw.optString("v")
                "b" -> raw.optBoolean("v")
                "i" -> raw.optInt("v")
                "l" -> raw.optLong("v")
                "f" -> raw.optDouble("v").toFloat()
                "ss" -> {
                    val set = raw.optJSONArray("v")
                        ?: return Result.Rejected(
                            Reason.MALFORMED, "chat $declaredId has a malformed string-set setting"
                        )
                    (0 until set.length()).mapTo(LinkedHashSet()) { set.optString(it) }
                }
                else -> return Result.Rejected(
                    Reason.UNSUPPORTED_SETTING_TYPE,
                    "chat $declaredId carries setting type '$type'"
                )
            }
            settings.add(SettingEntry(key, type, value))
        }

        return Result.Ok(
            Plan(
                listOf(
                    ChatPlan(
                        chatId = declaredId,
                        listRow = listRow,
                        messagesJson = messages.toString(),
                        messageCount = messages.length(),
                        settings = settings
                    )
                )
            )
        )
    }
}
