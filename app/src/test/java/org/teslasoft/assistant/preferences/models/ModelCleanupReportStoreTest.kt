package org.teslasoft.assistant.preferences.models

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ModelCleanupReportStoreTest {
    @Test fun preTtsReportRoundTripsAndReconcilesWithTtsOnlyReferences() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("model_cleanup_report", Context.MODE_PRIVATE).edit()
            .putString("latest_report", """{"generated_at_millis":42,
                "unavailable":[{"endpoint_id":"ep","model_id":"vendor/speech"}],
                "unchecked_endpoints":["ep"],"endpoint_labels":[{"endpoint_id":"ep","label":"Speech"}]}""")
            .commit()
        val store = ModelCleanupReportStore.get(context)
        val target = ModelIdentity("ep", "vendor/speech")
        val report = store.load()
        assertEquals(setOf(target), report.unavailable)
        val references = ModelCleanupReferences(emptySet(), emptyMap(), 0, ttsTargets = setOf(target))
        store.save(ModelCleanupPolicy.prune(report, references))
        assertEquals(report, store.load())
        assertEquals(setOf("ep"), store.load().uncheckedEndpointIds)
        assertEquals("Speech", store.load().endpointLabels["ep"])
        store.save(ModelCleanupPolicy.prune(report, references.copy(ttsTargets = emptySet())))
        assertTrue(store.load().unavailable.isEmpty())
        assertTrue(store.load().uncheckedEndpointIds.isEmpty())
        assertEquals(42L, store.load().generatedAtMillis)
    }
}
