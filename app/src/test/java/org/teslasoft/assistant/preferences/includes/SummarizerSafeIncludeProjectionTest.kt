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

    @Test
    fun activeSummarizerSeparatesPayloadFromStableConversationReference() {
        val source = include("stable-1", full = "SECRET DOCUMENT BODY")
        val projection = SummarizerSafeIncludeProjectionBuilder.build(
            listOf(CanonicalConversationMessage(false, "Read this.", listOf(source))),
            summarizerActive = true,
            foldedCount = 0
        )

        assertEquals(listOf("stable-1"), projection.persistentIncludes.map { it.include.id })
        assertEquals("SECRET DOCUMENT BODY", projection.persistentIncludes.single().include.modelText())
        val conversation = projection.conversation.single().text
        assertTrue(conversation.contains("Read this."))
        assertTrue(conversation.contains("\"id\":\"stable-1\""))
        assertFalse(conversation.contains("SECRET DOCUMENT BODY"))
        assertTrue(projection.conversation.single().inlineIncludes.isEmpty())
    }

    @Test
    fun activeSummarizerSplitsImmediatelyBeforeAnyFoldOrSummaryExists() {
        val source = include("first")
        val projection = SummarizerSafeIncludeProjectionBuilder.build(
            listOf(CanonicalConversationMessage(false, "", listOf(source))),
            summarizerActive = true,
            foldedCount = 0
        )

        assertEquals(1, projection.persistentIncludes.size)
        assertEquals(1, projection.conversation.size)
        assertTrue(projection.conversation.single().text.contains("first"))
        assertFalse(projection.conversation.single().text.contains("PAYLOAD-first"))
    }

    @Test
    fun foldedConversationNeverDropsOrMovesPersistentIncludes() {
        val first = include("first")
        val second = include("second")
        val canonical = listOf(
            CanonicalConversationMessage(false, "one", listOf(first)),
            CanonicalConversationMessage(true, "reply"),
            CanonicalConversationMessage(false, "two", listOf(second))
        )
        val projection = SummarizerSafeIncludeProjectionBuilder.build(
            canonical,
            summarizerActive = true,
            foldedCount = 2
        )

        assertEquals(listOf("first", "second"), projection.persistentIncludes.map { it.include.id })
        assertTrue(projection.persistentIncludes.all { it.include.form == IncludeForm.FULL })
        assertEquals(1, projection.conversation.size)
        assertTrue(projection.conversation.single().text.contains("\"id\":\"second\""))
        assertFalse(projection.conversation.single().text.contains("PAYLOAD-second"))
    }

    @Test
    fun fullImageSurvivesFoldAsOneLivePersistentImageUnit() {
        val image = fullImage("photo")
        val projection = SummarizerSafeIncludeProjectionBuilder.build(
            listOf(
                CanonicalConversationMessage(false, "look", listOf(image)),
                CanonicalConversationMessage(true, "seen"),
                CanonicalConversationMessage(false, "continue")
            ),
            true,
            foldedCount = 2
        )

        val unit = projection.persistentIncludes.single().include
        assertEquals("photo", unit.id)
        assertTrue(unit.hasLiveImageBytes())
        assertFalse(projection.conversation.single().text.contains("hash-photo"))
    }

    @Test
    fun formChangeReplacesPayloadInExistingSlotWithoutChangingReference() {
        val original = include("a")
        val neighbor = include("b")
        val before = SummarizerSafeIncludeProjectionBuilder.build(
            listOf(CanonicalConversationMessage(false, "turn", listOf(original, neighbor))),
            true,
            0
        )
        val changed = original.copy(
            form = IncludeForm.CONDENSED,
            condensedText = "SHORT-A"
        )
        val after = SummarizerSafeIncludeProjectionBuilder.build(
            listOf(CanonicalConversationMessage(false, "turn", listOf(changed, neighbor))),
            true,
            0
        )

        assertEquals(listOf("a", "b"), before.persistentIncludes.map { it.include.id })
        assertEquals(listOf("a", "b"), after.persistentIncludes.map { it.include.id })
        assertEquals("SHORT-A", after.persistentIncludes.first().include.modelText())
        assertEquals(before.conversation.single().text, after.conversation.single().text)
    }

    @Test
    fun reduceArtifactAndEditEachReplaceTheSameLogicalSlot() {
        val left = include("left")
        val image = fullImage("image")
        val right = include("right")
        fun project(changed: ChatInclude) = SummarizerSafeIncludeProjectionBuilder.build(
            listOf(CanonicalConversationMessage(false, "turn", listOf(left, changed, right))),
            true,
            0
        )

        val reduced = image.copy(
            form = IncludeForm.CONDENSED,
            condensedText = "REDUCED IMAGE",
            imageFileHash = null,
            imageMimeType = null
        )
        val artifact = reduced.copy(
            form = IncludeForm.ARTIFACT,
            artifactLine = "IMAGE BOOKMARK"
        )
        val edited = artifact.copy(artifactLine = "EDITED BOOKMARK")

        listOf(project(reduced), project(artifact), project(edited)).forEach { projection ->
            assertEquals(
                listOf("left", "image", "right"),
                projection.persistentIncludes.map { it.include.id }
            )
        }
        assertEquals("REDUCED IMAGE", project(reduced).persistentIncludes[1].include.modelText())
        assertEquals("IMAGE BOOKMARK", project(artifact).persistentIncludes[1].include.modelText())
        assertEquals("EDITED BOOKMARK", project(edited).persistentIncludes[1].include.modelText())
    }

    @Test
    fun summarizerOffPreservesInlineFullHistoryBehavior() {
        val source = include("inline", full = "INLINE BODY")
        val projection = SummarizerSafeIncludeProjectionBuilder.build(
            listOf(CanonicalConversationMessage(false, "Question", listOf(source))),
            summarizerActive = false,
            foldedCount = 1
        )

        assertTrue(projection.persistentIncludes.isEmpty())
        assertEquals(1, projection.conversation.size)
        assertTrue(projection.conversation.single().text.contains("INLINE BODY"))
        assertEquals(listOf("inline"), projection.conversation.single().inlineIncludes.map { it.id })
    }

    @Test
    fun togglingSummarizerChangesOnlyProjectionNotCanonicalOwnership() {
        val source = include("toggle")
        val canonical = listOf(CanonicalConversationMessage(false, "question", listOf(source)))
        val off = SummarizerSafeIncludeProjectionBuilder.build(canonical, false, 0)
        val on = SummarizerSafeIncludeProjectionBuilder.build(canonical, true, 0)
        val offAgain = SummarizerSafeIncludeProjectionBuilder.build(canonical, false, 0)

        assertTrue(off.conversation.single().text.contains("PAYLOAD-toggle"))
        assertFalse(on.conversation.single().text.contains("PAYLOAD-toggle"))
        assertEquals("PAYLOAD-toggle", on.persistentIncludes.single().include.modelText())
        assertEquals(off, offAgain)
        assertEquals(listOf("toggle"), canonical.single().includes.map { it.id })
    }

    @Test
    fun changingCompleteMessagesWindowNeverRewritesPersistentPrefix() {
        val canonical = listOf(
            CanonicalConversationMessage(false, "one", listOf(include("a"), include("b"))),
            CanonicalConversationMessage(true, "reply"),
            CanonicalConversationMessage(false, "two", listOf(include("c")))
        )
        val wide = SummarizerSafeIncludeProjectionBuilder.build(canonical, true, 0)
        val narrow = SummarizerSafeIncludeProjectionBuilder.build(canonical, true, 2)

        assertEquals(wide.persistentIncludes, narrow.persistentIncludes)
        assertEquals(
            wide.persistentIncludes.map {
                StableAttachmentReference.renderPersistentPayload(it.include)
            },
            narrow.persistentIncludes.map {
                StableAttachmentReference.renderPersistentPayload(it.include)
            }
        )
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
            true,
            1
        )

        assertEquals(
            listOf("a", "b", "c", "d"),
            projection.persistentIncludes.map { it.include.id }
        )
    }

    @Test
    fun newAttachmentOnlyTurnAppendsExactlyOneUnitAndRetryIsIdempotent() {
        val old = include("old")
        val fresh = include("fresh")
        val before = listOf(CanonicalConversationMessage(false, "old turn", listOf(old)))
        val after = before + CanonicalConversationMessage(false, "", listOf(fresh))

        val firstBuild = SummarizerSafeIncludeProjectionBuilder.build(after, true, 0)
        val retryBuild = SummarizerSafeIncludeProjectionBuilder.build(after, true, 0)

        assertEquals(listOf("old", "fresh"), firstBuild.persistentIncludes.map { it.include.id })
        assertEquals(firstBuild, retryBuild)
        val freshConversation = firstBuild.conversation.last().text
        assertTrue(freshConversation.contains("\"id\":\"fresh\""))
        assertFalse(freshConversation.contains("PAYLOAD-fresh"))
    }

    @Test
    fun newTextAndAttachmentTurnKeepsTextAndOnlyAReferenceInConversation() {
        val fresh = include("fresh", full = "LARGE NEW BODY")
        val projection = SummarizerSafeIncludeProjectionBuilder.build(
            listOf(CanonicalConversationMessage(false, "Please compare it.", listOf(fresh))),
            true,
            0
        )

        assertEquals(1, projection.persistentIncludes.size)
        assertTrue(projection.conversation.single().text.startsWith("Please compare it."))
        assertTrue(projection.conversation.single().text.contains("\"id\":\"fresh\""))
        assertFalse(projection.conversation.single().text.contains("LARGE NEW BODY"))
    }

    @Test
    fun duplicateFilenamesUseStableIdsAsIdentity() {
        val first = include("id-1", name = "same.txt")
        val second = include("id-2", name = "same.txt")
        val projection = SummarizerSafeIncludeProjectionBuilder.build(
            listOf(CanonicalConversationMessage(false, "", listOf(first, second))),
            true,
            0
        )

        assertEquals(listOf("id-1", "id-2"), projection.persistentIncludes.map { it.include.id })
        val refs = projection.conversation.single().text
        assertTrue(refs.contains("\"id\":\"id-1\""))
        assertTrue(refs.contains("\"id\":\"id-2\""))
        assertTrue(
            StableAttachmentReference.renderPersistentPayload(first)
                .contains("\"id\":\"id-1\"")
        )
        assertTrue(
            StableAttachmentReference.renderPersistentPayload(second)
                .contains("\"id\":\"id-2\"")
        )
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

        val projection = SummarizerSafeIncludeProjectionBuilder.build(canonical, true, 0)

        assertEquals(1, projection.persistentIncludes.size)
        assertEquals("FIRST OWNER", projection.persistentIncludes.single().include.modelText())
        assertFalse(projection.conversation.last().text.contains("\"id\":\"same\""))
    }

    @Test
    fun failedOrCancelledBuildCannotCreateOrphanedOwnership() {
        val source = include("owned")
        val canonical = listOf(CanonicalConversationMessage(false, "turn", listOf(source)))
        val snapshot = SummarizerSafeIncludeProjectionBuilder.build(canonical, true, 0)

        // A request projection is derived data. Discarding it leaves canonical
        // ownership unchanged; a retry deterministically recreates one unit.
        assertEquals(listOf("owned"), canonical.single().includes.map { it.id })
        assertEquals(snapshot, SummarizerSafeIncludeProjectionBuilder.build(canonical, true, 0))
        assertEquals(1, snapshot.persistentIncludes.size)
    }

    @Test
    fun frozenBuildDoesNotMixLaterCanonicalFormChanges() {
        val source = include("frozen", full = "OLD PAYLOAD")
        val canonicalAtDispatch = listOf(
            CanonicalConversationMessage(false, "turn", listOf(source))
        )
        val frozen = SummarizerSafeIncludeProjectionBuilder.build(canonicalAtDispatch, true, 0)
        val nextCanonical = listOf(
            CanonicalConversationMessage(
                false,
                "turn",
                listOf(source.copy(form = IncludeForm.ARTIFACT, artifactLine = "NEW BOOKMARK"))
            )
        )
        val next = SummarizerSafeIncludeProjectionBuilder.build(nextCanonical, true, 0)

        assertEquals("OLD PAYLOAD", frozen.persistentIncludes.single().include.modelText())
        assertEquals("NEW BOOKMARK", next.persistentIncludes.single().include.modelText())
        assertNotEquals(frozen.persistentIncludes, next.persistentIncludes)
    }

    @Test
    fun summarizerConversationKeepsAlignmentAndIsCompletelyAttachmentBlind() {
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
        assertEquals("attached", entries[1].text)
        assertFalse(entries[1].text.contains("\"id\":\"reference\""))
        assertFalse(entries[1].text.contains("NEVER SUMMARIZE THIS BODY"))
        assertEquals("", entries[2].text)
        assertEquals("reply", entries.last().text)
    }
}
