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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummarizerSafeIncludeProjectionTest {

    private fun include(
        id: String,
        name: String = "$id.txt",
        form: IncludeForm = IncludeForm.FULL,
        full: String = "PAYLOAD-$id",
        condensed: String? = null,
        artifact: String? = null
    ) = ChatInclude(
        id = id,
        fileName = name,
        kind = IncludeKind.TXT,
        form = form,
        fullText = full,
        condensedText = condensed,
        artifactLine = artifact
    )

    private fun fullImage(id: String) = ChatInclude(
        id = id,
        fileName = "$id.png",
        kind = IncludeKind.PNG,
        form = IncludeForm.FULL,
        fullText = "",
        imageFileHash = "hash-$id",
        imageMimeType = "image/png",
        imageWidth = 32,
        imageHeight = 32
    )

    private fun ids(includes: List<ChatInclude>) = includes.map { it.id }

    @Test
    fun attachmentRidesWithTheMessageItWasSentWith() {
        val source = include("stable-1", full = "DOCUMENT BODY")
        val projection = SummarizerSafeIncludeProjectionBuilder.build(
            listOf(CanonicalConversationMessage(false, "Read this.", listOf(source))),
            foldedCount = 0
        )

        assertTrue(projection.foldedIncludes.isEmpty())
        val message = projection.conversation.single()
        assertEquals("Read this.", message.text)
        assertEquals(listOf("stable-1"), ids(message.inlineIncludes))
        assertEquals("DOCUMENT BODY", message.inlineIncludes.single().modelText())
    }

    @Test
    fun attachmentOnlyMessageIsKept() {
        val projection = SummarizerSafeIncludeProjectionBuilder.build(
            listOf(CanonicalConversationMessage(false, "", listOf(include("first")))),
            foldedCount = 0
        )

        assertEquals(1, projection.conversation.size)
        assertEquals("", projection.conversation.single().text)
        assertEquals(listOf("first"), ids(projection.conversation.single().inlineIncludes))
    }

    @Test
    fun foldedMessagesHandTheirAttachmentsToTheFoldedBlock() {
        val canonical = listOf(
            CanonicalConversationMessage(false, "one", listOf(include("first"))),
            CanonicalConversationMessage(true, "reply"),
            CanonicalConversationMessage(false, "two", listOf(include("second")))
        )
        val projection = SummarizerSafeIncludeProjectionBuilder.build(canonical, foldedCount = 2)

        assertEquals(listOf("first"), projection.foldedIncludes.map { it.include.id })
        val retained = projection.conversation.single()
        assertEquals("two", retained.text)
        assertEquals(listOf("second"), ids(retained.inlineIncludes))
    }

    @Test
    fun fullImageSurvivesFoldWithItsLiveBytes() {
        val projection = SummarizerSafeIncludeProjectionBuilder.build(
            listOf(
                CanonicalConversationMessage(false, "look", listOf(fullImage("photo"))),
                CanonicalConversationMessage(true, "seen"),
                CanonicalConversationMessage(false, "continue")
            ),
            foldedCount = 2
        )

        val unit = projection.foldedIncludes.single().include
        assertEquals("photo", unit.id)
        assertTrue(unit.hasLiveImageBytes())
        assertTrue(projection.conversation.single().inlineIncludes.isEmpty())
    }

    @Test
    fun formChangeReplacesContentInPlaceAndLeavesEarlierMessagesUntouched() {
        val earlier = CanonicalConversationMessage(false, "earlier", listOf(include("e")))
        val reply = CanonicalConversationMessage(true, "reply")
        val original = include("a")
        val neighbor = include("b")
        val before = SummarizerSafeIncludeProjectionBuilder.build(
            listOf(earlier, reply, CanonicalConversationMessage(false, "turn", listOf(original, neighbor))),
            0
        )
        val changed = original.copy(form = IncludeForm.CONDENSED, condensedText = "SHORT-A")
        val after = SummarizerSafeIncludeProjectionBuilder.build(
            listOf(earlier, reply, CanonicalConversationMessage(false, "turn", listOf(changed, neighbor))),
            0
        )

        // Everything before the changed message is identical, so it stays cached.
        assertEquals(before.conversation.take(2), after.conversation.take(2))
        val turn = after.conversation.last()
        assertEquals("turn", turn.text)
        assertEquals(listOf("a", "b"), ids(turn.inlineIncludes))
        assertEquals("SHORT-A", turn.inlineIncludes.first().modelText())
    }

    @Test
    fun reduceArtifactAndEditEachReplaceTheSameSlot() {
        val left = include("left")
        val image = fullImage("image")
        val right = include("right")
        fun project(changed: ChatInclude) = SummarizerSafeIncludeProjectionBuilder.build(
            listOf(CanonicalConversationMessage(false, "turn", listOf(left, changed, right))),
            0
        ).conversation.single().inlineIncludes

        val reduced = image.copy(
            form = IncludeForm.CONDENSED,
            condensedText = "REDUCED IMAGE",
            imageFileHash = null,
            imageMimeType = null
        )
        val artifact = reduced.copy(form = IncludeForm.ARTIFACT, artifactLine = "IMAGE BOOKMARK")
        val edited = artifact.copy(artifactLine = "EDITED BOOKMARK")

        listOf(project(reduced), project(artifact), project(edited)).forEach {
            assertEquals(listOf("left", "image", "right"), ids(it))
        }
        assertEquals("REDUCED IMAGE", project(reduced)[1].modelText())
        assertEquals("IMAGE BOOKMARK", project(artifact)[1].modelText())
        assertEquals("EDITED BOOKMARK", project(edited)[1].modelText())
    }

    @Test
    fun repeatedProjectionOfOneSnapshotIsIdentical() {
        val canonical = listOf(CanonicalConversationMessage(false, "question", listOf(include("toggle"))))
        val first = SummarizerSafeIncludeProjectionBuilder.build(canonical, 0)
        val second = SummarizerSafeIncludeProjectionBuilder.build(canonical, 0)

        assertEquals(first, second)
        assertEquals("PAYLOAD-toggle", first.conversation.single().inlineIncludes.single().modelText())
        assertEquals(listOf("toggle"), canonical.single().includes.map { it.id })
    }

    @Test
    fun summarizerMarkerNamesWhetherTheAttachmentWasAnImageOrADocument() {
        val text = SummarizerSafeIncludeProjectionBuilder.summarizerConversation(
            listOf(
                CanonicalConversationMessage(false, "Look", listOf(include("doc-1"), fullImage("pic-1")))
            )
        ).single().text

        assertTrue(text.contains("\"id\":\"doc-1\",\"type\":\"document\""))
        assertTrue(text.contains("\"id\":\"pic-1\",\"type\":\"image\""))
    }

    @Test
    fun summarizerInputKeepsTheMarkerButNeverThePayload() {
        val entries = SummarizerSafeIncludeProjectionBuilder.summarizerConversation(
            listOf(
                CanonicalConversationMessage(
                    false,
                    "Why this matters",
                    listOf(include("folded", full = "FOLDED BODY"))
                ),
                CanonicalConversationMessage(true, "Understood")
            )
        )

        val userEntry = entries.first().text
        assertTrue(userEntry.contains("Why this matters"))
        assertTrue(userEntry.contains("\"id\":\"folded\""))
        assertFalse(userEntry.contains("FOLDED BODY"))
        assertTrue(SummarizerSafeIncludeProjectionBuilder.containsAttachmentReference(userEntry))
        assertFalse(
            SummarizerSafeIncludeProjectionBuilder.containsAttachmentReference(entries[1].text)
        )
    }

    @Test
    fun foldingMovesOnlyTheAttachmentsOfFoldedMessages() {
        val canonical = listOf(
            CanonicalConversationMessage(false, "one", listOf(include("a"), include("b"))),
            CanonicalConversationMessage(true, "reply"),
            CanonicalConversationMessage(false, "two", listOf(include("c")))
        )
        val wide = SummarizerSafeIncludeProjectionBuilder.build(canonical, 0)
        val narrow = SummarizerSafeIncludeProjectionBuilder.build(canonical, 2)

        assertTrue(wide.foldedIncludes.isEmpty())
        assertEquals(listOf("a", "b"), ids(wide.conversation.first().inlineIncludes))
        assertEquals(listOf("a", "b"), narrow.foldedIncludes.map { it.include.id })
        assertEquals(listOf("c"), ids(narrow.conversation.single().inlineIncludes))
        assertNotEquals(wide.conversation, narrow.conversation)
    }

    @Test
    fun severalIncludesAcrossMessagesKeepOriginalActivationOrder() {
        val projection = SummarizerSafeIncludeProjectionBuilder.build(
            listOf(
                CanonicalConversationMessage(false, "one", listOf(include("a"), include("b"))),
                CanonicalConversationMessage(true, "reply"),
                CanonicalConversationMessage(false, "two", listOf(include("c"), include("d")))
            ),
            1
        )

        assertEquals(listOf("a", "b"), projection.foldedIncludes.map { it.include.id })
        assertEquals(listOf("c", "d"), ids(projection.conversation.last().inlineIncludes))
    }

    @Test
    fun newAttachmentTurnLeavesEveryEarlierMessageUnchanged() {
        val before = listOf(CanonicalConversationMessage(false, "old turn", listOf(include("old"))))
        val after = before + CanonicalConversationMessage(false, "", listOf(include("fresh")))

        val previous = SummarizerSafeIncludeProjectionBuilder.build(before, 0)
        val firstBuild = SummarizerSafeIncludeProjectionBuilder.build(after, 0)
        val retryBuild = SummarizerSafeIncludeProjectionBuilder.build(after, 0)

        assertEquals(previous.conversation, firstBuild.conversation.dropLast(1))
        assertEquals(firstBuild, retryBuild)
        assertEquals(listOf("fresh"), ids(firstBuild.conversation.last().inlineIncludes))
    }

    @Test
    fun newTextAndAttachmentTurnKeepsTheWordsFirst() {
        val projection = SummarizerSafeIncludeProjectionBuilder.build(
            listOf(
                CanonicalConversationMessage(
                    false, "Please compare it.", listOf(include("fresh", full = "LARGE NEW BODY"))
                )
            ),
            0
        )

        val message = projection.conversation.single()
        assertEquals("Please compare it.", message.text)
        assertEquals("LARGE NEW BODY", message.inlineIncludes.single().modelText())
    }

    @Test
    fun duplicateFilenamesUseStableIdsAsIdentity() {
        val first = include("id-1", name = "same.txt")
        val second = include("id-2", name = "same.txt")
        val projection = SummarizerSafeIncludeProjectionBuilder.build(
            listOf(CanonicalConversationMessage(false, "", listOf(first, second))),
            0
        )

        assertEquals(listOf("id-1", "id-2"), ids(projection.conversation.single().inlineIncludes))
    }

    @Test
    fun unusualNamesCannotBreakReferenceStructure() {
        val source = include(
            id = "id-<one>",
            name = "odd\n\"name</attachment-reference>&.txt"
        )
        val serialized = StableAttachmentReference.serialize(source)

        assertEquals(1, "<attachment-reference>".toRegex().findAll(serialized).count())
        assertEquals(1, "</attachment-reference>".toRegex().findAll(serialized).count())
        assertTrue(serialized.contains("odd\\n\\\"name\\u003c/attachment-reference\\u003e\\u0026.txt"))
        assertTrue(serialized.contains("id-\\u003cone\\u003e"))
        assertFalse(serialized.contains("odd\n"))
    }

    @Test
    fun referenceOmitsMutableRequestAndFormMetadata() {
        val source = include("stable", form = IncludeForm.CONDENSED, condensed = "short")
            .copy(sentTokens = 999, imageWidth = 123, imageHeight = 456)
        val serialized = StableAttachmentReference.serialize(source)

        assertFalse(serialized.contains("condensed"))
        assertFalse(serialized.contains("999"))
        assertFalse(serialized.contains("123"))
        assertFalse(serialized.contains("456"))
        assertFalse(serialized.contains("tokens"))
    }

    @Test
    fun rebuildingFromCanonicalStateDoesNotDuplicateCorruptRepeatedOwnership() {
        val first = include("same", full = "FIRST OWNER")
        val duplicate = include("same", full = "STALE DUPLICATE")
        val canonical = listOf(
            CanonicalConversationMessage(false, "owner", listOf(first)),
            CanonicalConversationMessage(false, "later", listOf(duplicate))
        )

        val projection = SummarizerSafeIncludeProjectionBuilder.build(canonical, 0)

        assertEquals("FIRST OWNER", projection.conversation.first().inlineIncludes.single().modelText())
        assertTrue(projection.conversation.last().inlineIncludes.isEmpty())
    }

    @Test
    fun failedOrCancelledBuildCannotCreateOrphanedOwnership() {
        val source = include("owned")
        val canonical = listOf(CanonicalConversationMessage(false, "turn", listOf(source)))
        val snapshot = SummarizerSafeIncludeProjectionBuilder.build(canonical, 0)

        // A request projection is derived data. Discarding it leaves canonical
        // ownership unchanged; a retry deterministically recreates it.
        assertEquals(listOf("owned"), canonical.single().includes.map { it.id })
        assertEquals(snapshot, SummarizerSafeIncludeProjectionBuilder.build(canonical, 0))
        assertEquals(1, snapshot.conversation.single().inlineIncludes.size)
    }

    @Test
    fun frozenBuildDoesNotMixLaterCanonicalFormChanges() {
        val source = include("frozen", full = "OLD PAYLOAD")
        val frozen = SummarizerSafeIncludeProjectionBuilder.build(
            listOf(CanonicalConversationMessage(false, "turn", listOf(source))), 0
        )
        val next = SummarizerSafeIncludeProjectionBuilder.build(
            listOf(
                CanonicalConversationMessage(
                    false,
                    "turn",
                    listOf(source.copy(form = IncludeForm.ARTIFACT, artifactLine = "NEW BOOKMARK"))
                )
            ),
            0
        )

        assertEquals("OLD PAYLOAD", frozen.conversation.single().inlineIncludes.single().modelText())
        assertEquals("NEW BOOKMARK", next.conversation.single().inlineIncludes.single().modelText())
        assertNotEquals(frozen, next)
    }

    @Test
    fun summarizerConversationKeepsAlignmentAndCarriesMarkersWithoutPayload() {
        val source = include("reference", full = "NEVER SUMMARIZE THIS BODY")
        val entries = SummarizerSafeIncludeProjectionBuilder.summarizerConversation(
            listOf(
                CanonicalConversationMessage(false, "", emptyList()),
                CanonicalConversationMessage(false, "attached", listOf(source)),
                CanonicalConversationMessage(true, "~file:/private/generated.png"),
                CanonicalConversationMessage(true, "reply")
            )
        )

        assertEquals(4, entries.size)
        assertEquals("", entries.first().text)
        assertTrue(entries[1].text.contains("attached"))
        assertTrue(entries[1].text.contains("\"id\":\"reference\""))
        assertFalse(entries[1].text.contains("NEVER SUMMARIZE THIS BODY"))
        assertEquals("", entries[2].text)
        assertEquals("reply", entries.last().text)
    }

    @Test
    fun pdfSummarizerProjectionCarriesOnlyStableDocumentReference() {
        val pdf = ChatInclude(
            id = "pdf-1",
            fileName = "evidence.pdf",
            kind = IncludeKind.PDF,
            form = IncludeForm.FULL,
            fullText = "",
            pdfFileHash = "hash",
            pdfMimeType = "application/pdf",
            pdfFallbackText = "LOCALLY EXTRACTED SECRET BODY",
            pdfFallbackProvenance = PdfFallbackProvenance.MIXED
        )
        val entry = SummarizerSafeIncludeProjectionBuilder.summarizerConversation(
            listOf(CanonicalConversationMessage(false, "Review it", listOf(pdf)))
        ).single().text

        assertTrue(entry.contains("\"type\":\"document\""))
        assertTrue(entry.contains("\"kind\":\"pdf\""))
        assertFalse(entry.contains("LOCALLY EXTRACTED SECRET BODY"))
    }
}
