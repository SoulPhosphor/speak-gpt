package org.teslasoft.assistant.preferences.backup.portable

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.imagegen.GeneratedImageMetadata
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupImage
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupManifest
import org.teslasoft.assistant.preferences.backup.companion.CompanionProfileEntry
import org.teslasoft.assistant.preferences.backup.companion.RemovedLorebookLink
import org.teslasoft.assistant.preferences.dto.LoreBook
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogRecord
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogSnapshot

class PortableRestoreFinalStateTest {

    @Test
    fun copiedImageFollowsActualChatReferenceNotOriginMetadata() {
        val image = imageRecord(IMAGE_ID, originChatId = CHAT_A)
        val chats = chatPlan(
            chat(CHAT_A),
            chat(CHAT_B, imageMessage(IMAGE_ID))
        )

        val scanned = PortableRestoreFinalState.scanChatImageReferences(chats) as
            PortableRestoreDependencyRead.Available<PortableRestoreFinalState.ChatImageReferences>
        val planned = GeneratedImageCategoryPlanner.plan(
            emptyGenerated(),
            generated(image),
            PortableRestoreMode.REPLACE,
            scanned.snapshot.requiredAssetIds
        ) as GeneratedImageCategoryPlanner.Result.Ready

        assertEquals(setOf(IMAGE_ID), scanned.snapshot.allIds)
        assertEquals(setOf(IMAGE_ID), planned.snapshot.active.map { it.imageId }.toSet())
    }

    @Test
    fun originWithoutMessageReferenceDoesNotMakeImageRequired() {
        val chats = chatPlan(chat(CHAT_A))

        val scanned = PortableRestoreFinalState.scanChatImageReferences(chats) as
            PortableRestoreDependencyRead.Available<PortableRestoreFinalState.ChatImageReferences>

        assertTrue(scanned.snapshot.requiredAssetIds.isEmpty())
    }

    @Test
    fun galleryReplaceProtectsReferencedCurrentImageWithDifferentOrigin() {
        val current = imageRecord(IMAGE_ID, originChatId = CHAT_A)
        val chats = chatPlan(chat(CHAT_B, imageMessage(IMAGE_ID)))
        val referenced = (PortableRestoreFinalState.scanChatImageReferences(chats) as
            PortableRestoreDependencyRead.Available<PortableRestoreFinalState.ChatImageReferences>)
            .snapshot.requiredAssetIds

        val planned = GeneratedImageCategoryPlanner.plan(
            generated(current),
            emptyGenerated(),
            PortableRestoreMode.REPLACE,
            referenced
        ) as GeneratedImageCategoryPlanner.Result.Ready

        assertEquals(listOf(IMAGE_ID), planned.snapshot.active.map { it.imageId })
        assertEquals(listOf(IMAGE_ID), planned.report.protectedCurrentImageIds)
    }

    @Test
    fun everyRoleplayCharacterImageIsPartOfIdentityClosure() {
        val manifest = identityManifest(
            roleplayRows = listOf(
                mapOf(
                    "roleplay_character_id" to "rp-user",
                    "name" to "User hero",
                    "played_by" to "user",
                    "image_ref" to HASH_A
                ),
                mapOf(
                    "roleplay_character_id" to "rp-gm",
                    "name" to "GM character",
                    "played_by" to "companion-1",
                    "image_ref" to HASH_B
                )
            ),
            images = listOf(
                CompanionBackupImage(HASH_A, "images/profile_$HASH_A.jpg"),
                CompanionBackupImage(HASH_B, "images/profile_$HASH_B.jpg")
            )
        )

        val finalState = PortableRestoreFinalState.create(
            inputs(identities = manifest, profileAssets = setOf(HASH_A, HASH_B))
        ) as PortableRestoreDependencyRead.Available<PortableRestoreFinalState>

        assertEquals(
            setOf("rp-user", "rp-gm"),
            finalState.snapshot.identityProfileImageReferences.map { it.identityId }.toSet()
        )
    }

