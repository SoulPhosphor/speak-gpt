package org.teslasoft.assistant.preferences.backup.portable

import android.app.Application
import android.content.Context
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.preferences.tts.ManualTtsVoice
import org.teslasoft.assistant.preferences.tts.ManualTtsVoicesCodec
import org.teslasoft.assistant.preferences.tts.ManualTtsVoicesPreferences

/** Manually saved Voice IDs travel with the replace-only Settings and Preferences category. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ManualTtsVoicesPortabilityTest {
    private fun voices(context: Context) = ManualTtsVoicesPreferences.getPreferences(context).load().getOrThrow()

    private fun seed(context: Context, entries: List<ManualTtsVoice>) {
        File(context.filesDir, ManualTtsVoicesPreferences.RELATIVE_PATH).delete()
        val store = ManualTtsVoicesPreferences.getPreferences(context)
        entries.forEach { store.add(it.savedSourceId, it.voiceId).getOrThrow() }
    }

    @Test fun manualVoicesSurviveCaptureEncodeDecodeReplaceAndRollback() {
        val context = RuntimeEnvironment.getApplication()
        val backedUp = listOf(ManualTtsVoice("tts-1", "933563129e564b19a115bedd57b7406a"),
            ManualTtsVoice("tts-1", "Mixed Case/ID"), ManualTtsVoice("tts-2", "933563129e564b19a115bedd57b7406a"))
        // A renamed manual voice keeps its name through the settings store that already travels.
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("voice_identity_overrides", """{"api-tts:tts-1\u0000Mixed Case/ID":{"name":"Narrator"}}""").commit()
        seed(context, backedUp)
        val captured = AppSettingsPortableStore.capture(context).getOrThrow()
        val decoded = (AppSettingsPortableCodec.parse(AppSettingsPortableCodec.encode(captured))
            as AppSettingsPortableCodec.Result.Ok).data
        assertEquals(backedUp, ManualTtsVoicesCodec.decode(decoded.manualTtsVoicesJson))
        assertTrue(decoded.globalSettings.containsKey("voice_identity_overrides"))

        val prior = listOf(ManualTtsVoice("tts-9", "other"))
        seed(context, prior)
        val current = AppSettingsPortableStore.capture(context).getOrThrow()
        val root = Files.createTempDirectory("manual-voices-restore").toFile()
        try {
            val participant = AppSettingsRestoreParticipant(context, root,
                AppSettingsRestoreParticipant.PreparedPlan(current = current, desired = decoded))
            assertTrue(participant.validate())
            assertTrue(participant.stage())
            assertTrue(participant.apply())
            assertEquals(backedUp, voices(context))
            assertTrue(participant.rollback())
            assertEquals(prior, voices(context))
            participant.cleanup()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun backupsWithoutManualVoicesStillRestore() {
        val context = RuntimeEnvironment.getApplication()
        seed(context, emptyList())
        val encoded = AppSettingsPortableCodec.encode(AppSettingsPortableStore.capture(context).getOrThrow())
        val older = org.json.JSONObject(encoded).apply { remove("manual_tts_voices") }.toString()
        val parsed = AppSettingsPortableCodec.parse(older) as AppSettingsPortableCodec.Result.Ok
        assertEquals(emptyList<ManualTtsVoice>(), ManualTtsVoicesCodec.decode(parsed.data.manualTtsVoicesJson))
    }

    @Test fun malformedManualVoicesAreRejected() {
        val context = RuntimeEnvironment.getApplication()
        seed(context, emptyList())
        val encoded = AppSettingsPortableCodec.encode(AppSettingsPortableStore.capture(context).getOrThrow())
        for (damaged in listOf(
            """{"version":1,"entries":[{"savedSourceId":"tts-1","voiceId":""}]}""",
            """{"version":1,"entries":[{"savedSourceId":"tts-1","voiceId":"a"},{"savedSourceId":"tts-1","voiceId":"a"}]}""",
            """{"version":1,"entries":[{"savedSourceId":"tts-1","voiceId":"a","apiKey":"secret"}]}"""
        )) {
            val smuggled = org.json.JSONObject(encoded).put("manual_tts_voices", org.json.JSONObject(damaged)).toString()
            assertTrue(damaged, AppSettingsPortableCodec.parse(smuggled) is AppSettingsPortableCodec.Result.Invalid)
        }
    }
}
