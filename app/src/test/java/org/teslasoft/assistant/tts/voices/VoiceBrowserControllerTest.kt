package org.teslasoft.assistant.tts.voices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceBrowserControllerTest {
    private val googleVoice = BrowserVoice(
        providerId = "google",
        providerVoiceId = "en-us-x-iom-network",
        displayName = "Voice 1",
        language = VoiceFacetValue("en", "English"),
        region = VoiceFacetValue("US", "United States"),
        quality = VoiceFacetValue("high", "High"),
        requiresNetwork = true,
        canPreview = true
    )

    @Test fun existingStoredGoogleIdStillResolvesExactly() {
        val google = FakeProvider("google", listOf(googleVoice), activeId = googleVoice.providerVoiceId)
        val controller = VoiceBrowserController(listOf(google), "google")
        controller.load { }
        assertEquals(googleVoice.providerVoiceId, controller.provider.activeVoiceId())
        assertEquals(googleVoice.providerVoiceId, controller.visibleVoices().single().providerVoiceId)
    }

    @Test fun browsingAnotherProviderDoesNotActivateIt() {
        val google = FakeProvider("google", listOf(googleVoice), activeId = googleVoice.providerVoiceId)
        val openAi = FakeProvider("openai", listOf(googleVoice.copy(providerId = "openai", providerVoiceId = "nova")))
        val controller = VoiceBrowserController(listOf(google, openAi), "google")
        controller.browse("openai") { }
        assertEquals(0, openAi.activations)
        assertEquals(googleVoice.providerVoiceId, google.activeVoiceId())
    }

    @Test fun locationLanguageAndRegionFiltersUseNormalizedProperties() {
        val local = googleVoice.copy(providerVoiceId = "local", requiresNetwork = false)
        val uk = googleVoice.copy(
            providerVoiceId = "uk",
            region = VoiceFacetValue("GB", "United Kingdom"),
            requiresNetwork = false
        )
        val state = VoiceFilterState(VoiceLocation.ON_DEVICE, mutableMapOf(VoiceFacet.REGION to "GB"))
        assertEquals(listOf("uk"), VoiceBrowserFilters.apply(listOf(googleVoice, local, uk), state).map { it.providerVoiceId })
    }

    @Test fun unsupportedFiltersAreNotDefined() {
        val definitions = VoiceBrowserFilters.definitions(listOf(googleVoice.copy(quality = null)))
        assertFalse(definitions.any { it.facet == VoiceFacet.GENDER })
        assertFalse(definitions.any { it.facet == VoiceFacet.QUALITY })
        assertTrue(definitions.any { it.facet == VoiceFacet.LANGUAGE })
    }

    @Test fun opaqueGoogleCodesNeverProduceGuessedGender() {
        assertNull(GoogleVoiceMetadata.explicitGender(listOf("en-us-x-iom-network", "sfg", "tpf")))
        assertEquals("female", GoogleVoiceMetadata.explicitGender(listOf("en-US-language#female_1-local"))?.id)
        assertEquals("male", GoogleVoiceMetadata.explicitGender(listOf("gender=male"))?.id)
    }

    @Test fun androidQualityConstantsMapToReadableLabels() {
        assertEquals("Very Low", VoiceQualityLabels.fromAndroidQuality(100)?.label)
        assertEquals("Normal", VoiceQualityLabels.fromAndroidQuality(300)?.label)
        assertEquals("Very High", VoiceQualityLabels.fromAndroidQuality(500)?.label)
        assertNull(VoiceQualityLabels.fromAndroidQuality(123))
    }

    @Test fun googleNumberingIsStableAcrossFiltering() {
        val names = GoogleVoiceMetadata.deterministicDisplayNames(listOf("voice-z", "voice-a", "voice-m"))
        assertEquals("Voice 1", names["voice-a"])
        assertEquals("Voice 2", names["voice-m"])
        assertEquals("Voice 3", names["voice-z"])
    }

    @Test fun onlyMissingLocalDataRequiresDownload() {
        assertTrue(GoogleVoiceMetadata.isDownloadRequired(false, setOf("notInstalled"), "notInstalled"))
        assertFalse(GoogleVoiceMetadata.isDownloadRequired(false, emptySet(), "notInstalled"))
        assertFalse(GoogleVoiceMetadata.isDownloadRequired(true, setOf("notInstalled"), "notInstalled"))
    }

    @Test fun previewDoesNotSelectButRowSelectionDoes() {
        val google = FakeProvider("google", listOf(googleVoice))
        val controller = VoiceBrowserController(listOf(google), "google")
        controller.preview(googleVoice) { error(it) }
        assertEquals(1, google.previews)
        assertEquals(0, google.activations)
        controller.select(googleVoice)
        assertEquals(1, google.activations)
        assertEquals(googleVoice.providerVoiceId, google.activeVoiceId())
    }

    @Test fun filterEmptyAndProviderFailureRemainDifferentStates() {
        val google = FakeProvider("google", listOf(googleVoice))
        val failed = FakeProvider("failed", emptyList(), failure = IllegalStateException("engine unavailable"))
        val controller = VoiceBrowserController(listOf(google, failed), "google")
        controller.load { }
        controller.filterState.selectedFacetValues[VoiceFacet.REGION] = "AU"
        assertTrue(controller.visibleVoices().isEmpty())
        assertTrue(controller.loadState is VoiceLoadState.Ready)
        controller.browse("failed") { }
        assertTrue(controller.loadState is VoiceLoadState.Failed)
    }

    @Test fun closingBrowserShutsDownEveryProvider() {
        val first = FakeProvider("google", listOf(googleVoice))
        val second = FakeProvider("openai", emptyList())
        VoiceBrowserController(listOf(first, second), "google").shutdown()
        assertTrue(first.closed)
        assertTrue(second.closed)
    }

    private class FakeProvider(
        override val id: String,
        private val voices: List<BrowserVoice>,
        private var activeId: String? = null,
        private val failure: Throwable? = null
    ) : VoiceBrowserProvider {
        override val displayName = id
        override val exposesLocationFilter = id == "google"
        var activations = 0
        var previews = 0
        var closed = false

        override fun loadVoices(onResult: (Result<List<BrowserVoice>>) -> Unit) {
            onResult(failure?.let { Result.failure(it) } ?: Result.success(voices))
        }
        override fun activeVoiceId(): String? = activeId
        override fun activate(voice: BrowserVoice) { activations++; activeId = voice.providerVoiceId }
        override fun preview(voice: BrowserVoice, onFailure: (String) -> Unit) { previews++ }
        override fun download(voice: BrowserVoice, onFailure: (String) -> Unit) = Unit
        override fun stopPreview() = Unit
        override fun shutdown() { closed = true }
    }
}
