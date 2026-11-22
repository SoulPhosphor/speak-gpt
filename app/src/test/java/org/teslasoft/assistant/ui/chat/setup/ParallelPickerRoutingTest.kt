package org.teslasoft.assistant.ui.chat.setup

import android.app.Application
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.conversation.setup.ExperimentalSetupTest.Companion.model
import org.teslasoft.assistant.ui.activities.ChooseProviderActivity

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ParallelPickerRoutingTest {
    @Test fun editorIsResultOnlyAndStartsWithTheAssignmentsIndependentRouting() {
        val settings = model("a").settings().apply { selectedProvider = "assignment-provider" }
        val intent = Intent().putExtra(ChooseProviderActivity.EXTRA_PERSIST_DIRECTLY, true)
            .putExtra(ChooseProviderActivity.EXTRA_CHAT_ID, "ordinary-chat")
            .putExtra(ChooseProviderActivity.EXTRA_RETURN_ON_BACK_AFTER_SAVE, true)
        ParallelPickerRouting.prepare(intent, settings)
        assertFalse(intent.getBooleanExtra(ChooseProviderActivity.EXTRA_PERSIST_DIRECTLY, true))
        assertFalse(intent.getBooleanExtra(ChooseProviderActivity.EXTRA_RETURN_ON_BACK_AFTER_SAVE, true))
        assertEquals("", intent.getStringExtra(ChooseProviderActivity.EXTRA_CHAT_ID))
        val initial = ParallelPickerRouting.initial(intent, settings.endpointId, settings.modelId)!!
        assertEquals("assignment-provider", initial.selectedProvider)
        assertNull(ParallelPickerRouting.initial(intent, "another-profile", settings.modelId))
        assertNull(ParallelPickerRouting.initial(intent, settings.endpointId, "another-model"))
        assertEquals("assignment-provider", settings.selectedProvider)
    }

    @Test fun resultChangesRoutingWithoutDroppingReasoningOrSamplingOrMutatingOriginal() {
        val original = model("a").settings()
        val result = Intent().putExtra(ChooseProviderActivity.EXTRA_MODEL, original.modelId)
            .putExtra(ChooseProviderActivity.EXTRA_ROUTING_TYPE, "preferred")
            .putExtra(ChooseProviderActivity.EXTRA_SELECTED_PROVIDER, "")
            .putExtra(ChooseProviderActivity.EXTRA_ALLOW_FALLBACKS, false)
            .putStringArrayListExtra(ChooseProviderActivity.EXTRA_PROVIDER_ORDER, arrayListOf("second", "first"))
            .putStringArrayListExtra(ChooseProviderActivity.EXTRA_IGNORED_PROVIDERS, arrayListOf("excluded"))
        val updated = ParallelPickerRouting.result(result, original)
        assertEquals("preferred", updated.routingType)
        assertEquals(listOf("second", "first"), updated.providerOrder)
        assertEquals(listOf("excluded"), updated.ignoredProviders)
        assertFalse(updated.allowFallbacks)
        assertEquals(original.temperature, updated.temperature)
        assertEquals(original.reasoningEffort, updated.reasoningEffort)
        assertEquals("only", original.routingType)
        assertEquals("provider-a", original.selectedProvider)
    }
}
