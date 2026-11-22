package org.teslasoft.assistant.preferences

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.conversation.setup.ExperimentalSetup
import org.teslasoft.assistant.conversation.setup.ExperimentalSetupTest.Companion.model

class ExperimentalSetupPreferencesTest {
    @Test fun oldBooleanReadsAreLazyAndKeepTheirOriginalMeaning() {
        val store = FakeSharedPreferences()
        val global = FakeSharedPreferences()
        val prefs = Preferences(store, global, "chat")
        assertFalse(prefs.getChatExperimentalMode())
        assertFalse(prefs.getChatLastMessageOnly())
        prefs.setChatExperimentalMode(true)
        val before = store.all.toMap()
        assertEquals(ExperimentalSetup.Mode.SINGLE, prefs.readExperimentalSetup()!!.mode)
        assertTrue(prefs.getChatLastMessageOnly())
        assertEquals(before, store.all)
        assertFalse(store.contains("experimental_setup"))
    }

    @Test fun setupRoundTripModeSwitchAndLegacyTogglePreserveAllSelectionsAndHistory() {
        val store = FakeSharedPreferences()
        val global = FakeSharedPreferences()
        store.edit().putString("summarizer_summary", "preserved summary").putString("messages", "preserved messages").commit()
        val prefs = Preferences(store, global, "chat")
        val setup = ExperimentalSetup(ExperimentalSetup.Mode.PARALLEL, false, listOf(model("a"), model("b")), "b", false)
        assertTrue(prefs.saveExperimentalSetup(setup))
        val reopened = Preferences(store, global, "chat")
        assertEquals(setup, reopened.readExperimentalSetup())
        assertTrue(reopened.getChatExperimentalMode())
        assertFalse(reopened.getChatLastMessageOnly())
        reopened.setChatExperimentalMode(false)
        val off = reopened.readExperimentalSetup()!!
        assertEquals(ExperimentalSetup.Mode.OFF, off.mode)
        assertEquals(setup.models, off.models)
        assertEquals("b", off.primaryId)
        assertFalse(off.allowImages)
        assertTrue(reopened.saveExperimentalSetup(off.copy(mode = ExperimentalSetup.Mode.SINGLE)))
        assertFalse(reopened.getChatLastMessageOnly())
        assertEquals("preserved summary", store.getString("summarizer_summary", null))
        assertEquals("preserved messages", store.getString("messages", null))
        assertEquals("", Preferences(FakeSharedPreferences(), global, "other").getExperimentalSetupRaw())
        assertTrue(PerChatSettingKeys.ALL.contains("experimental_setup"))
    }

    @Test fun invalidRawDataIsRetainedAndNeverReplacedByLegacyToggle() {
        val store = FakeSharedPreferences()
        store.edit().putString("experimental_setup", "{future or damaged}").commit()
        val prefs = Preferences(store, FakeSharedPreferences(), "chat")
        assertNull(prefs.readExperimentalSetup())
        prefs.setChatExperimentalMode(true)
        assertEquals("{future or damaged}", prefs.getExperimentalSetupRaw())
    }
    @Test fun failedCommitRestoresTheOldLiveSettingsAsWellAsReportingFailure() {
        val backing = FakeSharedPreferences()
        backing.edit().putBoolean("experimental_mode", true).putString("experimental_setup", "previous raw").commit()
        val failing = object : SharedPreferences by backing {
            override fun edit(): SharedPreferences.Editor {
                val editor = backing.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value); return this
                    }
                    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                        editor.putBoolean(key, value); return this
                    }
                    override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                    override fun commit(): Boolean { editor.apply(); return false }
                }
            }
        }
        val prefs = Preferences(failing, FakeSharedPreferences(), "chat")
        assertFalse(prefs.saveExperimentalSetup(ExperimentalSetup()))
        assertEquals("previous raw", backing.getString("experimental_setup", null))
        assertTrue(backing.getBoolean("experimental_mode", false))
    }

}
