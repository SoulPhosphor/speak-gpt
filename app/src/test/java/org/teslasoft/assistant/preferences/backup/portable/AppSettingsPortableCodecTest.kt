package org.teslasoft.assistant.preferences.backup.portable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsPortableCodecTest {
    @Test
    fun roundTripPreservesDetailedSettingsAndSavedSpeechSources() {
        val data = AppSettingsPortableData(
            globalSettings = mapOf(
                "summarizer_prompt_1" to PortableSettingValue("string", "custom summary"),
                "memory_assistant_temperature" to PortableSettingValue("float", 0.65f),
                "whisper_decoder_mode" to PortableSettingValue("string", "beam"),
                "whisper_beam_size" to PortableSettingValue("int", 5),
                "vad_hysteresis_enabled" to PortableSettingValue("boolean", true),
                "diagnostic_categories" to PortableSettingValue("string_set", setOf("speech", "memory"))
            ),
            defaultSettings = mapOf(
                "tts_engine" to PortableSettingValue("string", "openai"),
                "openai_voice" to PortableSettingValue("string", "alloy")
            ),
            storageOptions = mapOf(
                "backup.auto_frequency" to PortableSettingValue("string", "weekly")
            ),
            savedTtsSourcesJson = """{"version":1,"entries":[{"id":"tts-1","endpointId":"endpoint-1","modelId":"tts-model","routing":{"mode":"only","selectedProvider":"provider-1","providerOrder":["provider-1"],"allowFallbacks":false}}]}""",
            logitBiasCatalog = mapOf(
                "configs" to PortableSettingValue("string", """[{"label":"Names","id":"lb-1"}]""")
            ),
            logitBiasConfigs = mapOf(
                "lb-1" to mapOf("entry_tokenId" to PortableSettingValue("int", 42))
            )
        )

        val parsed = AppSettingsPortableCodec.parse(AppSettingsPortableCodec.encode(data))

        assertTrue(parsed is AppSettingsPortableCodec.Result.Ok)
        parsed as AppSettingsPortableCodec.Result.Ok
        assertEquals(5, parsed.data.globalSettings["whisper_beam_size"]?.value)
        assertEquals(setOf("speech", "memory"), parsed.data.globalSettings["diagnostic_categories"]?.value)
        assertEquals(1, org.json.JSONObject(parsed.data.savedTtsSourcesJson).getJSONArray("entries").length())
    }

    @Test
    fun forbiddenCredentialKeyCannotBeEncodedOrParsed() {
        val unsafe = emptyData().copy(
            globalSettings = mapOf("custom_api_key" to PortableSettingValue("string", "secret"))
        )
        assertTrue(runCatching { AppSettingsPortableCodec.encode(unsafe) }.isFailure)

        val smuggled = AppSettingsPortableCodec.encode(emptyData()).replace(
            "\"global_settings\":[]",
            "\"global_settings\":[{\"key\":\"oauth_access_token\",\"type\":\"string\",\"value\":\"secret\"}]"
        )
        assertTrue(AppSettingsPortableCodec.parse(smuggled) is AppSettingsPortableCodec.Result.Invalid)
    }

    @Test
    fun deviceSpecificStorageKeysAndMalformedSpeechRoutingAreRejected() {
        val deviceSpecific = emptyData().copy(
            storageOptions = mapOf(
                "backup.auto_folder_uri" to PortableSettingValue("string", "content://device")
            )
        )
        assertTrue(runCatching { AppSettingsPortableCodec.encode(deviceSpecific) }.isFailure)

        val invalidRouting = emptyData().copy(
            savedTtsSourcesJson = """{"version":1,"entries":[{"id":"tts-1","endpointId":"e","modelId":"m","routing":{"mode":"only","selectedProvider":"","providerOrder":[],"allowFallbacks":true}}]}"""
        )
        assertTrue(runCatching { AppSettingsPortableCodec.encode(invalidRouting) }.isFailure)
    }

    @Test
    fun emptyDefaultSettingsCategoryIsExplicitlyRepresentable() {
        val encoded = AppSettingsPortableCodec.encode(emptyData())
        val parsed = AppSettingsPortableCodec.parse(encoded)
        assertTrue(parsed is AppSettingsPortableCodec.Result.Ok)
        assertEquals(0L, (parsed as AppSettingsPortableCodec.Result.Ok).data.recordCount)
    }

    private fun emptyData() = AppSettingsPortableData(
        emptyMap(),
        emptyMap(),
        emptyMap(),
        """{"version":1,"entries":[]}""",
        emptyMap(),
        emptyMap()
    )
}
