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

package org.teslasoft.assistant.preferences.includes

/** Separates the three history presentations without changing item order. */
object IncludeHistoryPresentation {

    /** Full attachment rows remain visible until a fourth row is present. */
    const val COLLAPSE_AT = 4

    /**
     * Which nouns a collapsed strip should use: all documents, all images, or
     * a mix. Drives the "Includes N Documents/Images/Files" line and the
     * matching Show/Hide screen-reader labels so neither ever calls an image
     * a document.
     */
    enum class Composition { DOCUMENTS, IMAGES, MIXED }

    fun compositionOf(includes: List<ChatInclude>): Composition {
        val anyImage = includes.any { it.kind.isImage() }
        val anyDocument = includes.any { !it.kind.isImage() }
        return when {
            anyImage && anyDocument -> Composition.MIXED
            anyImage -> Composition.IMAGES
            else -> Composition.DOCUMENTS
        }
    }

    data class Groups(
        val fullRecords: List<ChatInclude>,
        val condensedBookmarks: List<ChatInclude>,
        val artifactBookmarks: List<ChatInclude>
    )

    fun group(includes: List<ChatInclude>): Groups = Groups(
        fullRecords = includes.filter { it.form == IncludeForm.FULL },
        condensedBookmarks = includes.filter { it.form == IncludeForm.CONDENSED },
        artifactBookmarks = includes.filter { it.form == IncludeForm.ARTIFACT }
    )

    fun shouldCollapse(fullRecordCount: Int): Boolean =
        fullRecordCount >= COLLAPSE_AT
}
