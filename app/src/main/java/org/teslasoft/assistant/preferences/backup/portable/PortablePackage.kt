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

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Assembly, inspection and staged decoding of portable recovery packages
 * (format v2). Pure file-level logic — no Android dependencies — so the full
 * round trip, tamper vectors, and adversarial-input caps are provable by the
 * JVM unit suite, this project's one automated gate.
 *
 * Inner layout (identical for both tiers): a ZIP containing the artifact files
 * plus "manifest.json" — artifact names, types, per-artifact SHA-256s, the
 * format/cipher compatibility metadata (owner ruling on Section 7), and, for
 * SQLCipher artifacts, the database key hex + key-semantics marker
 * ("passphrase-bytes": the key MUST be fed through the byte[] passphrase API,
 * never as an x'hex' literal — the documented LoreBookEncryption invariant).
 * On the encrypted tier the envelope protects all of it; on the unencrypted
 * tier the same manifest hashes detect ACCIDENTAL damage only (an attacker can
 * recompute unkeyed hashes — wording must say "checked for damage", never
 * "verified") and the keys are exposed by design, which is exactly what the
 * unencrypted tier's privacy warning warns about.
 *
 * API keys are NEVER package contents on either tier (owner ruling 9).
 *
 * Restore-side hardening (owner ruling 13) — every package is adversarial
 * input: entry-name sanitization (Zip-Slip), entry-count and per-entry /
 * total uncompressed size caps, a bounded manifest read, duplicate-name
 * rejection (ZIP entries and manifest artifacts alike), manifest/type
 * validation. Nothing here APPLIES data to live stores; extraction targets
 * staging only.
 */
object PortablePackage {

    const val MANIFEST_ENTRY = "manifest.json"
    const val MAX_ENTRIES = 10_000
    const val MAX_ENTRY_BYTES: Long = 1L shl 30      // 1 GiB per entry
    const val MAX_TOTAL_BYTES: Long = 2L shl 30      // 2 GiB uncompressed total
    const val MAX_MANIFEST_BYTES = 1 shl 20          // 1 MiB — the manifest is tiny in practice

    const val KEY_SEMANTICS_PASSPHRASE = "passphrase-bytes"
    const val KEY_SEMANTICS_PLAINTEXT = "plaintext-empty"

    const val SQLCIPHER_VERSION = "4.16.0"
    const val CIPHER_COMPAT = 4

    data class Artifact(
        val entryName: String,
        val type: String,           // "sqlcipher-db" | "sqlite-db" | "chats-json"
        val file: File,
        val databaseKeyHex: String?, // sqlcipher-db only
        val keySemantics: String?,   // sqlcipher-db only
        val schemaVersion: Int?      // databases only, when known
    )

    // ----- creation ----------------------------------------------------------

