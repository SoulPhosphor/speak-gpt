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

package org.teslasoft.assistant.preferences.backup.portable

import org.json.JSONObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.util.Base64

/**
 * Portable recovery package format v2 (owner-approved architecture, July 22
 * 2026). File layout:
 *
 *   [8-byte magic "PHOSBKP2"][u32 big-endian header length][header JSON][body]
 *
 * The ENTIRE prefix (magic + length + header bytes) is the associated data of
 * the body's AES-GCM encryption, so no header field — including the format
 * version and the KDF parameters — can be altered without failing
 * authentication. The one unavoidable exception, stated honestly per owner
 * ruling 4: the header must be READ before it can be verified, so pre-
 * authentication decisions driven by it (which KDF cost to run, which
 * fingerprint to compare) are protected only by the hard v1 bounds
 * (KDF window, size caps) and by the rule that nothing decrypted is trusted
 * before doFinal() succeeds.
 *
 * Protection levels:
 *  - "aes256gcm" — encrypted tier; body is GCM ciphertext of the inner ZIP.
 *  - "none" — unencrypted tier; body is the inner ZIP verbatim. It carries
 *    manifest hashes for ACCIDENT detection only — wording must say "checked
 *    for damage", never "verified"; an attacker can recompute unkeyed hashes.
 *
 * The v1 installation-bound artifacts (SpeakGPT/legacy four-file sets) have no
 * magic; [readHeader] classifies them as NOT_A_V2_PACKAGE so callers can label
 * them honestly instead of mis-parsing them.
 */
object PortablePackageFormat {

    val MAGIC: ByteArray = "PHOSBKP2".toByteArray(Charsets.US_ASCII)
    const val FORMAT_VERSION = 2
    const val PROTECTION_ENCRYPTED = "aes256gcm"
    const val PROTECTION_NONE = "none"

    /** Sanity cap for the header itself (it is tiny in practice). */
    const val MAX_HEADER_BYTES = 64 * 1024

    /**
     * Restore error model (owner ruling 4 — exact user-facing wording lives in
     * strings.xml as backup_err_*; this layer returns types only):
     *  - MISTYPED_CODE       -> "Invalid or mistyped Recovery Code."
     *  - WRONG_KEY_OR_HEADER -> "This Recovery Code belongs to a different
     *                            Recovery Key, or the backup header is damaged."
     *  - DAMAGED_OR_ALTERED  -> "The backup is damaged or has been altered."
     *  - UNSUPPORTED_PROTECTION -> "Unsupported or invalid backup protection
     *                            settings." (KDF window violation — NEVER
     *                            reported as generic damage.)
     * No perfect cryptographic distinction between a wrong Recovery Key and a
     * maliciously altered fingerprint field is promised before the complete
     * package has been authenticated — hence WRONG_KEY_OR_HEADER is one state.
     */
    enum class RestoreError {
        MISTYPED_CODE,
        WRONG_KEY_OR_HEADER,
        DAMAGED_OR_ALTERED,
        UNSUPPORTED_PROTECTION,
        NOT_A_V2_PACKAGE,
        TOO_LARGE
    }

    data class PasswordBlob(
        val algorithm: String,
        val salt: ByteArray,
        val iterations: Int,
        val keyBytes: Int,
        val wrappedRecoverySecret: ByteArray
    )

    data class Header(
        val formatVersion: Int,
        val protection: String,
        val createdAtIso: String,
        val appVersion: String,
        val producerAppId: String,
        val producerDisplayName: String,
        val keyFingerprint: ByteArray?,   // null on the unencrypted tier
        val bodyNonce: ByteArray?,        // null on the unencrypted tier
        val wrappedDek: ByteArray?,       // null on the unencrypted tier
        val passwordBlob: PasswordBlob?,  // null when no password is set
        /** The exact serialized prefix (magic+len+json) — the body's AAD. */
        val aadBytes: ByteArray
    )

