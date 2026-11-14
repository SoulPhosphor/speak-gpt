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

package org.teslasoft.assistant.preferences.memory.librarian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.librarian.LongMemoryReread.WriteResult

/**
 * The one-time re-read of memories cut at the previous 512-token limit
 * (conversation_recall_plan.md Step 1): failures, retries, interrupted
 * passes, and memories edited while they are read.
 */
class LongMemoryRereadTest {

    /** In-memory stand-in for the memory store and the model. A document's
     *  token count is its length in characters. */
    private class FakeHost(var currentMarker: String = "2048|model-a") : LongMemoryReread.Host {
        val documents = LinkedHashMap<String, String>()
        val vectors = HashMap<String, FloatArray>()
        val meta = HashMap<String, String>()
        val readsOf = ArrayList<String>()
        /** Called before each read; may throw or edit memories. */
        var beforeRead: (String) -> Unit = {}
        /** Called between the read and the write (an edit landing mid-read). */
        var beforeWrite: (String) -> Unit = {}

        fun add(id: String, length: Int, letter: Char = 'x') {
            documents[id] = letter.toString().repeat(length)
            vectors[id] = floatArrayOf(-1f)
        }

        override val marker: String get() = currentMarker
        override fun readMeta(key: String) = meta[key]
        override fun writeMeta(key: String, value: String) { meta[key] = value }
        override fun deleteMeta(key: String) { meta.remove(key) }
        override fun memoriesWithVector() =
            documents.filterKeys { it in vectors }.map { LongMemoryReread.Item(it.key, it.value) }
        override fun countTokens(document: String) = document.length
        override fun read(document: String): FloatArray {
            beforeRead(document)
            readsOf += document
            return floatArrayOf(document.length.toFloat())
        }
        override fun replaceIfCurrent(memoryId: String, document: String, vector: FloatArray): WriteResult {
            beforeWrite(memoryId)
            val current = documents[memoryId] ?: return WriteResult.GONE
            if (memoryId !in vectors) return WriteResult.GONE
            if (current != document) return WriteResult.CHANGED
            vectors[memoryId] = vector
            return WriteResult.REPLACED
        }
        override fun currentDocument(memoryId: String) = documents[memoryId]

        fun idOf(document: String) = documents.entries.first { it.value == document }.key
    }

    private fun reread(host: FakeHost) = LongMemoryReread(host).run()

    @Test
    fun onlyMemoriesLongerThanTheOldLimitAreRead() {
        val host = FakeHost()
        host.add("short", 512)
        host.add("long", 513)
        val shortVector = host.vectors["short"]
        val outcome = reread(host)
        assertEquals(1, outcome.read)
        assertEquals(1, outcome.replaced)
        assertTrue(outcome.complete)
        assertSame(shortVector, host.vectors["short"])
        assertEquals(513f, host.vectors["long"]!![0])
        assertEquals("2048|model-a", host.meta[LongMemoryReread.META_COMPLETE])
        assertNull(host.meta[LongMemoryReread.META_PROGRESS])
    }

    @Test
    fun aCompletedPassReadsNothingAgain() {
        val host = FakeHost()
        host.add("long", 900)
        reread(host)
        host.readsOf.clear()
        val outcome = reread(host)
        assertTrue(outcome.complete)
        assertEquals(0, outcome.read)
        assertTrue(host.readsOf.isEmpty())
    }

    @Test
    fun aFailedReadKeepsTheOldVectorAndLeavesThePassIncomplete() {
        val host = FakeHost()
        host.add("a", 600, 'a')
        host.add("b", 700, 'b')
        host.add("c", 800, 'c')
        host.beforeRead = { if (it.startsWith("b")) throw IllegalStateException("model failed") }
        val outcome = reread(host)

        assertFalse(outcome.complete)
        assertEquals(1, outcome.stillPending)
        assertEquals(-1f, host.vectors["b"]!![0])
        assertEquals(600f, host.vectors["a"]!![0])
        assertEquals(800f, host.vectors["c"]!![0])
        assertNull(host.meta[LongMemoryReread.META_COMPLETE])
    }

    @Test
    fun aRetryReadsOnlyTheMemoriesThatFailed() {
        val host = FakeHost()
        host.add("a", 600, 'a')
        host.add("b", 700, 'b')
        host.add("c", 800, 'c')
        host.beforeRead = { if (it.startsWith("b")) throw IllegalStateException("model failed") }
        reread(host)

        host.beforeRead = {}
        host.readsOf.clear()
        val retry = reread(host)
        assertTrue(retry.complete)
        assertEquals(listOf("b"), host.readsOf.map { host.idOf(it) })
        assertEquals(700f, host.vectors["b"]!![0])
        assertEquals("2048|model-a", host.meta[LongMemoryReread.META_COMPLETE])
        assertNull(host.meta[LongMemoryReread.META_PROGRESS])
    }

