package org.teslasoft.assistant.preferences.backup.readable

import org.junit.Assert.*
import org.junit.Test

class ReadableBackupHistoryTest {
    @Test fun newAndExistingUsersWithoutPerCategoryHistoryHaveNoRecordedDates() {
        assertTrue(ReadableBackupHistory.decode(null).isEmpty())
        assertTrue(ReadableBackupHistory.decode("damaged").isEmpty())
    }
    @Test fun onlyActuallyExportedCategoriesReceiveTheNewDateAndStyle() {
        val original = ReadableBackupHistory.record(emptyMap(),
            setOf(ReadableBackupCategory.CHATS, ReadableBackupCategory.MODEL_RULES), true, 100)
        val updated = ReadableBackupHistory.record(original, setOf(ReadableBackupCategory.MODEL_RULES), false, 200)
        assertEquals(ReadableBackupHistory.Record(100, true), updated[ReadableBackupCategory.CHATS])
        assertEquals(ReadableBackupHistory.Record(200, false), updated[ReadableBackupCategory.MODEL_RULES])
        assertNull(updated[ReadableBackupCategory.PROFILE_IMAGES])
        assertEquals(original, ReadableBackupHistory.record(original, emptySet(), false, 300))
        assertEquals(original, ReadableBackupHistory.decode(ReadableBackupHistory.encode(original)))
    }
    @Test fun everyFileFamilyMapsToItsOwnHistoryCategory() {
        val files = mapOf("chats/title.txt" to ReadableBackupCategory.CHATS,
            "generated_images/assets/pic.jpg" to ReadableBackupCategory.GENERATED_IMAGES,
            "profile_images/catalog.json" to ReadableBackupCategory.PROFILE_IMAGES,
            "activation_prompts.json" to ReadableBackupCategory.ACTIVATION_PROMPTS,
            "system_prompts.json" to ReadableBackupCategory.SYSTEM_PROMPTS,
            "companions/assets/profile.jpg" to ReadableBackupCategory.COMPANIONS,
            "glamours.json" to ReadableBackupCategory.GLAMOURS,
            "roleplay.json" to ReadableBackupCategory.ROLEPLAY,
            "model_settings.json" to ReadableBackupCategory.MODEL_SETTINGS,
            "model_rules.json" to ReadableBackupCategory.MODEL_RULES,
            "memories.json" to ReadableBackupCategory.MEMORIES,
            "lorebooks.json" to ReadableBackupCategory.LOREBOOKS,
            "app_settings.json" to ReadableBackupCategory.SETTINGS)
        assertEquals(ReadableBackupCategory.entries.toSet(), files.values.toSet())
        files.forEach { (name, category) -> assertEquals(category, ReadableBackupCategory.forEntry(name)) }
        assertNull(ReadableBackupCategory.forEntry("unexpected-file.json"))
    }
}