    sealed class HeaderResult {
        data class Ok(val header: Header, val bodyOffset: Long) : HeaderResult()
        data class Invalid(val error: RestoreError) : HeaderResult()
    }

    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    // ----- writing -----------------------------------------------------------

    /** Serialize a header; returns the exact prefix bytes (magic+len+json),
     *  which are both what is written to disk and the body's AAD.
     *
     *  Producer metadata (owner filename architecture, July 22 2026): the
     *  package records WHO made it — producer application ID, app version,
     *  and the display name AT CREATION TIME — separately from any filename.
     *  Filename text is never identity; recognition rests on the magic,
     *  format version, protection metadata and authenticated contents, so an
     *  app rename can never orphan older backups. The application ID
     *  identifies the producing app but is never user-facing filename text. */
    fun buildHeaderPrefix(
        protection: String,
        createdAtIso: String,
        appVersion: String,
        keyFingerprint: ByteArray?,
        bodyNonce: ByteArray?,
        wrappedDek: ByteArray?,
        passwordBlob: PasswordBlob?,
        producerAppId: String = "",
        producerDisplayName: String = ""
    ): ByteArray {
        val json = JSONObject()
        json.put("format_version", FORMAT_VERSION)
        json.put("protection", protection)
        json.put("created_at", createdAtIso)
        json.put("app_version", appVersion)
        if (producerAppId.isNotEmpty()) json.put("producer_app_id", producerAppId)
        if (producerDisplayName.isNotEmpty()) json.put("producer_display_name", producerDisplayName)
        if (keyFingerprint != null) json.put("key_fingerprint", b64e.encodeToString(keyFingerprint))
        if (bodyNonce != null) json.put("body_nonce", b64e.encodeToString(bodyNonce))
        if (wrappedDek != null) json.put("wrapped_dek", b64e.encodeToString(wrappedDek))
        if (passwordBlob != null) {
            json.put("password_blob", JSONObject()
                .put("alg", passwordBlob.algorithm)
                .put("salt", b64e.encodeToString(passwordBlob.salt))
                .put("iterations", passwordBlob.iterations)
                .put("key_bytes", passwordBlob.keyBytes)
                .put("wrapped_rs", b64e.encodeToString(passwordBlob.wrappedRecoverySecret)))
        }
        val jsonBytes = json.toString().toByteArray(Charsets.UTF_8)
        require(jsonBytes.size <= MAX_HEADER_BYTES) { "header too large" }
        val len = ByteArray(4)
        len[0] = (jsonBytes.size ushr 24).toByte()
        len[1] = (jsonBytes.size ushr 16).toByte()
        len[2] = (jsonBytes.size ushr 8).toByte()
        len[3] = jsonBytes.size.toByte()
        return MAGIC + len + jsonBytes
    }

    // ----- reading -----------------------------------------------------------

