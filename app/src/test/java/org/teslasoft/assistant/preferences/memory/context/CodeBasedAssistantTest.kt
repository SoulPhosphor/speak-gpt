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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.RetrievableMemory
import org.teslasoft.assistant.preferences.memory.ScoredMemory
import org.teslasoft.assistant.preferences.memory.librarian.LiveRetrieval
import org.teslasoft.assistant.preferences.memory.librarian.LongMessageSearch
import org.teslasoft.assistant.preferences.memory.librarian.SavedMemorySearch

/** Code-based context assistance and the rules every choice keeps (G.9, G.9b). */
class CodeBasedAssistantTest {

    private fun mem(id: String, content: String = "memory $id", scope: String = "global") =
        RetrievableMemory(id, scope, content, null, 0, "2026-07-01T00:00:00Z", null)

    /** A memory's numbers for one message. */
    private fun e(id: String, meaning: Boolean = false, keyword: Boolean = false, standout: Double = 0.0) =
        LiveRetrieval.Explanation(mem(id), 0.5, standout, 0.0, emptyList(), meaning, keyword, 0.5, 0.0, 0.5)

    /** One message's own search: [found] passed, [sections] found only by a section. */
    private fun result(vararg found: LiveRetrieval.Explanation, sections: List<String> = emptyList()): SavedMemorySearch.Result {
        val ranked = found.filter { it.passed }.map { ScoredMemory(it.memory, 0.5f, 0.5f) } +
            sections.map { ScoredMemory(mem(it), 0.4f, 0.4f) }
        return SavedMemorySearch.Result(
            LiveRetrieval.Result(ranked.take(found.count { it.passed }), found.toList() + sections.map { e(it) },
                LiveRetrieval.Diagnostics(10, 10, true, ranked.size)),
            LongMessageSearch.Reading.NONE, emptyList(), ranked, sections.toSet()
        )
    }

    private val noMatch = result()
    private val all = listOf("a", "b", "c", "d").associateWith { mem(it) }

    private fun input(
        own: SavedMemorySearch.Result,
        chat: String = "chat",
        tag: String? = "model",
        eligible: Map<String, RetrievableMemory> = all,
        earlier: List<String> = emptyList(),
        searchEarlier: (String) -> SavedMemorySearch.Result? = { null }
    ) = ContextAssistant.Input(chat, tag, own, eligible, earlier, searchEarlier)

    private fun carried(out: ContextAssistant.Output) =
        (out as? ContextAssistant.Output.Carry)?.memories?.map { it.memory.memoryId }.orEmpty()

    @Test
    fun confidentMeansMeaningAndKeywordsOrAStrongStandOutOrASection() {
        val r = result(
            e("both", meaning = true, keyword = true),
            e("strong", meaning = true, standout = 4.0),
            e("weak", meaning = true, standout = 3.9),
            e("words", keyword = true),
            sections = listOf("section")
        )
        assertEquals(listOf("both", "strong", "section"), CodeBasedAssistant.confident(r).map { it.memory.memoryId })
    }

    @Test
    fun aFollowUpWithNoConfidentMatchReceivesTheSubject() {
        val assistant = CodeBasedAssistant(SubjectStore())
        assertEquals(emptyList<String>(), carried(assistant.assist(input(result(e("a", meaning = true, keyword = true))))))
        assertEquals(listOf("a"), carried(assistant.assist(input(noMatch))))
        // No turn limit.
        assertEquals(listOf("a"), carried(assistant.assist(input(noMatch))))
    }

    @Test
    fun aMemoryTheFollowUpAlreadyFoundIsNotAddedTwice() {
        val assistant = CodeBasedAssistant(SubjectStore())
        assistant.assist(input(result(e("a", meaning = true, keyword = true), e("b", meaning = true, keyword = true))))
        assertEquals(listOf("b"), carried(assistant.assist(input(result(e("a", keyword = true))))))
    }

    @Test
    fun aLaterConfidentMatchReplacesTheSubject() {
        val assistant = CodeBasedAssistant(SubjectStore())
        assistant.assist(input(result(e("a", meaning = true, keyword = true))))
        assistant.assist(input(result(e("b", meaning = true, standout = 5.0))))
        assertEquals(listOf("b"), carried(assistant.assist(input(noMatch))))
    }

