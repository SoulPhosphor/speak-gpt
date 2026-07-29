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

/**
 * Real file-type detection for generated images
 * (image-generation-rebuild-plan.md §4.5): the rebuilt path validates the
 * actual response bytes and stores the true type and extension instead of
 * assuming PNG. Detection is by magic bytes, never by what the provider
 * claimed. Null means the bytes are not one of the supported image
 * formats — the "response did not contain a usable image" case.
 */
enum class ImageFormat(val mimeType: String, val fileExtension: String) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    WEBP("image/webp", "webp"),
    GIF("image/gif", "gif");

    companion object {
        fun detect(bytes: ByteArray): ImageFormat? {
            if (bytes.size < 12) return null
            // PNG: 89 50 4E 47 0D 0A 1A 0A
            if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
            ) return PNG
            // JPEG: FF D8 FF
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte()
            ) return JPEG
            // GIF: "GIF8"
            if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte()
            ) return GIF
            // WEBP: "RIFF" <size> "WEBP"
            if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
            ) return WEBP
            return null
        }
    }
}
