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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * memory-doc-v2 (counterplan §10 A.2): the semantic document is built from
 * the current title, content, and tags; condensed text is an additional
 * hint that can never replace the content; and the document version is part
 * of the embedding key so old-format vectors are never treated as current.
 */
class RetrievalDocumentTest {

    @Test
    fun effectiveKeyCarriesModelTagAndDocumentVersion() {
        assertEquals("gemma-256|memory-doc-v2", RetrievalDocument.effectiveKey("gemma-256"))
        // Distinct from the plain model tag, so pre-v2 vectors never match.
        assertNotEquals("gemma-256", RetrievalDocument.effectiveKey("gemma-256"))
    }

    @Test
    fun currentContentIsAlwaysInTheDocument() {
        val doc = RetrievalDocument.semanticDocument(
            "Sister's dog", "The dog is named Biscuit now", "old note about an unnamed puppy",
            emptyList()
        )
        assertTrue(doc.contains("The dog is named Biscuit now"))
        // The condensed hint rides along but does not replace the content.
        assertTrue(doc.contains("old note about an unnamed puppy"))
    }

    @Test
    fun editingContentChangesTheDocumentEvenWithStaleCondensedText() {
        val before = RetrievalDocument.semanticDocument("t", "original fact", "condensed", emptyList())
        val after = RetrievalDocument.semanticDocument("t", "corrected fact", "condensed", emptyList())
        assertNotEquals(before, after)
    }

    @Test
    fun tagsAndTitleAreInTheDocument() {
        val doc = RetrievalDocument.semanticDocument(
            "Garden plans", "planting schedule", null, listOf("garden", "spring")
        )
        assertTrue(doc.contains("Garden plans"))
        assertTrue(doc.contains("garden, spring"))
    }

    @Test
    fun condensedTextIdenticalToContentIsNotDuplicated() {
        val doc = RetrievalDocument.semanticDocument("t", "same text", "same text", emptyList())
        assertEquals(1, Regex("same text").findAll(doc).count())
        val noHint = RetrievalDocument.semanticDocument("t", "same text", null, emptyList())
        assertEquals(noHint, doc)
        assertFalse(doc.endsWith("\n"))
    }
}
