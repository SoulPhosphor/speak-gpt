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

package org.teslasoft.assistant.preferences.includes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import org.teslasoft.assistant.util.Hash
import org.teslasoft.assistant.util.StableId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Turns a picked image into a [ChatInclude], on the device, with no network.
 *
 * The user's original file is never touched. The importer decodes it once
 * (via [ImageDecoder], which handles JPEG/PNG/HEIC natively on API 28+),
 * applies EXIF orientation so the model sees a photo the right way up,
 * downsamples so the longest edge is at most [MAX_DIMENSION_PX], and writes
 * the normalized copy into the chat's own directory under a content-hash
 * file name. HEIC input is transcoded to JPEG so every downstream OpenAI-
 * compatible endpoint can read it. The reported width, height and MIME on
 * the returned [ChatInclude] describe the NORMALIZED image — the one that
 * will actually be sent.
 */
object ImageImporter {

    /** Longest-edge cap after downsampling. Documented as an app-level policy
     *  in `document-includes-plan.md`; not a universal provider limit. Fine
     *  detail and small text may be affected by the resize. */
    const val MAX_DIMENSION_PX = 2048

    /** JPEG encoder quality for downsampled or transcoded output. High enough
     *  that photos hold up well while still noticeably shrinking a 2048-cap
     *  copy of a phone photo. */
    private const val JPEG_QUALITY = 90

    /** Hard cap on the raw input bytes we will hold in memory during decode.
     *  Keeps a truly malformed or hostile file from OOMing the app before
     *  ImageDecoder even inspects it. */
    private const val MAX_SOURCE_BYTES = 64L * 1024L * 1024L

    /** MIME types offered to the system image picker. HEIC/HEIF are accepted
     *  and converted to JPEG at import time; any other input is refused
     *  immediately with the approved dialog. */
    val PICKER_MIME_TYPES = arrayOf(
        "image/jpeg",
        "image/jpg",
        "image/png",
        "image/heic",
        "image/heif"
    )

    sealed class Result {
        data class Success(val include: ChatInclude, val onDiskFile: File) : Result()
        /** Extension or MIME is not one of JPEG, PNG or HEIC. */
        data class Unsupported(val fileName: String) : Result()
        /** HEIC decode/encode failed. Distinct from a generic read failure
         *  because the user's next step is different (convert externally). */
        data class HeicConversionFailed(val fileName: String) : Result()
        /** The bytes could not be read at all — permission gone, source app
         *  unresponsive, or the file damaged. */
        data class ReadFailed(val fileName: String) : Result()
        /** The image would not fit the safe processing budget on this device. */
        data class TooLarge(val fileName: String) : Result()
        /** Any other, unanticipated failure. */
        data class Unknown(val fileName: String) : Result()
    }

    /** Stable pending-only identity for an Android image URI, mirroring
     *  [DocumentImporter.sourceFingerprint] so both share duplicate-detection
     *  semantics. */
    fun sourceFingerprint(sourceIdentity: String): String = Hash.hash(sourceIdentity)

    /** Absolute path to the chat's per-conversation image directory. Files
     *  under this path are the app's own copies of what the user attached;
     *  they belong to that chat and are removed when the chat is deleted. */
    fun chatImagesDir(context: Context, chatId: String): File {
        val root = context.getExternalFilesDir(CHAT_IMAGES_ROOT)
            ?: File(context.filesDir, CHAT_IMAGES_ROOT).apply { mkdirs() }
        val subDir = File(root, sanitizeChatId(chatId))
        if (!subDir.exists()) subDir.mkdirs()
        return subDir
    }

    /** Concrete on-disk file for a chat's stored image, or null when the
     *  bytes are gone (Reduced/Removed) and the caller should not try to
     *  resend them. */
    fun imageFile(context: Context, chatId: String, include: ChatInclude): File? {
        val hash = include.imageFileHash?.takeIf { it.isNotEmpty() } ?: return null
        val ext = extensionFor(include.imageMimeType) ?: return null
        return File(chatImagesDir(context, chatId), "$hash.$ext")
    }

    /** Delete the bytes-on-disk for one include. Called from Reduce and Remove
     *  so the user's copy leaves the device the moment it is no longer sent. */
    fun deleteImageFile(context: Context, chatId: String, include: ChatInclude) {
        imageFile(context, chatId, include)?.takeIf { it.exists() }?.delete()
    }

    /** Remove every image the chat ever stored. Called when a chat is deleted
     *  in full, so private copies cannot outlive their chat. */
    fun deleteChatImages(context: Context, chatId: String) {
        val root = context.getExternalFilesDir(CHAT_IMAGES_ROOT)
            ?: File(context.filesDir, CHAT_IMAGES_ROOT)
        val subDir = File(root, sanitizeChatId(chatId))
        if (!subDir.exists()) return
        subDir.listFiles()?.forEach { it.delete() }
        subDir.delete()
    }

