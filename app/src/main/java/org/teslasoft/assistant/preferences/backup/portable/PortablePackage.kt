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
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupValidator
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
    const val MANIFEST_VERSION = 3
    const val MAX_ENTRIES = 10_000
    const val MAX_ENTRY_BYTES: Long = 1L shl 30      // 1 GiB per entry
    const val MAX_TOTAL_BYTES: Long = 2L shl 30      // 2 GiB uncompressed total
    const val MAX_MANIFEST_BYTES = 1 shl 20          // 1 MiB — the manifest is tiny in practice

    const val KEY_SEMANTICS_PASSPHRASE = "passphrase-bytes"
    const val KEY_SEMANTICS_PLAINTEXT = "plaintext-empty"

    const val SQLCIPHER_VERSION = "4.17.0"
    const val CIPHER_COMPAT = 4

    const val TYPE_SQLCIPHER_DB = "sqlcipher-db"
    const val TYPE_SQLITE_DB = "sqlite-db"
    const val TYPE_CHATS_JSON = "chats-json"
    const val TYPE_COMPANION_ROLEPLAY_ARCHIVE = "companion-roleplay-archive"
    const val TYPE_MODEL_ENDPOINT_SETTINGS = "model-endpoint-settings"
    const val TYPE_GENERATED_IMAGES_CATALOG = "generated-images-catalog"
    const val TYPE_GENERATED_IMAGE_ASSET = "generated-image-asset"
    const val TYPE_PROFILE_IMAGE_ASSET = "profile-image-asset"

    data class Artifact(
        val entryName: String,
        val type: String,
        val file: File,
        val databaseKeyHex: String?, // sqlcipher-db only
        val keySemantics: String?,   // sqlcipher-db only
        val schemaVersion: Int?      // database or logical artifact format, when known
    )

    enum class CategoryRepresentation(val value: String) {
        ARTIFACTS("artifacts"),
        EMPTY("empty")
    }

    data class CategoryDeclaration(
        val category: PortableRestoreCategory,
        val representation: CategoryRepresentation,
        val artifactNames: Set<String>,
        val recordCount: Long
    )

    // ----- creation ----------------------------------------------------------

    /** Build the inner ZIP (artifacts + manifest) into [innerZip].
     * [restoreCategories] is null only for compatibility fixtures and older
     * callers. A current Recovery Backup supplies the complete logical
     * category inventory, including categories that intentionally contain no
     * records and therefore need no artifact. */
    fun buildInnerZip(
        artifacts: List<Artifact>,
        createdAtIso: String,
        innerZip: File,
        restoreCategories: Set<PortableRestoreCategory>? = null,
        categoryDeclarations: List<CategoryDeclaration>? = null
    ) {
        require(artifacts.isNotEmpty()) { "no artifacts" }
        require(restoreCategories == null || categoryDeclarations == null) {
            "use either v2 categories or current category declarations"
        }
        val manifest = JSONObject()
        manifest.put("created_at", createdAtIso)
        manifest.put("sqlcipher_version", SQLCIPHER_VERSION)
        manifest.put("cipher_compat", CIPHER_COMPAT)
        if (categoryDeclarations != null) {
            require(categoryDeclarations.map { it.category }.toSet() == PortableRestoreCategory.entries.toSet()) {
                "current manifest must declare every category exactly once"
            }
            require(categoryDeclarations.size == PortableRestoreCategory.entries.size) {
                "duplicate category declaration"
            }
            manifest.put("manifest_version", MANIFEST_VERSION)
            manifest.put("limits_policy_version", PortableRecoveryLimits.POLICY_VERSION)
            manifest.put("categories", JSONArray().apply {
                categoryDeclarations.sortedBy { it.category.ordinal }.forEach { declaration ->
                    require(declaration.recordCount >= 0L) { "negative category record count" }
                    require(
                        declaration.representation != CategoryRepresentation.EMPTY ||
                            (declaration.artifactNames.isEmpty() && declaration.recordCount == 0L)
                    ) { "empty category has artifacts or records" }
                    put(JSONObject()
                        .put("category", declaration.category.key)
                        .put("representation", declaration.representation.value)
                        .put("artifacts", JSONArray().apply {
                            declaration.artifactNames.sorted().forEach(::put)
                        })
                        .put("record_count", declaration.recordCount))
                }
            })
        } else if (restoreCategories != null) {
            manifest.put("restore_categories", JSONArray().apply {
                restoreCategories.sortedBy { it.ordinal }.forEach { put(it.key) }
            })
        }
        val list = JSONArray()
        ZipOutputStream(innerZip.outputStream().buffered()).use { zip ->
            for (a in artifacts) {
                requireSafeEntryName(a.entryName)
                require(isSupportedArtifact(a.entryName, a.type)) { "unsupported artifact" }
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
                if (categoryDeclarations != null) entry.put("decoded_bytes", a.file.length())
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
        passwordBlob: PortablePackageFormat.PasswordBlob?,
        producerAppId: String = "",
        producerDisplayName: String = ""
    ) {
        if (recoverySecret == null) {
            val prefix = PortablePackageFormat.buildHeaderPrefix(
                PortablePackageFormat.PROTECTION_NONE, createdAtIso, appVersion,
                keyFingerprint = null, bodyNonce = null, wrappedDek = null, passwordBlob = null,
                producerAppId = producerAppId, producerDisplayName = producerDisplayName
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
                    passwordBlob = passwordBlob,
                    producerAppId = producerAppId, producerDisplayName = producerDisplayName
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
        val producerAppId: String,
        val producerDisplayName: String,
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
                    producerAppId = h.header.producerAppId,
                    producerDisplayName = h.header.producerDisplayName,
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
        val keySemantics: String?,
        val schemaVersion: Int?
    )

    sealed class ValidateResult {
        data class Ok(
            val artifacts: List<ValidatedArtifact>,
            /** Null identifies a compatible older package whose inventory
             * must be inferred from its artifact layout. */
            val declaredCategories: Set<PortableRestoreCategory>? = null,
            val explicitlyEmptyCategories: Set<PortableRestoreCategory> = emptySet(),
            val categoryRecordCounts: Map<PortableRestoreCategory, Long> = emptyMap(),
            val manifestVersion: Int = 2
        ) : ValidateResult()
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
                    val type = a.optString("type", "")
                    if (!isSafeEntryName(name) || name == MANIFEST_ENTRY) {
                        return ValidateResult.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)
                    }
                    if (!isSupportedArtifact(name, type)) {
                        return ValidateResult.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)
                    }
                    if (a.has("decoded_bytes") && a.opt("decoded_bytes") !is Number) {
                        return ValidateResult.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)
                    }
                    val declaredBytes = if (a.has("decoded_bytes")) a.optLong("decoded_bytes", -1L) else null
                    if (declaredBytes != null &&
                        !PortableRecoveryLimits.accepts(name, type, declaredBytes)
                    ) {
                        return ValidateResult.Failed(PortablePackageFormat.RestoreError.TOO_LARGE)
                    }
                    // Duplicate artifact names inside the manifest are rejected.
                    if (expected.put(name, a) != null) {
                        return ValidateResult.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)
                    }
                }

                val inventory = when (val parsed = parseManifestInventory(manifest, expected)) {
                    is ManifestInventoryResult.Ok -> parsed.inventory
                    ManifestInventoryResult.Invalid -> return ValidateResult.Failed(
                        PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED
                    )
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
                    val type = meta.optString("type", "")
                    val categoryLimit = PortableRecoveryLimits.maxDecodedBytes(name, type)
                        ?: return ValidateResult.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)
                    if (entry.size >= 0L && entry.size > categoryLimit) {
                        return ValidateResult.Failed(PortablePackageFormat.RestoreError.TOO_LARGE)
                    }
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
                                if (entryBytes > categoryLimit || entryBytes > MAX_ENTRY_BYTES ||
                                    total > MAX_TOTAL_BYTES
                                ) {
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
                    if (meta.has("decoded_bytes") && meta.optLong("decoded_bytes", -1L) != entryBytes) {
                        return ValidateResult.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)
                    }
                    if (
                        meta.optString("type", "") == TYPE_COMPANION_ROLEPLAY_ARCHIVE &&
                        CompanionBackupValidator.validate(staged) !is CompanionBackupValidator.Verdict.Valid
                    ) {
                        return ValidateResult.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)
                    }
                    if (meta.optString("type", "") == TYPE_MODEL_ENDPOINT_SETTINGS) {
                        if (staged.length() > ModelEndpointPortableCodec.MAX_ARTIFACT_BYTES ||
                            ModelEndpointPortableCodec.parse(staged.readText(Charsets.UTF_8))
                                !is ModelEndpointPortableCodec.Result.Ok
                        ) {
                            return ValidateResult.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)
                        }
                    }
                    out.add(
                        ValidatedArtifact(
                            entryName = name,
                            type = meta.optString("type", ""),
                            stagedFile = staged,
                            databaseKeyHex = meta.optString("db_key_hex", "").ifEmpty { null },
                            keySemantics = meta.optString("key_semantics", "").ifEmpty { null },
                            schemaVersion = if (meta.has("schema_version")) {
                                meta.optInt("schema_version", -1).takeIf { it >= 0 }
                            } else null
                        )
                    )
                }
                return ValidateResult.Ok(
                    out,
                    inventory.declaredCategories,
                    inventory.explicitlyEmptyCategories,
                    inventory.recordCounts,
                    inventory.manifestVersion
                )
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

    private data class ManifestInventory(
        val declaredCategories: Set<PortableRestoreCategory>?,
        val explicitlyEmptyCategories: Set<PortableRestoreCategory>,
        val recordCounts: Map<PortableRestoreCategory, Long>,
        val manifestVersion: Int
    )

    private sealed interface ManifestInventoryResult {
        data class Ok(val inventory: ManifestInventory) : ManifestInventoryResult
        data object Invalid : ManifestInventoryResult
    }

    /** Current manifests are exact. V2 is accepted conservatively: a declared
     * category must have its recognized carrier artifact, so a missing v2
     * artifact is never invented as an empty category. */
    private fun parseManifestInventory(
        manifest: JSONObject,
        artifacts: Map<String, JSONObject>
    ): ManifestInventoryResult {
        val version = if (manifest.has("manifest_version")) {
            manifest.optInt("manifest_version", -1)
        } else 2
        if (version == MANIFEST_VERSION) return parseCurrentInventory(manifest, artifacts)
        if (version != 2 || manifest.has("categories")) return ManifestInventoryResult.Invalid

        val declared = if (manifest.has("restore_categories")) {
            parseV2RestoreCategories(manifest) ?: return ManifestInventoryResult.Invalid
        } else null
        if (declared != null) {
            val represented = PortableRestoreCategory.entries.filterTo(LinkedHashSet()) {
                artifactNamesFor(it, artifacts).isNotEmpty()
            }
            if (declared != represented) return ManifestInventoryResult.Invalid
        }
        return ManifestInventoryResult.Ok(
            ManifestInventory(declared, emptySet(), emptyMap(), 2)
        )
    }

    private fun parseCurrentInventory(
        manifest: JSONObject,
        artifacts: Map<String, JSONObject>
    ): ManifestInventoryResult {
        if (manifest.optInt("limits_policy_version", -1) != PortableRecoveryLimits.POLICY_VERSION ||
            manifest.has("restore_categories")
        ) return ManifestInventoryResult.Invalid
        val array = manifest.optJSONArray("categories") ?: return ManifestInventoryResult.Invalid
        val byKey = PortableRestoreCategory.entries.associateBy(PortableRestoreCategory::key)
        val declared = LinkedHashSet<PortableRestoreCategory>()
        val empty = LinkedHashSet<PortableRestoreCategory>()
        val counts = LinkedHashMap<PortableRestoreCategory, Long>()
        val claimedArtifacts = LinkedHashSet<String>()
        repeat(array.length()) { index ->
            val item = array.optJSONObject(index) ?: return ManifestInventoryResult.Invalid
            if (item.keys().asSequence().toSet() != CURRENT_CATEGORY_KEYS) {
                return ManifestInventoryResult.Invalid
            }
            val category = byKey[item.optString("category", "")]
                ?: return ManifestInventoryResult.Invalid
            if (!declared.add(category)) return ManifestInventoryResult.Invalid
            val representation = when (item.optString("representation", "")) {
                CategoryRepresentation.ARTIFACTS.value -> CategoryRepresentation.ARTIFACTS
                CategoryRepresentation.EMPTY.value -> CategoryRepresentation.EMPTY
                else -> return ManifestInventoryResult.Invalid
            }
            val namesJson = item.optJSONArray("artifacts") ?: return ManifestInventoryResult.Invalid
            val names = LinkedHashSet<String>()
            repeat(namesJson.length()) { nameIndex ->
                val name = namesJson.optString(nameIndex, "")
                if (name.isBlank() || !names.add(name)) return ManifestInventoryResult.Invalid
            }
            if (item.opt("record_count") !is Number) return ManifestInventoryResult.Invalid
            val count = item.optLong("record_count", -1L)
            if (count < 0L) return ManifestInventoryResult.Invalid
            val actualNames = artifactNamesFor(category, artifacts)
            when (representation) {
                CategoryRepresentation.ARTIFACTS -> {
                    if (names.isEmpty() || names != actualNames) return ManifestInventoryResult.Invalid
                    claimedArtifacts.addAll(names)
                }
                CategoryRepresentation.EMPTY -> {
                    if (names.isNotEmpty() || count != 0L || actualNames.isNotEmpty()) {
                        return ManifestInventoryResult.Invalid
                    }
                    empty.add(category)
                }
            }
            counts[category] = count
        }
        if (declared != PortableRestoreCategory.entries.toSet() || claimedArtifacts != artifacts.keys) {
            return ManifestInventoryResult.Invalid
        }
        return ManifestInventoryResult.Ok(
            ManifestInventory(declared, empty, counts, MANIFEST_VERSION)
        )
    }

    private fun parseV2RestoreCategories(manifest: JSONObject): Set<PortableRestoreCategory>? {
        val array = manifest.optJSONArray("restore_categories") ?: return null
        val byKey = PortableRestoreCategory.entries.associateBy(PortableRestoreCategory::key)
        val categories = LinkedHashSet<PortableRestoreCategory>()
        repeat(array.length()) { index ->
            val category = byKey[array.optString(index, "")] ?: return null
            if (!categories.add(category)) return null
        }
        return categories
    }

    private fun artifactNamesFor(
        category: PortableRestoreCategory,
        artifacts: Map<String, JSONObject>
    ): Set<String> = artifacts.filterValues { item ->
        val name = item.optString("name", "")
        when (category) {
            PortableRestoreCategory.CHATS -> item.optString("type") == TYPE_CHATS_JSON
            PortableRestoreCategory.GENERATED_IMAGES -> item.optString("type") in setOf(
                TYPE_GENERATED_IMAGES_CATALOG, TYPE_GENERATED_IMAGE_ASSET
            )
            PortableRestoreCategory.COMPANIONS,
            PortableRestoreCategory.GLAMOURS,
            PortableRestoreCategory.ROLEPLAY,
            PortableRestoreCategory.ACTIVATION_PROMPTS,
            PortableRestoreCategory.SYSTEM_PROMPTS ->
                item.optString("type") == TYPE_COMPANION_ROLEPLAY_ARCHIVE
            PortableRestoreCategory.PROFILE_IMAGES -> item.optString("type") in setOf(
                TYPE_SQLITE_DB, TYPE_PROFILE_IMAGE_ASSET
            ) && (item.optString("type") != TYPE_SQLITE_DB || name == "user_images.db")
            PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS ->
                item.optString("type") == TYPE_MODEL_ENDPOINT_SETTINGS
            PortableRestoreCategory.MODEL_RULES,
            PortableRestoreCategory.MEMORIES -> name == "memory.db" &&
                item.optString("type") == TYPE_SQLCIPHER_DB
            PortableRestoreCategory.LOREBOOKS -> name == "lorebook.db" &&
                item.optString("type") == TYPE_SQLCIPHER_DB
        }
    }.keys

    private val CURRENT_CATEGORY_KEYS = setOf(
        "category", "representation", "artifacts", "record_count"
    )

    // ----- entry-name safety -------------------------------------------------

    fun isSafeEntryName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 200 &&
            !name.contains("..") && !name.startsWith('/') && !name.contains('\\') &&
            !name.contains(' ') && name.none { it.isISOControl() }

    private fun requireSafeEntryName(name: String) {
        require(isSafeEntryName(name)) { "unsafe entry name" }
    }

    private fun isSupportedArtifact(name: String, type: String): Boolean = when (type) {
        TYPE_SQLCIPHER_DB -> name == "memory.db" || name == "lorebook.db"
        TYPE_SQLITE_DB -> name == "user_images.db"
        TYPE_CHATS_JSON -> name == "chats.json"
        TYPE_COMPANION_ROLEPLAY_ARCHIVE -> name == "companion_roleplay.zip"
        TYPE_MODEL_ENDPOINT_SETTINGS -> name == "model_endpoint_settings.json"
        TYPE_GENERATED_IMAGES_CATALOG -> name == "generated_images/catalog.json"
        TYPE_GENERATED_IMAGE_ASSET -> {
            val fileName = name.removePrefix("generated_images/assets/")
            name.startsWith("generated_images/assets/") &&
                fileName.isNotBlank() && !fileName.contains('/') && !fileName.contains('\\')
        }
        TYPE_PROFILE_IMAGE_ASSET -> {
            val fileName = name.removePrefix("profile_images/assets/")
            name.startsWith("profile_images/assets/") &&
                Regex("^profile_[0-9a-f]{64}\\.jpg$").matches(fileName)
        }
        else -> false
    }
}