    /** Build the inner ZIP (artifacts + manifest) into [innerZip]. */
    fun buildInnerZip(artifacts: List<Artifact>, createdAtIso: String, innerZip: File) {
        require(artifacts.isNotEmpty()) { "no artifacts" }
        val manifest = JSONObject()
        manifest.put("created_at", createdAtIso)
        manifest.put("sqlcipher_version", SQLCIPHER_VERSION)
        manifest.put("cipher_compat", CIPHER_COMPAT)
        val list = JSONArray()
        ZipOutputStream(innerZip.outputStream().buffered()).use { zip ->
            for (a in artifacts) {
                requireSafeEntryName(a.entryName)
                zip.putNextEntry(ZipEntry(a.entryName))
                val digest = MessageDigest.getInstance("SHA-256")
                a.file.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        zip.write(buf, 0, n)
                        digest.update(buf, 0, n)
                    }
                }
                zip.closeEntry()
                val entry = JSONObject()
                entry.put("name", a.entryName)
                entry.put("type", a.type)
                entry.put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
                if (a.databaseKeyHex != null) entry.put("db_key_hex", a.databaseKeyHex)
                if (a.keySemantics != null) entry.put("key_semantics", a.keySemantics)
                if (a.schemaVersion != null) entry.put("schema_version", a.schemaVersion)
                list.put(entry)
            }
            manifest.put("artifacts", list)
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    /**
     * Envelope the inner ZIP into the final package file.
     * Encrypted tier: fresh DEK + nonce, DEK wrapped under the Recovery
     * Secret, optional pre-computed password blob byte-copied into the header
     * (the KDF never runs here — architecture A-prime).
     * Unencrypted tier: header + inner ZIP verbatim.
     * Enforces the whole-package size cap at creation (owner ruling 1).
     */
    fun envelope(
        innerZip: File,
        out: File,
        createdAtIso: String,
        appVersion: String,
        recoverySecret: ByteArray?,               // null = unencrypted tier
        passwordBlob: PortablePackageFormat.PasswordBlob?
    ) {
        if (recoverySecret == null) {
            val prefix = PortablePackageFormat.buildHeaderPrefix(
                PortablePackageFormat.PROTECTION_NONE, createdAtIso, appVersion,
                keyFingerprint = null, bodyNonce = null, wrappedDek = null, passwordBlob = null
            )
            out.outputStream().buffered().use { o ->
                o.write(prefix)
                innerZip.inputStream().use { it.copyTo(o) }
            }
        } else {
            val dek = PackageCrypto.newDek()
            val nonce = PackageCrypto.newNonce()
            try {
                val prefix = PortablePackageFormat.buildHeaderPrefix(
                    PortablePackageFormat.PROTECTION_ENCRYPTED, createdAtIso, appVersion,
                    keyFingerprint = PackageCrypto.fingerprint(recoverySecret),
                    bodyNonce = nonce,
                    wrappedDek = PackageCrypto.wrapDek(recoverySecret, dek),
                    passwordBlob = passwordBlob
                )
                out.outputStream().buffered().use { o ->
                    o.write(prefix)
                    innerZip.inputStream().use { input ->
                        PackageCrypto.encryptBody(dek, nonce, prefix, input, o)
                    }
                }
            } finally {
                PackageCrypto.wipe(dek)
            }
        }
        if (!PackageCrypto.withinSizeCap(out)) {
            out.delete()
            throw IllegalStateException("package exceeds the size cap")
        }
    }

    // ----- inspection --------------------------------------------------------

    data class Inspection(
        val protection: String,
        val createdAtIso: String,
        val appVersion: String,
        val keyFingerprint: ByteArray?,
        val hasPasswordSlot: Boolean
    )

    sealed class InspectResult {
        data class Ok(val inspection: Inspection) : InspectResult()
        data class Invalid(val error: PortablePackageFormat.RestoreError) : InspectResult()
    }

    /** Header-only inspection for the protection-level display. Performs NO
     *  authentication; the display must present these as claims, with trust
     *  arriving only after decryption verifies (or never, unencrypted tier). */
    fun inspect(file: File): InspectResult =
        when (val h = PortablePackageFormat.readHeader(file)) {
            is PortablePackageFormat.HeaderResult.Invalid -> InspectResult.Invalid(h.error)
            is PortablePackageFormat.HeaderResult.Ok -> InspectResult.Ok(
                Inspection(
                    protection = h.header.protection,
                    createdAtIso = h.header.createdAtIso,
                    appVersion = h.header.appVersion,
                    keyFingerprint = h.header.keyFingerprint,
                    hasPasswordSlot = h.header.passwordBlob != null
                )
            )
        }

    // ----- staged decoding ---------------------------------------------------

    sealed class DecodeResult {
        /** [innerZip] is the decoded (decrypted or verbatim) inner ZIP in
         *  staging; [authenticated] is true ONLY for the encrypted tier after
         *  doFinal() succeeded — the unencrypted tier is never authenticated. */
        data class Ok(val innerZip: File, val authenticated: Boolean) : DecodeResult()
        data class Failed(val error: PortablePackageFormat.RestoreError) : DecodeResult()
    }