    /**
     * Import one picked image into the given chat.
     *
     * @param displayNameOverride when the caller has a better name than the
     *  provider offers — used by the Camera flow, which needs the app to name
     *  the photo after the chat, not the always-`tmp.jpg` placeholder file.
     */
    fun import(
        context: Context,
        uri: Uri,
        chatId: String,
        displayNameOverride: String? = null
    ): Result {
        return try {
            importOrThrow(context, uri, chatId, displayNameOverride)
        } catch (_: OutOfMemoryError) {
            Result.TooLarge(displayNameSafely(context, uri, displayNameOverride))
        } catch (_: Exception) {
            Result.Unknown(displayNameSafely(context, uri, displayNameOverride))
        }
    }

    private fun importOrThrow(
        context: Context,
        uri: Uri,
        chatId: String,
        displayNameOverride: String?
    ): Result {
        val rawName = displayNameOverride?.takeIf { it.isNotBlank() }
            ?: displayName(context, uri)

        val sourceMime = try {
            context.contentResolver.getType(uri)
        } catch (_: SecurityException) {
            return Result.ReadFailed(rawName)
        } catch (_: Exception) {
            null
        }

        val inputKind = classify(sourceMime, rawName)
            ?: return Result.Unsupported(rawName)

        val bytes = try {
            readAllBytes(context, uri)
        } catch (_: SecurityException) {
            return Result.ReadFailed(rawName)
        } catch (_: SizeExceeded) {
            return Result.TooLarge(rawName)
        } catch (_: Exception) {
            return Result.ReadFailed(rawName)
        }
        if (bytes.isEmpty()) return Result.ReadFailed(rawName)

        // ImageDecoder decodes JPEG/PNG/HEIC natively on API 28+, which is
        // this app's minSdk. A ByteBuffer source is stable across multiple
        // internal reads and avoids re-opening the provider URI mid-import.
        val decoded = try {
            ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
            ) { decoder, info, _ ->
                decoder.setTargetSampleSize(
                    computeSampleSize(info.size.width, info.size.height)
                )
                // A software bitmap is what BitmapFactory used to hand back,
                // and it is what Bitmap.compress() requires later. Hardware
                // bitmaps cannot be re-encoded.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (_: OutOfMemoryError) {
            return Result.TooLarge(rawName)
        } catch (_: Exception) {
            return if (inputKind == InputKind.HEIC) {
                Result.HeicConversionFailed(rawName)
            } else {
                Result.ReadFailed(rawName)
            }
        }

        // EXIF orientation must be applied even after ImageDecoder — some
        // devices deliver a rotated bitmap, some deliver the raw pixels plus
        // an orientation tag we must honour ourselves.
        val orientation = readOrientation(bytes)
        val oriented = try {
            applyOrientation(decoded, orientation)
        } catch (_: OutOfMemoryError) {
            decoded.recycle()
            return Result.TooLarge(rawName)
        }
        if (oriented !== decoded) decoded.recycle()

        val downsized = try {
            downsampleToCap(oriented)
        } catch (_: OutOfMemoryError) {
            oriented.recycle()
            return Result.TooLarge(rawName)
        }
        if (downsized !== oriented) oriented.recycle()

        val outputMime = when (inputKind) {
            InputKind.JPEG, InputKind.HEIC -> "image/jpeg"
            InputKind.PNG -> "image/png"
        }
        val outputExt = when (outputMime) {
            "image/png" -> "png"
            else -> "jpg"
        }

        val encoded = try {
            encode(downsized, outputMime)
        } catch (_: OutOfMemoryError) {
            downsized.recycle()
            return Result.TooLarge(rawName)
        } finally {
            downsized.recycle()
        }
        if (encoded == null) {
            return if (inputKind == InputKind.HEIC) {
                Result.HeicConversionFailed(rawName)
            } else {
                Result.Unknown(rawName)
            }
        }

        val hash = Hash.hash(encoded)
        val dir = chatImagesDir(context, chatId)
        val target = File(dir, "$hash.$outputExt")
        try {
            if (!target.exists()) {
                FileOutputStream(target).use { out -> out.write(encoded) }
            }
        } catch (_: OutOfMemoryError) {
            target.delete()
            return Result.TooLarge(rawName)
        } catch (_: Exception) {
            target.delete()
            return Result.Unknown(rawName)
        }

        // The display name for camera captures is chosen by the caller; a
        // gallery pick keeps its own name unless the extension needs to be
        // rewritten because HEIC was transcoded to JPEG.
        val displayName = when {
            inputKind == InputKind.HEIC -> rewriteExtension(rawName, "jpg")
            else -> rawName
        }

        val include = ChatInclude(
            id = StableId.newId("inc-"),
            fileName = displayName,
            kind = if (outputMime == "image/png") IncludeKind.PNG else IncludeKind.JPEG,
            form = IncludeForm.FULL,
            fullText = "",
            imageFileHash = hash,
            imageMimeType = outputMime,
            imageWidth = downsizedDimensions.width,
            imageHeight = downsizedDimensions.height,
            sourceFingerprint = sourceFingerprint(uri.toString())
        )
        return Result.Success(include, target)
    }

