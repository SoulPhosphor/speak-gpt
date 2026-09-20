package org.teslasoft.assistant.preferences.backup.portable

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.preferences.profileimages.ProfileImageFileNaming

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProfileImagePortableBackupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: android.content.Context get() = RuntimeEnvironment.getApplication()
    private lateinit var imagesDir: File

    @Before
    fun setUp() {
        imagesDir = requireNotNull(context.getExternalFilesDir("profile_images"))
        imagesDir.mkdirs()
        imagesDir.listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        imagesDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun completeCatalogIncludesEveryImageEvenWithoutAssignmentData() {
        val first = jpegBytes(1)
        val second = jpegBytes(2)
        val firstHash = sha256(first)
        val secondHash = sha256(second)
        val catalog = catalog(firstHash, secondHash)
        File(imagesDir, ProfileImageFileNaming.permanentFileName(firstHash)).writeBytes(first)
        File(imagesDir, ProfileImageFileNaming.permanentFileName(secondHash)).writeBytes(second)

        val result = ProfileImagePortableBackup.buildArtifacts(context, catalog)

        assertTrue(result is ProfileImagePortableBackup.Result.Ok)
        result as ProfileImagePortableBackup.Result.Ok
        assertEquals(2, result.inventory.imageCount)
        assertEquals((first.size + second.size).toLong(), result.inventory.imageBytes)
        assertEquals(
            setOf(
                "profile_images/assets/${ProfileImageFileNaming.permanentFileName(firstHash)}",
                "profile_images/assets/${ProfileImageFileNaming.permanentFileName(secondHash)}"
            ),
            result.artifacts.map { it.entryName }.toSet()
        )
    }

    @Test
    fun missingCataloguedImageFailsTheWholeSnapshot() {
        val bytes = jpegBytes(3)
        val result = ProfileImagePortableBackup.buildArtifacts(context, catalog(sha256(bytes)))

        assertEquals(
            ProfileImagePortableBackup.Result.Failed(
                ProfileImagePortableBackup.Failure.MISSING_ASSET
            ),
            result
        )
    }

    @Test
    fun alteredOrNonJpegBytesAreRejected() {
        val bytes = jpegBytes(4)
        val hash = sha256(bytes)
        val file = File(imagesDir, ProfileImageFileNaming.permanentFileName(hash))
        file.writeBytes(jpegBytes(5))

        assertEquals(
            ProfileImagePortableBackup.Result.Failed(
                ProfileImagePortableBackup.Failure.INVALID_ASSET
            ),
            ProfileImagePortableBackup.buildArtifacts(context, catalog(hash))
        )
    }

    private fun catalog(vararg hashes: String): File {
        val file = File(tmp.root, "catalog-${System.nanoTime()}.db")
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

    private fun jpegBytes(marker: Int) = byteArrayOf(
        0xff.toByte(), 0xd8.toByte(), 0xff.toByte(),
        marker.toByte(), 0x01, 0x02,
        0xff.toByte(), 0xd9.toByte()
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