    @Test
    fun aDeletedDisabledOrOutOfScopeMemoryIsNeverCarried() {
        val assistant = CodeBasedAssistant(SubjectStore())
        assistant.assist(input(result(e("a", meaning = true, keyword = true), e("b", meaning = true, keyword = true))))
        // "a" is no longer among the memories this chat may use.
        assertEquals(listOf("b"), carried(assistant.assist(input(noMatch, eligible = all - "a"))))
    }

    @Test
    fun anEditedMemoryIsCarriedAsItIsStoredNow() {
        val assistant = CodeBasedAssistant(SubjectStore())
        assistant.assist(input(result(e("a", meaning = true, keyword = true))))
        val edited = all + ("a" to mem("a", content = "edited text"))
        val out = assistant.assist(input(noMatch, eligible = edited)) as ContextAssistant.Output.Carry
        assertEquals("edited text", out.memories.single().memory.content)
    }

    @Test
    fun switchingChatsNeverCarriesAnotherChatsSubject() {
        val assistant = CodeBasedAssistant(SubjectStore())
        assistant.assist(input(result(e("a", meaning = true, keyword = true)), chat = "one"))
        assertEquals(emptyList<String>(), carried(assistant.assist(input(noMatch, chat = "two"))))
        assertEquals(listOf("a"), carried(assistant.assist(input(noMatch, chat = "one"))))
    }

    @Test
    fun aChangedEmbeddingModelOrADeletedChatDropsTheSubject() {
        val store = SubjectStore()
        val assistant = CodeBasedAssistant(store)
        assistant.assist(input(result(e("a", meaning = true, keyword = true))))
        assertEquals(emptyList<String>(), carried(assistant.assist(input(noMatch, tag = "other-model"))))
        assistant.assist(input(result(e("a", meaning = true, keyword = true))))
        store.forget("chat")
        assertEquals(emptyList<String>(), carried(assistant.assist(input(noMatch))))
    }

    @Test
    fun afterARestartTheSubjectIsRebuiltFromTheNearestEarlierConfidentMessage() {
        val searched = ArrayList<String>()
        val earlier = mapOf(
            "newest" to noMatch,
            "middle" to result(e("c", meaning = true, keyword = true)),
            "oldest" to result(e("d", meaning = true, keyword = true))
        )
        val assistant = CodeBasedAssistant(SubjectStore())
        val out = assistant.assist(input(noMatch, earlier = listOf("newest", "middle", "oldest")) {
            searched.add(it); earlier[it]
        })
        assertEquals(listOf("c"), carried(out))
        assertEquals(listOf("newest", "middle"), searched)
        // Rebuilt once per chat per app run.
        searched.clear()
        assistant.assist(input(noMatch, earlier = listOf("newest")) { searched.add(it); earlier[it] })
        assertTrue(searched.isEmpty())
    }

    @Test
    fun aConfidentMessageNeverSearchesEarlierMessages() {
        val assistant = CodeBasedAssistant(SubjectStore())
        assistant.assist(input(result(e("a", meaning = true, keyword = true)), earlier = listOf("x")) {
            error("not searched")
        })
    }

    @Test
    fun theRebuildLooksAtMostThreeEarlierMessages() {
        val searched = ArrayList<String>()
        CodeBasedAssistant(SubjectStore()).assist(input(noMatch, earlier = listOf("1", "2", "3", "4")) {
            searched.add(it); noMatch
        })
        assertEquals(listOf("1", "2", "3"), searched)
    }

    @Test
    fun offAddsNothingAndCandidatesKeepTheMessagesOwnResultsFirst() {
        assertEquals(ContextAssistant.Output.Nothing, ContextAssistant.Off.assist(input(noMatch)))
        val own = listOf(ScoredMemory(mem("a"), 0.5f, 0.5f), ScoredMemory(mem("b"), 0.4f, 0.4f))
        val added = listOf(ScoredMemory(mem("b"), 0.9f, 0.9f), ScoredMemory(mem("c"), 0.9f, 0.9f))
        val (pool, carriedIds) = ContextAssistant.candidates(own, added)
        assertEquals(listOf("a", "b", "c"), pool.map { it.memory.memoryId })
        assertEquals(0.4f, pool[1].score)
        assertEquals(setOf("c"), carriedIds)
    }
}
