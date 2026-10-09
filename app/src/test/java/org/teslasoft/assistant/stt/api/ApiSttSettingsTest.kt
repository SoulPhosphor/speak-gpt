package org.teslasoft.assistant.stt.api

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.preferences.backup.portable.AppSettingsPortableCodec
import org.teslasoft.assistant.preferences.backup.portable.AppSettingsPortableStore
import org.teslasoft.assistant.preferences.backup.portable.AppSettingsPortabilityPolicy
import org.teslasoft.assistant.preferences.tts.TtsRoutingMode
import org.teslasoft.assistant.preferences.tts.TtsRoutingSettings
import org.teslasoft.assistant.tts.api.TtsTarget

/** API Voice Service settings live in global settings, so the settings backup carries them. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ApiSttSettingsTest {
    private val keys = listOf(ApiSttSettings.KEY_TARGET, ApiSttSettings.KEY_LANGUAGE, ApiSttSettings.KEY_VOCABULARY)

    @Test fun settingsRoundTripAndReportConfiguration() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = ApiSttSettings.get(context)
        assertEquals(ApiSttSettings.LANGUAGE_AUTOMATIC, settings.language)
        assertFalse(settings.isConfigured(setOf("ep")))
        settings.target = TtsTarget("ep", "openai/whisper-1", TtsRoutingSettings(TtsRoutingMode.ONLY, ""))
        assertFalse(settings.isConfigured(setOf("ep")))
        settings.target = TtsTarget("ep", "openai/whisper-1", TtsRoutingSettings(TtsRoutingMode.ONLY, "groq"))
        assertTrue(settings.isConfigured(setOf("ep")))
        assertFalse(settings.isConfigured(setOf("other")))
        settings.language = "ja"
        settings.vocabulary = listOf("Seket", "Phosphor, Shines")
        val reread = ApiSttSettings.get(context)
        assertEquals("groq", reread.target.routing.selectedProvider)
        assertEquals("ja", reread.language)
        assertEquals(listOf("Seket", "Phosphor, Shines"), reread.vocabulary)
    }

    @Test fun everyKeyIsCapturedEncodedAndDecodedByTheSettingsBackup() {
        for (key in keys) assertTrue(key,
            AppSettingsPortabilityPolicy.isPortable(AppSettingsPortabilityPolicy.Store.GLOBAL_SETTINGS, key))
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = ApiSttSettings.get(context)
        settings.target = TtsTarget("ep", "openai/whisper-1")
        settings.language = "en"
        settings.vocabulary = listOf("Seket")
        val captured = AppSettingsPortableStore.capture(context).getOrThrow()
        val decoded = (AppSettingsPortableCodec.parse(AppSettingsPortableCodec.encode(captured))
            as AppSettingsPortableCodec.Result.Ok).data
        for (key in keys) assertTrue(key, decoded.globalSettings.containsKey(key))
        assertEquals(captured.globalSettings, decoded.globalSettings)
    }
}
