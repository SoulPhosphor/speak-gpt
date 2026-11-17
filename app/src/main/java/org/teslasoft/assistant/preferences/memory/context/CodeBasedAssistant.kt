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

package org.teslasoft.assistant.preferences.memory.context

import org.teslasoft.assistant.preferences.memory.RetrievableMemory
import org.teslasoft.assistant.preferences.memory.ScoredMemory
import org.teslasoft.assistant.preferences.memory.librarian.SavedMemorySearch

/**
 * Code-based context assistance: subject tracking (conversation_recall_plan.md
 * G.9, Owner 2026-11-16), exactly the evaluated `subject_anchor` method of
 * recall_carryover_holdout/final_methods.json. Its numbers are not tuned.
 *
 *  - A user message sets the subject when it has a confident match of its own:
 *    a memory it matched by meaning and keywords together, by meaning with a
 *    stand-out of at least [CONFIDENT_STANDOUT], or through a section of a long
 *    message. Those memories become the chat's subject.
 *  - A message with no confident match of its own is sent its own matches,
 *    then the subject's memories it did not already find.
 *  - A later confident match replaces the subject. There is no turn limit.
 *
 * A +3 memory matched only through the top-match rule fails the meaning gate,
 * so it never sets the subject by itself.
 *
 * The subject lives only in [SubjectStore], in memory, per chat and embedding
 * model. A carried memory is always the memory as stored now: one that was
 * deleted, disabled, edited out of scope or is otherwise no longer eligible is
 * never carried, and an edit applies at once.
 *
 * After the app restarts (or when the helper is first turned on) a chat has no
 * known subject. The first time such a chat sends a message with no confident
 * match, up to [REBUILD_MESSAGES] earlier user messages are searched, newest
 * first, and the first with a confident match sets the subject. This costs
 * those extra searches once per chat per app run, and only when needed.
 */
class CodeBasedAssistant(private val store: SubjectStore = SubjectStore.shared) : ContextAssistant {

    companion object {
        const val CONFIDENT_STANDOUT = 4.0
        const val REBUILD_MESSAGES = 3

        /** The message's own confident matches, in its own order. */
        fun confident(result: SavedMemorySearch.Result): List<ScoredMemory> {
            val numbers = result.whole.explanations.associateBy { it.memory.memoryId }
            return result.ranked.filter { scored ->
                val id = scored.memory.memoryId
                val e = numbers[id]
                id in result.sectionIds || (e != null && e.passedMeaning &&
                    (e.passedKeyword || (e.standout ?: 0.0) >= CONFIDENT_STANDOUT))
            }
        }

        /**
         * One message under subject tracking, without any stored state: the
         * subject after this message, and the memories it adds. [subject] is
         * the chat's subject before this message (empty for none).
         */
        fun step(own: SavedMemorySearch.Result, subject: List<ScoredMemory>): Pair<List<ScoredMemory>, List<ScoredMemory>> {
            val confident = confident(own)
            if (confident.isNotEmpty()) return confident to emptyList()
            val have = own.ranked.map { it.memory.memoryId }.toSet()
            return subject to subject.filter { it.memory.memoryId !in have }
        }
    }

    override fun assist(input: ContextAssistant.Input): ContextAssistant.Assistance {
        val tag = input.modelTag
        var subject: List<ScoredMemory>? = store.get(input.chatId, tag)
        if (subject == null && confident(input.own).isEmpty()) {
            subject = input.earlierUserMessages.asSequence().take(REBUILD_MESSAGES)
                .mapNotNull { text -> input.searchEarlier(text)?.let(::confident) }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
        }
        val (next, added) = step(input.own, subject.orEmpty())
        val current = added.mapNotNull { carried -> refresh(carried, input.eligible) }
        val output = if (current.isEmpty()) ContextAssistant.Output.Nothing
        else ContextAssistant.Output.Carry(current)
        return ContextAssistant.Assistance(output, commit = { store.put(input.chatId, tag, next) })
    }

    /** The carried memory as stored now, or null when it may not be used. */
    private fun refresh(carried: ScoredMemory, eligible: Map<String, RetrievableMemory>): ScoredMemory? =
        eligible[carried.memory.memoryId]?.let { carried.copy(memory = it) }
}

/**
 * Each chat's current subject for Code-based context assistance, kept in
 * memory for the app process only and never written to disk. An entry belongs
 * to one chat and one embedding model: a different model's subject is
 * dropped, and a deleted chat's subject is removed ([forget]). Bounded to the
 * most recently used [MAX_CHATS] chats.
 */
class SubjectStore {
    companion object {
        const val MAX_CHATS = 64
        val shared = SubjectStore()
    }

    private class Entry(val modelTag: String?, val subject: List<ScoredMemory>)

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > MAX_CHATS
    }

    /** The chat's subject, empty when it has none, or null when it is not
     *  known in this app run for this model. */
    @Synchronized
    fun get(chatId: String, modelTag: String?): List<ScoredMemory>? {
        val entry = entries[chatId] ?: return null
        if (entry.modelTag != modelTag) {
            entries.remove(chatId)
            return null
        }
        return entry.subject
    }

    @Synchronized
    fun put(chatId: String, modelTag: String?, subject: List<ScoredMemory>) {
        entries[chatId] = Entry(modelTag, subject)
    }

    @Synchronized
    fun forget(chatId: String) {
        entries.remove(chatId)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