    @Test
    fun removedLorebookLinkIsCarriedByImmutablePlan() {
        val removed = RemovedLorebookLink("Aria", "Missing Book")
        val finalState = PortableRestoreFinalState.create(
            inputs(removed = listOf(removed))
        ) as PortableRestoreDependencyRead.Available<PortableRestoreFinalState>

        assertEquals(
            listOf(PortableRestoreFinalState.ReferenceChange.LorebookLinkRemoved(removed)),
            finalState.snapshot.plannedReferenceChanges
        )
    }

    @Test
    fun combinedRestoreResolvesIdentityLinkAgainstFinalBackupLorebooks() {
        val manifest = identityManifest(
            profiles = listOf(
                CompanionProfileEntry(
                    id = "companion-1",
                    label = "Aria",
                    prompt = "",
                    activationPromptId = "",
                    coreLoreBookId = "book-1",
                    coreLoreBookName = "Book One",
                    additionalLoreBookIds = emptyList(),
                    additionalLoreBookNames = emptyMap(),
                    autoLoadLastLoreBooks = false,
                    lastUsedLoreBookIds = emptyList(),
                    avatarRef = ""
                )
            )
        )
        val lorebooks = LorebookPortableData(
            books = listOf(LoreBook("book-1", "Book One", "", "", 1L, 1L)),
            entries = emptyList(),
            deletedEntries = emptyList()
        )
        val result = PortableRestoreFinalState.create(
            inputs(identities = manifest, lorebooks = lorebooks)
        ) as PortableRestoreDependencyRead.Available<PortableRestoreFinalState>

        assertEquals(
            listOf(PortableRestoreFinalState.IdentityLorebookReference("companion-1", "book-1")),
            result.snapshot.identityLorebookReferences
        )
    }

    @Test
    fun missingRequiredGeneratedAssetFailsClosed() {
        val result = PortableRestoreFinalState.create(
            inputs(chats = chatPlan(chat(CHAT_A, imageMessage(IMAGE_ID))))
        )

        assertTrue(result is PortableRestoreDependencyRead.Unavailable)
    }

    @Test
    fun generationChangeRetriesOnceAndReturnsTheSecondStablePlan() {
        val generations = ArrayDeque(listOf("one", "two", "two", "two"))
        var plans = 0
        val result = PortableRestoreStablePlanner.plan(
            capture = { _, _ -> PortableRestoreDependencyRead.Available(
                PortableRestoreStablePlanner.Capture(
                    generations.first(),
                    mapOf(PortableRestoreFinalState.Source.CHATS to generations.removeFirst())
                )
            ) },
            planner = { value, _ ->
                plans++
                PortableRestoreDependencyRead.Available(value)
            }
        )

        assertTrue(result is PortableRestoreStablePlanner.Result.Ready)
        assertEquals(2, plans)
        assertEquals("two", (result as PortableRestoreStablePlanner.Result.Ready).planned)
    }

    @Test
    fun secondGenerationChangeFailsWithoutApplyingAnything() {
        val generations = ArrayDeque(listOf("one", "two", "three", "four"))
        var applyCalls = 0
        val result = PortableRestoreStablePlanner.plan(
            capture = { _, _ ->
                val token = generations.removeFirst()
                PortableRestoreDependencyRead.Available(
                    PortableRestoreStablePlanner.Capture(
                        token,
                        mapOf(PortableRestoreFinalState.Source.CHATS to token)
                    )
                )
            },
            planner = { value, _ -> PortableRestoreDependencyRead.Available(value) }
        )
        if (result is PortableRestoreStablePlanner.Result.Ready) applyCalls++

        assertEquals(PortableRestoreStablePlanner.Result.ChangedTwice, result)
        assertEquals(0, applyCalls)
    }

