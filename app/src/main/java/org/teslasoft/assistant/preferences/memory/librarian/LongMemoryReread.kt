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

/**
 * The one-time re-read of saved memories whose vectors were cut at the
 * previous 512-token reading limit (conversation_recall_plan.md Step 1, G33).
 *
 * Guarantees:
 * - only memories longer than the previous limit are read; every other
 *   vector is left exactly as it is;
 * - a memory keeps its current vector until a new one has been read and
 *   written, so it never leaves meaning search while this runs;
 * - each memory that is updated is recorded at once, so a failure or an
 *   interrupted pass (the app closed, the process killed) never repeats
 *   finished work;
 * - a vector is written only if the memory still has the text that was read,
 *   checked in the same transaction as the write; an edited memory is read
 *   again with its new text;
 * - the pass is recorded complete only when no long memory still needs
 *   reading. Otherwise the next pass continues where this one stopped.
 *
 * Pure Kotlin: storage and the model are behind [Host], unit tested
 * (LongMemoryRereadTest). It writes no log lines.
 */
class LongMemoryReread(private val host: Host) {

    /** One active memory that has a vector under the current embedding key. */
    data class Item(val memoryId: String, val document: String)

    enum class WriteResult {
        /** The new vector replaced the old one. */
        REPLACED,
        /** The memory's text changed since it was read; nothing was written. */
        CHANGED,
        /** The memory is no longer active or no longer has a vector to
         *  replace; nothing was written and nothing is left to do. */
        GONE
    }

    interface Host {
        /** Identifies the current model, key, and limit; progress saved under
         *  another marker is ignored. */
        val marker: String
        fun readMeta(key: String): String?
        fun writeMeta(key: String, value: String)
        fun deleteMeta(key: String)
        /** Active memories that have a vector under the current key. */
        fun memoriesWithVector(): List<Item>
        /** Full token count of a stored document; may throw. */
        fun countTokens(document: String): Int
        /** Reads [document] at the stored-text limit; may throw. */
        fun read(document: String): FloatArray
        /** Atomically: if the memory is active, still has a vector, and its
         *  document still equals [document], replace the vector. */
        fun replaceIfCurrent(memoryId: String, document: String, vector: FloatArray): WriteResult
        /** The memory's current document, or null when it is not active. */
        fun currentDocument(memoryId: String): String?
    }

    data class Outcome(
        val complete: Boolean,
        val read: Int,
        val replaced: Int,
        val stillPending: Int
    )

    companion object {
        /** Meta key holding the completion marker. */
        const val META_COMPLETE = "stored_text_read_limit"
        /** Meta key holding the marker followed by the ids already updated. */
        const val META_PROGRESS = "stored_text_read_progress"
        /** Reads per memory per pass when it keeps being edited mid-read. */
        const val MAX_READS_PER_MEMORY = 3

        internal fun encodeProgress(marker: String, done: Collection<String>): String =
            (listOf(marker) + done).joinToString("\n")

        /** The ids already updated under [marker]; empty for another marker. */
        internal fun decodeProgress(value: String?, marker: String): MutableSet<String> {
            if (value == null) return LinkedHashSet()
            val lines = value.split('\n')
            if (lines.first() != marker) return LinkedHashSet()
            return lines.drop(1).filter { it.isNotEmpty() }.toCollection(LinkedHashSet())
        }
    }

    fun isComplete(): Boolean = host.readMeta(META_COMPLETE) == host.marker

    fun run(): Outcome {
        val marker = host.marker
        if (isComplete()) return Outcome(true, 0, 0, 0)
        val done = decodeProgress(host.readMeta(META_PROGRESS), marker)
        var reads = 0
        var replaced = 0
        var pending = 0
        for (item in host.memoriesWithVector()) {
            if (item.memoryId in done) continue
            var document = item.document
            var finished = false
            try {
                var attempts = 0
                while (!finished && attempts < MAX_READS_PER_MEMORY) {
                    if (!Librarian.needsLongerRead(host.countTokens(document))) {
                        // Fits the previous limit: its vector is already
                        // exactly what a new read would give.
                        if (document == item.document) { finished = true; break }
                    }
                    attempts++
                    reads++
                    val vector = host.read(document)
                    when (host.replaceIfCurrent(item.memoryId, document, vector)) {
                        WriteResult.REPLACED -> {
                            replaced++
                            done.add(item.memoryId)
                            host.writeMeta(META_PROGRESS, encodeProgress(marker, done))
                            finished = true
                        }
                        WriteResult.GONE -> finished = true
                        WriteResult.CHANGED -> {
                            // Edited during the read: read the new text.
                            val current = host.currentDocument(item.memoryId)
                            if (current == null) finished = true else document = current
                        }
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return Outcome(false, reads, replaced, pending + 1)
            } catch (_: Throwable) {
                // Left as it is: the old vector stays, and the next pass
                // tries this memory again.
            }
            if (!finished) pending++
        }
        if (pending == 0) {
            host.writeMeta(META_COMPLETE, marker)
            host.deleteMeta(META_PROGRESS)
        }
        return Outcome(pending == 0, reads, replaced, pending)
    }
}
