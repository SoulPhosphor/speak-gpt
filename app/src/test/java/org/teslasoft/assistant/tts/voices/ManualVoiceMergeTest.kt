package org.teslasoft.assistant.tts.voices

import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.tts.api.*

class ManualVoiceMergeTest {
    private val target = TtsTarget("endpoint", "vendor/model:free", sourceId = "api-tts:tts-1")
    private fun failure(kind: TtsFailureKind) = Result.failure<List<BrowserVoice>>(
        TtsException(TtsFailure(TtsOperation.VOICES, target, "Service", kind)))
    private fun discovered(vararg ids: String) = Result.success(ids.map {
        BrowserVoice("api-tts:tts-1", it, "Name $it", gender = VoiceFacetValue("female", "Female"),
            requiresNetwork = true, canPreview = true)
    })
    private fun merge(discovered: Result<List<BrowserVoice>>, vararg saved: String) =
        SavedApiVoiceProvider.merge("api-tts:tts-1", "vendor/model:free", discovered, saved.toList())

    @Test fun noVoiceListOffersManualEntryEvenBeforeAnythingIsSaved() {
        for (kind in listOf(TtsFailureKind.DISCOVERY_UNAVAILABLE, TtsFailureKind.EMPTY,
                TtsFailureKind.MALFORMED, TtsFailureKind.IDENTIFIERS_MISSING)) {
            val loaded = merge(failure(kind)).getOrThrow()
            assertTrue(loaded.manualEntryAvailable)
            assertTrue(loaded.voices.isEmpty())
            assertEquals(kind, (loaded.discoveryFailure as TtsException).failure.kind)
        }
    }

    @Test fun successfulCatalogDoesNotOfferManualEntry() {
        val loaded = merge(discovered("alloy", "nova")).getOrThrow()
        assertFalse(loaded.manualEntryAvailable)
        assertNull(loaded.discoveryFailure)
        assertTrue(loaded.voices.none { it.manuallySaved })
    }

    @Test fun savedVoicesStayVisibleWhenDiscoveryIsUnavailable() {
        val loaded = merge(failure(TtsFailureKind.DISCOVERY_UNAVAILABLE), "a", "b", "c").getOrThrow()
        assertEquals(listOf("a", "b", "c"), loaded.voices.map { it.providerVoiceId })
        assertTrue(loaded.voices.all { it.manuallySaved && it.canPreview && it.providerModelId == "vendor/model:free" })
        assertEquals(listOf("a", "b", "c"), loaded.voices.map { it.displayName })
        assertNotNull(loaded.discoveryFailure)
    }

    @Test fun failedRequestWithSavedVoicesShowsThemButNoEntryField() {
        val loaded = merge(failure(TtsFailureKind.RATE_LIMIT), "a").getOrThrow()
        assertEquals(listOf("a"), loaded.voices.map { it.providerVoiceId })
        assertFalse(loaded.manualEntryAvailable)
        assertEquals(TtsFailureKind.RATE_LIMIT, (loaded.discoveryFailure as TtsException).failure.kind)
        // Without saved voices a failed request stays the same failure as before.
        assertTrue(merge(failure(TtsFailureKind.RATE_LIMIT)).isFailure)
    }

    @Test fun anIdBothSavedAndDiscoveredAppearsOnceKeepingProviderDetails() {
        val loaded = merge(discovered("alloy", "nova"), "nova", "custom").getOrThrow()
        assertEquals(listOf("alloy", "nova", "custom"), loaded.voices.map { it.providerVoiceId })
        val nova = loaded.voices[1]
        assertTrue(nova.manuallySaved)
        assertEquals("Name nova", nova.displayName)
        assertEquals("female", nova.gender?.id)
        assertFalse(loaded.voices[0].manuallySaved)
        // Removing the saved copy leaves the provider's own row.
        val afterRemoval = merge(discovered("alloy", "nova"), "custom").getOrThrow()
        assertFalse(afterRemoval.voices.single { it.providerVoiceId == "nova" }.manuallySaved)
    }

    @Test fun userAssignedNameAppliesWithoutChangingTheVoiceId() {
        val manual = merge(failure(TtsFailureKind.DISCOVERY_UNAVAILABLE), "ref-123").getOrThrow().voices.single()
        val renamed = VoiceIdentityRegistry.applyOverride(manual,
            VoiceIdentityRegistry.VoiceIdentityOverride("Narrator", "male"))
        assertEquals("Narrator", renamed.displayName)
        assertEquals("ref-123", renamed.providerVoiceId)
        assertTrue(renamed.manuallySaved)
    }

    @Test fun unreadableSavedVoicesAreReportedNotTreatedAsNone() {
        val unreadable = org.teslasoft.assistant.preferences.tts.TtsStorageException(
            org.teslasoft.assistant.preferences.tts.TtsStorageFailure.INVALID_DATA, IllegalArgumentException("Failed requirement."))
        // Discovered voices still appear beside the read failure.
        val withCatalog = merge(discovered("alloy"), emptyList(), unreadable).getOrThrow()
        assertEquals(listOf("alloy"), withCatalog.voices.map { it.providerVoiceId })
        assertSame(unreadable, withCatalog.savedVoicesFailure)
        // A failed request no longer hides the read failure behind its own error.
        val withoutCatalog = merge(failure(TtsFailureKind.RATE_LIMIT), emptyList(), unreadable).getOrThrow()
        assertTrue(withoutCatalog.voices.isEmpty())
        assertSame(unreadable, withoutCatalog.savedVoicesFailure)
        assertEquals(TtsFailureKind.RATE_LIMIT, (withoutCatalog.discoveryFailure as TtsException).failure.kind)
    }

    @Test fun storageErrorDetailsKeepEveryReportedCause() {
        val error = org.teslasoft.assistant.preferences.tts.TtsStorageException(
            org.teslasoft.assistant.preferences.tts.TtsStorageFailure.WRITE_FAILED,
            java.io.IOException("No space left on device"))
        assertEquals(listOf("TtsStorageException: WRITE_FAILED", "IOException: No space left on device"),
            ManualVoiceDialogs.errorLines(error))
    }

    private fun merge(discovered: Result<List<BrowserVoice>>, saved: List<String>, savedFailure: Throwable) =
        SavedApiVoiceProvider.merge("api-tts:tts-1", "vendor/model:free", discovered, saved, savedFailure)
}
