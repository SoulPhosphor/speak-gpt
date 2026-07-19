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

package org.teslasoft.assistant.preferences.profileimages

/**
 * The pure accept/reject decision for an uncatalogued permanent file found
 * during reconciliation (RECONCILIATION, "Uncatalogued Permanent File").
 *
 * Filename validity (profile_<hash>.jpg) is checked separately by
 * [ProfileImageFileNaming.hashFromPermanentFilename] before this is called -
 * decoding and hashing an invalid filename is pointless work. This function
 * only judges the remaining two checks once the caller (Android-dependent:
 * needs BitmapFactory to decode and Hash.hash to digest the file bytes) has
 * already computed them: the JPEG must decode to exactly 512x512, and the
 * SHA-256 of the file's bytes must exactly match the hash encoded in its
 * filename. A file matching only one of the two must never be registered.
 */
object ProfileImageReconciliation {

    const val PERMANENT_IMAGE_SIZE = 512

    fun isValidReconciledImage(
        filenameHash: String,
        computedFileHash: String,
        decodedWidth: Int,
        decodedHeight: Int
    ): Boolean {
        return filenameHash == computedFileHash &&
            decodedWidth == PERMANENT_IMAGE_SIZE &&
            decodedHeight == PERMANENT_IMAGE_SIZE
    }
}