    /**
     * Decode [file] into [stagingDir] using the Recovery Secret. The caller
     * owns [stagingDir] lifecycle (per-run directory; deleted in finally on
     * every path; swept at startup — owner ruling 14).
     */
    fun decodeWithSecret(file: File, recoverySecret: ByteArray, stagingDir: File): DecodeResult {
        val h = PortablePackageFormat.readHeader(file)
        if (h is PortablePackageFormat.HeaderResult.Invalid) return DecodeResult.Failed(h.error)
        val header = (h as PortablePackageFormat.HeaderResult.Ok).header

        if (header.protection == PortablePackageFormat.PROTECTION_NONE) {
            // Unencrypted tier: copy body verbatim; no authentication exists.
            val innerZip = File(stagingDir, "inner.zip")
            PortablePackageFormat.openBody(file, h.bodyOffset).use { body ->
                innerZip.outputStream().buffered().use { body.copyTo(it) }
            }
            return DecodeResult.Ok(innerZip, authenticated = false)
        }

        // Fingerprint gate: constant-time; a mismatch is "different Recovery
        // Key OR damaged header" — one state, per owner ruling 4.
        val fp = header.keyFingerprint ?: return DecodeResult.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)
        if (!PackageCrypto.fingerprintMatches(fp, PackageCrypto.fingerprint(recoverySecret))) {
            return DecodeResult.Failed(PortablePackageFormat.RestoreError.WRONG_KEY_OR_HEADER)
        }

