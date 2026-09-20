package org.teslasoft.assistant.preferences.backup.portable

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.preferences.profileimages.ProfileImageFileNaming

/**
 * Read-only preparation of a portable Profile Image restore. The catalog and
 * its picture bytes must close (every catalog row has a matching, hash-valid
 * asset) before a direct restore may install them together (Phase 12.4, BR-08).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProfileImagePortableRestoreManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun preparesWhenEveryCatalogRowHasAMatchingAsset() {
        val first = jpeg(1)
        val second = jpeg(2)
        val firstHash = sha256(first)
        val secondHash = sha256(second)
        val artifacts = listOf(
            catalogArtifact(firstHash, secondHash),
            assetArtifact(firstHash, first),
            assetArtifact(secondHash, second)
        )

        val result = ProfileImagePortableRestoreManager.prepare(artifacts)

        assertTrue(result is ProfileImagePortableRestoreManager.Result.Ready)
        result as ProfileImagePortableRestoreManager.Result.Ready
        assertEquals(setOf(firstHash, secondHash), result.prepared.assets.keys)
        assertEquals(setOf(firstHash, secondHash), result.prepared.records.map { it.hash }.toSet())
    }

    @Test
    fun rejectsWhenACatalogRowHasNoAsset() {
        val first = jpeg(3)
        val second = jpeg(4)
        val artifacts = listOf(
            catalogArtifact(sha256(first), sha256(second)),
            assetArtifact(sha256(first), first)
            // second asset intentionally absent
        )

        assertEquals(
            ProfileImagePortableRestoreManager.Result.Invalid,
            ProfileImagePortableRestoreManager.prepare(artifacts)
        )
    }

    @Test
    fun rejectsWhenAnAssetDoesNotMatchItsHash() {
        val bytes = jpeg(5)
        val hash = sha256(bytes)
        val artifacts = listOf(
            catalogArtifact(hash),
            assetArtifact(hash, jpeg(6)) // wrong bytes for this hash
        )

        assertEquals(
            ProfileImagePortableRestoreManager.Result.Invalid,
            ProfileImagePortableRestoreManager.prepare(artifacts)
        )
    }

    @Test
    fun readsCatalogHashesAndRejectsANonCatalogFile() {
        val bytes = jpeg(7)
        val hash = sha256(bytes)
        val catalog = catalogFile(hash)

        assertEquals(listOf(hash), ProfileImagePortableRestoreManager.readCatalogHashes(catalog))
        assertNull(
            ProfileImagePortableRestoreManager.readCatalogHashes(
                File(tmp.root, "not-a-db.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            )
        )
    }

    private fun catalogFile(vararg hashes: String): File {
        val file = File(tmp.root, "user_images-${System.nanoTime()}.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE profile_images (hash TEXT PRIMARY KEY NOT NULL, created_at INTEGER NOT NULL)"
            )
            hashes.forEachIndexed { index, hash ->
                db.insertOrThrow(
                    "profile_images",
                    null,
                    ContentValues().apply {
                        put("hash", hash)
                        put("created_at", 100L + index)
                    }
                )
            }
        }
        return file
    }

    private fun catalogArtifact(vararg hashes: String): PortablePackage.ValidatedArtifact =
        PortablePackage.ValidatedArtifact(
            "user_images.db",
            PortablePackage.TYPE_SQLITE_DB,
            catalogFile(*hashes),
            null,
            null,
            null
        )

    private fun assetArtifact(hash: String, bytes: ByteArray): PortablePackage.ValidatedArtifact {
        val name = ProfileImageFileNaming.permanentFileName(hash)
        val file = File(tmp.root, name).apply { writeBytes(bytes) }
        return PortablePackage.ValidatedArtifact(
            "profile_images/assets/$name",
            PortablePackage.TYPE_PROFILE_IMAGE_ASSET,
            file,
            null,
            null,
            null
        )
    }

    private fun jpeg(marker: Int) = byteArrayOf(
        0xff.toByte(), 0xd8.toByte(), 0xff.toByte(),
        marker.toByte(), 0x01, 0x02,
        0xff.toByte(), 0xd9.toByte()
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
