/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.imagegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §4.5 of image-generation-rebuild-plan.md: the stored file must use the
 * REAL file type detected from the bytes, never an assumed PNG. Unknown
 * bytes are "no usable image", not a guessed format.
 */
class ImageFormatTest {

    private fun withHeader(vararg header: Int): ByteArray {
        val bytes = ByteArray(32)
        header.forEachIndexed { index, value -> bytes[index] = value.toByte() }
        return bytes
    }

    @Test
    fun pngMagicBytesDetectAsPng() {
        val png = withHeader(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals(ImageFormat.PNG, ImageFormat.detect(png))
        assertEquals("image/png", ImageFormat.PNG.mimeType)
        assertEquals("png", ImageFormat.PNG.fileExtension)
    }

    @Test
    fun jpegMagicBytesDetectAsJpeg() {
        val jpeg = withHeader(0xFF, 0xD8, 0xFF, 0xE0)
        assertEquals(ImageFormat.JPEG, ImageFormat.detect(jpeg))
        assertEquals("jpg", ImageFormat.JPEG.fileExtension)
    }

    @Test
    fun gifMagicBytesDetectAsGif() {
        val gif = "GIF89a".toByteArray().copyOf(32)
        assertEquals(ImageFormat.GIF, ImageFormat.detect(gif))
    }

    @Test
    fun webpMagicBytesDetectAsWebp() {
        val webp = ByteArray(32)
        "RIFF".toByteArray().copyInto(webp, 0)
        "WEBP".toByteArray().copyInto(webp, 8)
        assertEquals(ImageFormat.WEBP, ImageFormat.detect(webp))
    }

    @Test
    fun unknownBytesDetectAsNothing() {
        assertNull(ImageFormat.detect("this is not an image at all!!".toByteArray()))
        assertNull(ImageFormat.detect(ByteArray(0)))
        assertNull(ImageFormat.detect(ByteArray(4))) // too short to judge
    }
}
