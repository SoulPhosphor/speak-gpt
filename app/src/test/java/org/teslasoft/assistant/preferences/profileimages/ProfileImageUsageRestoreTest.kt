package org.teslasoft.assistant.preferences.profileimages

import android.app.Application
import android.content.Context
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.preferences.memory.MemoryStore

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProfileImageUsageRestoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        MemoryStore.invalidateInstance()
        context.getSharedPreferences("storage_health", Context.MODE_PRIVATE).edit().clear().commit()
        context.getDatabasePath(MemoryStore.DATABASE_NAME).apply {
            parentFile?.mkdirs()
            createNewFile()
        }
    }

    @After
    fun tearDown() {
        MemoryStore.invalidateInstance()
        context.deleteDatabase(MemoryStore.DATABASE_NAME)
        context.getSharedPreferences("storage_health", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun degradedProvisionedMemoryStoreMakesRestoreDependenciesUnavailable() {
        context.getSharedPreferences("storage_health", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("health.memory.degraded", true)
            .commit()

        val result = ProfileImageUsage.readForRestore(context)

        assertTrue(result is ProfileImageUsage.RestoreRead.Unavailable)
    }
}
