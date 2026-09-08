package org.teslasoft.assistant.preferences.generatedimages

import android.app.Application
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class GeneratedImageCatalogHealthCheckTest {

    private val context: android.content.Context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: File

    @Before
    fun setUp() {
        database = context.getDatabasePath(GeneratedImageCatalogStore.DATABASE_NAME)
        database.parentFile?.mkdirs()
        database.delete()
        GeneratedImageCatalogHealth.clear(context)
    }

    @After
    fun tearDown() {
        database.delete()
        GeneratedImageCatalogHealth.clear(context)
    }

    @Test
    fun absentNeverProvisionedCatalogIsHealthyWithoutCreatingIt() {
        val result = GeneratedImageCatalogStore.checkIntegrity(context)

        assertEquals(GeneratedImageCatalogStorageState.AVAILABLE, result.state)
        assertEquals(false, database.exists())
    }

    @Test
    fun missingPreviouslyProvisionedCatalogNeedsRecovery() {
        GeneratedImageCatalogHealth.markProvisioned(context)

        val result = GeneratedImageCatalogStore.checkIntegrity(context)

        assertEquals(GeneratedImageCatalogStorageState.NEEDS_RECOVERY, result.state)
        assertEquals(false, database.exists())
    }

    @Test
    fun corruptFlagIsReportedWithoutReplacingTheDatabase() {
        database.writeBytes(byteArrayOf(1, 2, 3))
        GeneratedImageCatalogHealth.markCorrupt(context, "test")

        val result = GeneratedImageCatalogStore.checkIntegrity(context)

        assertEquals(GeneratedImageCatalogStorageState.CORRUPT, result.state)
        assertEquals(3L, database.length())
    }
}
