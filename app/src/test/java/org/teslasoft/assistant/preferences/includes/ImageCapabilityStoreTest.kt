/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************/

package org.teslasoft.assistant.preferences.includes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCapabilityStoreTest {

    @Test fun anEmptyStoreReportsEverythingUnknown() {
        assertEquals(ImageCapability.UNKNOWN, ImageCapabilityStore.get(null, "gpt-4o"))
        assertEquals(ImageCapability.UNKNOWN, ImageCapabilityStore.get("", "gpt-4o"))
        assertEquals(ImageCapability.UNKNOWN, ImageCapabilityStore.get("{}", "gpt-4o"))
    }

    @Test fun setThenGetRoundTripsPerModel() {
        val afterFirst = ImageCapabilityStore.set(
            ImageCapabilityStore.EMPTY, "gpt-4o", ImageCapability.SUPPORTED
        )
        val afterSecond = ImageCapabilityStore.set(
            afterFirst, "gpt-3.5-turbo", ImageCapability.UNSUPPORTED
        )
        assertEquals(ImageCapability.SUPPORTED,
            ImageCapabilityStore.get(afterSecond, "gpt-4o"))
        assertEquals(ImageCapability.UNSUPPORTED,
            ImageCapabilityStore.get(afterSecond, "gpt-3.5-turbo"))
        assertEquals(ImageCapability.UNKNOWN,
            ImageCapabilityStore.get(afterSecond, "some-other-model"))
    }

    @Test fun settingUnknownRemovesTheEntry() {
        // A change back to Unknown must never leave an "unknown" row in the
        // store — the auto-learn path relies on absence-means-unknown.
        val first = ImageCapabilityStore.set(
            ImageCapabilityStore.EMPTY, "gpt-4o", ImageCapability.SUPPORTED
        )
        val cleared = ImageCapabilityStore.set(first, "gpt-4o", ImageCapability.UNKNOWN)
        assertTrue(ImageCapabilityStore.isEmpty(cleared))
        assertEquals(ImageCapability.UNKNOWN,
            ImageCapabilityStore.get(cleared, "gpt-4o"))
    }

    @Test fun entriesAreDeterministicallyOrderedAndSkipUnknowns() {
        val json = ImageCapabilityStore.set(
            ImageCapabilityStore.set(
                ImageCapabilityStore.set(
                    ImageCapabilityStore.EMPTY,
                    "z-model", ImageCapability.SUPPORTED
                ),
                "a-model", ImageCapability.UNSUPPORTED
            ),
            "m-model", ImageCapability.SUPPORTED
        )
        assertEquals(
            listOf(
                "a-model" to ImageCapability.UNSUPPORTED,
                "m-model" to ImageCapability.SUPPORTED,
                "z-model" to ImageCapability.SUPPORTED
            ),
            ImageCapabilityStore.entries(json)
        )
    }

    @Test fun clearingReturnsTheEmptyMarker() {
        assertEquals(ImageCapabilityStore.EMPTY, ImageCapabilityStore.clear())
    }

    @Test fun malformedStoredJsonDoesNotBlowUpTheApp() {
        assertEquals(ImageCapability.UNKNOWN,
            ImageCapabilityStore.get("not json at all", "gpt-4o"))
        assertTrue(ImageCapabilityStore.entries("not json at all").isEmpty())
        // A set on top of garbage discards the garbage and produces a clean map.
        val fixed = ImageCapabilityStore.set(
            "not json", "gpt-4o", ImageCapability.SUPPORTED
        )
        assertEquals(ImageCapability.SUPPORTED,
            ImageCapabilityStore.get(fixed, "gpt-4o"))
    }

    @Test fun blankModelIdIsIgnored() {
        val unchanged = ImageCapabilityStore.set(
            ImageCapabilityStore.EMPTY, "  ", ImageCapability.SUPPORTED
        )
        assertTrue(ImageCapabilityStore.isEmpty(unchanged))
    }

    @Test fun capabilityKeyLookupToleratesCaseAndBadInput() {
        assertEquals(ImageCapability.SUPPORTED, ImageCapability.fromKey("supported"))
        assertEquals(ImageCapability.SUPPORTED, ImageCapability.fromKey("SUPPORTED"))
        assertEquals(ImageCapability.UNSUPPORTED, ImageCapability.fromKey("unsupported"))
        assertEquals(ImageCapability.UNKNOWN, ImageCapability.fromKey(null))
        assertEquals(ImageCapability.UNKNOWN, ImageCapability.fromKey("nonsense"))
    }

    @Test fun isEmptyReflectsWhatEntriesReturns() {
        assertTrue(ImageCapabilityStore.isEmpty(null))
        assertTrue(ImageCapabilityStore.isEmpty(""))
        assertTrue(ImageCapabilityStore.isEmpty(ImageCapabilityStore.EMPTY))
        val one = ImageCapabilityStore.set(
            ImageCapabilityStore.EMPTY, "gpt-4o", ImageCapability.SUPPORTED
        )
        assertFalse(ImageCapabilityStore.isEmpty(one))
    }
}
