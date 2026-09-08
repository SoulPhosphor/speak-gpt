package org.teslasoft.assistant.preferences.backup.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.backup.portable.PortableRestoreCategory
import org.teslasoft.assistant.preferences.backup.portable.PortableRestoreMode

class CompanionCategoryPlannerTest {
    @Test
    fun `unselected categories stay current and merge keeps different current id`() {
        val current = manifest(
            profiles = listOf(profile("companion-profile", "Current", "old")),
            activation = listOf(ActivationPromptEntry("activation-current", "Current activation", "a")),
            personas = listOf(row("persona_id" to "glamour-current", "name" to "Current glamour"))
        )
        val backup = manifest(
            profiles = listOf(
                profile("companion-profile", "Backup", "different"),
                profile("companion-new", "New", "new")
            ),
            activation = listOf(ActivationPromptEntry("activation-backup", "Backup activation", "b")),
            personas = listOf(row("persona_id" to "glamour-backup", "name" to "Backup glamour"))
        )

        val ready = CompanionCategoryPlanner.plan(
            current,
            backup,
            listOf(
                CompanionCategoryPlanner.Selection(
                    PortableRestoreCategory.COMPANIONS,
                    PortableRestoreMode.MERGE
                )
            )
        ) as CompanionCategoryPlanner.Result.Ready

        assertEquals(listOf("companion-profile", "companion-new"), ready.manifest.companionProfiles.map { it.id })
        assertEquals("Current", ready.manifest.companionProfiles.first().label)
        assertEquals(current.activationPrompts, ready.manifest.activationPrompts)
        assertEquals(current.roleplayTables["user_personas"], ready.manifest.roleplayTables["user_personas"])
        assertEquals(1, ready.report.conflicts.size)
        assertEquals(PortableRestoreCategory.COMPANIONS, ready.report.conflicts.single().category)
    }

    @Test
    fun `replace one category does not replace other bundled tables`() {
        val current = manifest(
            personas = listOf(row("persona_id" to "old-glamour", "name" to "Old")),
            characters = listOf(row("roleplay_character_id" to "current-rp", "name" to "Current RP"))
        )
        val backup = manifest(
            personas = listOf(row("persona_id" to "new-glamour", "name" to "New")),
            characters = listOf(row("roleplay_character_id" to "backup-rp", "name" to "Backup RP"))
        )

        val ready = CompanionCategoryPlanner.plan(
            current,
            backup,
            listOf(
                CompanionCategoryPlanner.Selection(
                    PortableRestoreCategory.GLAMOURS,
                    PortableRestoreMode.REPLACE
                )
            )
        ) as CompanionCategoryPlanner.Result.Ready

        assertEquals("new-glamour", ready.manifest.roleplayTables.getValue("user_personas").single()["persona_id"])
        assertEquals("current-rp", ready.manifest.roleplayTables.getValue("roleplay_characters").single()["roleplay_character_id"])
    }

    @Test
    fun `missing selected dependencies are cleared before apply`() {
        val current = manifest()
        val backup = manifest(
            profiles = listOf(profile("profile", "Profile", "x", activationPromptId = "missing")),
            campaigns = listOf(
                row(
                    "campaign_id" to "campaign",
                    "name" to "Campaign",
                    "world_id" to "missing-world",
                    "roleplay_character_id" to "missing-character",
                    "companion_id" to "missing-companion"
                )
            )
        )

        val ready = CompanionCategoryPlanner.plan(
            current,
            backup,
            listOf(
                CompanionCategoryPlanner.Selection(PortableRestoreCategory.COMPANIONS, PortableRestoreMode.REPLACE),
                CompanionCategoryPlanner.Selection(PortableRestoreCategory.ROLEPLAY, PortableRestoreMode.REPLACE)
            )
        ) as CompanionCategoryPlanner.Result.Ready

        assertEquals("", ready.manifest.companionProfiles.single().activationPromptId)
        val campaign = ready.manifest.roleplayTables.getValue("campaigns").single()
        assertEquals(null, campaign["world_id"])
        assertEquals(null, campaign["roleplay_character_id"])
        assertEquals(null, campaign["companion_id"])
        assertEquals(4, ready.report.clearedReferences)
    }

    @Test
    fun `dependent join rows are removed and image list is scoped to final identities`() {
        val keptHash = "a".repeat(64)
        val unrelatedHash = "b".repeat(64)
        val current = manifest()
        val backup = manifest(
            profiles = listOf(profile("profile", "Profile", "x", avatarRef = keptHash)),
            campaignMembers = listOf(row("campaign_id" to "missing", "party_member_id" to "missing")),
            images = listOf(
                CompanionBackupImage(keptHash, CompanionBackupFormat.imageEntryName(keptHash)),
                CompanionBackupImage(unrelatedHash, CompanionBackupFormat.imageEntryName(unrelatedHash))
            )
        )

        val ready = CompanionCategoryPlanner.plan(
            current,
            backup,
            listOf(
                CompanionCategoryPlanner.Selection(PortableRestoreCategory.COMPANIONS, PortableRestoreMode.REPLACE),
                CompanionCategoryPlanner.Selection(PortableRestoreCategory.ROLEPLAY, PortableRestoreMode.REPLACE)
            )
        ) as CompanionCategoryPlanner.Result.Ready

        assertTrue(ready.manifest.roleplayTables.getValue("campaign_party_members").isEmpty())
        assertEquals(listOf(keptHash), ready.manifest.images.map { it.hash })
        assertEquals(1, ready.report.clearedReferences)
    }

    @Test
    fun `duplicate stable identity is rejected`() {
        val duplicate = profile("same", "Duplicate", "x")
        val result = CompanionCategoryPlanner.plan(
            manifest(),
            manifest(profiles = listOf(duplicate, duplicate)),
            listOf(
                CompanionCategoryPlanner.Selection(PortableRestoreCategory.COMPANIONS, PortableRestoreMode.MERGE)
            )
        )
        assertEquals(
            CompanionCategoryPlanner.Result.Rejected(CompanionCategoryPlanner.Rejection.DUPLICATE_ID),
            result
        )
    }

    private fun manifest(
        profiles: List<CompanionProfileEntry> = emptyList(),
        activation: List<ActivationPromptEntry> = emptyList(),
        personas: List<Map<String, Any?>> = emptyList(),
        characters: List<Map<String, Any?>> = emptyList(),
        campaigns: List<Map<String, Any?>> = emptyList(),
        campaignMembers: List<Map<String, Any?>> = emptyList(),
        images: List<CompanionBackupImage> = emptyList()
    ) = CompanionBackupManifest(
        1,
        "test",
        "2026-09-08T00:00:00Z",
        profiles,
        activation,
        emptyList(),
        "",
        CompanionBackupFormat.ROLEPLAY_TABLES.associateWith { table ->
            when (table) {
                "user_personas" -> personas
                "roleplay_characters" -> characters
                "campaigns" -> campaigns
                "campaign_party_members" -> campaignMembers
                else -> emptyList()
            }
        },
        images
    )

    private fun profile(
        id: String,
        label: String,
        prompt: String,
        activationPromptId: String = "",
        avatarRef: String = ""
    ) = CompanionProfileEntry(
        id, label, prompt, activationPromptId, "", null, emptyList(), emptyMap(),
        false, emptyList(), avatarRef
    )

    private fun row(vararg values: Pair<String, Any?>): Map<String, Any?> = linkedMapOf(*values)
}
