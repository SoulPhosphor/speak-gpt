package org.teslasoft.assistant.preferences.backup.portable

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableRecoveryLimitsTest {
    @Test
    fun everyApplicableArtifactAcceptsBoundaryAndRejectsOverLimitWithoutTruncation() {
        val artifacts = listOf(
            "chats.json" to PortablePackage.TYPE_CHATS_JSON,
            "generated_images/catalog.json" to PortablePackage.TYPE_GENERATED_IMAGES_CATALOG,
            "model_endpoint_settings.json" to PortablePackage.TYPE_MODEL_ENDPOINT_SETTINGS,
            "companion_roleplay.zip" to PortablePackage.TYPE_COMPANION_ROLEPLAY_ARCHIVE,
            "memory.db" to PortablePackage.TYPE_SQLCIPHER_DB,
            "lorebook.db" to PortablePackage.TYPE_SQLCIPHER_DB,
            "user_images.db" to PortablePackage.TYPE_SQLITE_DB,
            "generated_images/assets/image.png" to PortablePackage.TYPE_GENERATED_IMAGE_ASSET,
            "profile_images/assets/profile_${"a".repeat(64)}.jpg" to
                PortablePackage.TYPE_PROFILE_IMAGE_ASSET
        )

        artifacts.forEach { (name, type) ->
            val limit = requireNotNull(PortableRecoveryLimits.maxDecodedBytes(name, type))
            assertTrue("boundary rejected for $type", PortableRecoveryLimits.accepts(name, type, limit))
            assertFalse("over-limit accepted for $type", PortableRecoveryLimits.accepts(name, type, limit + 1L))
            assertFalse("negative size accepted for $type", PortableRecoveryLimits.accepts(name, type, -1L))
        }
    }

    @Test
    fun provisionalPolicyIsVersionedAndMatchesApprovedCeilings() {
        assertTrue(PortableRecoveryLimits.POLICY_VERSION > 0)
        assertTrue(PortableRecoveryLimits.CHATS_JSON_BYTES == 64L * 1024L * 1024L)
        assertTrue(PortableRecoveryLimits.GENERATED_IMAGE_CATALOG_BYTES == 32L * 1024L * 1024L)
        assertTrue(PortableRecoveryLimits.MODEL_ENDPOINT_SETTINGS_BYTES == 8L * 1024L * 1024L)
        assertTrue(PortableRecoveryLimits.COMPANION_ROLEPLAY_ARCHIVE_BYTES == 512L * 1024L * 1024L)
        assertTrue(PortableRecoveryLimits.DATABASE_BYTES == 512L * 1024L * 1024L)
        assertTrue(PortableRecoveryLimits.IMAGE_ASSET_BYTES == 64L * 1024L * 1024L)
        assertTrue(PortableRecoveryLimits.COMPLETE_PACKAGE_BYTES == 1L * 1024L * 1024L * 1024L)
        assertTrue(PackageCrypto.MAX_PACKAGE_BYTES == PortableRecoveryLimits.COMPLETE_PACKAGE_BYTES)
    }
}
