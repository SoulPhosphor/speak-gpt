package org.teslasoft.assistant.preferences

import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.backup.portable.*

class SummarizerTitlePreferencesTest {
    @Test fun titleChoiceDefaultsOffAndIsSharedAcrossChatsWithoutEnablingConversationSummaries() {
        val global = FakeSharedPreferences()
        val firstStore = FakeSharedPreferences()
        val secondStore = FakeSharedPreferences()
        val first = Preferences(firstStore, global, "first")
        val second = Preferences(secondStore, global, "second")
        assertFalse(first.getUseSummarizerForTitles())
        first.setUseSummarizerForTitles(true)
        assertTrue(second.getUseSummarizerForTitles())
        assertTrue(Preferences(FakeSharedPreferences(), global, "reopened").getUseSummarizerForTitles())
        assertFalse(second.getSummarizerOnForNewChats())
        assertTrue(firstStore.all.isEmpty())
        assertTrue(secondStore.all.isEmpty())
        second.setUseSummarizerForTitles(false)
        assertFalse(first.getUseSummarizerForTitles())
    }

    @Test fun titleChoiceSurvivesTheAppSettingsBackupCodec() {
        val data = AppSettingsPortableData(
            mapOf("summarizer_for_titles" to PortableSettingValue("string", "true")),
            emptyMap(), emptyMap(), """{"version":1,"entries":[]}""", emptyMap(), emptyMap())
        val restored = (AppSettingsPortableCodec.parse(AppSettingsPortableCodec.encode(data)) as AppSettingsPortableCodec.Result.Ok).data
        val global = FakeSharedPreferences()
        global.edit().putString("summarizer_for_titles", restored.globalSettings.getValue("summarizer_for_titles").value as String).commit()
        assertTrue(Preferences(FakeSharedPreferences(), global, "restored").getUseSummarizerForTitles())
    }
}