        val dek = when (val u = PackageCrypto.unwrapDek(recoverySecret, header.wrappedDek!!)) {
            is PackageCrypto.UnwrapResult.AuthFailed ->
                // Fingerprint matched but the wrap fails: damaged or altered.
                return DecodeResult.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)
            is PackageCrypto.UnwrapResult.Ok -> u.key
        }
        try {
            val innerZip = File(stagingDir, "inner.zip")
            val ok = PortablePackageFormat.openBody(file, h.bodyOffset).use { body ->
                innerZip.outputStream().buffered().use { staged ->
                    PackageCrypto.decryptBodyToStaging(dek, header.bodyNonce!!, header.aadBytes, body, staged)
                }
            }
            if (!ok) {
                innerZip.delete() // unauthenticated garbage — never parsed
                return DecodeResult.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)
            }
            return DecodeResult.Ok(innerZip, authenticated = true)
        } finally {
            PackageCrypto.wipe(dek)
        }
    }

    /** Password route: derive KEK_p (bounds already enforced by the header
     *  parser), unwrap the Recovery Secret from the blob, then proceed as the
     *  secret route. Under A-prime a password recovers RS ITSELF. */
    fun decodeWithPassword(file: File, password: CharArray, stagingDir: File): DecodeResult {
        val h = PortablePackageFormat.readHeader(file)
        if (h is PortablePackageFormat.HeaderResult.Invalid) return DecodeResult.Failed(h.error)
        val header = (h as PortablePackageFormat.HeaderResult.Ok).header
        if (header.protection == PortablePackageFormat.PROTECTION_NONE) {
            return decodeWithSecret(file, ByteArray(0), stagingDir) // no secret needed
        }
        val blob = header.passwordBlob
            ?: return DecodeResult.Failed(PortablePackageFormat.RestoreError.WRONG_KEY_OR_HEADER)
        val kek = PackageCrypto.derivePasswordKey(password, blob.salt, blob.iterations)
        try {
            return when (val u = PackageCrypto.unwrapRecoverySecret(kek, blob.wrappedRecoverySecret)) {
                is PackageCrypto.UnwrapResult.AuthFailed ->
                    DecodeResult.Failed(PortablePackageFormat.RestoreError.WRONG_KEY_OR_HEADER)
                is PackageCrypto.UnwrapResult.Ok -> try {
                    decodeWithSecret(file, u.key, stagingDir)
                } finally {
                    PackageCrypto.wipe(u.key)
                }
            }
        } finally {
            PackageCrypto.wipe(kek)
        }
    }

    // ----- staged validation -------------------------------------------------

    data class ValidatedArtifact(
        val entryName: String,
        val type: String,
        val stagedFile: File,
        val databaseKeyHex: String?,
        val keySemantics: String?
    )

    sealed class ValidateResult {
        data class Ok(val artifacts: List<ValidatedArtifact>) : ValidateResult()
        data class Failed(val error: PortablePackageFormat.RestoreError) : ValidateResult()
    }

    /**
     * Extract + validate a decoded inner ZIP into [stagingDir] under the
     * adversarial-input caps: entry count, per-entry and total uncompressed
     * size, a bounded manifest read ([MAX_MANIFEST_BYTES], counted toward the
     * total), Zip-Slip-safe names, DUPLICATE rejection (duplicate ZIP entry
     * names — including a second manifest.json — and duplicate artifact names
     * inside the manifest), manifest present, every manifest artifact present
     * with a matching SHA-256, no unexpected entries.
     *
     * Everything is resolved through ONE enumeration of the central directory:
     * ZipFile.getEntry's selection among duplicate names is unspecified, so
     * with duplicates present, validation and extraction could disagree about
     * which bytes they saw (owner correction, July 22 2026). Duplicates are
     * therefore rejected outright and entries are read via the enumerated
     * ZipEntry objects, never by a second name lookup.
     */
    fun validateAndExtract(innerZip: File, stagingDir: File): ValidateResult {
        try {
            ZipFile(innerZip).use { zip ->
                if (zip.size() > MAX_ENTRIES) return ValidateResult.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)

                // Single enumeration; ANY duplicate entry name is a rejection.
                val byName = LinkedHashMap<String, ZipEntry>()
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    val e = enumeration.nextElement()
                    if (byName.put(e.name, e) != null) {
                        return ValidateResult.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)
                    }
                }

                val manifestEntry = byName[MANIFEST_ENTRY]
                    ?: return ValidateResult.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)

                var total = 0L
                val manifestBytes = readBounded(zip, manifestEntry, MAX_MANIFEST_BYTES)
                    ?: return ValidateResult.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)
                total += manifestBytes.size

                val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
                val list = manifest.optJSONArray("artifacts")
                    ?: return ValidateResult.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)

                val expected = HashMap<String, JSONObject>()
                for (i in 0 until list.length()) {
                    val a = list.getJSONObject(i)
                    val name = a.optString("name", "")
                    if (!isSafeEntryName(name) || name == MANIFEST_ENTRY) {
                        return ValidateResult.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)
                    }
                    // Duplicate artifact names inside the manifest are rejected.
                    if (expected.put(name, a) != null) {
                        return ValidateResult.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)
                    }
                }

                // Exact set match: no unexpected entries, nothing missing.
                for (name in byName.keys) {
                    if (name != MANIFEST_ENTRY && name !in expected) {
                        return ValidateResult.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)
                    }
                }

                val out = ArrayList<ValidatedArtifact>(expected.size)
                for ((name, meta) in expected) {
                    val entry = byName[name]
                        ?: return ValidateResult.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)
                    val staged = File(stagingDir, "artifact_" + name.replace('/', '_'))
                    val digest = MessageDigest.getInstance("SHA-256")
                    var entryBytes = 0L
                    zip.getInputStream(entry).use { input ->
                        staged.outputStream().buffered().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                entryBytes += n
                                total += n
                                if (entryBytes > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                                    return ValidateResult.Failed(PortablePackageFormat.RestoreError.TOO_LARGE)
                                }
                                o.write(buf, 0, n)
                                digest.update(buf, 0, n)
                            }
                        }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (hash != meta.optString("sha256", "")) {
                        return ValidateResult.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)
                    }
                    out.add(
                        ValidatedArtifact(
                            entryName = name,
                            type = meta.optString("type", ""),
                            stagedFile = staged,
                            databaseKeyHex = meta.optString("db_key_hex", "").ifEmpty { null },
                            keySemantics = meta.optString("key_semantics", "").ifEmpty { null }
                        )
                    )
                }
                return ValidateResult.Ok(out)
            }
        } catch (_: Exception) {
            return ValidateResult.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)
        }
    }

    /** Bounded whole-entry read: null when the entry exceeds [cap]. */
    private fun readBounded(zip: ZipFile, entry: ZipEntry, cap: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        zip.getInputStream(entry).use { input ->
            val buf = ByteArray(8192)
            var count = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                count += n
                if (count > cap) return null
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    // ----- entry-name safety -------------------------------------------------

    fun isSafeEntryName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 200 &&
            !name.contains("..") && !name.startsWith('/') && !name.contains('\\') &&
            !name.contains(' ') && name.none { it.isISOControl() }

    private fun requireSafeEntryName(name: String) {
        require(isSafeEntryName(name)) { "unsafe entry name" }
    }
}
