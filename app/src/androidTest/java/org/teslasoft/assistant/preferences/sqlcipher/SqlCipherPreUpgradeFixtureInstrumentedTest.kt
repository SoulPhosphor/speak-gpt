package org.teslasoft.assistant.preferences.sqlcipher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStore
import org.teslasoft.assistant.preferences.memory.DatabaseKeys
import org.teslasoft.assistant.preferences.memory.MemoryStore

/** Copied unchanged onto the pinned SQLCipher 4.16.0 commit by the manual
 * arm64 workflow. The explicit instrumentation argument prevents ordinary
 * connected-test runs from replacing any installation's standard stores. */
@RunWith(AndroidJUnit4::class)
class SqlCipherPreUpgradeFixtureInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun createPreUpgradeFixtures() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("phase8UpgradeStage") == "pre"
        )
        assertTrue(android.os.Build.SUPPORTED_ABIS.first().startsWith("arm64"))
        System.loadLibrary("sqlcipher")

        fixtures().forEach { fixture ->
            deleteDatabase(fixture.databaseName)
            val key = DatabaseKeys.getOrCreate(context, fixture.keyName, databaseExists = false)
            assertNotNull(key)
            val file = context.getDatabasePath(fixture.databaseName)
            file.parentFile?.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(file.path, key!!, null, null).use { db ->
                db.execSQL("CREATE TABLE phase8_upgrade_fixture (value TEXT NOT NULL)")
                db.execSQL(
                    "INSERT INTO phase8_upgrade_fixture(value) VALUES (?)",
                    arrayOf(fixture.token)
                )
                db.rawQuery("PRAGMA integrity_check", emptyArray()).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.getString(0).equals("ok", ignoreCase = true))
                }
            }
            assertFalse(file.readBytes().containsSequence(fixture.token.toByteArray()))
        }
    }

    private fun fixtures() = listOf(
        Fixture(MemoryStore.DATABASE_NAME, DatabaseKeys.KEY_MEMORY, "phase8-memory-fixture-v1"),
        Fixture("lorebook.db", DatabaseKeys.KEY_LOREBOOK, "phase8-lorebook-fixture-v1"),
        Fixture(
            GeneratedImageCatalogStore.DATABASE_NAME,
            DatabaseKeys.KEY_GENERATED_IMAGES,
            "phase8-generated-images-fixture-v1"
        )
    )

    private fun deleteDatabase(name: String) {
        listOf("", "-wal", "-shm", "-journal").forEach {
            context.getDatabasePath(name + it).delete()
        }
    }

    private data class Fixture(val databaseName: String, val keyName: String, val token: String)

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || size < needle.size) return false
        return (0..size - needle.size).any { offset ->
            needle.indices.all { this[offset + it] == needle[it] }
        }
    }
}
