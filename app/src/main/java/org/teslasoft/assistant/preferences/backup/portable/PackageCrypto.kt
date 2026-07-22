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

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cryptographic engine of portable recovery format v2 — Option A as ruled
 * by the owner (July 22 2026): standard whole-package AES-256-GCM, one fresh
 * random DEK and one fresh 96-bit nonce per package, platform Cipher API with
 * explicit update()/doFinal() loops.
 *
 * HARD RULES (owner rulings; do not weaken):
 *  - NEVER CipherInputStream/CipherOutputStream for authenticated decryption —
 *    they can swallow tag failures and emit unauthenticated plaintext.
 *  - The complete versioned header is authenticated as associated data.
 *  - Decryption writes ONLY into app-private staging; callers must not parse,
 *    display as trusted, or apply any decrypted byte until doFinal() has
 *    succeeded (Conscrypt streams unauthenticated plaintext through update()
 *    during decryption — safe only under this discipline; desktop JVM buffers
 *    instead, which is why the JVM unit tests stay small).
 *  - Package size is capped at creation AND restore ([MAX_PACKAGE_BYTES]).
 *  - PBKDF2 parameters outside the v1 window [600_000, 2_000_000] are rejected
 *    with the typed UNSUPPORTED_PROTECTION reason (owner correction: the
 *    restore cap equals the writer ceiling — accepting more would only expand
 *    the pre-authentication denial-of-service surface). This condition is
 *    NEVER reported as generic file damage.
 *
 * Key hierarchy (architecture A-prime, owner-approved):
 *  - Recovery Secret RS (16 bytes) — the user's one secret.
 *  - Per-package DEK (32 bytes), wrapped under HKDF(RS, "psbk/v1/slot-recovery").
 *  - Optional password: KEK_p = PBKDF2-HMAC-SHA256(password, salt, iters, 32B)
 *    wraps RS ONCE into a static blob embedded per package; the KDF never runs
 *    during automatic backups.
 *  - Key fingerprint = HKDF(RS, "psbk/v1/key-id", 8 bytes); compared in
 *    constant time; a non-secret but stable identifier that LINKS packages of
 *    one key generation (stated, not hidden).
 */
object PackageCrypto {

    /** Conservative whole-package cap, enforced on create and restore. */
    const val MAX_PACKAGE_BYTES: Long = 1L shl 30 // 1 GiB

    const val KDF_ALG = "PBKDF2WithHmacSHA256"
    const val KDF_MIN_ITERATIONS = 600_000
    const val KDF_MAX_ITERATIONS = 2_000_000
    const val KDF_SALT_BYTES = 16
    const val KDF_KEY_BYTES = 32

    const val NONCE_BYTES = 12
    const val TAG_BITS = 128
    const val DEK_BYTES = 32
    const val FINGERPRINT_BYTES = 8

    private const val INFO_SLOT_RECOVERY = "psbk/v1/slot-recovery"
    private const val INFO_KEY_ID = "psbk/v1/key-id"
    private const val AAD_DEK_WRAP = "psbk/v1/dek-wrap"
    private const val AAD_RS_WRAP = "psbk/v1/rs-wrap"

    private val random = SecureRandom()

    sealed class UnwrapResult {
        data class Ok(val key: ByteArray) : UnwrapResult()

        /** Authentication failed: wrong key material or altered ciphertext. */
        object AuthFailed : UnwrapResult()
    }

    // ----- key material ------------------------------------------------------

    fun newRecoverySecret(): ByteArray = ByteArray(RecoveryCode.SECRET_BYTES).also { random.nextBytes(it) }

    fun newDek(): ByteArray = ByteArray(DEK_BYTES).also { random.nextBytes(it) }