    // ---------------------------------------------------------------------

    private const val CHAT_IMAGES_ROOT = "chat_includes"

    /** Sanitizes a chat id for use as a directory name. Chat ids are already
     *  stable hashes in this app, but a null/empty string would collapse
     *  every chat's images into a single "no-chat" directory. */
    internal fun sanitizeChatId(chatId: String): String {
        val trimmed = chatId.trim()
        if (trimmed.isEmpty()) return "unassigned"
        val sanitized = trimmed.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return sanitized.ifEmpty { "unassigned" }
    }

    /** Two static ints holding the last-decoded bitmap's dimensions. Kept as
     *  a tiny mutable holder so [importOrThrow] can name the numbers without
     *  a third-tier data class just for this one call. */
    private data class Dimensions(var width: Int = 0, var height: Int = 0)
    private val downsizedDimensions = Dimensions()

    private fun encode(bitmap: Bitmap, mime: String): ByteArray? {
        // Save dimensions BEFORE recycling; the estimator on the include reads
        // them and cannot re-derive them from bytes without a redecode.
        downsizedDimensions.width = bitmap.width
        downsizedDimensions.height = bitmap.height
        val output = ByteArrayOutputStream(bitmap.width * bitmap.height / 4)
        val format = if (mime == "image/png") {
            Bitmap.CompressFormat.PNG
        } else {
            Bitmap.CompressFormat.JPEG
        }
        val ok = bitmap.compress(format, JPEG_QUALITY, output)
        if (!ok) return null
        return output.toByteArray()
    }

    private fun downsampleToCap(bitmap: Bitmap): Bitmap {
        val longest = kotlin.math.max(bitmap.width, bitmap.height)
        if (longest <= MAX_DIMENSION_PX) return bitmap
        val scale = MAX_DIMENSION_PX.toFloat() / longest.toFloat()
        val newWidth = kotlin.math.max(1, (bitmap.width * scale).toInt())
        val newHeight = kotlin.math.max(1, (bitmap.height * scale).toInt())
        return bitmap.scale(newWidth, newHeight)
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun readOrientation(bytes: ByteArray): Int {
        return try {
            ByteArrayInputStream(bytes).use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    /** Two-power sample size chosen so the decoded bitmap is not wildly
     *  larger than the eventual 2048-cap output. Halves memory pressure for
     *  very large source photos. */
    internal fun computeSampleSize(sourceWidth: Int, sourceHeight: Int): Int {
        val longest = kotlin.math.max(sourceWidth, sourceHeight)
        if (longest <= MAX_DIMENSION_PX) return 1
        var sample = 1
        while (longest / (sample * 2) >= MAX_DIMENSION_PX) sample *= 2
        return sample
    }

    private class SizeExceeded : RuntimeException()

    private fun readAllBytes(context: Context, uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("provider returned null stream")
        return input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (total > MAX_SOURCE_BYTES) throw SizeExceeded()
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }

    internal enum class InputKind { JPEG, PNG, HEIC }

    /** Classifies the picked image by MIME first, falling back to the file
     *  name's extension when the provider offers no MIME (some content
     *  providers return null for stream types). */
    internal fun classify(mime: String?, fileName: String): InputKind? {
        val fromMime = when {
            mime == null -> null
            mime.equals("image/jpeg", true) -> InputKind.JPEG
            mime.equals("image/jpg", true) -> InputKind.JPEG
            mime.equals("image/png", true) -> InputKind.PNG
            mime.equals("image/heic", true) -> InputKind.HEIC
            mime.equals("image/heif", true) -> InputKind.HEIC
            else -> null
        }
        if (fromMime != null) return fromMime
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> InputKind.JPEG
            "png" -> InputKind.PNG
            "heic", "heif" -> InputKind.HEIC
            else -> null
        }
    }

    internal fun rewriteExtension(fileName: String, newExt: String): String {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        return "$base.$newExt"
    }

    private fun extensionFor(mime: String?): String? = when {
        mime == null -> null
        mime.equals("image/jpeg", true) || mime.equals("image/jpg", true) -> "jpg"
        mime.equals("image/png", true) -> "png"
        else -> null
    }

    private fun displayNameSafely(
        context: Context,
        uri: Uri,
        override: String?
    ): String {
        override?.takeIf { it.isNotBlank() }?.let { return it }
        return try { displayName(context, uri) } catch (_: Exception) { "image" }
    }

    private fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(index)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Exception) { /* fall through to a path-derived name */ }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "image"
    }
}

/** [Bitmap.scale] wrapper for cross-version compat — matches how the older
 *  profile-image code uses createScaledBitmap. */
private fun Bitmap.scale(w: Int, h: Int): Bitmap =
    Bitmap.createScaledBitmap(this, w, h, true)