    @Test
    fun aCountingFailureLeavesTheMemoryForTheNextPass() {
        val fake = FakeHost().apply { add("a", 600) }
        val host = object : LongMemoryReread.Host by fake {
            override fun countTokens(document: String): Int = throw IllegalStateException("tokenizer failed")
        }
        val outcome = LongMemoryReread(host).run()
        assertFalse(outcome.complete)
        assertEquals(1, outcome.stillPending)
        assertNull(host.readMeta(LongMemoryReread.META_COMPLETE))
    }

    @Test
    fun anInterruptedPassResumesWithoutRepeatingFinishedMemories() {
        val host = FakeHost()
        host.add("a", 600, 'a')
        host.add("b", 700, 'b')
        host.add("c", 800, 'c')
        host.beforeRead = { if (it.startsWith("b")) throw InterruptedException() }
        val first = reread(host)
        assertTrue(Thread.interrupted()) // the interrupt is passed on; clear it for the test
        assertFalse(first.complete)
        // Stopped at "b": "c" was never reached, "a" is recorded as done.
        assertEquals(listOf("a"), host.readsOf.map { host.idOf(it) })
        assertNull(host.meta[LongMemoryReread.META_COMPLETE])

        // The app restarts: a new pass with the same saved progress.
        host.beforeRead = {}
        host.readsOf.clear()
        val resumed = reread(host)
        assertTrue(resumed.complete)
        assertEquals(listOf("b", "c"), host.readsOf.map { host.idOf(it) })
    }

    @Test
    fun aMemoryEditedDuringItsReadIsReadAgainWithItsNewText() {
        val host = FakeHost()
        host.add("a", 600, 'a')
        var edited = false
        host.beforeWrite = {
            if (!edited) { edited = true; host.documents["a"] = "e".repeat(650) }
        }
        val outcome = reread(host)
        assertTrue(outcome.complete)
        assertEquals(2, outcome.read)
        // The vector matches the edited text, never the old one.
        assertEquals(650f, host.vectors["a"]!![0])
    }

    @Test
    fun aMemoryEditedToAShortTextIsStillWrittenFromItsNewText() {
        val host = FakeHost()
        host.add("a", 600, 'a')
        var edited = false
        host.beforeWrite = {
            if (!edited) { edited = true; host.documents["a"] = "e".repeat(40) }
        }
        val outcome = reread(host)
        assertTrue(outcome.complete)
        assertEquals(40f, host.vectors["a"]!![0])
    }

    @Test
    fun aMemoryThatKeepsChangingIsNeverMarkedDone() {
        val host = FakeHost()
        host.add("a", 600, 'a')
        host.add("b", 700, 'b')
        var edits = 0
        host.beforeWrite = { id -> if (id == "a") host.documents["a"] = "e".repeat(600 + ++edits) }
        val outcome = reread(host)

        assertFalse(outcome.complete)
        assertEquals(1, outcome.stillPending)
        assertEquals(LongMemoryReread.MAX_READS_PER_MEMORY + 1, outcome.read)
        assertEquals(-1f, host.vectors["a"]!![0])
        assertEquals(700f, host.vectors["b"]!![0])
        assertNull(host.meta[LongMemoryReread.META_COMPLETE])

        // Once the edits stop, the next pass finishes it and reads only "a".
        host.beforeWrite = {}
        host.readsOf.clear()
        assertTrue(reread(host).complete)
        assertEquals(listOf("a"), host.readsOf.map { host.idOf(it) })
    }

    @Test
    fun aMemoryArchivedOrDeletedDuringTheReadIsNotWrittenOrLeftPending() {
        val host = FakeHost()
        host.add("archived", 600, 'a')
        host.add("deleted", 700, 'd')
        host.beforeWrite = { id ->
            if (id == "archived") host.vectors.remove("archived")
            if (id == "deleted") host.documents.remove("deleted")
        }
        val outcome = reread(host)
        assertTrue(outcome.complete)
        assertEquals(0, outcome.replaced)
        assertFalse("archived" in host.vectors)
        assertEquals(-1f, host.vectors["deleted"]!![0])
    }

    @Test
    fun progressSavedForAnotherModelOrLimitIsIgnored() {
        val host = FakeHost("2048|model-a")
        host.add("a", 600, 'a')
        host.add("b", 700, 'b')
        host.beforeRead = { if (it.startsWith("b")) throw IllegalStateException("failed") }
        reread(host)

        host.currentMarker = "2048|model-b"
        host.beforeRead = {}
        host.readsOf.clear()
        val outcome = reread(host)
        assertTrue(outcome.complete)
        assertEquals(listOf("a", "b"), host.readsOf.map { host.idOf(it) })
        assertEquals("2048|model-b", host.meta[LongMemoryReread.META_COMPLETE])
    }

    @Test
    fun progressRoundTripsAndRejectsAnotherMarker() {
        val encoded = LongMemoryReread.encodeProgress("m", listOf("a", "b"))
        assertEquals(setOf("a", "b"), LongMemoryReread.decodeProgress(encoded, "m"))
        assertTrue(LongMemoryReread.decodeProgress(encoded, "other").isEmpty())
        assertTrue(LongMemoryReread.decodeProgress(null, "m").isEmpty())
    }
}
