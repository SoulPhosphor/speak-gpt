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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The Option-A engine: wraps, fingerprints, KDF bounds, and the streamed
 *  whole-package GCM body with its tamper/truncation behavior. */
class PackageCryptoTest {

    @Test
    fun dekWrapRoundTrips() {
        val rs = PackageCrypto.newRecoverySecret()
        val dek = PackageCrypto.newDek()
        val unwrapped = PackageCrypto.unwrapDek(rs, PackageCrypto.wrapDek(rs, dek))
        assertTrue(unwrapped is PackageCrypto.UnwrapResult.Ok)
        assertArrayEquals(dek, (unwrapped as PackageCrypto.UnwrapResult.Ok).key)
    }

    @Test
    fun dekUnwrapWithWrongSecretFailsAuthentication() {
        val dek = PackageCrypto.newDek()
        val wrapped = PackageCrypto.wrapDek(PackageCrypto.newRecoverySecret(), dek)
        assertTrue(
            PackageCrypto.unwrapDek(PackageCrypto.newRecoverySecret(), wrapped)
                is PackageCrypto.UnwrapResult.AuthFailed
        )
    }

    @Test
    fun fingerprintIsStablePerSecretAndDistinctAcrossSecrets() {
        val a = PackageCrypto.newRecoverySecret()
        val b = PackageCrypto.newRecoverySecret()
        assertTrue(PackageCrypto.fingerprintMatches(PackageCrypto.fingerprint(a), PackageCrypto.fingerprint(a)))
        assertFalse(PackageCrypto.fingerprintMatches(PackageCrypto.fingerprint(a), PackageCrypto.fingerprint(b)))
    }

    @Test
    fun kdfWindowIsExactlyTheV1Bounds() {
        // Owner correction: the restore cap equals the writer ceiling (2M).
        assertFalse(PackageCrypto.validateKdfIterations(599_999))
        assertTrue(PackageCrypto.validateKdfIterations(600_000))
        assertTrue(PackageCrypto.validateKdfIterations(2_000_000))
        assertFalse(PackageCrypto.validateKdfIterations(2_000_001))
        assertFalse(PackageCrypto.validateKdfIterations(10_000_000))
    }

    @Test
    fun passwordBlobRoundTripsAndWrongPasswordFails() {
        val rs = PackageCrypto.newRecoverySecret()
        val salt = PackageCrypto.newKdfSalt()
        // Tiny legal-shaped iteration count would be rejected by the header
        // parser; the KDF itself accepts any count — use the floor but with a
        // short-circuit: floor iterations are slow-ish (~0.2 s) yet fine once.
        val kek = PackageCrypto.derivePasswordKey("correct horse".toCharArray(), salt, PackageCrypto.KDF_MIN_ITERATIONS)
        val blob = PackageCrypto.wrapRecoverySecret(kek, rs)
        val ok = PackageCrypto.unwrapRecoverySecret(kek, blob)
        assertTrue(ok is PackageCrypto.UnwrapResult.Ok)
        assertArrayEquals(rs, (ok as PackageCrypto.UnwrapResult.Ok).key)

        val wrongKek = PackageCrypto.derivePasswordKey("wrong horse".toCharArray(), salt, PackageCrypto.KDF_MIN_ITERATIONS)
        assertTrue(PackageCrypto.unwrapRecoverySecret(wrongKek, blob) is PackageCrypto.UnwrapResult.AuthFailed)
    }

    // ----- streamed body ------------------------------------------------------

    private fun encrypt(dek: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        PackageCrypto.encryptBody(dek, nonce, aad, ByteArrayInputStream(plain), out)
        return out.toByteArray()
    }

    private fun decrypt(dek: ByteArray, nonce: ByteArray, aad: ByteArray, cipher: ByteArray): Pair<Boolean, ByteArray> {
        val out = ByteArrayOutputStream()
        val ok = PackageCrypto.decryptBodyToStaging(dek, nonce, aad, ByteArrayInputStream(cipher), out)
        return ok to out.toByteArray()
    }

    @Test
    fun bodyRoundTripsAcrossChunkBoundaries() {
        val dek = PackageCrypto.newDek()
        val nonce = PackageCrypto.newNonce()
        val aad = "header-bytes".toByteArray()
        val plain = ByteArray(200_000) { (it % 251).toByte() } // > one 64 KiB chunk
        val cipher = encrypt(dek, nonce, aad, plain)
        val (ok, decrypted) = decrypt(dek, nonce, aad, cipher)
        assertTrue(ok)
        assertArrayEquals(plain, decrypted)
    }

    @Test
    fun flippedCiphertextByteFailsAuthentication() {
        val dek = PackageCrypto.newDek()
        val nonce = PackageCrypto.newNonce()
        val aad = "header".toByteArray()
        val cipher = encrypt(dek, nonce, aad, ByteArray(50_000) { 7 })
        cipher[1234] = (cipher[1234].toInt() xor 1).toByte()
        assertFalse(decrypt(dek, nonce, aad, cipher).first)
    }

    @Test
    fun alteredAadFailsAuthentication() {
        val dek = PackageCrypto.newDek()
        val nonce = PackageCrypto.newNonce()
        val cipher = encrypt(dek, nonce, "header-A".toByteArray(), ByteArray(1000) { 1 })
        assertFalse(decrypt(dek, nonce, "header-B".toByteArray(), cipher).first)
    }

    @Test
    fun truncationFailsAuthentication() {
        val dek = PackageCrypto.newDek()
        val nonce = PackageCrypto.newNonce()
        val aad = "header".toByteArray()
        val cipher = encrypt(dek, nonce, aad, ByteArray(10_000) { 3 })
        assertFalse(decrypt(dek, nonce, aad, cipher.copyOfRange(0, cipher.size - 5)).first)
    }

    @Test
    fun wrongDekFailsAuthentication() {
        val nonce = PackageCrypto.newNonce()
        val aad = "header".toByteArray()
        val cipher = encrypt(PackageCrypto.newDek(), nonce, aad, ByteArray(1000) { 9 })
        assertFalse(decrypt(PackageCrypto.newDek(), nonce, aad, cipher).first)
    }

    @Test
    fun freshRandomMaterialIsUnique() {
        // One nonce per fresh DEK is GCM's ideal case; this pins that the
        // generators do not repeat trivially.
        assertFalse(PackageCrypto.newNonce().contentEquals(PackageCrypto.newNonce()))
        assertFalse(PackageCrypto.newDek().contentEquals(PackageCrypto.newDek()))
        assertFalse(PackageCrypto.newRecoverySecret().contentEquals(PackageCrypto.newRecoverySecret()))
    }
}
