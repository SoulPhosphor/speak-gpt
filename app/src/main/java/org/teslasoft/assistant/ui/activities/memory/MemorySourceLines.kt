/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.activities.memory

import android.app.Activity
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.TextAppearanceSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.chatnavigation.ChatListIndex
import org.teslasoft.assistant.preferences.chatnavigation.ConversationParticipants
import org.teslasoft.assistant.preferences.memory.MemorySource
import org.teslasoft.assistant.preferences.memory.MemorySourceRules
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.ui.activities.ChatActivity
import org.teslasoft.assistant.ui.chat.ChatSpeakerNames

/**
 * The source lines under a memory's Tags in the memory editor (development
 * plan M.3). A memory from the Memory Scanner shows its conversation: User
 * and Companion (every name the chat shows for each role), Date (the first
 * message), Last Message Date (the newest message, only on a different day),
 * and Source Conversation, which opens the chat. A memory the user wrote
 * shows only its Date. A memory with no recorded source shows nothing.
 *
 * Conversation details come from the chat list's details index, never from
 * reading the chat's history here. Appearance comes from the
 * Widget.App.SourceLine styles.
 */
object MemorySourceLines {

    /** What the editor knows about a source's conversation right now. */
    sealed class Conversation {
        /** The chat exists; [entry] is its indexed details, null when not indexed yet. */
        data class Present(
            val chatId: String,
            val chatName: String,
            val entry: ChatListIndex.Entry?,
            val userNames: List<String>,
            val companionNames: List<String>
        ) : Conversation()
        object Deleted : Conversation()
        object Unavailable : Conversation()
    }

    enum class Label { USER, COMPANION, DATE, LAST_MESSAGE_DATE }

    sealed class Line {
        /** A labeled value; a null value reads Unavailable. */
        data class Labeled(val label: Label, val value: String?) : Line()
        data class Open(val chatId: String, val chatName: String) : Line()
        object Deleted : Line()
        object Unavailable : Line()
    }

