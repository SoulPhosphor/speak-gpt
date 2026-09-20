package org.teslasoft.assistant.preferences.backup.portable

import android.app.Application
import android.content.Context
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.imagegen.GeneratedImageMetadata
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupCodec
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupFormat
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupImage
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupManifest
import org.teslasoft.assistant.preferences.backup.companion.CompanionProfileEntry
import org.teslasoft.assistant.util.Hash

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PortableRecoverySemanticValidatorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun malformedLogicalArtifactsFailTheSharedSemanticGate() {
        val cases = listOf(
            artifact("chats.json", PortablePackage.TYPE_CHATS_JSON, "not-json".toByteArray()),
            artifact(
                "generated_images/catalog.json",
                PortablePackage.TYPE_GENERATED_IMAGES_CATALOG,
                "{}".toByteArray(),
                schemaVersion = 1
            ),
            artifact(
                "companion_roleplay.zip",
                PortablePackage.TYPE_COMPANION_ROLEPLAY_ARCHIVE,
                "not-a-zip".toByteArray(),
                schemaVersion = CompanionBackupFormat.FORMAT_VERSION
            ),
            artifact("user_images.db", PortablePackage.TYPE_SQLITE_DB, "not-sqlite".toByteArray()),
            artifact(
                "model_endpoint_settings.json",
                PortablePackage.TYPE_MODEL_ENDPOINT_SETTINGS,
                "{}".toByteArray(),
                schemaVersion = ModelEndpointPortableCodec.SCHEMA_VERSION
            ),
            artifact(
                "memory.db",
                PortablePackage.TYPE_SQLCIPHER_DB,
                "not-sqlcipher".toByteArray(),
                databaseKeyHex = "not-hex",
                keySemantics = PortablePackage.KEY_SEMANTICS_PASSPHRASE
            ),
            artifact(
                "lorebook.db",
                PortablePackage.TYPE_SQLCIPHER_DB,
                "not-sqlcipher".toByteArray(),
                databaseKeyHex = "not-hex",
                keySemantics = PortablePackage.KEY_SEMANTICS_PASSPHRASE
            )
        )

        cases.forEach { malformed ->
            assertTrue(
                "semantic gate accepted ${malformed.type}",
                PortableRecoverySemanticValidator.validate(context, listOf(malformed))
                    is PortableRecoverySemanticValidator.Result.Invalid
            )
        }
    }

    @Test
    fun chatGeneratedImageReferenceMustResolveInsideThePackage() {
        val imageId = UUID.randomUUID().toString()
        val metadata = GeneratedImageMetadata(
            imageId, "a".repeat(64), "image/png", 1, 1,
            "endpoint", "model", "prompt", null, 1L,
            GeneratedImageMetadata.STATUS_COMPLETE, null
        )
        val chatId = UUID.randomUUID().toString()
        val chats = JSONObject()
            .put("format", "chat-logical-v2")
            .put("complete", true)
            .put("chats", JSONArray().put(JSONObject()
                .put("chat_id", chatId)
                .put("list_id", chatId)
                .put("messages", JSONArray().put(JSONObject()
                    .put(GeneratedImageMetadata.KEY, metadata.toJson())))
                .put("settings", JSONArray())))
            .put("folders", JSONArray())
            .toString()
        val result = PortableRecoverySemanticValidator.validate(
            context,
            listOf(artifact("chats.json", PortablePackage.TYPE_CHATS_JSON, chats.toByteArray()))
        )
        assertTrue(result is PortableRecoverySemanticValidator.Result.Invalid)
    }

    @Test
    fun currentManifestCountsMustMatchTheLogicalReaders() {
        val chatId = UUID.randomUUID().toString()
        val chats = JSONObject()
            .put("format", "chat-logical-v2")
            .put("complete", true)
            .put("chats", JSONArray().put(JSONObject()
                .put("chat_id", chatId)
                .put("list_id", chatId)
                .put("messages", JSONArray())
                .put("settings", JSONArray())))
            .put("folders", JSONArray())
            .toString()
        val all = PortableRestoreCategory.entries.toSet()
        val result = PortableRecoverySemanticValidator.validate(
            context,
            listOf(artifact("chats.json", PortablePackage.TYPE_CHATS_JSON, chats.toByteArray())),
            declaredCategories = all,
            explicitlyEmptyCategories = all - PortableRestoreCategory.CHATS,
            declaredRecordCounts = PortableRestoreCategory.entries.associateWith { category ->
                if (category == PortableRestoreCategory.CHATS) 2L else 0L
            }
        )
        assertTrue(result is PortableRecoverySemanticValidator.Result.Invalid)
    }

    @Test
    fun currentIdentityImagesAndLorebookLinksRequirePackageClosure() {
        val imageBytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
        val imageHash = Hash.hash(imageBytes)
        val archiveWithImage = companionArchive(
            "identity_image.zip",
            profile(imageHash, coreLorebookId = ""),
            listOf(CompanionBackupImage(imageHash, "images/profile_$imageHash.jpg")),
            imageBytes
        )
        val archiveWithLorebook = companionArchive(
            "identity_lorebook.zip",
            profile(avatar = "", coreLorebookId = "book-1"),
            emptyList(),
            null
        )
        val all = PortableRestoreCategory.entries.toSet()
        val identityCategories = setOf(
            PortableRestoreCategory.COMPANIONS,
            PortableRestoreCategory.GLAMOURS,
            PortableRestoreCategory.ROLEPLAY,
            PortableRestoreCategory.ACTIVATION_PROMPTS,
            PortableRestoreCategory.SYSTEM_PROMPTS
        )
        listOf(archiveWithImage, archiveWithLorebook).forEach { archive ->
            val result = PortableRecoverySemanticValidator.validate(
                context,
                listOf(artifact(
                    "companion_roleplay.zip",
                    PortablePackage.TYPE_COMPANION_ROLEPLAY_ARCHIVE,
                    archive.readBytes(),
                    CompanionBackupFormat.FORMAT_VERSION
                )),
                declaredCategories = all,
                explicitlyEmptyCategories = all - identityCategories
            )
            assertTrue(result is PortableRecoverySemanticValidator.Result.Invalid)
        }
    }

    private fun profile(avatar: String, coreLorebookId: String) = CompanionProfileEntry(
        id = "companion-1",
        label = "Companion",
        prompt = "Prompt",
        activationPromptId = "",
        coreLoreBookId = coreLorebookId,
        coreLoreBookName = coreLorebookId.takeIf(String::isNotEmpty)?.let { "Book" },
        additionalLoreBookIds = emptyList(),
        additionalLoreBookNames = emptyMap(),
        autoLoadLastLoreBooks = false,
        lastUsedLoreBookIds = emptyList(),
        avatarRef = avatar
    )

    private fun companionArchive(
        name: String,
        profile: CompanionProfileEntry,
        images: List<CompanionBackupImage>,
        imageBytes: ByteArray?
    ): File = tmp.newFile(name).also { file ->
        val manifest = CompanionBackupManifest(
            CompanionBackupFormat.FORMAT_VERSION,
            "1",
            "2026-09-14T00:00:00Z",
            listOf(profile),
            emptyList(),
            emptyList(),
            "",
            CompanionBackupFormat.ROLEPLAY_TABLES.associateWith { emptyList() },
            images
        )
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(CompanionBackupFormat.MANIFEST_ENTRY))
            zip.write(CompanionBackupCodec.toJson(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            if (imageBytes != null) {
                zip.putNextEntry(ZipEntry(images.single().file))
                zip.write(imageBytes)
                zip.closeEntry()
            }
        }
    }

    private fun artifact(
        entryName: String,
        type: String,
        bytes: ByteArray,
        schemaVersion: Int? = null,
        databaseKeyHex: String? = null,
        keySemantics: String? = null
    ): PortablePackage.ValidatedArtifact = PortablePackage.ValidatedArtifact(
        entryName,
        type,
        tmp.newFile("artifact_${System.nanoTime()}").apply { writeBytes(bytes) },
        databaseKeyHex,
        keySemantics,
        schemaVersion
    )
}
