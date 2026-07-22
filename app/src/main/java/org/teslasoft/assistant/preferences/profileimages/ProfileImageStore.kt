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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.teslasoft.assistant.util.Hash
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Facade over the permanent Profile Image store: the catalog database
 * ([ProfileImageDb]), the permanent JPEG files ([ProfileImageFileStore]),
 * and the temporary framing-session cache. This is the single entry point
 * later phases (Framing, the Gallery, every identity editor) use - none of
 * them should touch [ProfileImageDb] or the file layout directly.
 *
 * Headless by design: this class has no UI and knows nothing about which
 * identity a hash belongs to. Usage (who references a hash) is computed
 * elsewhere, off this class, from live identity data - never cached here.
 */
class ProfileImageStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val db = ProfileImageDb.getInstance(appContext)

    companion object {
        private const val PERMANENT_DIR_NAME = "profile_images"
        private const val FRAMING_SESSIONS_DIR_NAME = "profile_framing"

        const val JPEG_QUALITY = 92
        const val STALE_FRAMING_SESSION_MILLIS = 24 * 60 * 60 * 1000L

        @Volatile
        private var instance: ProfileImageStore? = null

        fun getInstance(context: Context): ProfileImageStore {
            return instance ?: synchronized(this) {
                instance ?: ProfileImageStore(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Forget the cached wrapper (it holds a [ProfileImageDb] reference)
         * so a catalog repair can replace the database file underneath.
         * Called by DatabaseRepairManager together with
         * [ProfileImageDb.invalidateInstance]; order does not matter, both
         * must run.
         */
        fun invalidateInstance() {
            synchronized(this) { instance = null }
        }
    }

    private fun permanentDir(): File? = appContext.getExternalFilesDir(PERMANENT_DIR_NAME)

    /**
     * Atomic Save Process (profile-images-plan.md): encodes [bitmap] once as
     * JPEG q92, hashes those exact encoded bytes, writes the permanent file,
     * then inserts or reuses the catalog record. Returns the bare hash only
     * after the permanent file is valid on disk; returns null if the file
     * could not be written, in which case the caller must not assign the
     * image to any profile. [bitmap] must already be the final, cropped
     * 512x512 result - this class performs no cropping or resizing.
     */
    fun save(bitmap: Bitmap): String? {
        val dir = permanentDir() ?: return null

        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        val encodedBytes = outputStream.toByteArray()

        val result = ProfileImageFileStore.writeEncodedImage(dir, encodedBytes) ?: return null

        // insertOrIgnore is a no-op when the hash is already catalogued, so a
        // dedup reuse never disturbs the original created_at.
        db.insertOrIgnore(result.hash, System.currentTimeMillis())

        return result.hash
    }

    fun listNewestFirst(): List<ProfileImageRecord> = db.listNewestFirst()

    fun contains(hash: String): Boolean = db.contains(hash)

    /** The permanent file for [hash], or null if it is not on disk (a missing-file record). */
    fun imageFile(hash: String): File? {
        val dir = permanentDir() ?: return null
        val file = File(dir, ProfileImageFileNaming.permanentFileName(hash))
        return if (file.exists()) file else null
    }

    /**
     * Permanently deletes [hash]: file first (a missing file counts as
     * success), then the catalog record. Callers are responsible for
     * confirming the image is actually unused immediately before calling
     * this - this class does not compute usage.
     */
    fun delete(hash: String): Boolean {
        val dir = permanentDir() ?: return false
        val fileDeleted = ProfileImageFileStore.deleteImageFile(dir, hash)
        if (fileDeleted) {
            db.delete(hash)
        }
        return fileDeleted
    }

    /**
     * Reconciliation (profile-images-plan.md): runs only when the caller
     * (Profile Images) opens - never at app startup. Registers valid
     * uncatalogued permanent files and reports catalog records whose file
     * is missing. Does not delete or modify missing-file records - whether
     * a missing-file record may be cleared depends on live usage, which is
     * computed by the caller (Phase 5), not by this headless store.
     */
    fun reconcile(): ReconciliationResult {
        val dir = permanentDir() ?: return ReconciliationResult(emptyList(), emptyList())

        val catalogHashes = db.listNewestFirst().map { it.hash }.toSet()
        val filesOnDisk = dir.listFiles()?.toList().orEmpty()

        val registered = ArrayList<String>()
        for (file in filesOnDisk) {
            val filenameHash = ProfileImageFileNaming.hashFromPermanentFilename(file.name) ?: continue
            if (catalogHashes.contains(filenameHash)) continue

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue // failed to decode

            val computedHash = Hash.hash(file.readBytes())
            if (!ProfileImageReconciliation.isValidReconciledImage(filenameHash, computedHash, bounds.outWidth, bounds.outHeight)) {
                continue
            }

            val createdAt = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
            db.insertOrIgnore(filenameHash, createdAt)
            registered.add(filenameHash)
        }

        val diskHashesAfter = filesOnDisk.mapNotNull { ProfileImageFileNaming.hashFromPermanentFilename(it.name) }.toSet()
        val missingFileHashes = db.listNewestFirst().map { it.hash }.filter { it !in diskHashesAfter }

        return ReconciliationResult(registered, missingFileHashes)
    }

    /** Creates a fresh, empty framing-session cache directory and returns it. */
    fun newFramingSessionDir(): File {
        val sessionsDir = File(appContext.cacheDir, FRAMING_SESSIONS_DIR_NAME)
        val sessionDir = File(sessionsDir, UUID.randomUUID().toString())
        sessionDir.mkdirs()
        return sessionDir
    }

    /** The framing-session directory for an existing [sessionToken], without creating it. */
    fun framingSessionDir(sessionToken: String): File {
        return File(File(appContext.cacheDir, FRAMING_SESSIONS_DIR_NAME), sessionToken)
    }

    /**
     * TEMPORARY FILE CLEANUP: removes framing session directories older than
     * [staleThresholdMillis]. Only called when Profile Images opens, and
     * only sweeps directories past the threshold - a session a restored
     * Framing activity still depends on must survive.
     */
    fun cleanupStaleFramingSessions(staleThresholdMillis: Long = STALE_FRAMING_SESSION_MILLIS) {
        val sessionsDir = File(appContext.cacheDir, FRAMING_SESSIONS_DIR_NAME)
        val now = System.currentTimeMillis()
        sessionsDir.listFiles()?.forEach { sessionDir ->
            if (sessionDir.isDirectory && ProfileImageFileNaming.isStaleFramingSession(sessionDir.lastModified(), now, staleThresholdMillis)) {
                sessionDir.deleteRecursively()
            }
        }
    }
}

data class ReconciliationResult(val registeredHashes: List<String>, val missingFileHashes: List<String>)