    /**
     * The lines for each source, in display order. Pure: [conversation] gives
     * a conversation source's current state, [formatDay] the spelled-out date
     * and [zone] the calendar used to compare days.
     */
    fun groups(
        sources: List<MemorySource>,
        conversation: (MemorySource) -> Conversation?,
        formatDay: (String?) -> String?,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<List<Line>> = MemorySourceRules.displayOrder(sources).mapNotNull { s ->
        when (s.kind) {
            MemorySource.KIND_USER_ENTRY -> listOf(Line.Labeled(Label.DATE, formatDay(s.statedAt)))
            MemorySource.KIND_CONVERSATION, MemorySource.KIND_CHAT_MESSAGE ->
                when (val c = conversation(s)) {
                    null -> null
                    Conversation.Deleted -> listOf(Line.Deleted)
                    Conversation.Unavailable -> listOf(Line.Unavailable)
                    is Conversation.Present -> conversationLines(c, formatDay, zone)
                }
            else -> null
        }
    }

    private fun conversationLines(
        c: Conversation.Present,
        formatDay: (String?) -> String?,
        zone: ZoneId
    ): List<Line> {
        val lines = ArrayList<Line>()
        if (c.userNames.isNotEmpty()) lines.add(Line.Labeled(Label.USER, c.userNames.joinToString(", ")))
        if (c.companionNames.isNotEmpty()) {
            lines.add(Line.Labeled(Label.COMPANION, c.companionNames.joinToString(", ")))
        }
        val first = c.entry?.participants?.firstMessageAt
        val last = c.entry?.participants?.lastMessageAt
        lines.add(Line.Labeled(Label.DATE, first?.let { formatDay(Instant.ofEpochMilli(it).toString()) }))
        if (last != null && (first == null || !sameDay(first, last, zone))) {
            lines.add(Line.Labeled(Label.LAST_MESSAGE_DATE, formatDay(Instant.ofEpochMilli(last).toString())))
        }
        lines.add(Line.Open(c.chatId, c.chatName))
        return lines
    }

    private fun sameDay(a: Long, b: Long, zone: ZoneId): Boolean =
        Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()

    /**
     * Reads [memoryId]'s sources and the current state of their conversations
     * from the chat list and its details index. Worker thread only. When
     * [prepareMissing], a chat not yet in the index is prepared first.
     */
    fun load(
        activity: Activity,
        store: MemoryStore,
        memoryId: String,
        prepareMissing: Boolean
    ): List<List<Line>> {
        val sources = store.memorySources(memoryId)
        if (sources.isEmpty()) return emptyList()
        val list = runCatching {
            ChatPreferences.getChatPreferences().getChatListResult(activity, includeFirstMessage = false)
        }.getOrNull()
        val readable = list != null && ChatStorageHealth.isAuthoritative(list.state)
        val names = list?.chats.orEmpty().associate { ChatPreferences.storedChatId(it) to it["name"].orEmpty() }
        return groups(
            sources,
            conversation = { s -> conversationOf(activity, s.chatId, readable, names, prepareMissing) },
            formatDay = { MemoryDateFormatter.formatDay(it) }
        )
    }

    private fun conversationOf(
        activity: Activity,
        chatId: String?,
        listReadable: Boolean,
        chatNames: Map<String, String>,
        prepareMissing: Boolean
    ): Conversation {
        if (!listReadable) return Conversation.Unavailable
        val id = chatId?.takeIf { it.isNotBlank() } ?: return Conversation.Deleted
        val name = chatNames[id] ?: return Conversation.Deleted
        if (prepareMissing && ChatListIndex.entryFor(activity, id) == null) {
            ChatListIndex.ensureReady(activity, listOf(id))
        }
        val entry = ChatListIndex.entryFor(activity, id)
        val participants = entry?.participants ?: ConversationParticipants()
        // The chat screen's own name rules: the chat's username replaces every
        // user name; an unnamed reply shows the chat's current companion.
        val chatUsername = runCatching { Preferences.getPreferences(activity, id).getChatUsername() }.getOrNull()
        val users = ConversationParticipants.displayed(
            participants.userNames,
            resolveUnnamed = { ChatSpeakerNames.userName(activity) },
            override = chatUsername
        )
        val companions = ConversationParticipants.displayed(
            participants.companionNames,
            resolveUnnamed = {
                ChatSpeakerNames.companionName(activity, currentName = currentCompanionLabel(activity, id))
            }
        )
        return Conversation.Present(id, name, entry, users, companions)
    }

    private fun currentCompanionLabel(activity: Activity, chatId: String): String? = runCatching {
        val personaId = Preferences.getPreferences(activity, chatId).getPersonaId()
        if (personaId.isEmpty()) null else PersonaPreferences.getPersonaPreferences(activity).getPersona(personaId).label
    }.getOrNull()

    /** Fills [container] with [groups]; hides it when there are none. */
    fun render(activity: Activity, container: LinearLayout, groups: List<List<Line>>) {
        container.removeAllViews()
        if (groups.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        val inflater = LayoutInflater.from(activity)
        groups.forEachIndexed { index, lines ->
            val group = inflater.inflate(
                if (index == 0) R.layout.item_memory_source_group
                else R.layout.item_memory_source_group_following,
                container, false
            ) as ViewGroup
            for (line in lines) group.addView(lineView(activity, inflater, group, line))
            container.addView(group)
        }
        container.visibility = View.VISIBLE
    }

    private fun lineView(activity: Activity, inflater: LayoutInflater, parent: ViewGroup, line: Line): View {
        val conversationLabel = activity.getString(R.string.memory_source_conversation)
        return when (line) {
            is Line.Labeled -> labeled(
                activity, inflater, parent,
                activity.getString(
                    when (line.label) {
                        Label.USER -> R.string.memory_source_user
                        Label.COMPANION -> R.string.memory_source_companion
                        Label.DATE -> R.string.memory_source_date
                        Label.LAST_MESSAGE_DATE -> R.string.memory_source_last_message_date
                    }
                ),
                line.value ?: activity.getString(R.string.memory_source_unavailable)
            )
            is Line.Open -> (inflater.inflate(R.layout.item_memory_source_link, parent, false) as TextView).apply {
                text = conversationLabel
                setOnClickListener {
                    activity.startActivity(ChatActivity.rootIntent(activity, line.chatId, line.chatName))
                }
            }
            Line.Deleted -> labeled(
                activity, inflater, parent, conversationLabel, activity.getString(R.string.memory_source_deleted)
            )
            Line.Unavailable -> labeled(
                activity, inflater, parent, conversationLabel, activity.getString(R.string.memory_source_unavailable)
            )
        }
    }

    private fun labeled(
        activity: Activity,
        inflater: LayoutInflater,
        parent: ViewGroup,
        label: String,
        value: String
    ): TextView {
        val view = inflater.inflate(R.layout.item_memory_source_line, parent, false) as TextView
        val text = SpannableStringBuilder(activity.getString(R.string.memory_source_line, label, value))
        val start = text.indexOf(label)
        if (start >= 0) {
            // The label and the separator that follows it take the label style.
            val end = (start + label.length + 1).coerceAtMost(text.length)
            text.setSpan(
                TextAppearanceSpan(activity, R.style.TextAppearance_App_SourceLine_Label),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        view.text = text
        return view
    }
}
