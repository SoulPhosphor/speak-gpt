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

    @Test fun googleNumberingNeverRenumbersOrReusesMissingAssignments() {
        val first = GoogleVoiceNumberRegistry.assign(emptyMap(), 1, listOf("voice-z", "voice-a"))
        assertEquals(1, first.assignments["voice-a"])
        assertEquals(2, first.assignments["voice-z"])
        val afterPackChange = GoogleVoiceNumberRegistry.assign(first.assignments, first.nextNumber, listOf("voice-new"))
        assertEquals(1, afterPackChange.assignments["voice-a"])
        assertEquals(2, afterPackChange.assignments["voice-z"])
        assertEquals(3, afterPackChange.assignments["voice-new"])
    }

    @Test fun onlyMissingLocalDataRequiresDownload() {
        assertTrue(GoogleVoiceMetadata.isDownloadRequired(false, setOf("notInstalled"), "notInstalled"))
        assertFalse(GoogleVoiceMetadata.isDownloadRequired(false, emptySet(), "notInstalled"))
        assertFalse(GoogleVoiceMetadata.isDownloadRequired(true, setOf("notInstalled"), "notInstalled"))
    }

    @Test fun userIdentityOverrideReplacesDisplayNameAndProviderGenderWithoutLosingOriginals() {
        val providerGender = VoiceFacetValue("female", "Female")
        val original = googleVoice.copy(
            displayName = "Voice 500",
            originalDisplayName = "Voice 500",
            gender = providerGender,
            providerGender = providerGender
        )
        val renamed = VoiceIdentityRegistry.applyOverride(
            original,
            VoiceIdentityRegistry.VoiceIdentityOverride("Fred", "neutral")
        )

        assertEquals("Fred", renamed.displayName)
        assertEquals("neutral", renamed.gender?.id)
        assertEquals("neutral", renamed.userAssignedGender?.id)
        assertEquals("female", renamed.providerGender?.id)
        assertEquals("Voice 500", renamed.originalDisplayName)
        assertEquals(original.providerVoiceId, renamed.providerVoiceId)
    }

    @Test fun identityOverrideCodecRoundTripsProviderScopedVoiceKeys() {
        val id = VoiceIdentityRegistry.key("openai", "server-voice-name")
        val expected = mapOf(id to VoiceIdentityRegistry.VoiceIdentityOverride("Fred", "male"))
        assertEquals(expected, VoiceIdentityRegistry.decode(VoiceIdentityRegistry.encode(expected)))
    }

    @Test fun userAssignedGenderCreatesOnlyTheAvailableGenderFilterOption() {
        val neutral = VoiceIdentityRegistry.applyOverride(
            googleVoice.copy(gender = null, providerGender = null),
            VoiceIdentityRegistry.VoiceIdentityOverride("Voice 1", "neutral")
        )
        val gender = VoiceBrowserFilters.definitions(listOf(neutral)).single { it.facet == VoiceFacet.GENDER }
        assertEquals(listOf("neutral"), gender.options.map { it.id })
    }

    @Test fun previewDoesNotSelectButRowSelectionDoes() {
        val google = FakeProvider("google", listOf(googleVoice))
        val controller = VoiceBrowserController(listOf(google), "google")
        controller.preview(googleVoice, VoicePreviewText.DEFAULT, onFailure = { error(it) })
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

    @Test fun lateResultFromPreviousProviderIsIgnored() {
        val google = DelayedProvider("google")
        val openAi = DelayedProvider("openai")
        val controller = VoiceBrowserController(listOf(google, openAi), "google")
        controller.load { }
        controller.browse("openai") { }
        openAi.complete(listOf(googleVoice.copy(providerId = "openai", providerVoiceId = "river")))
        google.complete(listOf(googleVoice))
        assertEquals("openai", controller.browsedProviderId)
        assertEquals("river", controller.visibleVoices().single().providerVoiceId)
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
        override fun preview(voice: BrowserVoice, sampleText: String, onFailure: (String) -> Unit, onCatalogChanged: () -> Unit) { previews++ }
        override fun download(voice: BrowserVoice, onFailure: (String) -> Unit) = Unit
        override fun stopPreview() = Unit
        override fun shutdown() { closed = true }
    }

    private class DelayedProvider(override val id: String) : VoiceBrowserProvider {
        override val displayName = id
        override val exposesLocationFilter = false
        private val callbacks = mutableListOf<(Result<List<BrowserVoice>>) -> Unit>()
        override fun loadVoices(onResult: (Result<List<BrowserVoice>>) -> Unit) { callbacks += onResult }
        fun complete(voices: List<BrowserVoice>) = callbacks.removeAt(0)(Result.success(voices))
        override fun activeVoiceId(): String? = null
        override fun activate(voice: BrowserVoice) = Unit
        override fun preview(voice: BrowserVoice, sampleText: String, onFailure: (String) -> Unit, onCatalogChanged: () -> Unit) = Unit
        override fun download(voice: BrowserVoice, onFailure: (String) -> Unit) = Unit
        override fun stopPreview() = Unit
        override fun shutdown() = Unit
    }
}
