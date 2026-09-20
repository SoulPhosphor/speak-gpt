package org.teslasoft.assistant.preferences.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.memory.DatabaseKeys
import org.teslasoft.assistant.preferences.memory.MemoryStore

@RunWith(AndroidJUnit4::class)
class DirectDatabaseRestoreInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val originalKey = ByteArray(32) { (it + 1).toByte() }
    private val desiredKey = ByteArray(32) { (it + 65).toByte() }
    private val sourceNames = ArrayList<String>()

    @Before
    fun prepare() {
        System.loadLibrary("sqlcipher")
        cleanup()
    }

    @After
    fun finish() = cleanup()

    @Test
    fun everyInjectedBoundaryRecoversToOneHealthyExactDatabase() {
        for (boundary in DirectDatabaseRestoreCoordinator.Boundary.entries) {
            cleanup()
            createDatabase(MemoryStore.DATABASE_NAME, originalKey, ORIGINAL)
            assertTrue(DatabaseKeys.replaceExisting(
                context, DatabaseKeys.KEY_MEMORY, originalKey
            ))
            val sourceName = ".direct-restore-source-${boundary.name.lowercase()}.db"
            sourceNames.add(sourceName)
            val source = createDatabase(sourceName, desiredKey, DESIRED)

            var interrupted = false
            try {
                DirectDatabaseRestoreCoordinator.install(
                    context,
                    BackupType.MEMORY,
                    source,
                    desiredKey,
                    sourcePlaintext = false,
                    faultInjector = DirectDatabaseRestoreCoordinator.FaultInjector {
                        if (it == boundary) throw SimulatedProcessDeath()
                    }
                )
            } catch (_: SimulatedProcessDeath) {
                interrupted = true
            }
            assertTrue(boundary.name, interrupted)

            MemoryStore.invalidateInstance()
            assertTrue(boundary.name, DirectDatabaseRestoreCoordinator.recoverAll(context))
            assertFalse(
                boundary.name,
                DirectDatabaseRestoreJournal.root(context, BackupType.MEMORY).exists()
            )
            val store = MemoryStore.getInstance(context)
            assertNull(boundary.name, store.integrityCheck())
            val marker = store.getMeta(TEST_META)
            assertTrue(boundary.name, marker == ORIGINAL || marker == DESIRED)

            val keyState = DatabaseKeys.readState(context, DatabaseKeys.KEY_MEMORY)
            assertTrue(boundary.name, keyState is DatabaseKeys.StoredKeyState.Present)
            val actualKey = (keyState as DatabaseKeys.StoredKeyState.Present).value
            try {
                assertArrayEquals(
                    boundary.name,
                    if (marker == ORIGINAL) originalKey else desiredKey,
                    actualKey
                )
            } finally {
                actualKey.fill(0)
            }
        }
    }

    @Test
    fun absentOriginalWithInstalledKeyAndMissingStageRollsBackToAbsence() {
        val sourceName = ".direct-restore-source-absent.db"
        sourceNames.add(sourceName)
        val source = createDatabase(sourceName, desiredKey, DESIRED)
        assertTrue(DatabaseKeys.clearExisting(context, DatabaseKeys.KEY_MEMORY))

        var interrupted = false
        try {
            DirectDatabaseRestoreCoordinator.install(
                context,
                BackupType.MEMORY,
                source,
                desiredKey,
                sourcePlaintext = false,
                faultInjector = DirectDatabaseRestoreCoordinator.FaultInjector {
                    if (it == DirectDatabaseRestoreCoordinator.Boundary.AFTER_KEY_SWITCHED) {
                        throw SimulatedProcessDeath()
                    }
                }
            )
        } catch (_: SimulatedProcessDeath) {
            interrupted = true
        }
        assertTrue(interrupted)
        val record = DirectDatabaseRestoreJournal.read(context, BackupType.MEMORY)
        assertNotNull(record)
        deleteFileSet(File(record!!.stagedPath))

        assertTrue(DirectDatabaseRestoreCoordinator.recoverAll(context))
        assertFalse(context.getDatabasePath(MemoryStore.DATABASE_NAME).exists())
        assertTrue(
            DatabaseKeys.readState(context, DatabaseKeys.KEY_MEMORY) ===
                DatabaseKeys.StoredKeyState.Absent
        )
    }

    private fun createDatabase(name: String, key: ByteArray, marker: String): File {
        deleteFileSet(context.getDatabasePath(name))
        val store = MemoryStore.openForTest(context, name, key)
        try {
            store.setMeta(TEST_META, marker)
            assertNull(store.integrityCheck())
        } finally {
            store.close()
        }
        return context.getDatabasePath(name)
    }

    private fun cleanup() {
        MemoryStore.invalidateInstance()
        DirectDatabaseRestoreJournal.root(context, BackupType.MEMORY).deleteRecursively()
        runCatching { DirectDatabaseRestoreCoordinator.recoverAll(context) }
        deleteFileSet(context.getDatabasePath(MemoryStore.DATABASE_NAME))
        sourceNames.forEach { deleteFileSet(context.getDatabasePath(it)) }
        sourceNames.clear()
        File(context.filesDir, "storage_recovery").listFiles()
            ?.filter { it.name.startsWith("companion_memory.pre-restore-") }
            ?.forEach { runCatching { it.delete() } }
        DatabaseKeys.clearExisting(context, DatabaseKeys.KEY_MEMORY)
    }

    private fun deleteFileSet(base: File) {
        listOf("", "-wal", "-shm", "-journal").forEach {
            runCatching { File(base.path + it).delete() }
        }
    }

    private class SimulatedProcessDeath : Error()

    private companion object {
        const val TEST_META = "direct_restore_instrumentation_marker"
        const val ORIGINAL = "original"
        const val DESIRED = "desired"
    }
}