    fun newNonce(): ByteArray = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }

    fun newKdfSalt(): ByteArray = ByteArray(KDF_SALT_BYTES).also { random.nextBytes(it) }

    /**
     * The non-secret key fingerprint: 8 bytes of HKDF-SHA256(RS, no salt,
     * info "psbk/v1/key-id"). Stored raw (base64 in the JSON header);
     * displayed, when shown at all, as 16 lowercase hex characters.
     */
    fun fingerprint(recoverySecret: ByteArray): ByteArray =
        Hkdf.derive(recoverySecret, INFO_KEY_ID, FINGERPRINT_BYTES)

    /** Constant-time fingerprint comparison (owner ruling 4). */
    fun fingerprintMatches(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    // ----- DEK wrap under the Recovery Secret --------------------------------

    /** Wrap a package DEK under HKDF(RS). Returns nonce || ciphertext+tag. */
    fun wrapDek(recoverySecret: ByteArray, dek: ByteArray): ByteArray =
        aeadSeal(Hkdf.derive(recoverySecret, INFO_SLOT_RECOVERY, 32), dek, AAD_DEK_WRAP)

    fun unwrapDek(recoverySecret: ByteArray, wrapped: ByteArray): UnwrapResult =
        aeadOpen(Hkdf.derive(recoverySecret, INFO_SLOT_RECOVERY, 32), wrapped, AAD_DEK_WRAP)

    // ----- optional password: KEK_p wraps RS (static blob) -------------------

    /** Derive the password KEK. Callers validate iteration bounds FIRST via
     *  [validateKdfIterations] — this runs the (expensive) KDF unconditionally. */
    fun derivePasswordKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KDF_KEY_BYTES * 8)
        try {
            return SecretKeyFactory.getInstance(KDF_ALG).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Pure calibration clamp (unit-tested): scale a probe run to [targetMillis]
     * and clamp to the v1 window [KDF_MIN_ITERATIONS, KDF_MAX_ITERATIONS]. The
     * clamp is what protects a slow API-28 device — a fast phone's calibration
     * can never push a slow phone's restore past the ceiling.
     */
    fun calibrateIterations(probeIterations: Int, probeMillis: Long, targetMillis: Long = 1000L): Int {
        if (probeIterations <= 0 || probeMillis <= 0) return KDF_MIN_ITERATIONS
        val scaled = probeIterations.toLong() * targetMillis / probeMillis
        return scaled.coerceIn(KDF_MIN_ITERATIONS.toLong(), KDF_MAX_ITERATIONS.toLong()).toInt()
    }

    /**
     * Android runtime calibration: time a small PBKDF2 probe on THIS device and
     * return the iteration count for ~[targetMillis]. Runs the KDF, so it must
     * be called off the main thread, and only at password set/change.
     */
    fun calibratedIterations(targetMillis: Long = 1000L): Int {
        val probe = 100_000
        val salt = newKdfSalt()
        val start = System.nanoTime()
        val kek = derivePasswordKey("calibration-probe".toCharArray(), salt, probe)
        wipe(kek)
        val ms = (System.nanoTime() - start) / 1_000_000
        return calibrateIterations(probe, ms.coerceAtLeast(1), targetMillis)
    }

    /** True when a package-supplied iteration count is inside the v1 window.
     *  Out-of-window counts mean "Unsupported or invalid backup protection
     *  settings" (owner wording) — never generic damage, and the KDF is never
     *  run for them. */
    fun validateKdfIterations(iterations: Int): Boolean =
        iterations in KDF_MIN_ITERATIONS..KDF_MAX_ITERATIONS

    /** The static password blob: RS wrapped under KEK_p. Computed at password
     *  set/change ONLY; automatic backups byte-copy it into each header. */
    fun wrapRecoverySecret(passwordKey: ByteArray, recoverySecret: ByteArray): ByteArray =
        aeadSeal(passwordKey, recoverySecret, AAD_RS_WRAP)

    fun unwrapRecoverySecret(passwordKey: ByteArray, blob: ByteArray): UnwrapResult =
        aeadOpen(passwordKey, blob, AAD_RS_WRAP)

    // ----- whole-package body encryption -------------------------------------

    /**
     * Encrypt [plainBody] into [out] with AES-256-GCM under [dek]/[nonce],
     * authenticating [headerAad] (the complete package prefix: magic + length
     * + header JSON bytes). Streams in 64 KiB chunks via explicit update().
     */
    fun encryptBody(dek: ByteArray, nonce: ByteArray, headerAad: ByteArray, plainBody: InputStream, out: OutputStream) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(headerAad)
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = plainBody.read(buf)
            if (n < 0) break
            val enc = cipher.update(buf, 0, n)
            if (enc != null && enc.isNotEmpty()) out.write(enc)
        }
        out.write(cipher.doFinal())
    }

    /**
     * Decrypt an encrypted body stream into [stagingOut]. Returns true only
     * when doFinal() verified the tag; on ANY failure returns false and the
     * caller must discard the staging file — its contents are unauthenticated
     * garbage that must never be parsed or shown.
     */
    fun decryptBodyToStaging(
        dek: ByteArray,
        nonce: ByteArray,
        headerAad: ByteArray,
        cipherBody: InputStream,
        stagingOut: OutputStream
    ): Boolean {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(headerAad)
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = cipherBody.read(buf)
                if (n < 0) break
                val plain = cipher.update(buf, 0, n)
                if (plain != null && plain.isNotEmpty()) stagingOut.write(plain)
            }
            val last = cipher.doFinal() // AEADBadTagException here on damage/wrong key
            if (last.isNotEmpty()) stagingOut.write(last)
            true
        } catch (_: AEADBadTagException) {
            false
        }
    }

    /** Best-effort zeroization; the JVM may hold copies — no stronger claim. */
    fun wipe(bytes: ByteArray) {
        bytes.fill(0)
    }

    fun withinSizeCap(file: File): Boolean = file.length() in 1..MAX_PACKAGE_BYTES

    // ----- internal AEAD helpers (small payloads: key wraps only) ------------

    private fun aeadSeal(key: ByteArray, plaintext: ByteArray, aad: String): ByteArray {
        val nonce = newNonce()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad.toByteArray(Charsets.US_ASCII))
        return nonce + cipher.doFinal(plaintext)
    }

    private fun aeadOpen(key: ByteArray, sealed: ByteArray, aad: String): UnwrapResult {
        if (sealed.size <= NONCE_BYTES) return UnwrapResult.AuthFailed
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, sealed.copyOfRange(0, NONCE_BYTES))
            )
            cipher.updateAAD(aad.toByteArray(Charsets.US_ASCII))
            UnwrapResult.Ok(cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES))
        } catch (_: AEADBadTagException) {
            UnwrapResult.AuthFailed
        }
    }
}
