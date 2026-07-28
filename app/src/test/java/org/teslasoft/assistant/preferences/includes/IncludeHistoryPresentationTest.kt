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

import org.teslasoft.assistant.preferences.includes.IncludeHistoryPresentation.Composition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncludeHistoryPresentationTest {

    private fun include(id: String, form: IncludeForm) = ChatInclude(
        id = id,
        fileName = "$id.txt",
        kind = IncludeKind.TXT,
        form = form,
        fullText = id,
        condensedText = if (form == IncludeForm.CONDENSED) "short $id" else null,
        artifactLine = if (form == IncludeForm.ARTIFACT) "User sent $id." else null
    )

    @Test fun fullCondensedAndRemovedItemsUseDifferentHistorySurfaces() {
        val groups = IncludeHistoryPresentation.group(
            listOf(
                include("full", IncludeForm.FULL),
                include("condensed", IncludeForm.CONDENSED),
                include("removed", IncludeForm.ARTIFACT)
            )
        )

        assertEquals(listOf("full"), groups.fullRecords.map { it.id })
        assertEquals(listOf("condensed"), groups.condensedBookmarks.map { it.id })
        assertEquals(listOf("removed"), groups.artifactBookmarks.map { it.id })
    }

    @Test fun groupingPreservesAttachmentOrderWithinEachMarker() {
        val groups = IncludeHistoryPresentation.group(
            listOf(
                include("condensed-1", IncludeForm.CONDENSED),
                include("full", IncludeForm.FULL),
                include("condensed-2", IncludeForm.CONDENSED)
            )
        )

        assertEquals(
            listOf("condensed-1", "condensed-2"),
            groups.condensedBookmarks.map { it.id }
        )
    }

    @Test fun historyRowsCollapseOnlyAtFourOrMore() {
        assertFalse(IncludeHistoryPresentation.shouldCollapse(0))
        assertFalse(IncludeHistoryPresentation.shouldCollapse(1))
        assertFalse(IncludeHistoryPresentation.shouldCollapse(3))
        assertTrue(IncludeHistoryPresentation.shouldCollapse(4))
        assertTrue(IncludeHistoryPresentation.shouldCollapse(8))
    }

    private fun image(id: String) = ChatInclude(
        id = id,
        fileName = "$id.jpg",
        kind = IncludeKind.JPEG,
        form = IncludeForm.FULL,
        fullText = "",
        imageFileHash = id,
        imageMimeType = "image/jpeg",
        imageWidth = 800,
        imageHeight = 600
    )

    @Test fun compositionNamesTheCollapsedNounCorrectly() {
        // Drives whether the collapse line reads Documents, Images, or Files —
        // so an image set can never be labelled "Documents".
        assertEquals(
            Composition.DOCUMENTS,
            IncludeHistoryPresentation.compositionOf(
                listOf(include("a", IncludeForm.FULL), include("b", IncludeForm.FULL))
            )
        )
        assertEquals(
            Composition.IMAGES,
            IncludeHistoryPresentation.compositionOf(listOf(image("a"), image("b")))
        )
        assertEquals(
            Composition.MIXED,
            IncludeHistoryPresentation.compositionOf(
                listOf(include("doc", IncludeForm.FULL), image("pic"))
            )
        )
    }
}
