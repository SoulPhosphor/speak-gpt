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

package org.teslasoft.assistant.util

/**
 * Naming rule for generated images in the app-owned image cache, extracted
 * from ChatActivity.writeImageToCache so regression tests can pin it before
 * the image generation rebuild (image-generation-rebuild-plan.md, step 1).
 *
 * The stored file is named by the SHA-256 of the Base64 ENCODING of the
 * image bytes — not of the bytes themselves — and chat history references
 * it as `~file:<stem>`. The download path derives the marker id from the
 * same encoded string, which is what keeps marker and filename agreeing.
 */
object GeneratedImageStorage {

    fun cacheFileName(bytes: ByteArray, imageType: String = "png"): String {
        return Hash.hash(java.util.Base64.getEncoder().encodeToString(bytes)) + "." + imageType
    }
}