    /**
     * Read and structurally validate the header of [file]. Enforces the size
     * cap and the v1 KDF window BEFORE any cryptography runs. This performs no
     * authentication — a HeaderResult.Ok only means "well-formed"; trust comes
     * later from GCM verification (encrypted tier) or never (unencrypted tier).
     */
    fun readHeader(file: File): HeaderResult {
        if (file.length() > PackageCrypto.MAX_PACKAGE_BYTES) return HeaderResult.Invalid(RestoreError.TOO_LARGE)
        file.inputStream().use { raw ->
            val stream = DataInputStream(raw.buffered())
            val magic = ByteArray(MAGIC.size)
            try {
                stream.readFully(magic)
            } catch (_: EOFException) {
                return HeaderResult.Invalid(RestoreError.NOT_A_V2_PACKAGE)
            }
            if (!magic.contentEquals(MAGIC)) return HeaderResult.Invalid(RestoreError.NOT_A_V2_PACKAGE)

            val len = try { stream.readInt() } catch (_: EOFException) {
                return HeaderResult.Invalid(RestoreError.DAMAGED_OR_ALTERED)
            }
            if (len <= 0 || len > MAX_HEADER_BYTES) return HeaderResult.Invalid(RestoreError.DAMAGED_OR_ALTERED)

            val jsonBytes = ByteArray(len)
            try { stream.readFully(jsonBytes) } catch (_: EOFException) {
                return HeaderResult.Invalid(RestoreError.DAMAGED_OR_ALTERED)
            }

            val json = try { JSONObject(String(jsonBytes, Charsets.UTF_8)) } catch (_: Exception) {
                return HeaderResult.Invalid(RestoreError.DAMAGED_OR_ALTERED)
            }

            if (json.optInt("format_version", -1) != FORMAT_VERSION) {
                return HeaderResult.Invalid(RestoreError.NOT_A_V2_PACKAGE)
            }
            val protection = json.optString("protection", "")
            if (protection != PROTECTION_ENCRYPTED && protection != PROTECTION_NONE) {
                return HeaderResult.Invalid(RestoreError.UNSUPPORTED_PROTECTION)
            }

            var passwordBlob: PasswordBlob? = null
            val pb = json.optJSONObject("password_blob")
            if (pb != null) {
                val iterations = pb.optInt("iterations", -1)
                val alg = pb.optString("alg", "")
                val salt = decodeOrNull(pb.optString("salt", null))
                val wrappedRs = decodeOrNull(pb.optString("wrapped_rs", null))
                val keyBytes = pb.optInt("key_bytes", -1)
                // The v1 window is enforced HERE, before any KDF could run — an
                // out-of-window count is a typed UNSUPPORTED_PROTECTION, never
                // generic damage (owner ruling 2).
                if (alg != PackageCrypto.KDF_ALG ||
                    !PackageCrypto.validateKdfIterations(iterations) ||
                    salt == null || salt.size != PackageCrypto.KDF_SALT_BYTES ||
                    keyBytes != PackageCrypto.KDF_KEY_BYTES ||
                    wrappedRs == null
                ) {
                    return HeaderResult.Invalid(RestoreError.UNSUPPORTED_PROTECTION)
                }
                passwordBlob = PasswordBlob(alg, salt, iterations, keyBytes, wrappedRs)
            }

            val fingerprint = decodeOrNull(json.optString("key_fingerprint", null))
            val bodyNonce = decodeOrNull(json.optString("body_nonce", null))
            val wrappedDek = decodeOrNull(json.optString("wrapped_dek", null))

            if (protection == PROTECTION_ENCRYPTED) {
                if (fingerprint == null || fingerprint.size != PackageCrypto.FINGERPRINT_BYTES ||
                    bodyNonce == null || bodyNonce.size != PackageCrypto.NONCE_BYTES ||
                    wrappedDek == null
                ) {
                    return HeaderResult.Invalid(RestoreError.DAMAGED_OR_ALTERED)
                }
            }

            val header = Header(
                formatVersion = FORMAT_VERSION,
                protection = protection,
                createdAtIso = json.optString("created_at", ""),
                appVersion = json.optString("app_version", ""),
                producerAppId = json.optString("producer_app_id", ""),
                producerDisplayName = json.optString("producer_display_name", ""),
                keyFingerprint = fingerprint,
                bodyNonce = bodyNonce,
                wrappedDek = wrappedDek,
                passwordBlob = passwordBlob,
                aadBytes = MAGIC + intBytes(len) + jsonBytes
            )
            return HeaderResult.Ok(header, bodyOffset = (MAGIC.size + 4 + len).toLong())
        }
    }

    /** Open the body stream of [file], positioned after the header. */
    fun openBody(file: File, bodyOffset: Long): InputStream {
        val stream = file.inputStream()
        var toSkip = bodyOffset
        while (toSkip > 0) {
            val skipped = stream.skip(toSkip)
            if (skipped <= 0) break
            toSkip -= skipped
        }
        return stream
    }

    private fun intBytes(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    private fun decodeOrNull(s: String?): ByteArray? =
        if (s.isNullOrEmpty()) null else try { b64d.decode(s) } catch (_: Exception) { null }
}
