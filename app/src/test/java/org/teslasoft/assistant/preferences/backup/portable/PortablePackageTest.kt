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

import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupCodec
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupFormat
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupManifest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * End-to-end format tests: build -> envelope -> inspect -> decode -> validate,
 * plus the tamper, wrong-key, truncation, KDF-window, and adversarial-input
 * vectors the owner's rulings require. Pure JVM — this is the project's one
 * automated verification gate, so the whole format lives or dies here.
 */
class PortablePackageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun artifactFile(name: String, content: ByteArray): File =
        tmp.newFile(name).apply { writeBytes(content) }

    private fun buildArtifacts(): List<PortablePackage.Artifact> = listOf(
        PortablePackage.Artifact(
            entryName = "memory.db", type = "sqlcipher-db",
            file = artifactFile("mem", ByteArray(5000) { (it % 13).toByte() }),
            databaseKeyHex = "aa".repeat(32),
            keySemantics = PortablePackage.KEY_SEMANTICS_PASSPHRASE,
            schemaVersion = 16
        ),
        PortablePackage.Artifact(
            entryName = "chats.json", type = "chats-json",
            file = artifactFile("chats", """{"format":"chat-logical-v1","complete":true,"chats":[]}""".toByteArray()),
            databaseKeyHex = null, keySemantics = null, schemaVersion = null
        )
    )

    private fun makePackage(
        recoverySecret: ByteArray?,
        passwordBlob: PortablePackageFormat.PasswordBlob? = null
    ): File {
        val inner = tmp.newFile("inner.zip")
        inner.delete()
        PortablePackage.buildInnerZip(buildArtifacts(), "2026-07-22T00:00:00Z", inner)
        val out = tmp.newFile("package.bin")
        out.delete()
        PortablePackage.envelope(inner, out, "2026-07-22T00:00:00Z", "1.0", recoverySecret, passwordBlob)
        return out
    }

    // ----- happy paths -------------------------------------------------------

    @Test
    fun encryptedRoundTrip() {
        val rs = PackageCrypto.newRecoverySecret()
        val pkg = makePackage(rs)

        val inspect = PortablePackage.inspect(pkg)
        assertTrue(inspect is PortablePackage.InspectResult.Ok)
        val info = (inspect as PortablePackage.InspectResult.Ok).inspection
        assertEquals(PortablePackageFormat.PROTECTION_ENCRYPTED, info.protection)
        assertFalse(info.hasPasswordSlot)
        assertArrayEquals(PackageCrypto.fingerprint(rs), info.keyFingerprint)

        val staging = tmp.newFolder()
        val decoded = PortablePackage.decodeWithSecret(pkg, rs, staging)
        assertTrue(decoded is PortablePackage.DecodeResult.Ok)
        assertTrue((decoded as PortablePackage.DecodeResult.Ok).authenticated)

        val validated = PortablePackage.validateAndExtract(decoded.innerZip, staging)
        assertTrue(validated is PortablePackage.ValidateResult.Ok)
        val artifacts = (validated as PortablePackage.ValidateResult.Ok).artifacts
        assertEquals(2, artifacts.size)
        val mem = artifacts.first { it.entryName == "memory.db" }
        assertEquals("aa".repeat(32), mem.databaseKeyHex)
        assertEquals(PortablePackage.KEY_SEMANTICS_PASSPHRASE, mem.keySemantics)
    }

    @Test
    fun validV2PackageRoundTripsOnlyArtifactBackedCategories() {
        val inner = tmp.newFile("inventory_inner.zip").apply { delete() }
        val declared = setOf(
            PortableRestoreCategory.CHATS,
            PortableRestoreCategory.MEMORIES,
            PortableRestoreCategory.MODEL_RULES
        )
        PortablePackage.buildInnerZip(
            buildArtifacts(),
            "2026-09-09T00:00:00Z",
            inner,
            declared
        )

        val extracted = PortablePackage.validateAndExtract(inner, tmp.newFolder())
        assertTrue(extracted is PortablePackage.ValidateResult.Ok)
        assertEquals(
            declared,
            (extracted as PortablePackage.ValidateResult.Ok).declaredCategories
        )
    }

    @Test
    fun ambiguousV2MissingArtifactsAreNotInventedAsEmptyCategories() {
        val inner = tmp.newFile("ambiguous_v2_inner.zip").apply { delete() }
        PortablePackage.buildInnerZip(
            buildArtifacts(),
            "2026-09-09T00:00:00Z",
            inner,
            setOf(
                PortableRestoreCategory.CHATS,
                PortableRestoreCategory.GENERATED_IMAGES,
                PortableRestoreCategory.MEMORIES,
                PortableRestoreCategory.MODEL_RULES
            )
        )

        assertTrue(
            PortablePackage.validateAndExtract(inner, tmp.newFolder())
                is PortablePackage.ValidateResult.Failed
        )
    }

    @Test
    fun currentManifestRoundTripsExactArtifactAndEmptyRepresentations() {
        val artifacts = buildArtifacts()
        val declarations = PortableRestoreCategory.entries.map { category ->
            val names = when (category) {
                PortableRestoreCategory.CHATS -> setOf("chats.json")
                PortableRestoreCategory.MEMORIES,
                PortableRestoreCategory.MODEL_RULES -> setOf("memory.db")
                else -> emptySet()
            }
            PortablePackage.CategoryDeclaration(
                category,
                if (names.isEmpty()) PortablePackage.CategoryRepresentation.EMPTY
                else PortablePackage.CategoryRepresentation.ARTIFACTS,
                names,
                0L
            )
        }
        val inner = tmp.newFile("current_inventory_inner.zip").apply { delete() }
        PortablePackage.buildInnerZip(
            artifacts,
            "2026-09-14T00:00:00Z",
            inner,
            categoryDeclarations = declarations
        )

        val result = PortablePackage.validateAndExtract(inner, tmp.newFolder())
        assertTrue(result is PortablePackage.ValidateResult.Ok)
        result as PortablePackage.ValidateResult.Ok
        assertEquals(PortablePackage.MANIFEST_VERSION, result.manifestVersion)
        assertEquals(PortableRestoreCategory.entries.toSet(), result.declaredCategories)
        assertEquals(
            PortableRestoreCategory.entries.toSet() - setOf(
                PortableRestoreCategory.CHATS,
                PortableRestoreCategory.MEMORIES,
                PortableRestoreCategory.MODEL_RULES
            ),
            result.explicitlyEmptyCategories
        )
    }

    @Test
    fun currentManifestRejectsMissingDuplicateAndContradictoryCategoryDeclarations() {
        fun currentInner(name: String): File {
            val artifacts = buildArtifacts()
            val declarations = PortableRestoreCategory.entries.map { category ->
                val names = when (category) {
                    PortableRestoreCategory.CHATS -> setOf("chats.json")
                    PortableRestoreCategory.MEMORIES,
                    PortableRestoreCategory.MODEL_RULES -> setOf("memory.db")
                    else -> emptySet()
                }
                PortablePackage.CategoryDeclaration(
                    category,
                    if (names.isEmpty()) PortablePackage.CategoryRepresentation.EMPTY
                    else PortablePackage.CategoryRepresentation.ARTIFACTS,
                    names,
                    0L
                )
            }
            return tmp.newFile(name).apply {
                delete()
                PortablePackage.buildInnerZip(
                    artifacts,
                    "2026-09-14T00:00:00Z",
                    this,
                    categoryDeclarations = declarations
                )
            }
        }

        val missing = rewriteManifest(currentInner("source_missing.zip"), "missing_category.zip") { manifest ->
            manifest.getJSONArray("categories").remove(0)
        }
        val duplicate = rewriteManifest(currentInner("source_duplicate.zip"), "duplicate_category.zip") { manifest ->
            val categories = manifest.getJSONArray("categories")
            categories.put(JSONObject(categories.getJSONObject(0).toString()))
        }
        val contradictory = rewriteManifest(currentInner("source_contradictory.zip"), "contradictory.zip") { manifest ->
            val categories = manifest.getJSONArray("categories")
            val chat = (0 until categories.length())
                .map { categories.getJSONObject(it) }
                .first { it.getString("category") == PortableRestoreCategory.CHATS.key }
            chat.put("representation", PortablePackage.CategoryRepresentation.EMPTY.value)
                .put("artifacts", org.json.JSONArray())
                .put("record_count", 0)
        }

        listOf(missing, duplicate, contradictory).forEach { invalid ->
            assertTrue(
                PortablePackage.validateAndExtract(invalid, tmp.newFolder())
                    is PortablePackage.ValidateResult.Failed
            )
        }
    }

    @Test
    fun currentManifestRejectsOverLimitDeclarationBeforeArtifactParsing() {
        val chats = artifactFile(
            "limit_chats",
            """{"format":"chat-logical-v2","complete":true,"chats":[],"folders":[]}"""
                .toByteArray(Charsets.UTF_8)
        )
        val declarations = PortableRestoreCategory.entries.map { category ->
            val names = if (category == PortableRestoreCategory.CHATS) setOf("chats.json") else emptySet()
            PortablePackage.CategoryDeclaration(
                category,
                if (names.isEmpty()) PortablePackage.CategoryRepresentation.EMPTY
                else PortablePackage.CategoryRepresentation.ARTIFACTS,
                names,
                0L
            )
        }
        val valid = tmp.newFile("limit_source.zip").apply { delete() }
        PortablePackage.buildInnerZip(
            listOf(
                PortablePackage.Artifact(
                    "chats.json",
                    PortablePackage.TYPE_CHATS_JSON,
                    chats,
                    null,
                    null,
                    2
                )
            ),
            "2026-09-14T00:00:00Z",
            valid,
            categoryDeclarations = declarations
        )
        val invalid = rewriteManifest(valid, "limit_invalid.zip") { manifest ->
            manifest.getJSONArray("artifacts").getJSONObject(0).put(
                "decoded_bytes",
                PortableRecoveryLimits.CHATS_JSON_BYTES + 1L
            )
        }

        val result = PortablePackage.validateAndExtract(invalid, tmp.newFolder())
        assertTrue(result is PortablePackage.ValidateResult.Failed)
        assertEquals(
            PortablePackageFormat.RestoreError.TOO_LARGE,
            (result as PortablePackage.ValidateResult.Failed).error
        )
    }

    @Test
    fun unencryptedRoundTripIsNeverAuthenticated() {
        val pkg = makePackage(recoverySecret = null)
        val inspect = PortablePackage.inspect(pkg)
        assertTrue(inspect is PortablePackage.InspectResult.Ok)
        assertEquals(
            PortablePackageFormat.PROTECTION_NONE,
            (inspect as PortablePackage.InspectResult.Ok).inspection.protection
        )

        val staging = tmp.newFolder()
        val decoded = PortablePackage.decodeWithSecret(pkg, ByteArray(0), staging)
        assertTrue(decoded is PortablePackage.DecodeResult.Ok)
        // "Checked for damage", never "verified": authenticated must be false.
        assertFalse((decoded as PortablePackage.DecodeResult.Ok).authenticated)
        assertTrue(PortablePackage.validateAndExtract(decoded.innerZip, staging) is PortablePackage.ValidateResult.Ok)
    }

    @Test
    fun generatedImageCatalogAndAssetArePortableArtifacts() {
        val inner = tmp.newFile("generated_inner.zip").apply { delete() }
        val catalog = artifactFile(
            "generated_catalog",
            """{"format":"generated-images-logical-v1","active":[],"tombstones":[],"meta":[],"backfill_chats":[]}""".toByteArray()
        )
        val png = artifactFile(
            "generated_png",
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0x00, 0x00, 0x00, 0x00
            )
        )
        PortablePackage.buildInnerZip(
            listOf(
                PortablePackage.Artifact(
                    "generated_images/catalog.json",
                    PortablePackage.TYPE_GENERATED_IMAGES_CATALOG,
                    catalog,
                    null,
                    null,
                    1
                ),
                PortablePackage.Artifact(
                    "generated_images/assets/79b4e47b-b6d4-4e7d-8d8d-413215eab779.png",
                    PortablePackage.TYPE_GENERATED_IMAGE_ASSET,
                    png,
                    null,
                    null,
                    null
                )
            ),
            "2026-09-08T00:00:00Z",
            inner
        )

        val extracted = PortablePackage.validateAndExtract(inner, tmp.newFolder())
        assertTrue(extracted is PortablePackage.ValidateResult.Ok)
        val artifacts = (extracted as PortablePackage.ValidateResult.Ok).artifacts
        assertEquals(2, artifacts.size)
        assertEquals(
            1,
            artifacts.first { it.type == PortablePackage.TYPE_GENERATED_IMAGES_CATALOG }.schemaVersion
        )
    }

    @Test
    fun profileImageAssetIsAPortableArtifactWithStrictHashFilename() {
        val inner = tmp.newFile("profile_image_inner.zip").apply { delete() }
        val jpeg = artifactFile(
            "profile_jpeg",
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
        )
        val hash = "a".repeat(64)
        PortablePackage.buildInnerZip(
            listOf(
                PortablePackage.Artifact(
                    "profile_images/assets/profile_$hash.jpg",
                    PortablePackage.TYPE_PROFILE_IMAGE_ASSET,
                    jpeg,
                    null,
                    null,
                    null
                )
            ),
            "2026-09-08T00:00:00Z",
            inner
        )

        val extracted = PortablePackage.validateAndExtract(inner, tmp.newFolder())
        assertTrue(extracted is PortablePackage.ValidateResult.Ok)
        assertEquals(
            PortablePackage.TYPE_PROFILE_IMAGE_ASSET,
            (extracted as PortablePackage.ValidateResult.Ok).artifacts.single().type
        )

        val unsafe = tmp.newFile("unsafe_profile.zip").apply { delete() }
        assertTrue(
            runCatching {
                PortablePackage.buildInnerZip(
                    listOf(
                        PortablePackage.Artifact(
                            "profile_images/assets/not-a-hash.jpg",
                            PortablePackage.TYPE_PROFILE_IMAGE_ASSET,
                            jpeg,
                            null,
                            null,
                            null
                        )
                    ),
                    "2026-09-08T00:00:00Z",
                    unsafe
                )
            }.exceptionOrNull() is IllegalArgumentException
        )
    }

    @Test
    fun companionAndRoleplayArchiveIsAValidatedPortableArtifact() {
        val companionArchive = artifactFile("companion_archive", ByteArray(0))
        ZipOutputStream(companionArchive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(CompanionBackupFormat.MANIFEST_ENTRY))
            zip.write(
                CompanionBackupCodec.toJson(
                    CompanionBackupManifest(
                        formatVersion = CompanionBackupFormat.FORMAT_VERSION,
                        appVersion = "1.0",
                        exportedAt = "2026-09-08T00:00:00Z",
                        companionProfiles = emptyList(),
                        activationPrompts = emptyList(),
                        systemPrompts = emptyList(),
                        selectedSystemPromptId = "",
                        roleplayTables = CompanionBackupFormat.ROLEPLAY_TABLES
                            .associateWith { emptyList() },
                        images = emptyList()
                    )
                ).toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
        }
        val inner = tmp.newFile("companion_inner.zip").apply { delete() }
        PortablePackage.buildInnerZip(
            listOf(
                PortablePackage.Artifact(
                    "companion_roleplay.zip",
                    PortablePackage.TYPE_COMPANION_ROLEPLAY_ARCHIVE,
                    companionArchive,
                    null,
                    null,
                    CompanionBackupFormat.FORMAT_VERSION
                )
            ),
            "2026-09-08T00:00:00Z",
            inner
        )

        val extracted = PortablePackage.validateAndExtract(inner, tmp.newFolder())
        assertTrue(extracted is PortablePackage.ValidateResult.Ok)
        assertEquals(
            PortablePackage.TYPE_COMPANION_ROLEPLAY_ARCHIVE,
            (extracted as PortablePackage.ValidateResult.Ok).artifacts.single().type
        )

        companionArchive.writeBytes("not a zip".toByteArray())
        val invalidInner = tmp.newFile("invalid_companion_inner.zip").apply { delete() }
        PortablePackage.buildInnerZip(
            listOf(
                PortablePackage.Artifact(
                    "companion_roleplay.zip",
                    PortablePackage.TYPE_COMPANION_ROLEPLAY_ARCHIVE,
                    companionArchive,
                    null,
                    null,
                    CompanionBackupFormat.FORMAT_VERSION
                )
            ),
            "2026-09-08T00:00:00Z",
            invalidInner
        )
        assertTrue(
            PortablePackage.validateAndExtract(invalidInner, tmp.newFolder())
                is PortablePackage.ValidateResult.Failed
        )
    }

    @Test
    fun modelEndpointSettingsIsAValidatedPortableArtifact() {
        val settings = artifactFile(
            "model_endpoint_settings",
            ModelEndpointPortableCodec.encode(
                ModelEndpointPortableCodec.Data(emptyList(), emptyList())
            ).toByteArray(Charsets.UTF_8)
        )
        val inner = tmp.newFile("model_endpoint_inner.zip").apply { delete() }
        PortablePackage.buildInnerZip(
            listOf(
                PortablePackage.Artifact(
                    "model_endpoint_settings.json",
                    PortablePackage.TYPE_MODEL_ENDPOINT_SETTINGS,
                    settings,
                    null,
                    null,
                    ModelEndpointPortableCodec.SCHEMA_VERSION
                )
            ),
            "2026-09-08T00:00:00Z",
            inner
        )

        val extracted = PortablePackage.validateAndExtract(inner, tmp.newFolder())
        assertTrue(extracted is PortablePackage.ValidateResult.Ok)
        assertEquals(
            PortablePackage.TYPE_MODEL_ENDPOINT_SETTINGS,
            (extracted as PortablePackage.ValidateResult.Ok).artifacts.single().type
        )
    }

    @Test
    fun passwordRouteRecoversTheSecretItself() {
        val rs = PackageCrypto.newRecoverySecret()
        val salt = PackageCrypto.newKdfSalt()
        val kek = PackageCrypto.derivePasswordKey("hunter2 but long".toCharArray(), salt, PackageCrypto.KDF_MIN_ITERATIONS)
        val blob = PortablePackageFormat.PasswordBlob(
            algorithm = PackageCrypto.KDF_ALG,
            salt = salt,
            iterations = PackageCrypto.KDF_MIN_ITERATIONS,
            keyBytes = PackageCrypto.KDF_KEY_BYTES,
            wrappedRecoverySecret = PackageCrypto.wrapRecoverySecret(kek, rs)
        )
        val pkg = makePackage(rs, blob)

        val staging = tmp.newFolder()
        val decoded = PortablePackage.decodeWithPassword(pkg, "hunter2 but long".toCharArray(), staging)
        assertTrue(decoded is PortablePackage.DecodeResult.Ok)
        assertTrue((decoded as PortablePackage.DecodeResult.Ok).authenticated)

        val wrong = PortablePackage.decodeWithPassword(pkg, "not the password".toCharArray(), tmp.newFolder())
        assertTrue(wrong is PortablePackage.DecodeResult.Failed)
        assertEquals(
            PortablePackageFormat.RestoreError.WRONG_KEY_OR_HEADER,
            (wrong as PortablePackage.DecodeResult.Failed).error
        )
    }

    // ----- filename-independent identity + producer metadata -----------------

    @Test
    fun producerMetadataRoundTripsInTheAuthenticatedHeader() {
        val rs = PackageCrypto.newRecoverySecret()
        val inner = tmp.newFile("inner_p.zip")
        inner.delete()
        PortablePackage.buildInnerZip(buildArtifacts(), "2026-07-22T00:00:00Z", inner)
        val out = tmp.newFile("package_p.bin")
        out.delete()
        PortablePackage.envelope(
            inner, out, "2026-07-22T00:00:00Z", "1.0", rs, null,
            producerAppId = "com.example.app", producerDisplayName = "Old App Name"
        )
        val inspect = PortablePackage.inspect(out)
        assertTrue(inspect is PortablePackage.InspectResult.Ok)
        val info = (inspect as PortablePackage.InspectResult.Ok).inspection
        assertEquals("com.example.app", info.producerAppId)
        assertEquals("Old App Name", info.producerDisplayName)
    }

    @Test
    fun renamingThePackageFileChangesNothingAboutItsIdentity() {
        // Filename text is NOT identity (owner filename architecture): a
        // package renamed to anything — including another brand's shape —
        // must inspect and decode identically.
        val rs = PackageCrypto.newRecoverySecret()
        val pkg = makePackage(rs)
        val renamed = File(tmp.root, "TotallyDifferentBrand-Automatic-Recovery-Protected-2020-01-01_0000.zip")
        assertTrue(pkg.renameTo(renamed))

        val inspect = PortablePackage.inspect(renamed)
        assertTrue(inspect is PortablePackage.InspectResult.Ok)

        val staging = tmp.newFolder()
        val decoded = PortablePackage.decodeWithSecret(renamed, rs, staging)
        assertTrue(decoded is PortablePackage.DecodeResult.Ok)
        assertTrue((decoded as PortablePackage.DecodeResult.Ok).authenticated)
        assertTrue(PortablePackage.validateAndExtract(decoded.innerZip, staging) is PortablePackage.ValidateResult.Ok)
    }

    // ----- the owner-ruled error model ---------------------------------------

    @Test
    fun wrongSecretIsWrongKeyOrHeaderNotDamage() {
        val pkg = makePackage(PackageCrypto.newRecoverySecret())
        val decoded = PortablePackage.decodeWithSecret(pkg, PackageCrypto.newRecoverySecret(), tmp.newFolder())
        assertTrue(decoded is PortablePackage.DecodeResult.Failed)
        assertEquals(
            PortablePackageFormat.RestoreError.WRONG_KEY_OR_HEADER,
            (decoded as PortablePackage.DecodeResult.Failed).error
        )
    }

    @Test
    fun flippedBodyByteIsDamagedOrAltered() {
        val rs = PackageCrypto.newRecoverySecret()
        val pkg = makePackage(rs)
        RandomAccessFile(pkg, "rw").use { raf ->
            raf.seek(pkg.length() - 100)
            val b = raf.read()
            raf.seek(pkg.length() - 100)
            raf.write(b xor 1)
        }
        val decoded = PortablePackage.decodeWithSecret(pkg, rs, tmp.newFolder())
        assertTrue(decoded is PortablePackage.DecodeResult.Failed)
        assertEquals(
            PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED,
            (decoded as PortablePackage.DecodeResult.Failed).error
        )
    }

    @Test
    fun truncatedPackageIsDamagedOrAltered() {
        val rs = PackageCrypto.newRecoverySecret()
        val pkg = makePackage(rs)
        RandomAccessFile(pkg, "rw").use { it.setLength(pkg.length() - 24) }
        val decoded = PortablePackage.decodeWithSecret(pkg, rs, tmp.newFolder())
        assertTrue(decoded is PortablePackage.DecodeResult.Failed)
        assertEquals(
            PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED,
            (decoded as PortablePackage.DecodeResult.Failed).error
        )
    }

    @Test
    fun kdfIterationsOutsideWindowIsUnsupportedProtectionNotDamage() {
        val rs = PackageCrypto.newRecoverySecret()
        val salt = PackageCrypto.newKdfSalt()
        val kek = PackageCrypto.derivePasswordKey("pw".toCharArray(), salt, PackageCrypto.KDF_MIN_ITERATIONS)
        val blob = PortablePackageFormat.PasswordBlob(
            algorithm = PackageCrypto.KDF_ALG,
            salt = salt,
            iterations = 10_000_000, // above the v1 cap (owner correction: cap == ceiling)
            keyBytes = PackageCrypto.KDF_KEY_BYTES,
            wrappedRecoverySecret = PackageCrypto.wrapRecoverySecret(kek, rs)
        )
        val pkg = makePackage(rs, blob)
        val header = PortablePackageFormat.readHeader(pkg)
        assertTrue(header is PortablePackageFormat.HeaderResult.Invalid)
        assertEquals(
            PortablePackageFormat.RestoreError.UNSUPPORTED_PROTECTION,
            (header as PortablePackageFormat.HeaderResult.Invalid).error
        )
    }

    @Test
    fun legacyOrForeignFileIsNotAV2Package() {
        val notAPackage = tmp.newFile("legacy.zip").apply { writeBytes("PKjunk".toByteArray()) }
        val header = PortablePackageFormat.readHeader(notAPackage)
        assertTrue(header is PortablePackageFormat.HeaderResult.Invalid)
        assertEquals(
            PortablePackageFormat.RestoreError.NOT_A_V2_PACKAGE,
            (header as PortablePackageFormat.HeaderResult.Invalid).error
        )
    }

    // ----- adversarial input (ruling 13) -------------------------------------

    @Test
    fun manifestHashMismatchFailsValidation() {
        val rs = PackageCrypto.newRecoverySecret()
        val pkg = makePackage(rs)
        val staging = tmp.newFolder()
        val decoded = PortablePackage.decodeWithSecret(pkg, rs, staging) as PortablePackage.DecodeResult.Ok

        // Rewrite the inner zip with one artifact's bytes changed but the old
        // manifest kept — hash must catch it.
        val corrupted = tmp.newFile("corrupted.zip")
        corrupted.delete()
        java.util.zip.ZipOutputStream(corrupted.outputStream()).use { out ->
            java.util.zip.ZipFile(decoded.innerZip).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    out.putNextEntry(java.util.zip.ZipEntry(e.name))
                    val bytes = zip.getInputStream(e).use { it.readBytes() }
                    if (e.name == "memory.db") bytes[0] = (bytes[0].toInt() xor 1).toByte()
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
        val validated = PortablePackage.validateAndExtract(corrupted, tmp.newFolder())
        assertTrue(validated is PortablePackage.ValidateResult.Failed)
    }

    @Test
    fun unexpectedEntryFailsValidation() {
        val rs = PackageCrypto.newRecoverySecret()
        val pkg = makePackage(rs)
        val staging = tmp.newFolder()
        val decoded = PortablePackage.decodeWithSecret(pkg, rs, staging) as PortablePackage.DecodeResult.Ok

        val augmented = tmp.newFile("augmented.zip")
        augmented.delete()
        java.util.zip.ZipOutputStream(augmented.outputStream()).use { out ->
            java.util.zip.ZipFile(decoded.innerZip).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    out.putNextEntry(java.util.zip.ZipEntry(e.name))
                    out.write(zip.getInputStream(e).use { it.readBytes() })
                    out.closeEntry()
                }
            }
            out.putNextEntry(java.util.zip.ZipEntry("planted.bin"))
            out.write(ByteArray(10))
            out.closeEntry()
        }
        assertTrue(PortablePackage.validateAndExtract(augmented, tmp.newFolder()) is PortablePackage.ValidateResult.Failed)
    }

    @Test
    fun zipSlipNamesAreRejected() {
        assertFalse(PortablePackage.isSafeEntryName("../../../etc/passwd"))
        assertFalse(PortablePackage.isSafeEntryName("/absolute"))
        assertFalse(PortablePackage.isSafeEntryName("a\\b"))
        assertFalse(PortablePackage.isSafeEntryName(""))
        assertTrue(PortablePackage.isSafeEntryName("memory.db"))
    }

    private fun rewriteManifest(
        source: File,
        destinationName: String,
        mutate: (JSONObject) -> Unit
    ): File {
        val destination = tmp.newFile(destinationName).apply { delete() }
        ZipFile(source).use { input ->
            ZipOutputStream(destination.outputStream()).use { output ->
                val entries = input.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    output.putNextEntry(ZipEntry(entry.name))
                    val bytes = input.getInputStream(entry).use { it.readBytes() }
                    if (entry.name == PortablePackage.MANIFEST_ENTRY) {
                        val manifest = JSONObject(String(bytes, Charsets.UTF_8))
                        mutate(manifest)
                        output.write(manifest.toString().toByteArray(Charsets.UTF_8))
                    } else {
                        output.write(bytes)
                    }
                    output.closeEntry()
                }
            }
        }
        return destination
    }
}
