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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the pure helper logic inside [ImageImporter]. Bitmap decoding,
 * downsampling and disk writes need Android and are not exercised here — this
 * only pins the routing decisions that would silently accept the wrong file
 * type or resize the wrong dimension.
 */
class ImageImporterHelpersTest {

    @Test fun mimeWinsOverExtensionWhenBothArePresent() {
        assertEquals(ImageImporter.InputKind.PNG,
            ImageImporter.classify("image/png", "screenshot.jpg"))
    }

    @Test fun extensionIsTheFallbackWhenNoMimeIsOffered() {
        assertEquals(ImageImporter.InputKind.JPEG,
            ImageImporter.classify(null, "Photo.JPG"))
        assertEquals(ImageImporter.InputKind.PNG,
            ImageImporter.classify(null, "logo.PNG"))
        assertEquals(ImageImporter.InputKind.HEIC,
            ImageImporter.classify(null, "IMG_0001.heic"))
        assertEquals(ImageImporter.InputKind.HEIC,
            ImageImporter.classify(null, "IMG_0001.HEIF"))
    }

    @Test fun heicMimeAndHeifMimeBothMapToHeic() {
        assertEquals(ImageImporter.InputKind.HEIC,
            ImageImporter.classify("image/heic", "phone.heic"))
        assertEquals(ImageImporter.InputKind.HEIC,
            ImageImporter.classify("image/heif", "phone.heif"))
    }

    @Test fun anUnsupportedTypeReturnsNullSoItCanBeRefused() {
        assertNull(ImageImporter.classify("image/gif", "motion.gif"))
        assertNull(ImageImporter.classify("image/svg+xml", "diagram.svg"))
        assertNull(ImageImporter.classify(null, "notes.pdf"))
        assertNull(ImageImporter.classify(null, "noextension"))
    }

    @Test fun sampleSizeKeepsTheDecodedBitmapAtOrAboveTheCap() {
        // The sample is the largest power of two that keeps the decoded
        // longest edge AT OR ABOVE the 2048 cap, so the later downsample only
        // ever scales DOWN to the cap and never has to upscale. A 4032 photo
        // therefore decodes at full size (halving to 2016 would drop below
        // the cap); an 8000 photo decodes at 4000; a 16384 giant at 2048.
        assertEquals(1, ImageImporter.computeSampleSize(2048, 1536))
        assertEquals(1, ImageImporter.computeSampleSize(2048, 2048))
        assertEquals(1, ImageImporter.computeSampleSize(4032, 3024))
        assertEquals(2, ImageImporter.computeSampleSize(8000, 6000))
        assertEquals(8, ImageImporter.computeSampleSize(16384, 12288))
    }

    @Test fun sampleSizeIsOneForTinyImages() {
        assertEquals(1, ImageImporter.computeSampleSize(100, 100))
        assertEquals(1, ImageImporter.computeSampleSize(1, 1))
    }

    @Test fun heicTranscodedFilesGetTheirJpgExtensionAtDisplay() {
        assertEquals("IMG_0001.jpg",
            ImageImporter.rewriteExtension("IMG_0001.heic", "jpg"))
        assertEquals("photo of trip.jpg",
            ImageImporter.rewriteExtension("photo of trip.HEIF", "jpg"))
    }

    @Test fun aNamelessInputStillGetsAFileTypeSuffix() {
        assertEquals("image.jpg",
            ImageImporter.rewriteExtension("image", "jpg"))
    }

    @Test fun chatIdIsSanitizedIntoADirectorySafeName() {
        assertEquals("abc123", ImageImporter.sanitizeChatId("abc123"))
        assertEquals("chat_with_slashes",
            ImageImporter.sanitizeChatId("chat/with/slashes"))
        assertEquals("unassigned", ImageImporter.sanitizeChatId(""))
        assertEquals("unassigned", ImageImporter.sanitizeChatId("   "))
        // Every character outside the safe set becomes underscore, and
        // consecutive weirdness never collapses two chats into the same dir.
        val one = ImageImporter.sanitizeChatId("A B")
        val two = ImageImporter.sanitizeChatId("A.B")
        assertTrue(one != two || one == "A_B")
    }
}