    @Test
    fun finalStateDefensivelyCopiesSourceCollections() {
        val modes = linkedMapOf(PortableRestoreCategory.CHATS to PortableRestoreMode.MERGE)
        val tokens = linkedMapOf(PortableRestoreFinalState.Source.CHATS to "generation")
        val state = PortableRestoreFinalState.create(
            inputs(modes = modes, tokens = tokens)
        ) as PortableRestoreDependencyRead.Available<PortableRestoreFinalState>

        modes.clear()
        tokens.clear()

        assertEquals(PortableRestoreMode.MERGE, state.snapshot.categoryModes[PortableRestoreCategory.CHATS])
        assertEquals("generation", state.snapshot.sourceGenerations[PortableRestoreFinalState.Source.CHATS])
    }

    private fun inputs(
        modes: Map<PortableRestoreCategory, PortableRestoreMode> = emptyMap(),
        chats: PortableChatRestorePlan.Plan? = null,
        identities: CompanionBackupManifest? = null,
        lorebooks: LorebookPortableData? = null,
        profileAssets: Set<String> = emptySet(),
        removed: List<RemovedLorebookLink> = emptyList(),
        tokens: Map<PortableRestoreFinalState.Source, String> = emptyMap()
    ) = PortableRestoreFinalState.Inputs(
        categoryModes = modes,
        finalChats = chats,
        finalGeneratedImages = emptyGenerated(),
        finalIdentities = identities,
        finalLorebooks = lorebooks,
        finalMemories = null,
        finalProfileImageAssetHashes = profileAssets,
        sourceGenerations = tokens,
        removedLorebookLinks = removed
    )

    private fun chatPlan(vararg chats: ChatLogicalImportPlan.ChatPlan) =
        PortableChatRestorePlan.Plan(chats.toList(), emptyList())

    private fun chat(id: String, vararg messages: JSONObject) = ChatLogicalImportPlan.ChatPlan(
        chatId = id,
        listRow = mapOf("id" to id, "name" to id),
        messagesJson = JSONArray(messages.toList()).toString(),
        messageCount = messages.size,
        settings = emptyList()
    )

    private fun imageMessage(id: String): JSONObject {
        val metadata = GeneratedImageMetadata(
            imageId = id,
            fileHash = HASH_A,
            mimeType = "image/png",
            width = 1,
            height = 1,
            endpointId = "endpoint",
            modelId = "model",
            prompt = "prompt",
            description = "description",
            createdAt = 1L,
            status = GeneratedImageMetadata.STATUS_COMPLETE,
            failureCode = null,
            assetFileName = "image.png"
        )
        return JSONObject().put(GeneratedImageMetadata.KEY, metadata.toJson())
    }

    private fun imageRecord(id: String, originChatId: String?) = GeneratedImageCatalogRecord(
        imageId = id,
        fileHash = HASH_A,
        assetFileName = "image.png",
        mimeType = "image/png",
        width = 1,
        height = 1,
        createdAt = 1L,
        originChatId = originChatId,
        originChatName = null,
        originMessageId = null
    )

    private fun generated(vararg records: GeneratedImageCatalogRecord) =
        GeneratedImageCatalogSnapshot(records.toList(), emptyList(), emptyMap(), emptyMap())

    private fun emptyGenerated() = generated()

    private fun identityManifest(
        roleplayRows: List<Map<String, Any?>> = emptyList(),
        images: List<CompanionBackupImage> = emptyList(),
        profiles: List<CompanionProfileEntry> = emptyList()
    ) = CompanionBackupManifest(
        formatVersion = 1,
        appVersion = "test",
        exportedAt = "test",
        companionProfiles = profiles,
        activationPrompts = emptyList(),
        systemPrompts = emptyList(),
        selectedSystemPromptId = "",
        roleplayTables = mapOf("roleplay_characters" to roleplayRows),
        images = images
    )

    private companion object {
        const val CHAT_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val CHAT_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val IMAGE_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
