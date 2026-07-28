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
 * memory-doc-v2 (counterplan §10 A.2): the versioned semantic retrieval
 * document and its embedding key.
 *
 * The document is title + current content + tags; the condensed
 * `embedding_text` rides along as an ADDITIONAL hint only and can never
 * replace the current content — so an edited memory cannot remain
 * discoverable solely by stale condensed wording. The document version is
 * part of the effective embedding key (existing model-keyed embeddings
 * table, no schema change), so vectors built from the old document format
 * are never treated as current; until a rebuild produces current vectors,
 * the complete-set rule keeps retrieval on the lexical path. Pure Kotlin,
 * unit tested (RetrievalDocumentTest).
 */
object RetrievalDocument {

    const val DOC_VERSION = "memory-doc-v2"

    /** The embeddings-table key for [modelTag] under the current document
     *  version. Old plain-tag vectors simply never match this key. */
    fun effectiveKey(modelTag: String): String = "$modelTag|$DOC_VERSION"

    /** The text embedded for one memory. */
    fun semanticDocument(
        title: String,
        content: String,
        embeddingText: String?,
        tags: List<String>
    ): String {
        val sb = StringBuilder(title.trim())
        sb.append('\n').append(content.trim())
        embeddingText?.trim()?.takeIf { it.isNotEmpty() && it != content.trim() }
            ?.let { sb.append('\n').append(it) }
        if (tags.isNotEmpty()) sb.append('\n').append(tags.joinToString(", "))
        return sb.toString()
    }
}
